package com.meshchat.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Offline mesh chat over a Wi-Fi hotspot with no internet.
 *
 * v2 adds: heartbeat + auto-reconnect, an editable display name defaulted from
 * the phone, a split Chat/Log UI with verbose logging, a size-capped
 * "Copy log" button, and an online updater that pulls the newest debug APK
 * from GitHub Releases.
 *
 * Identity model:
 *   - serviceName ("MeshChat-<id>") is the stable WIRE identity used for NSD,
 *     dedup, and the single-initiator-per-pair rule. Never shown to the user.
 *   - displayName is a cosmetic, user-editable name (defaulted from the device)
 *     carried in the handshake and in every chat message.
 */
class MainActivity : AppCompatActivity() {

    private val serviceType = "_meshchat._tcp."
    private val deviceId = UUID.randomUUID().toString().substring(0, 8)
    private val desiredName = "MeshChat-$deviceId"
    @Volatile private var registeredName: String? = null

    @Volatile private var displayName: String = "Android"

    private lateinit var nsdManager: NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private var serverSocket: ServerSocket? = null
    private var localPort = 0
    @Volatile private var running = true

    /** peerServiceName -> live connection. */
    private val peers = ConcurrentHashMap<String, Peer>()
    /** peerServiceName -> last known endpoint, for auto-reconnect. */
    private val knownEndpoints = ConcurrentHashMap<String, Endpoint>()
    /** peerServiceNames we are currently dialing, to avoid duplicate dials. */
    private val dialing = Collections.synchronizedSet(HashSet<String>())
    /** message UUIDs already shown/relayed, to kill relay loops. */
    private val seenMessages = Collections.synchronizedSet(HashSet<String>())

    private val resolveQueue = LinkedBlockingQueue<NsdServiceInfo>()
    private val resolveExecutor = Executors.newSingleThreadExecutor()
    private val ioExecutor = Executors.newCachedThreadPool()
    private val scheduler = Executors.newScheduledThreadPool(2)

    private var msgSent = 0
    private var msgRecv = 0

    // --- update state ---
    @Volatile private var pendingApkUrl: String? = null
    @Volatile private var pendingVersionName: String? = null

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var statusView: TextView
    private lateinit var nameInput: EditText
    private lateinit var chatView: TextView
    private lateinit var chatScroll: ScrollView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var updateButton: Button

    private val logBuffer = StringBuilder()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private class Peer(
        val name: String,
        val socket: Socket,
        val writer: BufferedWriter
    ) {
        val writeLock = Any()
        @Volatile var display: String = name
        @Volatile var lastRx: Long = System.currentTimeMillis()
    }

    private class Endpoint(val host: InetAddress, val port: Int)

    companion object {
        private const val TAG = "MeshChat"
        private const val HEARTBEAT_MS = 5_000L
        private const val READ_TIMEOUT_MS = 20_000
        private const val RECONNECT_MS = 5_000L
        // Clipboard is bound by the ~1MB Binder transaction buffer (shared per
        // process, identical on Pixel/Samsung), which throws below 1MB in
        // practice. Base the cap on a conservative safe ceiling, copy 80% of it.
        private const val MAX_CLIPBOARD_BYTES = 500_000
        private const val LOG_BUFFER_CAP = 2_000_000

        private const val RELEASE_BASE =
            "https://github.com/chef55555/hotspot-chat/releases/latest/download"
        private const val VERSION_JSON_URL = "$RELEASE_BASE/version.json"
        private const val DEFAULT_APK_URL = "$RELEASE_BASE/app-debug.apk"
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("meshchat_prefs", Context.MODE_PRIVATE)
        displayName = prefs.getString("display_name", null)?.let { sanitizeName(it) } ?: defaultName()

        buildUi()

        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        acquireMulticastLock()

        startServer()
        startResolveWorker()
        registerService()
        startDiscovery()
        startSchedulers()

        logD("I", "Started as $desiredName (\"$displayName\") on port $localPort")
        logD("I", "Give discovery 10-20s. Drops self-heal within ~5-20s.")
        updateStatus()

        // Auto-check for an update on launch (silent; only works with internet).
        checkForUpdate(manual = false)
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
        scheduler.shutdownNow()
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
            setPadding(0, 0, 0, 8)
        }
        root.addView(statusView)

