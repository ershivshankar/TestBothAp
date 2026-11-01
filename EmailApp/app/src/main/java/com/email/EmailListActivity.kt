package com.email

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.email.databinding.ActivityEmailListBinding

class EmailListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEmailListBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEmailListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Inbox"

        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Inbox"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Sent"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Drafts"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Spam"))
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
