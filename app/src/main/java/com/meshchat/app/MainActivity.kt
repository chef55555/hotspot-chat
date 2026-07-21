package com.meshchat.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

/**
 * Offline mesh chat over a Wi-Fi hotspot with no internet.
 *
 * Each phone:
 *   - advertises itself via NSD (mDNS/DNS-SD) on service type _meshchat._tcp.
 *   - discovers other instances and resolves them (one at a time; NSD can't
 *     resolve concurrently on many Android versions)
 *   - runs a ServerSocket on an ephemeral port so peers can connect to it
 *   - opens a TCP socket to each discovered peer (deterministic single
 *     initiator per pair, decided by comparing service names)
 *   - broadcasts each typed message to every connected peer and relays every
 *     received message to all *other* peers, so the whole mesh converges.
 *
 * Relay loops are broken with a per-message UUID kept in a "seen" set.
 */
class MainActivity : AppCompatActivity() {

    private val serviceType = "_meshchat._tcp."
    private val deviceId = UUID.randomUUID().toString().substring(0, 8)

    // The name we ask NSD to advertise. NSD may append a numeric suffix on a
    // collision, so the *actual* registered name arrives in onServiceRegistered.
    private val desiredName = "MeshChat-$deviceId"
    @Volatile private var registeredName: String? = null

    private lateinit var nsdManager: NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private var serverSocket: ServerSocket? = null
    private var localPort = 0
    @Volatile private var running = true

    /** peerName -> live connection. peerName is exchanged in the handshake. */
    private val peers = ConcurrentHashMap<String, Peer>()
    /** message UUIDs already displayed/relayed, to kill relay loops. */
    private val seenMessages = Collections.synchronizedSet(HashSet<String>())

    // NSD resolves must be serialized: doing two at once throws on many
    // Android versions ("listener already in use").
    private val resolveQueue = LinkedBlockingQueue<NsdServiceInfo>()
    private val resolveExecutor = Executors.newSingleThreadExecutor()
    private val ioExecutor = Executors.newCachedThreadPool()

    private lateinit var messagesView: TextView
    private lateinit var statusView: TextView
    private lateinit var input: EditText

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private class Peer(
        val name: String,
        val socket: Socket,
        val writer: BufferedWriter
    ) {
        val writeLock = Any()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        acquireMulticastLock()

        startServer()
        startResolveWorker()
        registerService()
        startDiscovery()

        log("You are $desiredName (port $localPort). Give discovery 10-20s.")
        updateStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        stopDiscovery()
        unregisterService()
        closeAllPeers()
        try { serverSocket?.close() } catch (_: Exception) {}
        resolveExecutor.shutdownNow()
        ioExecutor.shutdownNow()
        multicastLock?.let { if (it.isHeld) it.release() }
    }

    // ----------------------------------------------------------------------- UI

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        statusView = TextView(this).apply {
            textSize = 12f
            setPadding(0, 0, 0, 12)
        }
        root.addView(statusView)

        messagesView = TextView(this).apply {
            textSize = 14f
            movementMethod = ScrollingMovementMethod()
        }
        val scroll = ScrollView(this).apply {
            addView(messagesView)
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(this).apply {
            hint = "Type a message"
        }
        inputRow.addView(
            input,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val sendBtn = Button(this).apply {
            text = "Send"
            setOnClickListener { sendTyped() }
        }
        inputRow.addView(sendBtn)
        root.addView(inputRow)

        val rescanBtn = Button(this).apply {
            text = "Rescan peers"
            setOnClickListener { rescan() }
        }
        root.addView(rescanBtn)

        setContentView(root)
    }

    private fun sendTyped() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        broadcastOwnMessage(text)
    }

    private fun rescan() {
        log("Rescanning...")
        stopDiscovery()
        // small delay via the io pool so the framework releases the listener
        ioExecutor.execute {
            try { Thread.sleep(500) } catch (_: Exception) {}
            if (running) startDiscovery()
        }
    }

    private fun updateStatus() {
        val name = currentName()
        statusView.text = "You: $name  |  Peers: ${peers.size}  |  Port: $localPort"
    }

    private fun log(line: String) {
        runOnUiThread {
            messagesView.append("[${clock.format(Date())}] $line\n")
        }
    }

    private fun currentName(): String = registeredName ?: desiredName

    // ------------------------------------------------------------ server socket

    private fun startServer() {
        serverSocket = ServerSocket(0)
        localPort = serverSocket!!.localPort
        ioExecutor.execute {
            while (running) {
                val socket = try {
                    serverSocket!!.accept()
                } catch (e: Exception) {
                    break
                }
                ioExecutor.execute { handleSocket(socket) }
            }
        }
    }

    // ------------------------------------------------------------- NSD register

