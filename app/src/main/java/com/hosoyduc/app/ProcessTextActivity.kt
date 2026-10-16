package com.hosoyduc.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

class ProcessTextActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_process_text)

        val selectedText = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        val tvPreview = findViewById<android.widget.TextView>(R.id.tvPreview)
        val tvStatus = findViewById<android.widget.TextView>(R.id.tvStatus)
        val btnSave = findViewById<android.widget.Button>(R.id.btnSave)
        val btnCancel = findViewById<android.widget.Button>(R.id.btnCancel)

        tvPreview.text = selectedText.ifBlank { "(Không có văn bản được chọn)" }

        lifecycleScope.launch {
            val auth = FirebaseClient.getValidAuth(this@ProcessTextActivity)
            if (auth == null) {
                tvStatus.text = "Chưa đăng nhập hoặc không có kết nối mạng. Hãy mở app Hồ Sơ Y Đức để kiểm tra."
                btnSave.isEnabled = false
            } else {
                tvStatus.text = "Đăng nhập: ${auth.email}"
            }
        }

        btnCancel.setOnClickListener { finish() }

        btnSave.setOnClickListener {
            btnSave.isEnabled = false
            tvStatus.text = "Đang lưu..."
            lifecycleScope.launch {
                when (val outcome = RecordManager.saveText(this@ProcessTextActivity, selectedText, "process_text")) {
                    is SaveOutcome.Success -> { tvStatus.text = "✅ Đã lưu thành công."; finishDelayed() }
                    is SaveOutcome.Duplicate -> { tvStatus.text = "⚠️ Trùng lặp >68% với bản ghi đã có."; btnSave.isEnabled = true }
                    is SaveOutcome.NotLoggedIn -> { tvStatus.text = "Chưa đăng nhập."; btnSave.isEnabled = true }
                    is SaveOutcome.Error -> { tvStatus.text = "Lỗi: ${outcome.message}"; btnSave.isEnabled = true }
                }
            }
        }
    }

    private fun finishDelayed() {
        findViewById<android.view.View>(android.R.id.content).postDelayed({ finish() }, 800)
    }
}
