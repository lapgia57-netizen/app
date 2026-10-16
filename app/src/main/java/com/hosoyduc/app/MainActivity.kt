package com.hosoyduc.app

import android.net.Uri
import android.os.Bundle
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private companion object {
        const val PAGE = 100          // số dòng mỗi lần tải từ cache cục bộ
        const val SEARCH_DEBOUNCE_MS = 250L
    }

    private var isRegisterMode = false
    private lateinit var adapter: RecordsAdapter
    private lateinit var db: RecordDb

    // Trạng thái danh sách (phân trang keyset trên SQLite cục bộ)
    private var query = ""
    private var total = 0
    private var matching = -1         // -1 = đang không tìm kiếm
    private var cursor: RecordDb.PageCursor? = null
    private var hasMore = false
    private var loadingMore = false
    private var generation = 0        // chặn kết quả của lần tải cũ ghi đè lần mới
    private var statusExtra = ""
    private var busy = false          // đang xuất / khôi phục

    private var listJob: Job? = null
    private var searchJob: Job? = null
    private var syncJob: Job? = null

    // Launchers cho chọn file (Storage Access Framework) - không cần xin quyền bộ nhớ
    private val createBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) writeBackupToUri(uri) }

    private val openBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) promptImportPassword(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = RecordDb.get(this)

        adapter = RecordsAdapter { row -> confirmDelete(row) }
        findViewById<RecyclerView>(R.id.recyclerView).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            setHasFixedSize(true)
            setItemViewCacheSize(20)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    val lm = rv.layoutManager as LinearLayoutManager
                    if (lm.findLastVisibleItemPosition() >= this@MainActivity.adapter.itemCount - 20) loadMore()
                }
            })
        }

        setupAuthUI()
        setupDashboardUI()
        checkLoginState()
    }

    private fun checkLoginState() {
        lifecycleScope.launch {
            // Offline: không làm mới được token nhưng vẫn cho xem dữ liệu đã lưu trong máy
            val auth = FirebaseClient.getValidAuth(this@MainActivity) ?: Prefs.getAuth(this@MainActivity)
            if (auth != null) openDashboard(auth) else showAuth()
        }
    }

    private fun showAuth() {
        findViewById<View>(R.id.authContainer).visibility = View.VISIBLE
        findViewById<View>(R.id.dashboardContainer).visibility = View.GONE
    }

    /** Đổi tài khoản -> xoá cache của tài khoản trước để không hiện nhầm dữ liệu. */
    private fun openDashboard(auth: AuthData) {
        lifecycleScope.launch {
            if (Prefs.getSyncUid(this@MainActivity) != auth.uid) {
                withContext(Dispatchers.IO) { db.clear() }
                Prefs.resetSync(this@MainActivity, auth.uid)
            }
            showDashboard(auth)
        }
    }

    private fun showDashboard(auth: AuthData) {
        findViewById<View>(R.id.authContainer).visibility = View.GONE
        findViewById<View>(R.id.dashboardContainer).visibility = View.VISIBLE
        findViewById<TextView>(R.id.tvUserInfo).text = "${auth.displayName}\n${auth.email}"
        reload(toTop = true)     // hiển thị ngay từ cache cục bộ
        startSync(full = false)  // rồi đồng bộ nền
    }

    // ---------- Đăng nhập / đăng ký ----------

    private fun setupAuthUI() {
        val etDisplayName = findViewById<EditText>(R.id.etDisplayName)
        val etEmail = findViewById<EditText>(R.id.etEmail)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val btnAuth = findViewById<Button>(R.id.btnAuth)
        val tvToggle = findViewById<TextView>(R.id.tvToggleMode)
        val tvError = findViewById<TextView>(R.id.tvAuthError)

        tvToggle.setOnClickListener {
            isRegisterMode = !isRegisterMode
            etDisplayName.visibility = if (isRegisterMode) View.VISIBLE else View.GONE
            btnAuth.text = if (isRegisterMode) "Đăng ký" else "Đăng nhập"
            tvToggle.text = if (isRegisterMode) "Đã có tài khoản? Đăng nhập" else "Chưa có tài khoản? Đăng ký ngay"
        }

        btnAuth.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()
            val displayName = etDisplayName.text.toString().trim()
            tvError.text = ""

            if (email.isEmpty() || password.isEmpty()) { tvError.text = "Vui lòng nhập email và mật khẩu."; return@setOnClickListener }
            if (isRegisterMode && displayName.isEmpty()) { tvError.text = "Vui lòng nhập tên hiển thị."; return@setOnClickListener }

            btnAuth.isEnabled = false
            lifecycleScope.launch {
                val result = if (isRegisterMode) FirebaseClient.signUp(email, password, displayName)
                             else FirebaseClient.signIn(email, password)
                btnAuth.isEnabled = true
                when (result) {
                    is ApiResult.Success -> {
                        Prefs.saveAuth(this@MainActivity, result.data)
                        openDashboard(result.data)
                    }
                    is ApiResult.Failure -> tvError.text = result.message
                }
            }
        }
    }

    // ---------- Dashboard ----------

    private fun setupDashboardUI() {
        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            Prefs.clearAuth(this)
            listJob?.cancel(); searchJob?.cancel(); syncJob?.cancel()
            generation++
            adapter.replaceAll(emptyList())
            lifecycleScope.launch {
                // Đăng xuất = xoá cache trong máy
                withContext(Dispatchers.IO) { db.clear() }
                Prefs.resetSync(this@MainActivity, null)
            }
            showAuth()
        }

        findViewById<Button>(R.id.btnTimezoneSettings).setOnClickListener { showTimezoneDialog() }

        findViewById<Button>(R.id.btnManualSave).setOnClickListener { btn ->
            val et = findViewById<EditText>(R.id.etManualInput)
            val text = et.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            btn.isEnabled = false
            lifecycleScope.launch {
                when (val outcome = RecordManager.saveText(this@MainActivity, text, "manual")) {
                    is SaveOutcome.Success -> { et.setText(""); reload(toTop = true) }
                    is SaveOutcome.Duplicate -> toast("Trùng lặp >68% với bản ghi đã có.")
                    is SaveOutcome.NotLoggedIn -> toast("Chưa đăng nhập.")
                    is SaveOutcome.Error -> toast("Lỗi: ${outcome.message}")
                }
                btn.isEnabled = true
            }
        }

        // Tìm kiếm chạy trên SQLite (nền) và chỉ chạy sau khi ngừng gõ 250ms
        findViewById<EditText>(R.id.etSearch).addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                query = s?.toString().orEmpty()
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(SEARCH_DEBOUNCE_MS)
                    reload(toTop = true)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        findViewById<Button>(R.id.btnExport).setOnClickListener { promptBackupPassword() }
        findViewById<Button>(R.id.btnImport).setOnClickListener { openBackupLauncher.launch(arrayOf("application/json")) }
        findViewById<Button>(R.id.btnSync).setOnClickListener { startSync(full = true) }
    }

    // ---------- Danh sách: đọc từ cache cục bộ, phân trang ----------

    private fun reload(toTop: Boolean = false) {
        listJob?.cancel()
        val gen = ++generation
        loadingMore = false
        val q = query
        listJob = lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                Triple(db.page(q, null, PAGE), db.count(), if (q.isBlank()) -1 else db.countMatching(q))
            }
            if (gen != generation) return@launch
            val rows = data.first
            total = data.second
            matching = data.third
            cursor = rows.lastOrNull()?.let { RecordDb.PageCursor(it.savedAt, it.key) }
            hasMore = rows.size == PAGE
            adapter.replaceAll(rows)
            if (toTop) findViewById<RecyclerView>(R.id.recyclerView).scrollToPosition(0)
            findViewById<TextView>(R.id.tvEmpty).visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            updateCount()
        }
    }

    private fun loadMore() {
        if (loadingMore || !hasMore) return
        val c = cursor ?: return
        loadingMore = true
        val gen = generation
        val q = query
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { db.page(q, c, PAGE) }
            if (gen != generation) return@launch
            adapter.append(rows)
            cursor = rows.lastOrNull()?.let { RecordDb.PageCursor(it.savedAt, it.key) } ?: c
            hasMore = rows.size == PAGE
            loadingMore = false
        }
    }

    private fun updateCount() {
        val base = if (matching >= 0) "Khớp: $matching · Tổng bản ghi: $total" else "Tổng bản ghi: $total"
        findViewById<TextView>(R.id.tvCount).text = base + statusExtra
    }

    private fun setStatus(msg: String) {
        statusExtra = if (msg.isEmpty()) "" else " · $msg"
        updateCount()
    }

    // ---------- Đồng bộ nền ----------

    private fun startSync(full: Boolean) {
        if (syncJob?.isActive == true) { if (full) toast("Đang đồng bộ…"); return }
        syncJob = lifecycleScope.launch {
            val auth = FirebaseClient.getValidAuth(this@MainActivity)
            if (auth == null) { if (full) toast("Không xác thực được (kiểm tra kết nối mạng)."); return@launch }
            setStatus("đang đồng bộ…")
            val res = SyncManager.sync(this@MainActivity, auth, full) { n ->
                runOnUiThread {
                    setStatus("đã tải $n")
                    if (adapter.itemCount < PAGE) reload()   // lần đầu: cho xem dữ liệu ngay khi những trang đầu về
                }
            }
            setStatus("")
            when (res) {
                is ApiResult.Success -> {
                    val s = res.data
                    if (s.added > 0 || s.removed > 0 || full) reload()
                    if (full) toast("Đồng bộ xong: +${s.added}, -${s.removed}")
                }
                is ApiResult.Failure -> toast("Đồng bộ lỗi: ${res.message}")
            }
        }
    }

    private fun confirmDelete(row: RecordRow) {
        AlertDialog.Builder(this)
            .setTitle("Xoá bản ghi?")
            .setMessage(row.original.take(80))
            .setPositiveButton("Xoá") { _, _ ->
                lifecycleScope.launch {
                    val auth = FirebaseClient.getValidAuth(this@MainActivity) ?: return@launch
                    if (FirebaseClient.deleteRecord(auth, row.key)) {
                        withContext(Dispatchers.IO) { db.delete(row.key) }
                        adapter.remove(row.key)
                        total = maxOf(0, total - 1)
                        if (matching > 0) matching--
                        updateCount()
                    } else {
                        toast("Xoá thất bại (kiểm tra kết nối mạng).")
                    }
                }
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    // ---------- Cài đặt múi giờ ----------

    private fun showTimezoneDialog() {
        val current = Prefs.getSelectedTimezones(this).toMutableList()
        // Lọc bỏ code cũ không còn trong danh sách (ví dụ "US" → "US_NY")
        current.retainAll { code -> SUPPORTED_TIMEZONES.any { it.code == code } }
        val labels = SUPPORTED_TIMEZONES.map { it.label }.toTypedArray()
        val checked = SUPPORTED_TIMEZONES.map { current.contains(it.code) }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("Chọn múi giờ lưu trữ (${SUPPORTED_TIMEZONES.size} vùng)")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val code = SUPPORTED_TIMEZONES[which].code
                if (isChecked) {
                    if (!current.contains(code)) current.add(code)
                } else {
                    current.remove(code)
                }
            }
            .setPositiveButton("Lưu") { _, _ ->
                val finalList = if (current.isEmpty()) listOf("VN") else current
                Prefs.setSelectedTimezones(this, finalList)
                toast("Đã lưu ${finalList.size} múi giờ.")
            }
            .setNeutralButton("Chọn mặc định") { _, _ ->
                Prefs.setSelectedTimezones(this, Prefs.DEFAULT_TIMEZONES)
                toast("Đã đặt lại múi giờ mặc định.")
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    // ---------- Backup / Khôi phục (AES) ----------

    private var pendingBackupPassword: String? = null
    private var pendingImportUri: Uri? = null

    private fun promptBackupPassword() {
        val input = EditText(this).apply { hint = "Mật khẩu mã hoá AES"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        AlertDialog.Builder(this)
            .setTitle("Mã hoá xuất tệp backup")
            .setView(input)
            .setPositiveButton("Tiếp tục") { _, _ ->
                val pwd = input.text.toString()
                if (pwd.isBlank()) { toast("Vui lòng nhập mật khẩu."); return@setPositiveButton }
                pendingBackupPassword = pwd
                val filename = "YDucDB-Backup-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.json"
                createBackupLauncher.launch(filename)
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    /** Ghi trực tiếp từ SQLite ra tệp theo dòng (không dựng cả JSON 20k bản ghi trong RAM). Định dạng giữ nguyên. */
    private fun writeBackupToUri(uri: Uri) {
        val pwd = pendingBackupPassword ?: return
        if (busy) { toast("Đang xử lý, vui lòng đợi."); return }
        busy = true
        lifecycleScope.launch {
            setStatus("đang xuất backup…")
            val result = withContext(Dispatchers.IO) {
                try {
                    if (db.count() == 0) return@withContext -1
                    val codec = CryptoUtils.AesCodec(pwd)
                    var n = 0
                    val os = contentResolver.openOutputStream(uri) ?: return@withContext -2
                    JsonWriter(OutputStreamWriter(os.buffered(), Charsets.UTF_8)).use { w ->
                        w.setIndent("  ")
                        w.beginObject()
                        db.forEachRecord { r ->
                            w.name(r.key)
                            writeJsonObject(w, r.toJson(originalOverride = codec.encrypt(r.original)))
                            n++
                        }
                        w.endObject()
                    }
                    n
                } catch (e: Exception) { -2 }
            }
            busy = false
            setStatus("")
            when (result) {
                -1 -> toast("Chưa có dữ liệu để xuất.")
                -2 -> toast("Lỗi ghi file backup.")
                else -> toast("Đã lưu file backup ($result bản ghi).")
            }
        }
    }

    private fun writeJsonObject(w: JsonWriter, o: JSONObject) {
        w.beginObject()
        val it = o.keys()
        while (it.hasNext()) { val k = it.next(); w.name(k); writeJsonValue(w, o.get(k)) }
        w.endObject()
    }

    private fun writeJsonValue(w: JsonWriter, v: Any?) {
        when (v) {
            null, JSONObject.NULL -> w.nullValue()
            is JSONObject -> writeJsonObject(w, v)
            is JSONArray -> { w.beginArray(); for (i in 0 until v.length()) writeJsonValue(w, v.get(i)); w.endArray() }
            is Number -> w.value(v)
            is Boolean -> w.value(v)
            else -> w.value(v.toString())
        }
    }

    private fun promptImportPassword(uri: Uri) {
        pendingImportUri = uri
        val input = EditText(this).apply { hint = "Mật khẩu giải mã"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        AlertDialog.Builder(this)
            .setTitle("Giải mã dữ liệu khôi phục")
            .setView(input)
            .setPositiveButton("Khôi phục") { _, _ -> doImport(input.text.toString()) }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private class BackupRead(val texts: List<String>, val totalRecords: Int)

    /** Đọc tệp backup dạng luồng, chỉ lấy trường "original" rồi giải mã (khoá AES tính một lần). */
    private fun readBackup(uri: Uri, password: String): BackupRead {
        val codec = CryptoUtils.AesCodec(password)
        val texts = ArrayList<String>()
        var total = 0
        contentResolver.openInputStream(uri)?.use { ins ->
            JsonReader(InputStreamReader(ins.buffered(), Charsets.UTF_8)).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    r.nextName()
                    total++
                    r.beginObject()
                    var enc: String? = null
                    while (r.hasNext()) {
                        val name = r.nextName()
                        if (name == "original" && r.peek() == JsonToken.STRING) enc = r.nextString() else r.skipValue()
                    }
                    r.endObject()
                    if (enc != null) codec.decrypt(enc)?.let { texts.add(it) }
                }
                r.endObject()
            }
        }
        return BackupRead(texts, total)
    }

    private fun doImport(password: String) {
        val uri = pendingImportUri ?: return
        if (busy) { toast("Đang xử lý, vui lòng đợi."); return }
        busy = true
        lifecycleScope.launch {
            try {
                setStatus("đang đọc file…")
                val backup = try {
                    withContext(Dispatchers.IO) { readBackup(uri, password) }
                } catch (e: Exception) {
                    toast("Lỗi giải mã: sai mật khẩu hoặc sai định dạng tệp.")
                    return@launch
                }
                if (backup.totalRecords > 0 && backup.texts.isEmpty()) {
                    toast("Lỗi giải mã: sai mật khẩu hoặc sai định dạng tệp.")
                    return@launch
                }
                val res = RecordManager.importTexts(this@MainActivity, backup.texts) { msg ->
                    runOnUiThread { setStatus(msg) }
                }
                if (res.error != null) toast("Khôi phục dừng giữa chừng: ${res.error} (đã khôi phục ${res.imported} bản ghi)")
                else toast("Khôi phục ${res.imported} bản ghi" + if (res.skipped > 0) ", bỏ qua ${res.skipped}" else "")
                reload()
            } finally {
                busy = false
                setStatus("")
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