        // Name row.
        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nameInput = EditText(this).apply {
            setText(displayName)
            hint = "Display name"
        }
        nameRow.addView(nameInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nameRow.addView(Button(this).apply {
            text = "Save"
            setOnClickListener { saveName() }
        })
        root.addView(nameRow)

        // Chat | Log toggle.
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chatTab = Button(this).apply { text = "Chat" }
        val logTab = Button(this).apply { text = "Log" }
        tabRow.addView(chatTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tabRow.addView(logTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(tabRow)

        // Content: chat + log, one visible at a time.
        chatView = TextView(this).apply { textSize = 14f; movementMethod = ScrollingMovementMethod() }
        chatScroll = ScrollView(this).apply { addView(chatView) }
        logView = TextView(this).apply { textSize = 11f; movementMethod = ScrollingMovementMethod() }
        logScroll = ScrollView(this).apply { addView(logView); visibility = View.GONE }

        val contentParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        root.addView(chatScroll, contentParams)
        root.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        chatTab.setOnClickListener {
            chatScroll.visibility = View.VISIBLE
            logScroll.visibility = View.GONE
        }
        logTab.setOnClickListener {
            chatScroll.visibility = View.GONE
            logScroll.visibility = View.VISIBLE
        }

        // Message input row.
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(this).apply { hint = "Type a message" }
        inputRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(Button(this).apply {
            text = "Send"
            setOnClickListener { sendTyped() }
        })
        root.addView(inputRow)

        // Action buttons.
        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actionRow.addView(Button(this).apply {
            text = "Rescan"
            setOnClickListener { rescan() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(Button(this).apply {
            text = "Copy log"
            setOnClickListener { copyLog() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(Button(this).apply {
            text = "Check update"
            setOnClickListener { checkForUpdate(manual = true) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actionRow)

        updateButton = Button(this).apply {
            text = "Update"
            visibility = View.GONE
            setOnClickListener { downloadAndInstall() }
        }
        root.addView(updateButton)

        setContentView(root)
    }

    private fun sendTyped() {
        // Strip newlines so a pasted multi-line string can't corrupt the
        // newline-delimited wire protocol.
        val text = input.text.toString().replace('\n', ' ').replace('\r', ' ').trim()
        if (text.isEmpty()) return
        input.setText("")
        broadcastOwnMessage(text)
    }

    private fun saveName() {
        val newName = sanitizeName(nameInput.text.toString())
        displayName = newName
        prefs.edit().putString("display_name", newName).apply()
        nameInput.setText(newName)
        logD("I", "Display name set to \"$newName\"")
        // Tell connected peers so their view updates live.
        val line = "NAME|${currentName()}|$newName"
        relay(line, exclude = null, includeControl = true)
        updateStatus()
        toast("Name saved")
    }

    private fun rescan() {
        logD("I", "Manual rescan")
        stopDiscovery()
        ioExecutor.execute {
            try { Thread.sleep(500) } catch (_: Exception) {}
            if (running) startDiscovery()
            // Also re-dial anything we already know about.
            for (name in knownEndpoints.keys) maybeDial(name)
        }
    }

    private fun updateStatus() {
        runOnUiThread {
            statusView.text = "You: $displayName   |   Peers: ${peers.size}   |   Sent $msgSent / Recv $msgRecv"
        }
    }

    private fun appendChat(line: String) {
        runOnUiThread {
            chatView.append("$line\n")
            chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun logD(level: String, msg: String) {
        val stamped = "[${clock.format(Date())}] $level/ $msg"
        synchronized(logBuffer) {
            logBuffer.append(stamped).append('\n')
            if (logBuffer.length > LOG_BUFFER_CAP) {
                logBuffer.delete(0, logBuffer.length - LOG_BUFFER_CAP / 2)
            }
        }
        when (level) {
            "E" -> Log.e(TAG, msg)
            "W" -> Log.w(TAG, msg)
            else -> Log.d(TAG, msg)
        }
        runOnUiThread {
            logView.append("$stamped\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun currentName(): String = registeredName ?: desiredName

    // ------------------------------------------------------------- display name

    private fun defaultName(): String {
        val deviceName = try {
            // "device_name" is the literal key behind Settings.Global.DEVICE_NAME
            // (added API 25); the string works on API 24 too.
            Settings.Global.getString(contentResolver, "device_name")
        } catch (e: Exception) {
            null
        }
        val base = if (!deviceName.isNullOrBlank()) deviceName else (Build.MODEL ?: "Android")
        return sanitizeName(base)
    }

    private fun sanitizeName(raw: String): String {
        var r = raw.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim()
        if (r.length > 24) r = r.substring(0, 24)
        if (r.isEmpty()) r = "Android"
        return r
    }

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
                logD("I", "Inbound connection from ${socket.inetAddress?.hostAddress}")
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
                logD("I", "Advertising as ${info.serviceName}")
                updateStatus()
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                logD("E", "NSD registration failed (err $errorCode)")
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
                logD("I", "Discovery started")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName == currentName()) return
                logD("D", "Found ${info.serviceName}, queueing resolve")
                resolveQueue.offer(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                // Keep the endpoint for reconnect resilience; NSD is flaky.
                logD("W", "Lost ${info.serviceName} (keeping for reconnect)")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                logD("I", "Discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                logD("E", "Discovery start failed (err $errorCode)")
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

    private fun startResolveWorker() {
        resolveExecutor.execute {
            while (running) {
                val info = try { resolveQueue.take() } catch (e: Exception) { break }
                if (peers.containsKey(info.serviceName)) continue
                val latch = CountDownLatch(1)
                val listener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        logD("W", "Resolve failed for ${info.serviceName} (err $errorCode)")
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
                    logD("W", "Resolve threw for ${info.serviceName}: ${e.message}; requeueing")
                    resolveQueue.offer(info)
                }
                try { Thread.sleep(300) } catch (_: Exception) {}
            }
        }
    }

    private fun handleResolved(info: NsdServiceInfo) {
        val name = info.serviceName
        if (name == currentName()) return
        val host = info.host ?: return
        knownEndpoints[name] = Endpoint(host, info.port)
        logD("I", "Resolved $name -> ${host.hostAddress}:${info.port}")
        maybeDial(name)
    }

    // -------------------------------------------------------- connection dialing

    /** Dial a known peer if we are the initiator and not already connected. */
    private fun maybeDial(name: String) {
        if (name == currentName()) return
        if (currentName() >= name) return          // the other side dials us
        if (peers.containsKey(name)) return
        val ep = knownEndpoints[name] ?: return
        if (!dialing.add(name)) return              // already dialing
        ioExecutor.execute {
            try {
                logD("I", "Dialing $name at ${ep.host.hostAddress}:${ep.port}")
                val socket = Socket()
                socket.connect(InetSocketAddress(ep.host, ep.port), 5000)
                handleSocket(socket)                // blocks until the link dies
            } catch (e: Exception) {
                logD("W", "Dial to $name failed: ${e.message}")
            } finally {
                dialing.remove(name)
            }
        }
    }

    // -------------------------------------------------------- connection handling

    private fun handleSocket(socket: Socket) {
        var peerName: String? = null
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = READ_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))

            // Both sides send HELLO first, then read. Small + flushed => no deadlock.
            writer.write("HELLO|${currentName()}|$displayName\n")
            writer.flush()

            val hello = reader.readLine()
            if (hello == null || !hello.startsWith("HELLO|")) {
                logD("W", "Bad handshake, closing")
                socket.close()
                return
            }
            val parts = hello.split("|", limit = 3)
            val name = parts.getOrNull(1)?.trim().orEmpty()
            val peerDisplay = parts.getOrNull(2)?.trim().orEmpty().ifEmpty { name }
            if (name.isEmpty() || name == currentName()) {
                socket.close()
                return
            }

            val peer = Peer(name, socket, writer).apply { display = peerDisplay }
            val existing = peers.putIfAbsent(name, peer)
            if (existing != null) {
                logD("D", "Duplicate connection to $name, dropping new one")
                socket.close()
                return
            }
            peerName = name
            logD("I", "Connected to $name (\"$peerDisplay\")")
            updateStatus()

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                peer.lastRx = System.currentTimeMillis()
                onWireLine(line!!, peer)
            }
        } catch (e: Exception) {
            if (peerName != null) logD("W", "Link to $peerName ended: ${e.message}")
        } finally {
            if (peerName != null) dropPeer(peerName, "read loop ended")
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun dropPeer(name: String, reason: String) {
        val peer = peers.remove(name) ?: return
        try { peer.socket.close() } catch (_: Exception) {}
        logD("I", "Disconnected from $name ($reason). Will auto-reconnect if in range.")
        updateStatus()
    }

    // --------------------------------------------------------------- messaging

    /** Wire lines: HELLO|.., MSG|uuid|sender|text, NAME|svc|display, PING, PONG. */
    private fun onWireLine(line: String, from: Peer) {
        when {
            line == "PING" -> sendRaw(from, "PONG")
            line == "PONG" -> { /* liveness only; lastRx already bumped */ }
            line.startsWith("MSG|") -> handleMessage(line, from)
            line.startsWith("NAME|") -> handleName(line, from)
            else -> logD("D", "Unknown line from ${from.name}: ${line.take(40)}")
        }
    }

    private fun handleMessage(line: String, from: Peer) {
        val parts = line.split("|", limit = 4)
        if (parts.size < 4) return
        val msgId = parts[1]
        val sender = parts[2]
        val text = parts[3]
        if (!seenMessages.add(msgId)) {
            logD("D", "Dropping duplicate msg $msgId from ${from.name}")
            return
        }
        msgRecv++
        appendChat("$sender: $text")
        logD("D", "Recv msg $msgId from ${from.name}: \"${text.take(40)}\"")
        relay(line, exclude = from.name, includeControl = false)
        updateStatus()
    }

    private fun handleName(line: String, from: Peer) {
        val parts = line.split("|", limit = 3)
        if (parts.size < 3) return
        val svc = parts[1]
        val display = parts[2]
        peers[svc]?.display = display
        logD("I", "$svc is now known as \"$display\"")
    }

    private fun broadcastOwnMessage(text: String) {
        val msgId = UUID.randomUUID().toString()
        seenMessages.add(msgId)
        val line = "MSG|$msgId|$displayName|$text"
        msgSent++
        appendChat("me: $text")
        logD("D", "Send msg $msgId to ${peers.size} peer(s)")
        relay(line, exclude = null, includeControl = false)
        updateStatus()
    }

    /** Send a wire line to every peer except [exclude]. */
    private fun relay(line: String, exclude: String?, includeControl: Boolean) {
        for ((name, peer) in peers) {
            if (name == exclude) continue
            sendRaw(peer, line)
        }
    }

    private fun sendRaw(peer: Peer, line: String): Boolean {
        return try {
            synchronized(peer.writeLock) {
                peer.writer.write(line)
                peer.writer.write("\n")
                peer.writer.flush()
            }
            true
        } catch (e: Exception) {
            dropPeer(peer.name, "write failed: ${e.message}")
            false
        }
    }

    private fun closeAllPeers() {
        for ((_, peer) in peers) {
            try { peer.socket.close() } catch (_: Exception) {}
        }
        peers.clear()
    }

    // ------------------------------------------------------ schedulers (health)

    private fun startSchedulers() {
        // Heartbeat: keep traffic flowing and reap dead links.
        scheduler.scheduleWithFixedDelay({
            if (!running) return@scheduleWithFixedDelay
            try {
                val now = System.currentTimeMillis()
                for ((name, peer) in peers) {
                    if (now - peer.lastRx > READ_TIMEOUT_MS) {
                        dropPeer(name, "heartbeat timeout")
                    } else {
                        sendRaw(peer, "PING")
                    }
                }
            } catch (e: Exception) {
                logD("E", "Heartbeat error: ${e.message}")
            }
        }, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS)

        // Reconnect: re-dial any known peer we've lost.
        scheduler.scheduleWithFixedDelay({
            if (!running) return@scheduleWithFixedDelay
            try {
                for (name in knownEndpoints.keys) maybeDial(name)
            } catch (e: Exception) {
                logD("E", "Reconnect error: ${e.message}")
            }
        }, RECONNECT_MS, RECONNECT_MS, TimeUnit.MILLISECONDS)
    }

    // --------------------------------------------------------------- clipboard

    private fun copyLog() {
        val full = synchronized(logBuffer) { logBuffer.toString() }
        var budgetChars = (MAX_CLIPBOARD_BYTES * 0.8 / 2).toInt() // ~200k chars
        var text = if (full.length > budgetChars) {
            "...[log truncated to last $budgetChars chars]...\n" +
                full.substring(full.length - budgetChars)
        } else {
            full
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        var attempt = 0
        while (true) {
            try {
                cm.setPrimaryClip(ClipData.newPlainText("MeshChat log", text))
                val kb = text.length / 1024
                toast("Copied last $kb KB of log")
                logD("I", "Copied ${text.length} chars of log to clipboard")
                return
            } catch (e: Exception) {
                attempt++
                if (attempt > 4 || text.length < 2000) {
                    logD("E", "Clipboard copy failed: ${e.message}")
                    toast("Copy failed: ${e.message}")
                    return
                }
                budgetChars /= 2
                text = text.substring(text.length - budgetChars)
                logD("W", "Clipboard too large, retrying with $budgetChars chars")
            }
        }
    }

    // ------------------------------------------------------------------ updater

    private fun hasInternet(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            // VALIDATED means real reachability — false on an offline hotspot.
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            false
        }
    }

    private fun localVersionCode(): Int {
        return try {
            val info = packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION") info.versionCode
            }
        } catch (e: Exception) {
            1
        }
    }

    private fun checkForUpdate(manual: Boolean) {
        ioExecutor.execute {
            if (!hasInternet()) {
                logD("I", "Update check skipped: no internet (expected on the offline hotspot)")
                if (manual) toast("No internet — update while online first")
                return@execute
            }
            try {
                val json = httpGetString(VERSION_JSON_URL)
                val obj = JSONObject(json)
                val remoteCode = obj.getInt("versionCode")
                val remoteName = obj.optString("versionName", remoteCode.toString())
                val apkUrl = obj.optString("apkUrl", DEFAULT_APK_URL)
                val localCode = localVersionCode()
                logD("I", "Update check: local=$localCode remote=$remoteCode ($remoteName)")
                if (remoteCode > localCode) {
                    pendingApkUrl = apkUrl
                    pendingVersionName = remoteName
                    runOnUiThread {
                        updateButton.visibility = View.VISIBLE
                        updateButton.isEnabled = true
                        updateButton.text = "Update to $remoteName"
                    }
                    logD("I", "Update available: $remoteName")
                    if (manual) toast("Update available: $remoteName")
                } else {
                    if (manual) toast("Up to date")
                }
            } catch (e: Exception) {
                logD("E", "Update check failed: ${e.message}")
                if (manual) toast("Update check failed")
            }
        }
    }

    private fun downloadAndInstall() {
        val url = pendingApkUrl ?: DEFAULT_APK_URL
        toast("Downloading update...")
        ioExecutor.execute {
            try {
                logD("I", "Downloading APK from $url")
                val file = File(cacheDir, "update.apk")
                httpDownload(url, file)
                logD("I", "Downloaded ${file.length()} bytes to ${file.path}")
                runOnUiThread { launchInstall(file) }
            } catch (e: Exception) {
                logD("E", "Download failed: ${e.message}")
                toast("Download failed: ${e.message}")
            }
        }
    }

    private fun launchInstall(file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            logD("W", "Install permission not granted; sending user to settings")
            toast("Allow installs, then tap Update again")
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                logD("E", "Cannot open install-permission settings: ${e.message}")
            }
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            logD("I", "Launched system installer")
        } catch (e: Exception) {
            logD("E", "Install launch failed: ${e.message}")
            toast("Install failed: ${e.message}")
        }
    }

    private fun httpGetString(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            conn.inputStream.use { input ->
                return input.readBytes().toString(Charsets.UTF_8)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpDownload(urlStr: String, dest: File) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 20000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            conn.inputStream.use { input ->
                dest.outputStream.use { output ->
                    input.copyTo(output)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------- wifi

    private fun acquireMulticastLock() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("meshchat").apply {
                setReferenceCounted(true)
                acquire()
            }
            logD("I", "Multicast lock acquired")
        } catch (e: Exception) {
            logD("W", "Multicast lock failed: ${e.message}")
        }
    }
}
