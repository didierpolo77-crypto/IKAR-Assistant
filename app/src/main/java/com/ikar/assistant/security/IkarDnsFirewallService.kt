package com.ikar.assistant.security

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional DNS-only filtering tunnel.
 *
 * Only IPv4 DNS/UDP requests sent to the virtual DNS address are intercepted.
 * Other app traffic is not routed through this tunnel. DoH/DoT and IPv6 DNS may bypass it.
 */
class IkarDnsFirewallService : VpnService() {
    private var tunnel: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private val running = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running.get()) return START_STICKY
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Filtrage DNS local en préparation"))
        val descriptor = try {
            Builder()
                .setSession("IKAR — filtre DNS")
                .setMtu(1500)
                .addAddress("10.77.0.1", 32)
                .addDnsServer("10.77.0.2")
                .addRoute("10.77.0.2", 32)
                // Keep IPv6 connectivity working; IPv6 DNS is not inspected by this IPv4-only filter.
                .allowFamily(android.system.OsConstants.AF_INET6)
                .establish()
        } catch (_: Exception) {
            null
        }
        if (descriptor == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        tunnel = descriptor
        running.set(true)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("dns_firewall_running", true).apply()
        updateNotification("Filtrage DNS actif — trafic DNS IPv4 uniquement")
        worker = Thread { packetLoop(descriptor) }.apply {
            name = "ikar-dns-firewall"
            isDaemon = true
            start()
        }
        return START_STICKY
    }

    private fun packetLoop(descriptor: ParcelFileDescriptor) {
        val input = FileInputStream(descriptor.fileDescriptor)
        val output = FileOutputStream(descriptor.fileDescriptor)
        val packet = ByteArray(32767)
        try {
            while (running.get()) {
                val length = input.read(packet)
                if (length <= 0) continue
                val response = handleDnsPacket(packet, length) ?: continue
                output.write(response)
            }
        } catch (_: Exception) {
            // The user may revoke VPN permission or Android may close the TUN descriptor.
        } finally {
            stopSelf()
        }
    }

    private fun handleDnsPacket(packet: ByteArray, length: Int): ByteArray? {
        if (length < 28 || packet[0].toInt().ushr(4) != 4 || (packet[9].toInt() and 0xff) != 17) return null
        val ipHeaderLength = (packet[0].toInt() and 0x0f) * 4
        if (ipHeaderLength < 20 || length < ipHeaderLength + 8 + 12) return null
        val sourceIp = packet.copyOfRange(12, 16)
        val destinationIp = packet.copyOfRange(16, 20)
        val sourcePort = u16(packet, ipHeaderLength)
        val destinationPort = u16(packet, ipHeaderLength + 2)
        if (destinationPort != 53) return null
        val udpLength = u16(packet, ipHeaderLength + 4)
        if (udpLength < 20 || ipHeaderLength + udpLength > length) return null
        val dnsOffset = ipHeaderLength + 8
        val dns = packet.copyOfRange(dnsOffset, ipHeaderLength + udpLength)
        if (dns.size < 17 || dns.size > 4096) return null
        val dnsFlags = u16(dns, 2)
        // Accept only ordinary one-question DNS queries; reject responses, opcodes and malformed packets.
        if ((dnsFlags and 0x8000) != 0 || ((dnsFlags ushr 11) and 0x0f) != 0 || u16(dns, 4) != 1) return null
        val host = readQuestionHost(dns) ?: return null
        val blocked = ThreatIntelStore.isKnownMaliciousHost(this, host)
        val responseDns = if (blocked) makeNxdomain(dns) else forwardDns(dns) ?: return null
        return buildIpv4UdpResponse(
            sourceIp = destinationIp,
            destinationIp = sourceIp,
            sourcePort = destinationPort,
            destinationPort = sourcePort,
            payload = responseDns
        )
    }

    private fun forwardDns(query: ByteArray): ByteArray? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            if (!protect(socket)) return null
            socket.soTimeout = 2800
            val upstream = InetAddress.getByName("1.1.1.1")
            socket.send(DatagramPacket(query, query.size, upstream, 53))
            val buffer = ByteArray(4096)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            val received = buffer.copyOf(response.length)
            if (received.size < 12 || received[0] != query[0] || received[1] != query[1]) return null
            if ((u16(received, 2) and 0x8000) == 0) return null
            received
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: Exception) {
            null
        } finally {
            socket?.close()
        }
    }

    private fun readQuestionHost(dns: ByteArray): String? {
        if (dns.size < 17) return null
        var offset = 12
        val labels = mutableListOf<String>()
        while (offset < dns.size) {
            val size = dns[offset++].toInt() and 0xff
            if (size == 0) break
            if (size > 63 || offset + size > dns.size) return null
            labels += String(dns, offset, size, Charsets.US_ASCII)
            offset += size
            if (labels.size > 32) return null
        }
        if (labels.isEmpty() || offset + 4 > dns.size) return null
        val queryType = u16(dns, offset)
        val queryClass = u16(dns, offset + 2)
        if (queryType == 0 || queryClass != 1) return null
        return labels.joinToString(".").lowercase()
    }

    private fun makeNxdomain(query: ByteArray): ByteArray {
        val response = query.copyOf()
        val flags = u16(response, 2)
        putU16(response, 2, (flags and 0x0100) or 0x8083) // QR, RA, NXDOMAIN; preserve RD
        putU16(response, 6, 0) // ANCOUNT
        putU16(response, 8, 0) // NSCOUNT
        putU16(response, 10, 0) // ARCOUNT
        return response
    }

    private fun buildIpv4UdpResponse(
        sourceIp: ByteArray,
        destinationIp: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLength = 8 + payload.size
        val packet = ByteArray(20 + udpLength)
        packet[0] = 0x45
        packet[1] = 0
        putU16(packet, 2, packet.size)
        putU16(packet, 4, 0)
        putU16(packet, 6, 0x4000)
        packet[8] = 64
        packet[9] = 17
        System.arraycopy(sourceIp, 0, packet, 12, 4)
        System.arraycopy(destinationIp, 0, packet, 16, 4)
        putU16(packet, 10, checksum(packet, 0, 20))
        putU16(packet, 20, sourcePort)
        putU16(packet, 22, destinationPort)
        putU16(packet, 24, udpLength)
        putU16(packet, 26, 0) // UDP checksum is optional for IPv4.
        System.arraycopy(payload, 0, packet, 28, payload.size)
        return packet
    }

    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        while (i < offset + length) {
            val high = data[i].toInt() and 0xff
            val low = if (i + 1 < offset + length) data[i + 1].toInt() and 0xff else 0
            sum += (high shl 8) or low
            i += 2
        }
        while ((sum shr 16) != 0L) sum = (sum and 0xffff) + (sum shr 16)
        return sum.inv().toInt() and 0xffff
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

    private fun putU16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 8).toByte()
        data[offset + 1] = value.toByte()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Pare-feu DNS IKAR", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("IKAR — pare-feu DNS")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onRevoke() {
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        running.set(false)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("dns_firewall_running", false).apply()
        runCatching { tunnel?.close() }
        tunnel = null
        worker?.interrupt()
        worker = null
        super.onDestroy()
    }

    companion object {
        private const val PREFS = "ikar_security_center"
        private const val CHANNEL_ID = "ikar_dns_firewall"
        private const val NOTIFICATION_ID = 3621
    }
}
