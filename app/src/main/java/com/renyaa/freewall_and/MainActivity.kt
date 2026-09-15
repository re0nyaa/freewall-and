package com.renyaa.freewall_and

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: FreewallPreferences

    // 상단 헤더
    private lateinit var viewStatusDot: View
    private lateinit var tvStatusPill: TextView

    // 3대 뷰 컨테이너 (홈 / 설정 / 로그)
    private lateinit var viewHome: View
    private lateinit var viewSettings: View
    private lateinit var viewLogs: View
    private lateinit var bottomNav: BottomNavigationView

    // 홈 (보호) 화면 위젯
    private lateinit var viewPowerRing: View
    private lateinit var btnPower: MaterialCardView
    private lateinit var ivPowerSymbol: ImageView
    private lateinit var tvPowerAction: TextView
    private lateinit var viewStatusCenterDot: View
    private lateinit var tvStatusHeadline: TextView
    private lateinit var tvStatusDescription: TextView
    private lateinit var badgePreset: TextView
    private lateinit var badgeSni: TextView

    // 설정 화면 위젯
    private lateinit var rgPresets: RadioGroup
    private lateinit var rbPresetExtreme: RadioButton
    private lateinit var rbPresetStandard: RadioButton
    private lateinit var rbPresetFast: RadioButton
    private lateinit var switchDns: SwitchMaterial
    private lateinit var tvDnsInfo: TextView
    private lateinit var switchAutoStart: SwitchMaterial

    // 로그 화면 위젯
    private lateinit var tvLogCount: TextView
    private lateinit var chkAutoScroll: CheckBox
    private lateinit var btnCopyLogs: Button
    private lateinit var btnClearLogs: Button
    private lateinit var scrollLogs: ScrollView
    private lateinit var tvLogs: TextView

    private var isUpdatingUi = false

    private val logListener: (LogEntry) -> Unit = { entry ->
        runOnUiThread {
            appendLogToUi(entry)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        requestVpnAndStart()
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, "VPN 권한이 거부되어 보호를 시작할 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = FreewallPreferences.getInstance(this)
        initViews()
        setupListeners()
        loadLogs()
        updateSettingsUi()

        if (prefs.autoStartProtection && !FreewallVpnService.isRunning) {
            checkNotificationAndStartVpn()
        }
    }

    override fun onResume() {
        super.onResume()
        AppLogger.addListener(logListener)
        updateConnectionStateUi()
        updateSettingsUi()
    }

    override fun onPause() {
        super.onPause()
        AppLogger.removeListener(logListener)
    }

    private fun initViews() {
        // 상단 헤더
        viewStatusDot = findViewById(R.id.view_status_dot)
        tvStatusPill = findViewById(R.id.tv_status_pill)

        // 탭 컨테이너 & 네비게이션
        viewHome = findViewById(R.id.view_home)
        viewSettings = findViewById(R.id.view_settings)
        viewLogs = findViewById(R.id.view_logs)
        bottomNav = findViewById(R.id.bottom_nav)

        // 홈 위젯
        viewPowerRing = findViewById(R.id.view_power_ring)
        btnPower = findViewById(R.id.btn_power)
        ivPowerSymbol = findViewById(R.id.iv_power_symbol)
        tvPowerAction = findViewById(R.id.tv_power_action)
        viewStatusCenterDot = findViewById(R.id.view_status_center_dot)
        tvStatusHeadline = findViewById(R.id.tv_status_headline)
        tvStatusDescription = findViewById(R.id.tv_status_description)
        badgePreset = findViewById(R.id.badge_preset)
        badgeSni = findViewById(R.id.badge_sni)

        // 설정 위젯
        rgPresets = findViewById(R.id.rg_presets)
        rbPresetExtreme = findViewById(R.id.rb_preset_extreme)
        rbPresetStandard = findViewById(R.id.rb_preset_standard)
        rbPresetFast = findViewById(R.id.rb_preset_fast)
        switchDns = findViewById(R.id.switch_dns)
        tvDnsInfo = findViewById(R.id.tv_dns_info)
        switchAutoStart = findViewById(R.id.switch_auto_start)

        // 로그 위젯
        tvLogCount = findViewById(R.id.tv_log_count)
        chkAutoScroll = findViewById(R.id.chk_auto_scroll)
        btnCopyLogs = findViewById(R.id.btn_copy_logs)
        btnClearLogs = findViewById(R.id.btn_clear_logs)
        scrollLogs = findViewById(R.id.scroll_logs)
        tvLogs = findViewById(R.id.tv_logs)
    }

    private fun setupListeners() {
        // 바텀 네비게이션 탭 전환
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_protection -> switchTab(0)
                R.id.nav_settings -> switchTab(1)
                R.id.nav_logs -> switchTab(2)
            }
            true
        }

        // 전원 버튼 토글
        btnPower.setOnClickListener {
            btnPower.animate().scaleX(0.92f).scaleY(0.92f).setDuration(100).withEndAction {
                btnPower.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
            }.start()

            if (FreewallVpnService.isRunning) {
                stopVpnService()
            } else {
                checkNotificationAndStartVpn()
            }
        }

        // 프리셋 라디오 선택
        rgPresets.setOnCheckedChangeListener { _, checkedId ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            when (checkedId) {
                R.id.rb_preset_extreme -> {
                    prefs.applyExtremeBypassPreset()
                    Toast.makeText(this, "🚀 Chunk 1B Disorder 프리셋 적용됨", Toast.LENGTH_SHORT).show()
                }
                R.id.rb_preset_standard -> {
                    prefs.applyStandardPreset()
                    Toast.makeText(this, "⚡ Mode 1 (표준 권장) 프리셋 적용됨", Toast.LENGTH_SHORT).show()
                }
                R.id.rb_preset_fast -> {
                    prefs.dnsMode = "udp"
                    prefs.httpsSplitMode = "chunk"
                    prefs.httpsChunkSize = 2
                    prefs.httpsDisorder = false
                    Toast.makeText(this, "⚡ Mode 2 (고속 분할) 프리셋 적용됨", Toast.LENGTH_SHORT).show()
                }
            }
            updateSettingsUi()
        }

        // DNS 보안 스위치
        switchDns.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            if (isChecked) {
                prefs.dnsMode = "udp"
                prefs.dnsAddr = "9.9.9.9:9953"
            } else {
                prefs.dnsMode = "system"
            }
            updateSettingsUi()
        }

        // 자동 시작 스위치
        switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            prefs.autoStartProtection = isChecked
        }

        // 로그 제어
        btnCopyLogs.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Freewall Logs", tvLogs.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "로그가 클립보드에 복사되었습니다", Toast.LENGTH_SHORT).show()
        }

        btnClearLogs.setOnClickListener {
            AppLogger.clear()
            loadLogs()
        }
    }

    private fun switchTab(tabIndex: Int) {
        viewHome.visibility = if (tabIndex == 0) View.VISIBLE else View.GONE
        viewSettings.visibility = if (tabIndex == 1) View.VISIBLE else View.GONE
        viewLogs.visibility = if (tabIndex == 2) View.VISIBLE else View.GONE

        if (tabIndex == 2 && chkAutoScroll.isChecked) {
            scrollLogs.post { scrollLogs.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun checkNotificationAndStartVpn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        requestVpnAndStart()
    }

    private fun requestVpnAndStart() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, FreewallVpnService::class.java).apply {
            action = FreewallVpnService.ACTION_START
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "서비스 시작 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        btnPower.postDelayed({ updateConnectionStateUi() }, 300)
    }

    private fun stopVpnService() {
        val intent = Intent(this, FreewallVpnService::class.java).apply {
            action = FreewallVpnService.ACTION_STOP
        }
        startService(intent)
        btnPower.postDelayed({ updateConnectionStateUi() }, 300)
    }

    private fun updateConnectionStateUi() {
        val isRunning = FreewallVpnService.isRunning

        if (isRunning) {
            // 활성화 (freewall-win 에메랄드 그린 네온 테마)
            val accentGreen = ContextCompat.getColor(this, R.color.accent_green)
            val accentGreenBg = ContextCompat.getColor(this, R.color.accent_green_bg)
            val ringColor = ContextCompat.getColor(this, R.color.accent_green_light)

            viewStatusDot.backgroundTintList = ColorStateList.valueOf(accentGreen)
            tvStatusPill.text = "보호 활성화됨"
            tvStatusPill.setTextColor(accentGreen)

            viewStatusCenterDot.backgroundTintList = ColorStateList.valueOf(accentGreen)
            tvStatusHeadline.text = "보호 활성화됨"
            tvStatusHeadline.setTextColor(accentGreen)
            tvStatusDescription.text = "DPI 검열 및 방화벽 패킷 차단이 완벽히 우회 중입니다"

            btnPower.setCardBackgroundColor(accentGreen)
            btnPower.strokeColor = ringColor
            btnPower.strokeWidth = 2
            viewPowerRing.backgroundTintList = ColorStateList.valueOf(accentGreenBg)

            ivPowerSymbol.imageTintList = ColorStateList.valueOf(Color.WHITE)
            tvPowerAction.text = "ACTIVE"
            tvPowerAction.setTextColor(Color.WHITE)
        } else {
            // 비활성화 (freewall-win 다크 테마)
            val textMuted = ContextCompat.getColor(this, R.color.text_muted)
            val powerBtnOff = ContextCompat.getColor(this, R.color.power_btn_off)
            val ringColor = ContextCompat.getColor(this, R.color.power_btn_ring)
            val borderDark = ContextCompat.getColor(this, R.color.surface_card_border)

            viewStatusDot.backgroundTintList = ColorStateList.valueOf(textMuted)
            tvStatusPill.text = "연결 안 됨"
            tvStatusPill.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))

            viewStatusCenterDot.backgroundTintList = ColorStateList.valueOf(textMuted)
            tvStatusHeadline.text = "보호 비활성화됨"
            tvStatusHeadline.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            tvStatusDescription.text = "버튼을 눌러 DPI 패킷 우회 보호를 시작하세요"

            btnPower.setCardBackgroundColor(powerBtnOff)
            btnPower.strokeColor = borderDark
            btnPower.strokeWidth = 2
            viewPowerRing.backgroundTintList = ColorStateList.valueOf(ringColor)

            ivPowerSymbol.imageTintList = ColorStateList.valueOf(textMuted)
            tvPowerAction.text = "START"
            tvPowerAction.setTextColor(textMuted)
        }
    }

    private fun updateSettingsUi() {
        isUpdatingUi = true

        // 홈 화면 배지 텍스트 갱신
        if (prefs.httpsSplitMode == "chunk" && prefs.httpsChunkSize == 1 && prefs.httpsDisorder) {
            badgePreset.text = "Chunk 1B (Disorder)"
            badgeSni.text = "TLS SNI 파편화"
            rbPresetExtreme.isChecked = true
        } else if (prefs.httpsSplitMode == "sni") {
            badgePreset.text = "Mode 1 (표준)"
            badgeSni.text = "SNI 분할"
            rbPresetStandard.isChecked = true
        } else {
            badgePreset.text = "Mode 2 (고속)"
            badgeSni.text = "Chunk 2B"
            rbPresetFast.isChecked = true
        }

        // 설정 화면 토글 갱신
        val isDnsProtected = prefs.dnsMode != "system"
        switchDns.isChecked = isDnsProtected
        tvDnsInfo.text = if (isDnsProtected) {
            "※ 안전 보호 활성: Quad9 (${prefs.dnsAddr})"
        } else {
            "※ 통신사 기본 DNS 사용 중 (DNS 오염 위험)"
        }

        switchAutoStart.isChecked = prefs.autoStartProtection

        isUpdatingUi = false
    }

    private fun loadLogs() {
        val allLogs = AppLogger.getLogs()
        val sb = StringBuilder()
        for (log in allLogs) {
            val prefix = if (log.isError) "❌" else "•"
            sb.append("${log.formattedTime} $prefix ${log.message}\n")
        }
        tvLogs.text = sb.toString()
        tvLogCount.text = "${allLogs.size}개 라인"
        if (chkAutoScroll.isChecked) {
            scrollLogs.post { scrollLogs.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun appendLogToUi(entry: LogEntry) {
        val prefix = if (entry.isError) "❌" else "•"
        tvLogs.append("${entry.formattedTime} $prefix ${entry.message}\n")
        val currentLines = AppLogger.getLogs().size
        tvLogCount.text = "${currentLines}개 라인"

        if (chkAutoScroll.isChecked) {
            scrollLogs.post { scrollLogs.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
