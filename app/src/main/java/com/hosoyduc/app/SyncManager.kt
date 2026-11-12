package com.hosoyduc.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Đồng bộ Firebase -> RecordDb mà không phải tải lại toàn bộ dữ liệu mỗi lần:
 *  - Lần đầu: quét tuần tự theo key, mỗi trang 1000 bản; không dùng bound giả có thể không hợp lệ.
 *  - Các lần sau: chỉ lấy bản mới (savedAt >= mốc cũ - 5 phút) nếu có .indexOn savedAt.
 *  - Mỗi 24h / bấm "Đồng bộ": đối chiếu danh sách khoá (shallow) để bắt bản bị xoá/ghi lệch đồng hồ.
 */
object SyncManager {
    data class Stats(val added: Int, val removed: Int, val total: Int, val mode: String)

    private const val PAGE = 1000
    private const val OVERLAP_MS = 5 * 60_000L
    private const val FULL_RECONCILE_MS = 24 * 3_600_000L
    private const val SMALL_FETCH = 200
    private const val INCREMENTAL_PAGE = 1000
    private const val MAX_INCREMENTAL_PAGE = 50_001
    private const val FRESH_MS = 60_000L
    private const val FRESH_MS_NO_INDEX = 10 * 60_000L

    private val mutex = Mutex()

    fun hasSyncedBefore(ctx: Context, uid: String): Boolean =
        Prefs.getSyncUid(ctx) == uid && Prefs.getLastFullSyncAt(ctx) > 0L

    /** Dùng trước khi lưu: bỏ qua nếu vừa đồng bộ xong, để mỗi lần lưu không tốn request. */
    suspend fun ensureFresh(ctx: Context, auth: AuthData, onProgress: ((Int) -> Unit)? = null): ApiResult<Stats> {
        val db = RecordDb.get(ctx)
        val maxAge = if (Prefs.isIndexMissing(ctx)) FRESH_MS_NO_INDEX else FRESH_MS
        val age = System.currentTimeMillis() - Prefs.getLastSyncAt(ctx)
        if (hasSyncedBefore(ctx, auth.uid) && age in 0 until maxAge) {
            return ApiResult.Success(Stats(0, 0, withContext(Dispatchers.IO) { db.count() }, "skip"))
        }
        return sync(ctx, auth, false, onProgress)
    }

    suspend fun sync(ctx: Context, auth: AuthData, full: Boolean, onProgress: ((Int) -> Unit)? = null): ApiResult<Stats> =
        mutex.withLock {
            val db = RecordDb.get(ctx)
            withContext(Dispatchers.IO) {
                if (Prefs.getSyncUid(ctx) != auth.uid) { db.clear(); Prefs.resetSync(ctx, auth.uid) }
            }
            val before = withContext(Dispatchers.IO) { db.count() }
            val now = System.currentTimeMillis()
            val lastFull = Prefs.getLastFullSyncAt(ctx)

            val result: ApiResult<String> = when {
                lastFull == 0L -> initialScan(ctx, auth, db, onProgress)
                full || now - lastFull > FULL_RECONCILE_MS -> reconcile(ctx, auth, db, onProgress)
                else -> incremental(ctx, auth, db, onProgress)
            }
            when (result) {
                is ApiResult.Failure -> ApiResult.Failure(result.message)
                is ApiResult.Success -> {
                    Prefs.setLastSyncAt(ctx, System.currentTimeMillis())
                    val after = withContext(Dispatchers.IO) { db.count() }
                    ApiResult.Success(Stats(maxOf(0, after - before), maxOf(0, before - after), after, result.data))
                }
            }
        }

    // ---------- Lần đầu: quét phân trang theo key ----------

    private suspend fun initialScan(ctx: Context, auth: AuthData, db: RecordDb, onProgress: ((Int) -> Unit)?): ApiResult<String> {
        val r = scanAll(auth, db, onProgress)
        if (r is ApiResult.Failure) return r
        Prefs.setSyncCursor(ctx, maxOf(Prefs.getSyncCursor(ctx), (r as ApiResult.Success).data))
        Prefs.setLastFullSyncAt(ctx, System.currentTimeMillis())
        return ApiResult.Success("initial")
    }

