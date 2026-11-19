package com.phone2gamepad.net

import com.phone2gamepad.protocol.Crypto
import com.phone2gamepad.protocol.Discovery
import com.phone2gamepad.protocol.Wire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

data class DiscoveredPc(val name: String, val host: String, val tcpPort: Int, val udpPort: Int, val pairingOpen: Boolean)

/** UDP broadcast discovery (PROTOCOL.md section 2). Collecting the flow scans until cancelled. */
object DiscoveryClient {
    private fun broadcastTargets(): List<InetAddress> {
        val out = linkedSetOf<InetAddress>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { if (it is Inet4Address) out += it }
            }
        } catch (_: Exception) {
        }
        out += InetAddress.getByName("255.255.255.255")
        return out.toList()
    }

    fun scan(): Flow<List<DiscoveredPc>> = channelFlow {
        val socket = DatagramSocket().apply { broadcast = true; soTimeout = 250 }
        val nonce = ByteBuffer.wrap(Crypto.randomBytes(4)).int.toLong() and 0xFFFFFFFFL
        val request = Discovery.encodeRequest(nonce)
        val found = linkedMapOf<String, DiscoveredPc>()

        launch(Dispatchers.IO) {
            while (isActive) {
                for (addr in broadcastTargets()) {
                    try {
                        socket.send(DatagramPacket(request, request.size, addr, Wire.DISCOVERY_PORT))
                    } catch (_: Exception) {
                    }
                }
                delay(1000)
            }
        }
        launch(Dispatchers.IO) {
            val buf = ByteArray(128)
            while (isActive) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    val r = Discovery.parseResponse(buf, p.length) ?: continue
                    if (r.nonce != nonce) continue
                    val host = p.address.hostAddress ?: continue
                    val pc = DiscoveredPc(r.name, host, r.tcpPort, r.udpPort, r.pairingOpen)
                    if (found[host] != pc) {
                        found[host] = pc
                        send(found.values.toList())
                    }
                } catch (_: SocketTimeoutException) {
                } catch (e: Exception) {
                    if (isActive) delay(250)
                }
            }
        }
        awaitClose { socket.close() }
    }
}
