package com.email

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.email.databinding.ActivityEmailDetailBinding

class EmailDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEmailDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEmailDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val sender = intent.getStringExtra("sender") ?: "Unknown Sender"
        val subject = intent.getStringExtra("subject") ?: ""
        val preview = intent.getStringExtra("preview") ?: ""
        val time = intent.getStringExtra("time") ?: ""

        supportActionBar?.title = subject
        binding.tvDetailSender.text = sender
        binding.tvDetailTime.text = time
        binding.tvDetailSubject.text = subject
        binding.tvDetailBody.text = buildFullBody(sender, preview)
        binding.tvDetailAvatar.text = sender.first().uppercase()
    }

    private fun buildFullBody(sender: String, preview: String): String {
        return """$preview

This message was sent securely via end-to-end encryption.

Best regards,
$sender

──────────────────────────────────────────
This email and any attachments are confidential and may be legally privileged. 
If you are not the intended recipient, please notify the sender immediately."""
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
