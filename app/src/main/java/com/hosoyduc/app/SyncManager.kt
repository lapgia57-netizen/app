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
import java.util.concurrent.atomic.AtomicLong

/**
 * Đồng bộ Firebase -> RecordDb mà không phải tải lại toàn bộ dữ liệu mỗi lần:
 *  - Lần đầu: tải song song theo 19 "phân vùng" khoá (hex 0-f + 3 vùng biên), mỗi trang 1000 bản.
 *  - Các lần sau: chỉ lấy bản mới (savedAt >= mốc cũ - 5 phút) nếu có .indexOn savedAt.
 *  - Mỗi 24h / bấm "Đồng bộ": đối chiếu danh sách khoá (shallow) để bắt bản bị xoá/ghi lệch đồng hồ.
 */
object SyncManager {
    data class Stats(val added: Int, val removed: Int, val total: Int, val mode: String)

    private const val PAGE = 1000
    private const val OVERLAP_MS = 5 * 60_000L
    private const val FULL_RECONCILE_MS = 24 * 3_600_000L
    private const val SMALL_FETCH = 200
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

    // ---------- Lần đầu: tải song song theo phân vùng ----------

    private val SHARDS: List<Pair<String?, String?>> =
        listOf<Pair<String?, String?>>(null to "/\uf8ff") +
            "0123456789abcdef".map { c -> c.toString() to "$c\uf8ff" } +
            listOf<Pair<String?, String?>>(":" to "`\uf8ff", "g" to null)

    private suspend fun initialScan(ctx: Context, auth: AuthData, db: RecordDb, onProgress: ((Int) -> Unit)?): ApiResult<String> {
        val r = scanAll(auth, db, onProgress)
        if (r is ApiResult.Failure) return r
        Prefs.setSyncCursor(ctx, maxOf(Prefs.getSyncCursor(ctx), (r as ApiResult.Success).data))
        Prefs.setLastFullSyncAt(ctx, System.currentTimeMillis())
        return ApiResult.Success("initial")
    }

    /** Tải toàn bộ, trả về savedAt lớn nhất đã thấy. */
    private suspend fun scanAll(auth: AuthData, db: RecordDb, onProgress: ((Int) -> Unit)?): ApiResult<Long> {
        val loaded = AtomicInteger(0)
        val maxSaved = AtomicLong(0L)
        val sem = Semaphore(4)
        val results = coroutineScope {
            SHARDS.map { (start, end) ->
                async { sem.withPermit { scanShard(auth, db, start, end, loaded, maxSaved, onProgress) } }
            }.awaitAll()
        }
        results.firstOrNull { it is ApiResult.Failure }?.let { return it as ApiResult.Failure }
        return ApiResult.Success(maxSaved.get())
    }

    private suspend fun scanShard(
        auth: AuthData, db: RecordDb, start: String?, end: String?,
        loaded: AtomicInteger, maxSaved: AtomicLong, onProgress: ((Int) -> Unit)?
    ): ApiResult<Unit> {
        var cursor = start
        while (true) {
            // startAt bao gồm biên -> xin PAGE+1 và bỏ phần tử trùng với con trỏ
            val res = FirebaseClient.fetchKeyRange(auth, cursor, end, PAGE + 1)
            if (res is ApiResult.Failure) return ApiResult.Failure(res.message)
            val all = (res as ApiResult.Success).data
            val page = if (cursor != null) all.filter { it.key != cursor } else all
            if (page.isNotEmpty()) {
                withContext(Dispatchers.IO) { db.upsertAll(page) }
                var m = 0L
                for (rec in page) if (rec.savedAt > m) m = rec.savedAt
                while (true) { val cur = maxSaved.get(); if (m <= cur || maxSaved.compareAndSet(cur, m)) break }
                onProgress?.invoke(loaded.addAndGet(page.size))
            }
            if (all.size < PAGE + 1) return ApiResult.Success(Unit)
            val last = all.maxOf { it.key }
            if (cursor != null && last <= cursor) return ApiResult.Success(Unit) // không tiến triển -> dừng, tránh vòng lặp vô hạn
            cursor = last
        }
    }

    // ---------- Các lần sau: chỉ bản mới ----------

    private suspend fun incremental(ctx: Context, auth: AuthData, db: RecordDb, onProgress: ((Int) -> Unit)?): ApiResult<String> {
        val oldCursor = Prefs.getSyncCursor(ctx)
        var from = (oldCursor - OVERLAP_MS).coerceAtLeast(0L)
        var maxSaved = oldCursor
        var loaded = 0
        while (true) {
            when (val res = FirebaseClient.fetchSince(auth, from, PAGE)) {
                is ApiResult.Failure -> {
                    if (res.indexMissing) {
                        Prefs.setIndexMissing(ctx, true)
                        return reconcile(ctx, auth, db, onProgress)
                    }
                    return ApiResult.Failure(res.message)
                }
                is ApiResult.Success -> {
                    Prefs.setIndexMissing(ctx, false)
                    val list = res.data
                    if (list.isEmpty()) break
                    withContext(Dispatchers.IO) { db.upsertAll(list) }
                    loaded += list.size
                    onProgress?.invoke(loaded)
                    val pageMax = list.maxOf { it.savedAt }
                    if (pageMax > maxSaved) maxSaved = pageMax
                    if (list.size < PAGE) break
                    from = if (pageMax > from) pageMax else pageMax + 1
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
