package com.renyaa.freewall_and

import java.nio.ByteBuffer

/**
 * IPv4, TCP, UDP 패킷 파싱 및 체크섬 계산 유틸리티
 */
object PacketUtils {

    const val IP_HEADER_MIN_LEN = 20
    const val TCP_HEADER_MIN_LEN = 20
    const val UDP_HEADER_LEN = 8

    const val PROTOCOL_TCP = 6
    const val PROTOCOL_UDP = 17

    // TCP Flags
    const val TCP_FIN = 0x01
    const val TCP_SYN = 0x02
    const val TCP_RST = 0x04
    const val TCP_PSH = 0x08
    const val TCP_ACK = 0x10
    const val TCP_URG = 0x20

    data class ParsedPacket(
        val protocol: Int,
        val srcIp: String,
        val dstIp: String,
        val srcIpInt: Int,
        val dstIpInt: Int,
        val srcPort: Int,
        val dstPort: Int,
        val tcpFlags: Int,
        val seqNumber: Long,
        val ackNumber: Long,
        val ipHeaderLen: Int,
        val transportHeaderLen: Int,
        val payloadOffset: Int,
        val payloadLength: Int
    )

    fun parse(buffer: ByteArray, length: Int): ParsedPacket? {
        if (length < IP_HEADER_MIN_LEN) return null

        val versionAndIhl = buffer[0].toInt() and 0xFF
        val version = versionAndIhl ushr 4
        if (version != 4) return null // IPv4만 우선 지원

        val ihl = (versionAndIhl and 0x0F) * 4
        if (ihl < IP_HEADER_MIN_LEN || length < ihl) return null

        val protocol = buffer[9].toInt() and 0xFF
        val srcIpInt = getInt(buffer, 12)
        val dstIpInt = getInt(buffer, 16)
        val srcIp = intToIp(srcIpInt)
        val dstIp = intToIp(dstIpInt)

        if (protocol == PROTOCOL_TCP) {
            if (length < ihl + TCP_HEADER_MIN_LEN) return null
            val srcPort = getShort(buffer, ihl) and 0xFFFF
            val dstPort = getShort(buffer, ihl + 2) and 0xFFFF
            val seqNumber = getInt(buffer, ihl + 4).toLong() and 0xFFFFFFFFL
            val ackNumber = getInt(buffer, ihl + 8).toLong() and 0xFFFFFFFFL
            val dataOffset = ((buffer[ihl + 12].toInt() and 0xFF) ushr 4) * 4
            if (dataOffset < TCP_HEADER_MIN_LEN || length < ihl + dataOffset) return null
            val tcpFlags = buffer[ihl + 13].toInt() and 0x3F

            val payloadOffset = ihl + dataOffset
            val payloadLength = length - payloadOffset

            return ParsedPacket(
                protocol = protocol,
                srcIp = srcIp,
                dstIp = dstIp,
                srcIpInt = srcIpInt,
                dstIpInt = dstIpInt,
                srcPort = srcPort,
                dstPort = dstPort,
                tcpFlags = tcpFlags,
                seqNumber = seqNumber,
                ackNumber = ackNumber,
                ipHeaderLen = ihl,
                transportHeaderLen = dataOffset,
                payloadOffset = payloadOffset,
                payloadLength = payloadLength
            )
        } else if (protocol == PROTOCOL_UDP) {
            if (length < ihl + UDP_HEADER_LEN) return null
            val srcPort = getShort(buffer, ihl) and 0xFFFF
            val dstPort = getShort(buffer, ihl + 2) and 0xFFFF
            val udpLen = getShort(buffer, ihl + 4) and 0xFFFF
            val payloadOffset = ihl + UDP_HEADER_LEN
            val payloadLength = minOf(udpLen - UDP_HEADER_LEN, length - payloadOffset)

            return ParsedPacket(
                protocol = protocol,
                srcIp = srcIp,
                dstIp = dstIp,
                srcIpInt = srcIpInt,
                dstIpInt = dstIpInt,
                srcPort = srcPort,
                dstPort = dstPort,
                tcpFlags = 0,
                seqNumber = 0L,
                ackNumber = 0L,
                ipHeaderLen = ihl,
                transportHeaderLen = UDP_HEADER_LEN,
                payloadOffset = payloadOffset,
                payloadLength = maxOf(0, payloadLength)
            )
        }

        return null
    }

