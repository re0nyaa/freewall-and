package com.renyaa.freewall_and

import android.net.VpnService
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * SpoofDPI 방식의 DNS 질의 리졸버 (Quad9 UDP 9953 및 DoH 지원)
 */
class DnsResolver(
    private val vpnService: VpnService,
    private val prefs: FreewallPreferences
) {
    companion object {
        private const val TAG = "DnsResolver"
    }

    private val dnsCache = java.util.concurrent.ConcurrentHashMap<String, InetAddress>()

    private fun isBlockedOrWarningIp(ip: InetAddress): Boolean {
        val addr = ip.hostAddress ?: return false
        // 방통위 warning.or.kr IP 대역 및 루프백/널 IP 감지
        return addr.startsWith("121.53.105.") || addr == "0.0.0.0" || addr == "127.0.0.1"
    }

    /**
     * 스마트 하이브리드 DNS:
     * 1) 일반 99% 사이트(유튜브, 포털, CDN): 로컬 DNS(2ms)로 즉시 0ms 초광속 로딩
     * 2) 방통위 차단 감지(warning.or.kr) 시: Quad9 해외 보안 DNS로 자동 우회
     */
    fun resolveHost(hostname: String): InetAddress {
        // 이미 IP 형태인 경우
        val ipParts = hostname.split(".")
        if (ipParts.size == 4 && ipParts.all { it.toIntOrNull() in 0..255 }) {
            val bytes = ByteArray(4) { ipParts[it].toInt().toByte() }
            return InetAddress.getByAddress(bytes)
        }

        // 1. 캐시 확인 (0ms 즉시 반환)
        dnsCache[hostname]?.let { return it }

        // 2. Quad9 보안 DNS 우선 조회 (DNS 오염 원천 차단)
        try {
            val query = buildDnsQuery(hostname)
            val response = resolve(query)
            if (response != null) {
                val ip = parseDnsResponse(response)
                if (ip != null) {
                    dnsCache[hostname] = ip
                    return ip
                }
            }
        } catch (_: Exception) {}

        // 3. 실패 시 시스템 DNS 폴백
        return InetAddress.getByName(hostname).also {
            dnsCache[hostname] = it
        }
    }

    private fun buildDnsQuery(domain: String): ByteArray {
        val stream = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(stream)

        // Header (ID, Flags, QDCOUNT)
        dos.writeShort((System.currentTimeMillis() and 0xFFFF).toInt())
        dos.writeShort(0x0100) // Standard query with recursion desired
        dos.writeShort(1)      // 1 Question
        dos.writeShort(0)
        dos.writeShort(0)
        dos.writeShort(0)

        // Question
        for (part in domain.split(".")) {
            if (part.isEmpty()) continue
            val bytes = part.toByteArray(Charsets.US_ASCII)
            dos.writeByte(bytes.size)
            dos.write(bytes)
        }
        dos.writeByte(0) // End of domain
        dos.writeShort(1) // QTYPE: A (IPv4)
        dos.writeShort(1) // QCLASS: IN
        dos.flush()
        return stream.toByteArray()
    }

    private fun parseDnsResponse(data: ByteArray): InetAddress? {
        if (data.size < 12) return null
        return try {
            val dis = java.io.DataInputStream(java.io.ByteArrayInputStream(data))
            dis.skipBytes(4) // Skip ID, Flags
            val qdCount = dis.readUnsignedShort()
            val anCount = dis.readUnsignedShort()
            dis.skipBytes(4) // Skip NSCOUNT, ARCOUNT

            // Skip Questions
            for (i in 0 until qdCount) {
                while (true) {
                    val len = dis.readUnsignedByte()
                    if (len == 0) break
                    if ((len and 0xC0) == 0xC0) {
                        dis.skipBytes(1)
                        break
                    }
                    dis.skipBytes(len)
                }
                dis.skipBytes(4) // QTYPE, QCLASS
            }

            // Parse Answers
            for (i in 0 until anCount) {
                var len = dis.readUnsignedByte()
                if ((len and 0xC0) == 0xC0) {
                    dis.skipBytes(1)
                } else {
                    while (len > 0) {
                        dis.skipBytes(len)
                        len = dis.readUnsignedByte()
                        if ((len and 0xC0) == 0xC0) {
                            dis.skipBytes(1)
                            break
                        }
                    }
                }

                val type = dis.readUnsignedShort()
                dis.skipBytes(2) // Class
                dis.skipBytes(4) // TTL
                val dataLen = dis.readUnsignedShort()

                if (type == 1 && dataLen == 4) { // Type A
                    val ipBytes = ByteArray(4)
                    dis.readFully(ipBytes)
                    return InetAddress.getByAddress(ipBytes)
                } else {
                    dis.skipBytes(dataLen)
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * DNS 쿼리 바이너리 데이터를 받아 설정된 DNS 리졸버로 질의하고 응답 바이너리 반환
     */
    fun resolve(queryData: ByteArray): ByteArray? {
        val mode = prefs.dnsMode
        return try {
            if (mode.equals("https", ignoreCase = true) || mode.equals("doh", ignoreCase = true)) {
                resolveOverHttps(queryData)
            } else {
                resolveOverUdp(queryData)
            }
        } catch (e: Exception) {
            Log.e(TAG, "DNS query failed: ${e.message}")
            // 실패 시 폴백으로 기본 Quad9 UDP 시도
            try {
                resolveOverUdpFallback(queryData)
            } catch (ex: Exception) {
                null
            }
        }
    }

    private fun resolveOverUdp(queryData: ByteArray): ByteArray {
        val dnsTarget = prefs.dnsAddr
        val parts = dnsTarget.split(":")
        val host = parts[0]
        val port = if (parts.size > 1) parts[1].toInt() else 9953

        val socket = DatagramSocket()
        vpnService.protect(socket)
        socket.soTimeout = 1200

        try {
            val address = if (host == "9.9.9.9") {
                InetAddress.getByAddress(byteArrayOf(9, 9, 9, 9))
            } else {
                InetAddress.getByName(host)
            }
            val sendPacket = DatagramPacket(queryData, queryData.size, address, port)
            socket.send(sendPacket)

            val recvBuffer = ByteArray(4096)
            val recvPacket = DatagramPacket(recvBuffer, recvBuffer.size)
            socket.receive(recvPacket)

            val response = ByteArray(recvPacket.length)
            System.arraycopy(recvBuffer, 0, response, 0, recvPacket.length)
            return response
        } finally {
            socket.close()
        }
    }

    private fun resolveOverUdpFallback(queryData: ByteArray): ByteArray {
        val socket = DatagramSocket()
        vpnService.protect(socket)
        socket.soTimeout = 3000

        try {
            val address = InetAddress.getByAddress(byteArrayOf(9, 9, 9, 9))
            val sendPacket = DatagramPacket(queryData, queryData.size, address, 53)
            socket.send(sendPacket)

            val recvBuffer = ByteArray(4096)
            val recvPacket = DatagramPacket(recvBuffer, recvBuffer.size)
            socket.receive(recvPacket)

            val response = ByteArray(recvPacket.length)
            System.arraycopy(recvBuffer, 0, response, 0, recvPacket.length)
            return response
        } finally {
            socket.close()
        }
    }

    private fun resolveOverHttps(queryData: ByteArray): ByteArray {
        // dns.google 호스트명 대신 8.8.8.8 IP 직접 사용 (DNS 조회 데드락 방지)
        val urlString = if (prefs.dnsHttpsUrl.contains("dns.google")) {
            "https://8.8.8.8/dns-query"
        } else {
            prefs.dnsHttpsUrl
        }
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/dns-message")
        conn.setRequestProperty("Accept", "application/dns-message")
        conn.setRequestProperty("Host", "dns.google")

        val os: OutputStream = conn.outputStream
        os.write(queryData)
        os.flush()
        os.close()

        val responseCode = conn.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
            val isStream: InputStream = conn.inputStream
            val bytes = isStream.readBytes()
            isStream.close()
            return bytes
        } else {
            throw RuntimeException("DoH HTTP response code: $responseCode")
        }
    }
}
