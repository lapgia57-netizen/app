package com.hosoyduc.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private var isRegisterMode = false
    private var allRecords: List<RecordItem> = emptyList()
    private lateinit var adapter: RecordsAdapter

    // Launchers cho chọn file (Storage Access Framework) - không cần xin quyền bộ nhớ
    private val createBackupLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) writeBackupToUri(uri) }

    private val openBackupLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) promptImportPassword(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        adapter = RecordsAdapter(emptyList()) { item -> confirmDelete(item) }
        findViewById<RecyclerView>(R.id.recyclerView).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        setupAuthUI()
        setupDashboardUI()
        checkLoginState()
    }

    private fun checkLoginState() {
        lifecycleScope.launch {
            val auth = FirebaseClient.getValidAuth(this@MainActivity)
            if (auth != null) showDashboard(auth) else showAuth()
        }
    }

    private fun showAuth() {
        findViewById<View>(R.id.authContainer).visibility = View.VISIBLE
        findViewById<View>(R.id.dashboardContainer).visibility = View.GONE
    }

    private fun showDashboard(auth: AuthData) {
        findViewById<View>(R.id.authContainer).visibility = View.GONE
        findViewById<View>(R.id.dashboardContainer).visibility = View.VISIBLE
        findViewById<TextView>(R.id.tvUserInfo).text = "${auth.displayName}\n${auth.email}"
        loadRecords()
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
                        showDashboard(result.data)
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
            showAuth()
        }

        findViewById<Button>(R.id.btnTimezoneSettings).setOnClickListener { showTimezoneDialog() }

        findViewById<Button>(R.id.btnManualSave).setOnClickListener {
            val et = findViewById<EditText>(R.id.etManualInput)
            val text = et.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            lifecycleScope.launch {
                when (val outcome = RecordManager.saveText(this@MainActivity, text, "manual")) {
                    is SaveOutcome.Success -> { et.setText(""); loadRecords() }
                    is SaveOutcome.Duplicate -> toast("Trùng lặp >68% với bản ghi đã có.")
                    is SaveOutcome.NotLoggedIn -> toast("Chưa đăng nhập.")
                    is SaveOutcome.Error -> toast("Lỗi: ${outcome.message}")
                }
            }
        }

        findViewById<EditText>(R.id.etSearch).addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { applyFilter(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        findViewById<Button>(R.id.btnExport).setOnClickListener { promptBackupPassword() }
        findViewById<Button>(R.id.btnImport).setOnClickListener { openBackupLauncher.launch(arrayOf("application/json")) }
    }

    private fun loadRecords() {
        lifecycleScope.launch {
            val auth = FirebaseClient.getValidAuth(this@MainActivity) ?: return@launch showAuth()
            when (val res = FirebaseClient.readAllRecords(auth)) {
                is ApiResult.Success -> {
                    val list = mutableListOf<RecordItem>()
                    val keys = res.data.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        list.add(RecordItem(k, res.data.getJSONObject(k)))
                    }
                    list.sortByDescending { it.json.optLong("savedAt", 0L) }
                    allRecords = list
                    findViewById<TextView>(R.id.tvCount).text = "Tổng bản ghi: ${list.size}"
                    applyFilter(findViewById<EditText>(R.id.etSearch).text.toString())
                }
                is ApiResult.Failure -> toast("Lỗi tải dữ liệu: ${res.message}")
            }
        }
    }

    private fun applyFilter(query: String) {
        val q = query.lowercase().trim()
        val filtered = if (q.isEmpty()) allRecords else allRecords.filter {
            it.json.optString("original", "").lowercase().contains(q) ||
            it.json.optString("sha", "").lowercase().contains(q) ||
            it.json.optString("source", "").lowercase().contains(q)
        }
        adapter.updateItems(filtered)
        findViewById<TextView>(R.id.tvEmpty).visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun confirmDelete(item: RecordItem) {
        AlertDialog.Builder(this)
            .setTitle("Xoá bản ghi?")
            .setMessage(item.json.optString("original", "").take(80))
            .setPositiveButton("Xoá") { _, _ ->
                lifecycleScope.launch {
                    val auth = FirebaseClient.getValidAuth(this@MainActivity) ?: return@launch
                    FirebaseClient.deleteRecord(auth, item.key)
                    loadRecords()
                }
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    // ---------- Cài đặt múi giờ ----------

    private fun showTimezoneDialog() {
        val current = Prefs.getSelectedTimezones(this).toMutableList()
        val labels = SUPPORTED_TIMEZONES.map { it.label }.toTypedArray()
        val checked = SUPPORTED_TIMEZONES.map { current.contains(it.code) }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("Chọn múi giờ để lưu trữ")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val code = SUPPORTED_TIMEZONES[which].code
                if (isChecked) { if (!current.contains(code)) current.add(code) }
                else current.remove(code)
            }
            .setPositiveButton("Lưu") { _, _ ->
                val finalList = if (current.isEmpty()) listOf("VN") else current
                Prefs.setSelectedTimezones(this, finalList)
                toast("Đã cập nhật múi giờ lưu trữ.")
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    // ---------- Backup / Khôi phục (AES) ----------

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

    private var pendingBackupPassword: String? = null

    private fun writeBackupToUri(uri: Uri) {
        val pwd = pendingBackupPassword ?: return
        if (allRecords.isEmpty()) { toast("Chưa có dữ liệu để xuất."); return }

        val out = JSONObject()
        allRecords.forEach { item ->
            val copy = JSONObject(item.json.toString())
            val original = copy.optString("original", "")
            copy.put("original", CryptoUtils.aesEncrypt(original, pwd))
            out.put(item.key, copy)
        }

        contentResolver.openOutputStream(uri)?.use { it.write(out.toString(2).toByteArray()) }
        toast("Đã lưu file backup.")
    }

    private var pendingImportUri: Uri? = null

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

    private fun doImport(password: String) {
        val uri = pendingImportUri ?: return
        lifecycleScope.launch {
            val text = withContextIO { contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: "" }
            try {
                val imported = JSONObject(text)
                val keys = imported.keys()
                var success = 0; var skipped = 0
                val currentOriginals = allRecords.map { it.json.optString("original", "") }.toMutableList()

                while (keys.hasNext()) {
                    val k = keys.next()
                    val item = imported.getJSONObject(k)
                    val decrypted = CryptoUtils.aesDecrypt(item.optString("original", ""), password) ?: continue

                    val isDup = currentOriginals.any { CryptoUtils.similarity(decrypted, it) > 0.68 }
                    if (isDup) { skipped++; continue }

                    val outcome = RecordManager.saveText(this@MainActivity, decrypted, "import")
                    if (outcome is SaveOutcome.Success) { currentOriginals.add(decrypted); success++ } else { skipped++ }
                }
                toast("Khôi phục $success bản ghi" + if (skipped > 0) ", bỏ qua $skipped" else "")
                loadRecords()
            } catch (e: Exception) {
                toast("Lỗi giải mã: sai mật khẩu hoặc sai định dạng tệp.")
            }
        }
    }

    private suspend fun <T> withContextIO(block: () -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