    fun buildTcpPacket(
        srcIpInt: Int,
        dstIpInt: Int,
        srcPort: Int,
        dstPort: Int,
        seqNumber: Long,
        ackNumber: Long,
        flags: Int,
        windowSize: Int = 65535,
        payload: ByteArray? = null
    ): ByteArray {
        val payloadLen = payload?.size ?: 0
        val totalLen = IP_HEADER_MIN_LEN + TCP_HEADER_MIN_LEN + payloadLen
        val packet = ByteArray(totalLen)
        val bb = ByteBuffer.wrap(packet)

        // IP Header
        bb.put(0x45.toByte()) // IPv4 + IHL 5
        bb.put(0x00.toByte()) // ToS
        bb.putShort(totalLen.toShort())
        bb.putShort((System.currentTimeMillis() and 0xFFFF).toShort()) // ID
        bb.putShort(0x4000.toShort()) // DF Flag
        bb.put(64.toByte()) // TTL
        bb.put(PROTOCOL_TCP.toByte())
        bb.putShort(0) // Checksum (후 계산)
        bb.putInt(srcIpInt)
        bb.putInt(dstIpInt)

        // IP Checksum 계산
        val ipChecksum = calculateChecksum(packet, 0, IP_HEADER_MIN_LEN)
        bb.putShort(10, ipChecksum.toShort())

        // TCP Header
        val tcpOffset = IP_HEADER_MIN_LEN
        bb.position(tcpOffset)
        bb.putShort(srcPort.toShort())
        bb.putShort(dstPort.toShort())
        bb.putInt(seqNumber.toInt())
        bb.putInt(ackNumber.toInt())
        bb.put((5 shl 4).toByte()) // Data Offset (20 bytes)
        bb.put(flags.toByte())
        bb.putShort(windowSize.toShort())
        bb.putShort(0) // Checksum (후 계산)
        bb.putShort(0) // Urgent Pointer

        if (payload != null && payloadLen > 0) {
            bb.put(payload)
        }

        // TCP Checksum 계산 (Pseudo Header 포함)
        val tcpLength = TCP_HEADER_MIN_LEN + payloadLen
        val tcpChecksum = calculateTcpUdpChecksum(packet, tcpOffset, tcpLength, srcIpInt, dstIpInt, PROTOCOL_TCP)
        bb.putShort(tcpOffset + 16, tcpChecksum.toShort())

        return packet
    }

    fun buildUdpPacket(
        srcIpInt: Int,
        dstIpInt: Int,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val totalLen = IP_HEADER_MIN_LEN + UDP_HEADER_LEN + payload.size
        val packet = ByteArray(totalLen)
        val bb = ByteBuffer.wrap(packet)

        // IP Header
        bb.put(0x45.toByte())
        bb.put(0x00.toByte())
        bb.putShort(totalLen.toShort())
        bb.putShort((System.currentTimeMillis() and 0xFFFF).toShort())
        bb.putShort(0x0000.toShort())
        bb.put(64.toByte())
        bb.put(PROTOCOL_UDP.toByte())
        bb.putShort(0)
        bb.putInt(srcIpInt)
        bb.putInt(dstIpInt)

        val ipChecksum = calculateChecksum(packet, 0, IP_HEADER_MIN_LEN)
        bb.putShort(10, ipChecksum.toShort())

        // UDP Header
        val udpOffset = IP_HEADER_MIN_LEN
        bb.position(udpOffset)
        bb.putShort(srcPort.toShort())
        bb.putShort(dstPort.toShort())
        bb.putShort((UDP_HEADER_LEN + payload.size).toShort())
        bb.putShort(0) // Checksum (0은 UDP에서 체크섬 생략 허용)
        bb.put(payload)

        return packet
    }

    private fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        val end = offset + length - 1
        while (i < end) {
            val b1 = data[i].toInt() and 0xFF
            val b2 = data[i + 1].toInt() and 0xFF
            sum += (b1 shl 8) or b2
            i += 2
        }
        if (i <= end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv()) and 0xFFFF
    }

    private fun calculateTcpUdpChecksum(
        packet: ByteArray,
        offset: Int,
        length: Int,
        srcIp: Int,
        dstIp: Int,
        protocol: Int
    ): Int {
        var sum = 0
        // Pseudo Header
        sum += (srcIp ushr 16) and 0xFFFF
        sum += srcIp and 0xFFFF
        sum += (dstIp ushr 16) and 0xFFFF
        sum += dstIp and 0xFFFF
        sum += protocol
        sum += length

        var i = offset
        val end = offset + length - 1
        while (i < end) {
            val b1 = packet[i].toInt() and 0xFF
            val b2 = packet[i + 1].toInt() and 0xFF
            sum += (b1 shl 8) or b2
            i += 2
        }
        if (i <= end) {
            sum += (packet[i].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv()) and 0xFFFF
    }

    fun intToIp(ip: Int): String {
        return "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"
    }

    fun ipToInt(ip: String): Int {
        val parts = ip.split(".")
        if (parts.size != 4) return 0
        return (parts[0].toInt() shl 24) or
                (parts[1].toInt() shl 16) or
                (parts[2].toInt() shl 8) or
                parts[3].toInt()
    }

    private fun getShort(buffer: ByteArray, offset: Int): Int {
        return ((buffer[offset].toInt() and 0xFF) shl 8) or
                (buffer[offset + 1].toInt() and 0xFF)
    }

    private fun getInt(buffer: ByteArray, offset: Int): Int {
        return ((buffer[offset].toInt() and 0xFF) shl 24) or
                ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
                ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
                (buffer[offset + 3].toInt() and 0xFF)
    }
}
