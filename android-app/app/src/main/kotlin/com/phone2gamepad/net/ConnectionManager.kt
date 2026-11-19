package com.phone2gamepad.net

import com.phone2gamepad.data.SecretStore
import com.phone2gamepad.protocol.ControllerState
import com.phone2gamepad.protocol.Decoded
import com.phone2gamepad.protocol.Packets
import com.phone2gamepad.protocol.ProtocolException
import com.phone2gamepad.protocol.SequenceTracker
import com.phone2gamepad.protocol.Wire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong

data class PcTarget(val name: String, val host: String, val tcpPort: Int = Wire.DEFAULT_TCP_PORT)

sealed class ConnState {
    data object Idle : ConnState()
    data class Connecting(val target: PcTarget) : ConnState()
    data class NeedsPairing(val target: PcTarget, val pairingOpen: Boolean, val error: String? = null) : ConnState()
    data class Pairing(val target: PcTarget) : ConnState()
    data class Connected(val target: PcTarget, val sessionId: Long) : ConnState()
    data class Reconnecting(val target: PcTarget, val attempt: Int, val retryInMs: Long, val reason: String) : ConnState()
    data class Failed(val target: PcTarget, val message: String) : ConnState()
}

/** Live link metrics. Null = not measurable (UI shows "---"). */
data class Metrics(
    val rttMs: Long? = null,
    val sendRateHz: Int? = null,
    val lastPeerPacketAgeMs: Long? = null,
    val reconnectAttempt: Int = 0,
)

/** Thread-safe holder of the current controller state written by the UI and read by the sender loop. */
class InputState {
    @Volatile private var current = ControllerState.NEUTRAL
    fun set(s: ControllerState) { current = s }
    fun snapshot(): ControllerState = current
    fun releaseAll() { current = ControllerState.NEUTRAL }
}

private class LinkState {
    @Volatile var lastPeerTs = Wire.NO_ECHO
    @Volatile var lastPeerRecvMs = 0L
    @Volatile var lastValidPeerMs = nowMs()
    @Volatile var rtt: Long? = null
}

private class SessionEnded(val reason: String, val fatal: Boolean = false) : Exception(reason)

private fun nowMs(): Long = System.nanoTime() / 1_000_000

/**
 * Owns the whole connection lifecycle: hello -> (pairing) -> session -> UDP streaming, and bounded
 * exponential-backoff reconnection with a fresh session each time.
 */
