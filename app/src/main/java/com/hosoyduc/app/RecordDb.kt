package com.hosoyduc.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/**
 * Bộ nhớ đệm cục bộ (SQLite) cho bản ghi của người dùng.
 * - Mở app: hiển thị ngay từ máy, không phải tải lại 50k bản ghi từ Firebase.
 * - Phân trang keyset (saved_at, k) -> chỉ truy vấn các dòng đang cần hiển thị.
 * - Chống trùng chỉ quét các ứng viên cùng dải độ dài, không tải lại toàn bộ dữ liệu.
 */
class RecordDb private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "records.db", null, 1) {

    class PageCursor(val savedAt: Long, val key: String)

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE records(
                k TEXT PRIMARY KEY,
                original TEXT NOT NULL,
                norm TEXT NOT NULL,
                nlen INTEGER NOT NULL,
                sig INTEGER NOT NULL,
                sha TEXT, sha512 TEXT, source TEXT, device TEXT, android TEXT,
                times TEXT,
                saved_at INTEGER NOT NULL,
                time_sha TEXT, seal256 TEXT, seal512 TEXT,
                extra TEXT)"""
        )
        db.execSQL("CREATE INDEX idx_records_saved ON records(saved_at, k)")
        db.execSQL("CREATE INDEX idx_records_nlen ON records(nlen)")
    }

    // Đây chỉ là cache -> nâng cấp schema = dựng lại, dữ liệu gốc nằm trên Firebase
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS records")
        onCreate(db)
    }

    // ---------- Ghi ----------

    fun upsert(r: Record) = upsertAll(listOf(r))

    fun upsertAll(list: Collection<Record>) {
        if (list.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val st = db.compileStatement(
                "INSERT OR REPLACE INTO records(k, original, norm, nlen, sig, sha, sha512, source, device, " +
                    "android, times, saved_at, time_sha, seal256, seal512, extra) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            )
            for (r in list) {
                val norm = Dedup.normalize(r.original)
                st.clearBindings()
                st.bindString(1, r.key)
                st.bindString(2, r.original)
                st.bindString(3, norm)
                st.bindLong(4, norm.length.toLong())
                st.bindLong(5, Dedup.signature(norm))
                st.bindString(6, r.sha)
                st.bindString(7, r.sha512)
                st.bindString(8, r.source)
                st.bindString(9, r.device)
                st.bindString(10, r.androidVersion)
                st.bindString(11, JSONObject(r.times).toString())
                st.bindLong(12, r.savedAt)
                st.bindString(13, r.timeSha256)
                st.bindString(14, r.sealSha256)
                st.bindString(15, r.sealSha512)
                if (r.extra != null) st.bindString(16, r.extra) else st.bindNull(16)
                st.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun delete(key: String) {
        writableDatabase.delete("records", "k = ?", arrayOf(key))
    }

    fun deleteKeys(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val st = db.compileStatement("DELETE FROM records WHERE k = ?")
            for (k in keys) { st.clearBindings(); st.bindString(1, k); st.executeUpdateDelete() }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clear() {
        writableDatabase.execSQL("DELETE FROM records")
    }

    // ---------- Đọc ----------

    fun count(): Int = scalarLong("SELECT COUNT(*) FROM records", emptyArray()).toInt()

    fun countMatching(query: String): Int {
        val q = query.lowercase().trim()
        if (q.isEmpty()) return count()
        return scalarLong(
            "SELECT COUNT(*) FROM records WHERE instr(norm, ?) > 0 OR instr(sha, ?) > 0 OR instr(source, ?) > 0",
            arrayOf(q, q, q)
        ).toInt()
    }

    fun maxSavedAt(): Long = scalarLong("SELECT COALESCE(MAX(saved_at), 0) FROM records", emptyArray())

    fun exists(key: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM records WHERE k = ? LIMIT 1", arrayOf(key)).use { it.moveToFirst() }

    fun allKeys(): HashSet<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery("SELECT k FROM records", null).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }

    /** Trang kế tiếp (mới -> cũ), có lọc tìm kiếm. */
    fun page(query: String, after: PageCursor?, limit: Int): List<RecordRow> {
        val q = query.lowercase().trim()
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        if (q.isNotEmpty()) {
            where.add("(instr(norm, ?) > 0 OR instr(sha, ?) > 0 OR instr(source, ?) > 0)")
            args.add(q); args.add(q); args.add(q)
        }
        if (after != null) {
            where.add("(saved_at < ${after.savedAt} OR (saved_at = ${after.savedAt} AND k < ?))")
            args.add(after.key)
        }
        val sql = "SELECT k, original, source, device, times, saved_at, seal256 FROM records" +
            (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) +
            " ORDER BY saved_at DESC, k DESC LIMIT $limit"

        val out = ArrayList<RecordRow>(limit)
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                val original = c.getString(1) ?: ""
                val timesMap = parseTimes(c.getString(4))
                val savedAt = c.getLong(5)
                val sb = StringBuilder()
                for ((k, v) in timesMap) sb.append(k).append(": ").append(v).append("  ")
                out.add(
                    RecordRow(
                        key = c.getString(0), original = original,
                        source = c.getString(2) ?: "", device = c.getString(3) ?: "",
                        timesText = sb.toString(), savedAt = savedAt,
                        seal = HashUtils.verify(original, savedAt, timesMap, c.getString(6))
                    )
                )
            }
        }
        return out
    }

    // ---------- Chống trùng ----------

    /** Có bản ghi nào >68% giống `norm` không? Chỉ quét các bản cùng dải độ dài. */
    fun hasSimilar(norm: String): Boolean {
        val range = Dedup.lengthRange(norm.length)
        val matcher = Dedup.Matcher().also { it.prepare(norm) }
        readableDatabase.rawQuery(
            "SELECT norm, sig FROM records WHERE nlen BETWEEN ? AND ?",
            arrayOf(range.first.toString(), range.last.toString())
        ).use { c ->
            while (c.moveToNext()) if (matcher.isSimilar(c.getString(0), c.getLong(1))) return true
        }
        return false
    }

    /** Chỉ mục trong RAM cho nhập hàng loạt; bộ nhớ dùng tùy độ dài bản ghi. */
    fun loadIndex(): Dedup.Index {
        val idx = Dedup.Index()
        readableDatabase.rawQuery("SELECT norm, sig FROM records", null).use { c ->
            while (c.moveToNext()) idx.add(c.getString(0), c.getLong(1))
        }
        return idx
    }

    /** Duyệt toàn bộ (mới -> cũ) theo dòng, không nạp hết vào RAM. Dùng cho xuất backup. */
    fun forEachRecord(block: (Record) -> Unit) {
        readableDatabase.rawQuery(
            "SELECT k, original, sha, sha512, source, device, android, times, saved_at, time_sha, seal256, seal512, extra " +
                "FROM records ORDER BY saved_at DESC, k DESC", null
        ).use { c ->
            while (c.moveToNext()) {
                block(
                    Record(
                        key = c.getString(0), original = c.getString(1) ?: "",
                        sha = c.getString(2) ?: "", sha512 = c.getString(3) ?: "",
                        source = c.getString(4) ?: "", device = c.getString(5) ?: "",
                        androidVersion = c.getString(6) ?: "",
                        times = parseTimes(c.getString(7)), savedAt = c.getLong(8),
                        timeSha256 = c.getString(9) ?: "", sealSha256 = c.getString(10) ?: "",
                        sealSha512 = c.getString(11) ?: "", extra = c.getString(12)
                    )
                )
            }
        }
    }

    private fun scalarLong(sql: String, args: Array<String>): Long =
        readableDatabase.rawQuery(sql, args).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun parseTimes(json: String?): Map<String, String> {
        if (json.isNullOrEmpty()) return emptyMap()
        return try {
            val o = JSONObject(json)
            val m = LinkedHashMap<String, String>()
            o.keys().forEach { k -> m[k] = o.optString(k, "") }
            m
        } catch (e: Exception) { emptyMap() }
    }

    companion object {
        @Volatile private var instance: RecordDb? = null
        fun get(ctx: Context): RecordDb =
            instance ?: synchronized(this) { instance ?: RecordDb(ctx).also { instance = it } }
    }
}