    /** Tải toàn bộ theo key; dùng key thực có trong DB làm bound cho trang kế tiếp. */
    private suspend fun scanAll(auth: AuthData, db: RecordDb, onProgress: ((Int) -> Unit)?): ApiResult<Long> {
        var cursor: String? = null
        var loaded = 0
        var maxSaved = 0L
        while (true) {
            val all = when (val res = FirebaseClient.fetchKeyRange(auth, cursor, null, PAGE + 1)) {
                is ApiResult.Failure -> return res
                is ApiResult.Success -> res.data
            }
            val page = if (cursor != null) all.filter { it.key != cursor } else all
            if (page.isNotEmpty()) {
                withContext(Dispatchers.IO) { db.upsertAll(page) }
                page.maxOfOrNull { it.savedAt }?.let { maxSaved = maxOf(maxSaved, it) }
                loaded += page.size
                onProgress?.invoke(loaded)
            }
            if (all.size < PAGE + 1) return ApiResult.Success(maxSaved)
            val last = all.maxOf { it.key }
            if (cursor != null && last <= cursor) {
                return ApiResult.Failure("Đồng bộ dừng vì phân trang theo key không tiến triển.")
            }
            cursor = last
        }
    }

    // ---------- Các lần sau: chỉ bản mới ----------

    private suspend fun incremental(ctx: Context, auth: AuthData, db: RecordDb, onProgress: ((Int) -> Unit)?): ApiResult<String> {
        val oldCursor = Prefs.getSyncCursor(ctx)
        var from = (oldCursor - OVERLAP_MS).coerceAtLeast(0L)
        var maxSaved = oldCursor
        var loaded = 0
        var fetchLimit = INCREMENTAL_PAGE + 1
        while (true) {
            when (val res = FirebaseClient.fetchSince(auth, from, fetchLimit)) {
                is ApiResult.Failure -> {
                    if (res.indexMissing) {
                        Prefs.setIndexMissing(ctx, true)
                        return reconcile(ctx, auth, db, onProgress)
                    }
                    return ApiResult.Failure(res.message)
                }
                is ApiResult.Success -> {
                    Prefs.setIndexMissing(ctx, false)
                    val records = res.data
                    if (records.isEmpty()) break

                    val pageIsFull = records.size == fetchLimit
                    val boundaryTime = records.last().savedAt
                    val completePageSize = if (pageIsFull) {
                        records.indexOfFirst { it.savedAt == boundaryTime }
                    } else {
                        records.size
                    }

                    if (pageIsFull && completePageSize == 0) {
                        if (fetchLimit >= MAX_INCREMENTAL_PAGE) {
                            return ApiResult.Failure("Không thể phân trang đồng bộ: quá nhiều bản ghi có cùng savedAt.")
                        }
                        fetchLimit = (fetchLimit * 2).coerceAtMost(MAX_INCREMENTAL_PAGE)
                        continue
                    }

                    val list = records.take(completePageSize)
                    withContext(Dispatchers.IO) { db.upsertAll(list) }
                    loaded += list.size
                    onProgress?.invoke(loaded)
                    list.maxOfOrNull { it.savedAt }?.let { maxSaved = maxOf(maxSaved, it) }

                    if (!pageIsFull) break
                    from = boundaryTime
                    fetchLimit = INCREMENTAL_PAGE + 1
                }
            }
        }
        Prefs.setSyncCursor(ctx, maxSaved)
        return ApiResult.Success("incremental")
    }

    // ---------- Đối chiếu khoá: bắt bản bị xoá / ghi lệch đồng hồ ----------

    private suspend fun reconcile(ctx: Context, auth: AuthData, db: RecordDb, onProgress: ((Int) -> Unit)?): ApiResult<String> {
        val remote = when (val r = FirebaseClient.shallowKeys(auth)) {
            is ApiResult.Failure -> return ApiResult.Failure(r.message)
            is ApiResult.Success -> r.data
        }
        val local = withContext(Dispatchers.IO) { db.allKeys() }
        val toDelete = local.filter { it !in remote }
        val toFetch = remote.filter { it !in local }
        withContext(Dispatchers.IO) { db.deleteKeys(toDelete) }

        if (toFetch.isNotEmpty()) {
            if (toFetch.size <= SMALL_FETCH) {
                val sem = Semaphore(6)
                val fetched = coroutineScope {
                    toFetch.map { k -> async { sem.withPermit { FirebaseClient.fetchOne(auth, k) } } }.awaitAll()
                }
                fetched.firstOrNull { it is ApiResult.Failure }?.let { return ApiResult.Failure((it as ApiResult.Failure).message) }
                val recs = fetched.mapNotNull { (it as ApiResult.Success).data }
                withContext(Dispatchers.IO) { db.upsertAll(recs) }
                onProgress?.invoke(recs.size)
            } else {
                val r = scanAll(auth, db, onProgress)
                if (r is ApiResult.Failure) return ApiResult.Failure(r.message)
            }
        }
        val maxLocal = withContext(Dispatchers.IO) { db.maxSavedAt() }
        Prefs.setSyncCursor(ctx, maxOf(Prefs.getSyncCursor(ctx), maxLocal))
        Prefs.setLastFullSyncAt(ctx, System.currentTimeMillis())
        return ApiResult.Success("reconcile")
    }
}