class ConnectionManager(
    private val scope: CoroutineScope,
    private val secrets: SecretStore,
    private val deviceName: String,
) {
    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state: StateFlow<ConnState> = _state
    private val _metrics = MutableStateFlow(Metrics())
    val metrics: StateFlow<Metrics> = _metrics
    val input = InputState()

    private var job: Job? = null
    private val pins = Channel<String>(Channel.CONFLATED)
    @Volatile private var pairError: String? = null

    fun connect(target: PcTarget) {
        job?.cancel()
        input.releaseAll()
        pairError = null
        job = scope.launch(Dispatchers.IO) { runLoop(target) }
    }

    /** Provides the PIN while the state is [ConnState.NeedsPairing]. */
    fun submitPin(pin: String) {
        pins.trySend(pin)
    }

    fun disconnect() {
        job?.cancel()
        job = null
        input.releaseAll()
        _state.value = ConnState.Idle
        _metrics.value = Metrics()
    }

    private suspend fun runLoop(target: PcTarget) {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val startedAt = nowMs()
            var reason = "connection ended"
            var fatal = false
            try {
                runOnce(target, attempt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionEnded) {
                reason = e.reason; fatal = e.fatal
            } catch (e: ProtocolException) {
                reason = describe(e); fatal = e.code == "bad_version"
            } catch (e: Exception) {
                reason = friendly(e)
            }
            if (fatal) {
                _state.value = ConnState.Failed(target, reason)
                _metrics.value = Metrics()
                return
            }
            // A session that lasted a while resets the backoff.
            attempt = if (nowMs() - startedAt > 5000) 1 else attempt + 1
            val wait = minOf(500L shl minOf(attempt - 1, 4), 8000L) + (0..250).random()
            _state.value = ConnState.Reconnecting(target, attempt, wait, reason)
            _metrics.value = Metrics(reconnectAttempt = attempt)
            delay(wait)
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is java.net.ConnectException, is java.net.SocketTimeoutException, is java.net.NoRouteToHostException ->
            "Can't reach the PC. Is the companion running and on the same Wi-Fi?"
        is java.net.UnknownHostException -> "Unknown host address"
        is java.io.EOFException -> "The PC closed the connection"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun describe(e: ProtocolException): String = when (e.code) {
        "busy" -> "PC already has an active controller session"
        "bad_version" -> "PC uses an incompatible protocol version"
        "rate_limited" -> "Too many attempts, wait a moment"
        else -> e.message ?: e.code
    }

    private suspend fun runOnce(target: PcTarget, attempt: Int) {
        if (attempt == 0) _state.value = ConnState.Connecting(target)
        val ctrl = ControlChannel.open(target.host, target.tcpPort)
        var udp: DatagramSocket? = null
        var session: SessionInfo? = null
        var seqCounter: AtomicLong? = null
        var startNs = 0L
        try {
            val hello = ctrl.hello(secrets.deviceId(), deviceName)
            val resolver = PcSecretResolver(secrets, hello)
            var secret = resolver.load()
            if (secret == null) secret = pairUntilSuccess(target, ctrl, hello, resolver)

            session = try {
                ctrl.startSession(secret)
            } catch (e: ProtocolException) {
                if (e.code == "auth_failed" || e.code == "not_paired") {
                    // PC no longer knows this device (revoked) or the secret is stale.
                    resolver.rejected()
                    pairError = "This PC no longer trusts this phone. Pair again."
                    throw SessionEnded("Pairing was revoked on the PC")
                }
                throw e
            }
            val sess = session
            resolver.sessionEstablished(secret)
            val sock = DatagramSocket()
            udp = sock
            sock.connect(InetAddress.getByName(target.host), hello.udpPort)
            sock.soTimeout = 300
            val seq = AtomicLong(0)
            seqCounter = seq
            startNs = System.nanoTime()
            _state.value = ConnState.Connected(target, sess.sessionId)
            _metrics.value = Metrics()
            stream(ctrl, sock, sess, seq, startNs)
        } finally {
            withContext(NonCancellable) {
                // Never leave the PC with held inputs: send neutral state a few times, then close.
                val s = session; val u = udp; val q = seqCounter
                if (s != null && u != null && q != null) {
                    repeat(3) {
                        try {
                            val ts = ((System.nanoTime() - startNs) / 1_000_000) and 0xFFFFFFFFL
                            val b = Packets.encodeState(s.sessionId, q.incrementAndGet() and 0xFFFFFFFFL, ts, ControllerState.NEUTRAL, s.keys.c2s)
                            u.send(DatagramPacket(b, b.size))
                        } catch (_: Exception) {
                        }
                    }
                }
                ctrl.sendDisconnect("client closing")
                ctrl.close()
                udp?.close()
            }
        }
    }

    private suspend fun pairUntilSuccess(target: PcTarget, ctrl: ControlChannel, hello: HelloInfo, resolver: PcSecretResolver): ByteArray {
        while (true) {
            _state.value = ConnState.NeedsPairing(target, hello.pairingOpen, pairError)
            val pin = pins.receive()
            _state.value = ConnState.Pairing(target)
            try {
                val secret = ctrl.pair(pin)
                resolver.saveNew(secret)
                pairError = null
                return secret
            } catch (e: ProtocolException) {
                pairError = when (e.code) {
                    "bad_pin" -> "Wrong PIN. Check the code shown on the PC."
                    "rejected_by_user" -> "Pairing was declined on the PC."
                    "pairing_closed" -> "Pairing is not open on the PC. Open the pairing window and try again."
                    "rate_limited" -> "Too many attempts. Wait a moment."
                    else -> e.message ?: e.code
                }
                // The PC ends a pairing attempt after any failure; reconnect for a clean slate.
                throw SessionEnded(pairError!!)
            }
        }
    }

    private suspend fun stream(ctrl: ControlChannel, udp: DatagramSocket, s: SessionInfo, seq: AtomicLong, startNs: Long) = coroutineScope {
        val link = LinkState()
        val sent = AtomicLong(0)
        fun ts() = ((System.nanoTime() - startNs) / 1_000_000) and 0xFFFFFFFFL
        // Sequence allocation and the send must be atomic, otherwise the sender and heartbeat coroutines can
        // put seq N+1 on the wire before N and the PC drops N as out-of-order.
        val sendLock = Any()
        fun sendPacket(build: (seq: Long) -> ByteArray) {
            synchronized(sendLock) {
                val b = build(seq.incrementAndGet() and 0xFFFFFFFFL)
                udp.send(DatagramPacket(b, b.size))
            }
        }

        // Closes the sockets when the session scope is cancelled so blocking reads/receives unblock.
        launch {
            try { awaitCancellation() } finally { ctrl.close(); udp.close() }
        }
        // Controller state sender: up to ~60 Hz while changing, at least ~33 Hz while idle.
        launch(Dispatchers.IO) {
            var last: ControllerState? = null
            var lastSend = 0L
            while (isActive) {
                val now = nowMs()
                val cur = input.snapshot()
                val since = now - lastSend
                if ((cur != last && since >= 16) || since >= 30) {
                    sendPacket { Packets.encodeState(s.sessionId, it, ts(), cur, s.keys.c2s) }
                    sent.incrementAndGet()
                    last = cur
                    lastSend = now
                }
                delay(2)
            }
        }
        // Heartbeat at 2 Hz carrying the RTT echo.
        launch(Dispatchers.IO) {
            while (isActive) {
                val now = nowMs()
                val echo = link.lastPeerTs
                val delayMs = if (echo == Wire.NO_ECHO) 0 else (now - link.lastPeerRecvMs).coerceIn(0, 0xFFFF).toInt()
                sendPacket { Packets.encodeHeartbeat(s.sessionId, it, ts(), echo, delayMs, s.keys.c2s) }
                delay(500)
            }
        }
        // Receive PC heartbeats (validated: length, version, session, tag, newer sequence).
        launch(Dispatchers.IO) {
            val tracker = SequenceTracker()
            val buf = ByteArray(128)
            while (isActive) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    udp.receive(p)
                    val d = Packets.decode(buf.copyOf(p.length), s.keys.s2c, s.sessionId)
                    if (d is Decoded.Heartbeat && tracker.accept(d.seq)) {
                        val now = nowMs()
                        link.lastValidPeerMs = now
                        link.lastPeerTs = d.timestamp
                        link.lastPeerRecvMs = now
                        if (d.echo != Wire.NO_ECHO) {
                            val r = (ts() - d.echo - d.echoDelay) and 0xFFFFFFFFL
                            if (r < 10_000) link.rtt = r
                        }
                    }
                } catch (_: SocketTimeoutException) {
                }
            }
        }
        // PC liveness + metrics publisher.
        launch {
            var lastSent = 0L
            var lastTick = nowMs()
            while (isActive) {
                delay(500)
                val now = nowMs()
                val age = now - link.lastValidPeerMs
                val total = sent.get()
                val rate = ((total - lastSent) * 1000.0 / (now - lastTick)).toInt()
                lastSent = total; lastTick = now
                _metrics.value = Metrics(rttMs = link.rtt, sendRateHz = rate, lastPeerPacketAgeMs = age)
                if (age > 5000) throw SessionEnded("PC stopped responding")
            }
        }
        // TCP control watcher: PC closing the connection or sending disconnect ends the session.
        launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val m = ctrl.read(timeoutMs = 0)
                    if (m.optString("t") == "disconnect") {
                        throw SessionEnded("PC ended the session: " + m.optString("reason", "no reason given"))
                    }
                }
            } catch (e: SessionEnded) {
                throw e
            } catch (e: Exception) {
                if (isActive) throw SessionEnded("Connection to PC lost")
            }
        }
    }
}
