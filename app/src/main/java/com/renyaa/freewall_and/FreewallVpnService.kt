package com.renyaa.freewall_and

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class FreewallVpnService : VpnService() {

    companion object {
        private const val TAG = "FreewallVpnService"
        const val ACTION_START = "com.renyaa.freewall_and.START"
        const val ACTION_STOP = "com.renyaa.freewall_and.STOP"
        const val NOTIFICATION_CHANNEL_ID = "freewall_service_channel"
        const val NOTIFICATION_ID = 1001

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var prefs: FreewallPreferences
    private lateinit var bypassEngine: DpiBypassEngine
    private lateinit var dnsResolver: DnsResolver

    private var localProxyServer: ServerSocket? = null
    private var spoofDpiProcess: java.lang.Process? = null
    private val activeConnections = ConcurrentHashMap<String, Socket>()

    override fun onCreate() {
        super.onCreate()
        prefs = FreewallPreferences.getInstance(this)
        dnsResolver = DnsResolver(this, prefs)
        bypassEngine = DpiBypassEngine(this, prefs, dnsResolver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }

        startVpn()
        return START_NOT_STICKY
    }

    private fun startVpn() {
        if (isRunning) return

        try {
            createNotificationChannel()
            val notification = buildNotification("DPI 우회 보호가 활성화되어 있습니다")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    0
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground 예외 무시 (VpnService 자체 동작 유지): ${e.message}")
        }

        try {
            // 로컬 DPI 우회 프록시 서버 시작 (127.0.0.1:8080)
            startLocalProxyServer()

            // VPN 터널 인터페이스 구성 (가상 주소 및 프록시 설정)
            val builder = Builder()
                .setSession("Freewall")
                .addAddress("10.233.0.2", 30)
                .addRoute("10.233.0.0", 30)

            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                Log.w(TAG, "addDisallowedApplication 예외: ${e.message}")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", prefs.port))
                } catch (e: Exception) {
                    Log.w(TAG, "ProxyInfo 설정 실패: ${e.message}")
                }
            }

            vpnInterface = builder.establish()

            isRunning = true
            updateTileState()

            AppLogger.log("🛡️ Freewall 보호가 시작되었습니다 (포트: ${prefs.port})")
            Log.i(TAG, "Freewall VPN 및 DPI 우회 프록시 시작 성공 (포트: ${prefs.port})")
        } catch (e: Exception) {
            AppLogger.log("❌ VPN 시작 실패: ${e.message}", true)
            Log.e(TAG, "VPN 시작 실패: ${e.message}", e)
            stopVpn()
        }
    }

    private fun stopVpn() {
        isRunning = false
        updateTileState()

        try {
            spoofDpiProcess?.destroy()
        } catch (_: Exception) {}
        spoofDpiProcess = null

        try {
            localProxyServer?.close()
        } catch (_: Exception) {}
        localProxyServer = null

        for ((_, socket) in activeConnections) {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
        activeConnections.clear()

        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        AppLogger.log("🛑 Freewall 보호가 중지되었습니다")
        Log.i(TAG, "Freewall VPN 중지됨")
    }



    /**
     * 로컬 HTTP CONNECT / SOCKS 프록시 서버
     */
    private fun startLocalProxyServer() {
        val port = prefs.port
        val server = ServerSocket(port)
        localProxyServer = server

        serviceScope.launch {
            while (isActive && !server.isClosed) {
                try {
                    val clientSocket = server.accept()
                    clientSocket.tcpNoDelay = true
                    launch {
                        handleProxyClient(clientSocket)
                    }
                } catch (e: Exception) {
                    if (server.isClosed) break
                }
            }
        }
    }

    /**
     * 클라이언트 프록시 연결 처리 (HTTP CONNECT 파싱 및 DPI 우회 중계)
     */
    private fun handleProxyClient(clientSocket: Socket) {
        val clientId = "${clientSocket.inetAddress}:${clientSocket.port}"
        activeConnections[clientId] = clientSocket

        try {
            clientSocket.tcpNoDelay = true
            clientSocket.sendBufferSize = 131072
            clientSocket.receiveBufferSize = 131072

            val bufferedIn = java.io.BufferedInputStream(clientSocket.getInputStream(), 8192)
            val clientOut = clientSocket.getOutputStream()

            // HTTP 요청 첫 줄 읽기 (예: "CONNECT example.com:443 HTTP/1.1")
            val requestLine = readLine(bufferedIn) ?: return
            val parts = requestLine.split(" ")

            if (parts.size >= 2 && parts[0].equals("CONNECT", ignoreCase = true)) {
                val hostPort = parts[1].split(":")
                val host = hostPort[0]
                val remotePort = if (hostPort.size > 1) hostPort[1].toInt() else 443

                // HTTP 200 Connection Established 응답을 위한 헤더 소진
                while (true) {
                    val header = readLine(bufferedIn) ?: break
                    if (header.isEmpty()) break
                }

                try {
                    // 원격 서버 소켓 연결 (VpnService.protect() 적용됨)
                    val remoteSocket = bypassEngine.connectRemote(host, remotePort)
                    val remoteIn = remoteSocket.getInputStream()
                    val remoteOut = remoteSocket.getOutputStream()

                    // 200 Connection Established 회신
                    clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                    clientOut.flush()

                    // 최초 페이로드 읽기 (TLS ClientHello)
                    val buffer = ByteArray(16384)
                    val initialRead = bufferedIn.read(buffer)
                    if (initialRead > 0) {
                        // DPI 우회 적용 전송
                        bypassEngine.sendWithBypass(remoteOut, buffer, initialRead)

                        // 양방향 데이터 중계
                        val job1 = serviceScope.launch {
                            pipeData(bufferedIn, remoteOut)
                        }
                        val job2 = serviceScope.launch {
                            pipeData(remoteIn, clientOut)
                        }

                        job1.invokeOnCompletion { 
                            try { remoteSocket.shutdownOutput() } catch (_: Exception) {}
                        }
                        job2.invokeOnCompletion { 
                            try { clientSocket.shutdownOutput() } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    try {
                        clientOut.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                        clientOut.flush()
                    } catch (_: Exception) {}
                }
            } else if (parts.size >= 2) {
                // 일반 HTTP GET / POST 요청 처리
                try {
                    val urlStr = parts[1]
                    val uri = java.net.URI(urlStr)
                    val host = uri.host ?: parts[1].split("/")[0]
                    val port = if (uri.port != -1) uri.port else 80

                    val remoteSocket = bypassEngine.connectRemote(host, port)
                    val remoteIn = remoteSocket.getInputStream()
                    val remoteOut = remoteSocket.getOutputStream()

                    // 원본 HTTP 요청 라인 및 헤더 재전송
                    val newPath = if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath + if (uri.rawQuery != null) "?${uri.rawQuery}" else ""
                    val newRequestLine = "${parts[0]} $newPath ${if (parts.size > 2) parts[2] else "HTTP/1.1"}\r\n"
                    remoteOut.write(newRequestLine.toByteArray())

                    var headerLine: String?
                    while (readLine(bufferedIn).also { headerLine = it } != null) {
                        if (headerLine.isNullOrEmpty()) break
                        remoteOut.write("$headerLine\r\n".toByteArray())
                    }
                    remoteOut.write("\r\n".toByteArray())
                    remoteOut.flush()

                    val job1 = serviceScope.launch { pipeData(bufferedIn, remoteOut) }
                    val job2 = serviceScope.launch { pipeData(remoteIn, clientOut) }

                    job1.invokeOnCompletion { try { remoteSocket.shutdownOutput() } catch (_: Exception) {} }
                    job2.invokeOnCompletion { try { clientSocket.shutdownOutput() } catch (_: Exception) {} }
                } catch (e: Exception) {
                    try { clientSocket.close() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            // 연결 종료 처리
        } finally {
            activeConnections.remove(clientId)
        }
    }

    private fun pipeData(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(65536)
        try {
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                output.flush()
            }
        } catch (_: Exception) {}
    }

    private fun readLine(input: InputStream): String? {
        val sb = java.lang.StringBuilder()
        var b: Int
        while (input.read().also { b = it } != -1) {
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return if (sb.isNotEmpty() || b != -1) sb.toString() else null
    }

    /**
     * 가상 TUN 인터페이스 패킷 수신 루프 (UDP DNS 패킷 가로채기 및 안전한 DoH/Quad9 처리)
     */
    private fun handleTunPackets() {
        val fd = vpnInterface?.fileDescriptor ?: return
        val vpnInput = FileInputStream(fd)
        val vpnOutput = FileOutputStream(fd)
        val packetBuffer = ByteArray(32767)

        while (isRunning && !Thread.currentThread().isInterrupted) {
            try {
                val length = vpnInput.read(packetBuffer)
                if (length <= 0) continue

                val parsed = PacketUtils.parse(packetBuffer, length) ?: continue

                // 포트 53 UDP DNS 쿼리 가로채기
                if (parsed.protocol == PacketUtils.PROTOCOL_UDP && parsed.dstPort == 53) {
                    val queryData = ByteArray(parsed.payloadLength)
                    System.arraycopy(packetBuffer, parsed.payloadOffset, queryData, 0, parsed.payloadLength)

                    serviceScope.launch {
                        val responseData = dnsResolver.resolve(queryData)
                        if (responseData != null) {
                            val respPacket = PacketUtils.buildUdpPacket(
                                srcIpInt = parsed.dstIpInt,
                                dstIpInt = parsed.srcIpInt,
                                srcPort = parsed.dstPort,
                                dstPort = parsed.srcPort,
                                payload = responseData
                            )
                            synchronized(vpnOutput) {
                                try {
                                    vpnOutput.write(respPacket)
                                    vpnOutput.flush()
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (!isRunning) break
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Freewall 서비스",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Freewall DPI 우회 서비스 실행 상태 알림"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, FreewallVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Freewall 실행 중")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "중지", stopPendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build().apply {
                flags = flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT
            }
    }

    private fun updateTileState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            TileService.requestListeningState(
                this,
                ComponentName(this, FreewallTileService::class.java)
            )
        }
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