    private fun registerService() {
        val info = NsdServiceInfo().apply {
            serviceName = desiredName
            serviceType = this@MainActivity.serviceType
            port = localPort
        }
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredName = info.serviceName
                log("Advertising as ${info.serviceName}")
                runOnUiThread { updateStatus() }
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                log("Registration failed (err $errorCode)")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    private fun unregisterService() {
        registrationListener?.let {
            try { nsdManager.unregisterService(it) } catch (_: Exception) {}
        }
        registrationListener = null
    }

    // ------------------------------------------------------------ NSD discovery

    private fun startDiscovery() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                log("Discovery started")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName == currentName()) return // ourselves
                if (peers.containsKey(info.serviceName)) return
                resolveQueue.offer(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                log("Lost ${info.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                log("Discovery start failed (err $errorCode)")
                try { nsdManager.stopServiceDiscovery(this) } catch (_: Exception) {}
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                try { nsdManager.stopServiceDiscovery(this) } catch (_: Exception) {}
            }
        }
        discoveryListener = listener
        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun stopDiscovery() {
        discoveryListener?.let {
            try { nsdManager.stopServiceDiscovery(it) } catch (_: Exception) {}
        }
        discoveryListener = null
    }

    /** Pulls one service off the queue, resolves it, waits, repeats. */
    private fun startResolveWorker() {
        resolveExecutor.execute {
            while (running) {
                val info = try { resolveQueue.take() } catch (e: Exception) { break }
                if (peers.containsKey(info.serviceName)) continue
                val latch = CountDownLatch(1)
                val listener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        latch.countDown()
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        handleResolved(info)
                        latch.countDown()
                    }
                }
                try {
                    nsdManager.resolveService(info, listener)
                    latch.await()
                } catch (e: Exception) {
                    // listener busy or similar; requeue and back off
                    resolveQueue.offer(info)
                }
                try { Thread.sleep(300) } catch (_: Exception) {}
            }
        }
    }

    private fun handleResolved(info: NsdServiceInfo) {
        val peerName = info.serviceName
        if (peerName == currentName()) return
        if (peers.containsKey(peerName)) return

        // Deterministic single initiator per pair: only the lexicographically
        // smaller name dials out. The other side waits for the inbound socket.
        if (currentName() >= peerName) {
            log("Discovered $peerName (waiting for it to connect)")
            return
        }

        val host = info.host ?: return
        val port = info.port
        log("Connecting to $peerName at ${host.hostAddress}:$port")
        ioExecutor.execute {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 5000)
                handleSocket(socket)
            } catch (e: Exception) {
                log("Connect to $peerName failed: ${e.message}")
            }
        }
    }

    // -------------------------------------------------------- connection handling

    /**
     * Runs the handshake, registers the peer, then loops reading lines.
     * Used for both inbound (accepted) and outbound (connected) sockets.
     */
    private fun handleSocket(socket: Socket) {
        var peerName: String? = null
        try {
            socket.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))

            // Both sides send HELLO first, then read. Small + flushed => no deadlock.
            writer.write("HELLO ${currentName()}\n")
            writer.flush()

            val hello = reader.readLine()
            if (hello == null || !hello.startsWith("HELLO ")) {
                socket.close()
                return
            }
            val name = hello.removePrefix("HELLO ").trim()
            if (name.isEmpty() || name == currentName()) {
                socket.close()
                return
            }

            val peer = Peer(name, socket, writer)
            val existing = peers.putIfAbsent(name, peer)
            if (existing != null) {
                // Already connected to this peer; drop the duplicate.
                socket.close()
                return
            }
            peerName = name
            log("Connected to $name")
            runOnUiThread { updateStatus() }

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                onWireLine(line!!, name)
            }
        } catch (e: Exception) {
            // fall through to cleanup
        } finally {
            if (peerName != null) {
                peers.remove(peerName)
                log("Disconnected from $peerName")
                runOnUiThread { updateStatus() }
            }
            try { socket.close() } catch (_: Exception) {}
        }
    }

    // --------------------------------------------------------------- messaging

    /** Wire format: MSG|<uuid>|<sender>|<text>  (text may contain '|'). */
    private fun onWireLine(line: String, from: String) {
        val parts = line.split("|", limit = 4)
        if (parts.size < 4 || parts[0] != "MSG") return
        val msgId = parts[1]
        val sender = parts[2]
        val text = parts[3]

        if (!seenMessages.add(msgId)) return // already seen -> stop the loop

        log("$sender: $text")
        relay(line, exclude = from)
    }

    private fun broadcastOwnMessage(text: String) {
        val msgId = UUID.randomUUID().toString()
        seenMessages.add(msgId)
        val line = "MSG|$msgId|${currentName()}|$text"
        log("me: $text")
        relay(line, exclude = null)
    }

    /** Send a wire line to every peer except [exclude]. */
    private fun relay(line: String, exclude: String?) {
        for ((name, peer) in peers) {
            if (name == exclude) continue
            try {
                synchronized(peer.writeLock) {
                    peer.writer.write(line)
                    peer.writer.write("\n")
                    peer.writer.flush()
                }
            } catch (e: Exception) {
                peers.remove(name)
                try { peer.socket.close() } catch (_: Exception) {}
                runOnUiThread { updateStatus() }
            }
        }
    }

    private fun closeAllPeers() {
        for ((_, peer) in peers) {
            try { peer.socket.close() } catch (_: Exception) {}
        }
        peers.clear()
    }

    // ------------------------------------------------------------------- wifi

    private fun acquireMulticastLock() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("meshchat").apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            // Non-fatal; NSD may still work on many devices.
        }
    }
}
