package com.renyaa.freewall_and

import android.content.Context
import android.content.SharedPreferences

/**
 * macOS freewall의 AppSettings와 1:1로 대응되는 설정 관리 클래스
 */
class FreewallPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("freewall_prefs", Context.MODE_PRIVATE)

    companion object {
        const val KEY_PORT = "proxyPort"
        const val KEY_DNS_MODE = "dnsMode"
        const val KEY_DNS_ADDR = "dnsAddr"
        const val KEY_DNS_HTTPS_URL = "dnsHttpsUrl"
        const val KEY_HTTPS_SPLIT_MODE = "httpsSplitMode"
        const val KEY_HTTPS_CHUNK_SIZE = "httpsChunkSize"
        const val KEY_HTTPS_DISORDER = "httpsDisorder"
        const val KEY_AUTO_START = "autoStartProtection"

        @Volatile
        private var INSTANCE: FreewallPreferences? = null

        fun getInstance(context: Context): FreewallPreferences {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FreewallPreferences(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    var port: Int
        get() = prefs.getInt(KEY_PORT, 8080)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var dnsMode: String
        get() = prefs.getString(KEY_DNS_MODE, "udp") ?: "udp"
        set(value) = prefs.edit().putString(KEY_DNS_MODE, value).apply()

    var dnsAddr: String
        get() = prefs.getString(KEY_DNS_ADDR, "9.9.9.9:9953") ?: "9.9.9.9:9953"
        set(value) = prefs.edit().putString(KEY_DNS_ADDR, value).apply()

    var dnsHttpsUrl: String
        get() = prefs.getString(KEY_DNS_HTTPS_URL, "https://dns.google/dns-query") ?: "https://dns.google/dns-query"
        set(value) = prefs.edit().putString(KEY_DNS_HTTPS_URL, value).apply()

    var httpsSplitMode: String
        get() = prefs.getString(KEY_HTTPS_SPLIT_MODE, "chunk") ?: "chunk"
        set(value) = prefs.edit().putString(KEY_HTTPS_SPLIT_MODE, value).apply()

    var httpsChunkSize: Int
        get() = prefs.getInt(KEY_HTTPS_CHUNK_SIZE, 1)
        set(value) = prefs.edit().putInt(KEY_HTTPS_CHUNK_SIZE, value).apply()

    var httpsDisorder: Boolean
        get() = prefs.getBoolean(KEY_HTTPS_DISORDER, true)
        set(value) = prefs.edit().putBoolean(KEY_HTTPS_DISORDER, value).apply()

    var autoStartProtection: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    /**
     * macOS freewall Extreme Bypass 프리셋 적용
     */
    fun applyExtremeBypassPreset() {
        prefs.edit()
            .putString(KEY_DNS_MODE, "udp")
            .putString(KEY_DNS_ADDR, "9.9.9.9:9953")
            .putString(KEY_HTTPS_SPLIT_MODE, "chunk")
            .putInt(KEY_HTTPS_CHUNK_SIZE, 1)
            .putBoolean(KEY_HTTPS_DISORDER, true)
            .apply()
    }

    /**
     * macOS freewall Standard 프리셋 적용
     */
    fun applyStandardPreset() {
        prefs.edit()
            .putString(KEY_DNS_MODE, "https")
            .putString(KEY_DNS_HTTPS_URL, "https://dns.google/dns-query")
            .putString(KEY_HTTPS_SPLIT_MODE, "sni")
            .putInt(KEY_HTTPS_CHUNK_SIZE, 35)
            .putBoolean(KEY_HTTPS_DISORDER, false)
            .apply()
    }
}
