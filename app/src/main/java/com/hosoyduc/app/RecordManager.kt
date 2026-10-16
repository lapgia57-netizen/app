package com.hosoyduc.app

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

sealed class SaveOutcome {
    object Success : SaveOutcome()
    object NotLoggedIn : SaveOutcome()
    object Duplicate : SaveOutcome()
    data class Error(val message: String) : SaveOutcome()
}

data class ImportResult(val imported: Int, val skipped: Int, val error: String?)

object RecordManager {

    private val deviceName get() = "${Build.MANUFACTURER} ${Build.MODEL}"
    private val androidVer get() = Build.VERSION.RELEASE ?: ""

    // Chỉ được gọi khi người dùng CHỦ ĐỘNG yêu cầu lưu (chọn app trong menu bôi đen,
    // hoặc bấm nút "Lưu" trong app). Không có nơi nào khác gọi hàm này tự động.
    suspend fun saveText(ctx: Context, text: String, source: String): SaveOutcome {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return SaveOutcome.Error("Nội dung trống")

        val auth = FirebaseClient.getValidAuth(ctx) ?: return SaveOutcome.NotLoggedIn
        val db = RecordDb.get(ctx)

        // Cập nhật cache (thường là bỏ qua hoặc 1 request nhỏ) thay vì tải lại toàn bộ dữ liệu như bản cũ
        val sync = SyncManager.ensureFresh(ctx, auth)
        if (sync is ApiResult.Failure && !SyncManager.hasSyncedBefore(ctx, auth.uid)) {
            return SaveOutcome.Error(sync.message)
        }

        return withContext<SaveOutcome>(Dispatchers.IO) {
            val key = HashUtils.md5(trimmed)
            if (db.exists(key) || db.hasSimilar(Dedup.normalize(trimmed))) return@withContext SaveOutcome.Duplicate

            val now = System.currentTimeMillis()
            val times = computeTimes(Prefs.getSelectedTimezones(ctx), now)   // cùng một mốc `now` với savedAt
            val rec = Record.create(trimmed, source, deviceName, androidVer, now, times)

            when (val res = FirebaseClient.writeRecord(auth, rec.key, rec.toJson())) {
                is ApiResult.Success -> { db.upsert(rec); SaveOutcome.Success }
                is ApiResult.Failure -> SaveOutcome.Error(res.message)
            }
        }
    }

    private class Cand(val text: String, val key: String, val norm: String, val sig: Long)

    /**
     * Khôi phục hàng loạt. Bản cũ gọi saveText() cho từng bản ghi => mỗi bản tải lại toàn bộ DB (O(n²) mạng).
     * Bản mới: 1 lần đồng bộ, lọc trùng song song nhiều luồng, ghi theo lô 300 bản/request.
     */
    suspend fun importTexts(ctx: Context, texts: List<String>, onProgress: (String) -> Unit): ImportResult {
        val auth = FirebaseClient.getValidAuth(ctx) ?: return ImportResult(0, texts.size, "Chưa đăng nhập")
        val db = RecordDb.get(ctx)

        onProgress("Đang đồng bộ dữ liệu hiện có…")
        val sync = SyncManager.sync(ctx, auth, false) { n -> onProgress("Đang tải $n bản ghi…") }
        if (sync is ApiResult.Failure && !SyncManager.hasSyncedBefore(ctx, auth.uid)) {
            return ImportResult(0, texts.size, sync.message)
        }

        val prepared = withContext(Dispatchers.Default) {
            val existingKeys = db.allKeys()
            val existingIndex = db.loadIndex()

            // 1) Loại trùng tuyệt đối (md5) với dữ liệu có sẵn và trong chính tệp
            var skipped = 0
            val seen = HashSet<String>()
            val cands = ArrayList<Cand>()
            for (t in texts) {
                val tr = t.trim()
                if (tr.isEmpty()) { skipped++; continue }
                val k = HashUtils.md5(tr)
                if (k in existingKeys || !seen.add(k)) { skipped++; continue }
                val n = Dedup.normalize(tr)
                cands.add(Cand(tr, k, n, Dedup.signature(n)))
            }

            // 2) Loại trùng mờ (>68%) với dữ liệu có sẵn và với các bản đứng trước trong tệp — song song
            val batchIndex = Dedup.Index().also { idx -> cands.forEach { idx.add(it.norm, it.sig) } }
            val keep = BooleanArray(cands.size)
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
            val chunk = maxOf(50, cands.size / (threads * 4) + 1)
            val done = AtomicInteger(0)
            coroutineScope {
                (0 until cands.size step chunk).map { start ->
                    async {
                        val matcher = Dedup.Matcher()
                        for (i in start until minOf(start + chunk, cands.size)) {
                            ensureActive()
                            val c = cands[i]
                            matcher.prepare(c.norm)
                            keep[i] = !existingIndex.hasSimilar(matcher, c.norm) &&
                                !batchIndex.hasSimilar(matcher, c.norm, beforeId = i)
                            val d = done.incrementAndGet()
                            if (d % 200 == 0) onProgress("Đang lọc trùng $d/${cands.size}…")
                        }
                    }
                }.awaitAll()
            }
            val kept = cands.filterIndexed { i, _ -> keep[i] }
            skipped += cands.size - kept.size
            Pair(kept, skipped)
        }

        val kept = prepared.first
        var skipped = prepared.second
        if (kept.isEmpty()) return ImportResult(0, skipped, null)

        // 3) Ghi theo lô
        val base = System.currentTimeMillis()
        val times = computeTimes(Prefs.getSelectedTimezones(ctx), base)
        val records = withContext(Dispatchers.Default) {
            kept.mapIndexed { i, c -> Record.create(c.text, "import", deviceName, androidVer, base + i, times) }
        }
        var imported = 0
        for (batch in records.chunked(300)) {
            when (val res = FirebaseClient.writeRecordsBatch(auth, batch)) {
                is ApiResult.Success -> {
                    withContext(Dispatchers.IO) { db.upsertAll(batch) }
                    imported += batch.size
                    onProgress("Đang ghi $imported/${records.size}…")
                }
                is ApiResult.Failure -> return ImportResult(imported, skipped + (records.size - imported), res.message)
            }
        }
        return ImportResult(imported, skipped, null)
    }
}
