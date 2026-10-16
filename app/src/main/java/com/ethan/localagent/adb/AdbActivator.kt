package com.ethan.localagent.adb

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.ethan.localagent.R
import com.ethan.localagent.app
import com.ethan.localagent.util.Notifications
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

sealed interface ActivationStep {
    data object Pair : ActivationStep
    /** Pairing service found, waiting for the code from the notification. */
    data object WaitingForCode : ActivationStep
    data class Applying(val done: Int, val total: Int, val current: String) : ActivationStep
    data class Failed(val message: String, val fixIndex: Int?) : ActivationStep
    data object Done : ActivationStep
}

data class Fix(val title: String, val command: String, val ok: Boolean? = null)

/**
 * Shizuku-style self activation: pair with the phone's own wireless-debugging service, then use
 * the resulting shell-level access to switch off the battery and background limits that would
 * otherwise kill long agent runs.
 */
class AdbActivator(private val context: Context, private val scope: CoroutineScope) {
    private val pkg = context.packageName
    private val _step = MutableStateFlow<ActivationStep>(ActivationStep.Pair)
    val step: StateFlow<ActivationStep> = _step.asStateFlow()
    private val _fixes = MutableStateFlow(defaultFixes())
    val fixes: StateFlow<List<Fix>> = _fixes.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private var mdns: AdbMdns? = null
    private var pairingHost: InetAddress? = null
    private var pairingPort: Int = -1
    private val manager by lazy { Manager(context) }

    private fun defaultFixes() = listOf(
        Fix("Battery: Unrestricted", "dumpsys deviceidle whitelist +$pkg && cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow && cmd appops set $pkg RUN_IN_BACKGROUND allow"),
        Fix("Background process limit off", "device_config set_sync_disabled_for_tests persistent; device_config put activity_manager max_phantom_processes 2147483647 && settings put global settings_enable_monitor_phantom_procs false"),
        Fix("Standby bucket: Active", "am set-standby-bucket $pkg active"),
        Fix("Never paused when unused", "cmd appops set $pkg AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore"),
        Fix("All files access for Code mode", "cmd appops set $pkg MANAGE_EXTERNAL_STORAGE allow"),
        Fix("Notifications allowed", "pm grant $pkg android.permission.POST_NOTIFICATIONS"),
    )

    private fun log(line: String) {
        Log.i(TAG, line)
        _log.value = (_log.value + line).takeLast(300)
    }

    /** Tries an existing pairing first; otherwise waits for the pairing dialog and asks for the code. */
    fun start() {
        _log.value = emptyList()
        _fixes.value = defaultFixes()
        scope.launch(Dispatchers.IO) {
            if (tryConnect(4_000)) { applyFixes(0); return@launch }
            _step.value = ActivationStep.Pair
            log("Waiting for the pairing service… open Wireless debugging › Pair device with pairing code")
            startDiscovery()
            notifyPairing(found = false)
        }
    }

    private fun startDiscovery() {
        mdns?.stop()
        mdns = AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host, port ->
            if (port > 0 && host != null) {
                pairingHost = host; pairingPort = port
                log("Pairing service at ${host.hostAddress}:$port")
                _step.value = ActivationStep.WaitingForCode
                notifyPairing(found = true)
            }
        }.also { it.start() }
    }

    fun submitCode(code: String) {
        scope.launch(Dispatchers.IO) {
            val host = pairingHost; val port = pairingPort
            if (host == null || port <= 0) { log("No pairing service yet"); notifyPairing(found = false); return@launch }
            notify("Pairing…", "Checking the code", input = false)
            try {
                val ok = manager.pair(host.hostAddress, port, code.filter { it.isDigit() })
                if (!ok) error("Pairing was refused. Check the code and try again.")
                log("$ adb pair ${host.hostAddress}:$port\nSuccessfully paired")
                mdns?.stop()
                if (!tryConnect(15_000)) error("Paired, but couldn't connect to wireless debugging")
                cancelNotification()
                applyFixes(0)
            } catch (e: Exception) {
                Log.w(TAG, "pair failed", e)
                log("pairing failed: ${e.message}")
                _step.value = ActivationStep.Failed("Pairing didn't work: ${e.message}. Open the pairing dialog again and retry.", null)
                notifyPairing(found = pairingHost != null)
            }
        }
    }

    private suspend fun tryConnect(timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        if (manager.isConnected) return@withContext true
        runCatching { manager.connectTls(context, timeoutMs) }.onFailure { log("connect: ${it.message}") }.getOrDefault(false).also {
            if (it) log("Connected to wireless debugging")
        }
    }

    /** Runs the fixes from [from]; stops at the first failure so the user can retry or skip it. */
    fun applyFixes(from: Int) {
        scope.launch(Dispatchers.IO) {
            val list = _fixes.value
            for (i in from until list.size) {
                val fix = list[i]
                _step.value = ActivationStep.Applying(i, list.size, fix.title + "…")
                log("$ ${fix.command}")
                val (ok, out) = runCatching { shell(fix.command) }.getOrElse { false to (it.message ?: "error") }
                out.lines().filter { it.isNotBlank() }.forEach { log(it) }
                _fixes.value = _fixes.value.mapIndexed { j, f -> if (j == i) f.copy(ok = ok) else f }
                if (!ok) {
                    _step.value = ActivationStep.Failed("Couldn't do “${fix.title}”. Your phone blocked it. Everything before it worked.", i)
                    return@launch
                }
            }
            context.app.prefs.update { it.copy(onboardingDone = true) }
            _step.value = ActivationStep.Done
        }
    }

    fun retry() {
        val s = _step.value
        if (s is ActivationStep.Failed && s.fixIndex != null) applyFixes(s.fixIndex) else start()
    }

    fun skip() {
        val s = _step.value as? ActivationStep.Failed ?: return
        val i = s.fixIndex ?: return
        _fixes.value = _fixes.value.mapIndexed { j, f -> if (j == i) f.copy(ok = false) else f }
        applyFixes(i + 1)
    }

    /** True once we hold a wireless-debugging connection (paired earlier). */
    suspend fun ensureConnected(): Boolean = tryConnect(5_000)

    /** Runs a shell command with ADB (shell-user) rights. Returns success and combined output. */
    @Synchronized
    fun runShell(command: String): Pair<Boolean, String> = shell(command)

    /** A PNG screenshot of the main display, taken through ADB. */
    @Synchronized
    fun screenshot(): ByteArray = manager.openStream("exec:screencap -p").use { it.openInputStream().readBytes() }

    private fun shell(command: String): Pair<Boolean, String> {
        manager.openStream("shell:$command; echo __EXIT=\$?").use { stream ->
            val text = stream.openInputStream().bufferedReader().readText()
            val code = Regex("__EXIT=(\\d+)").findAll(text).lastOrNull()?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val clean = text.replace(Regex("__EXIT=\\d+\\s*"), "").trim()
            val bad = clean.contains("Exception", true) || clean.contains("Permission denial", true) || clean.contains("Unknown", false)
            return (code == 0 && !bad) to clean
        }
    }

    fun openWirelessDebugging() {
        val fragment = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", "toggle_adb_wireless") })
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(fragment) }
    }

    private fun notifyPairing(found: Boolean) = if (found) {
        notify("Pairing service found", "Enter the 6-digit code shown in Wireless debugging", input = true)
    } else {
        notify("Ready to pair", "In Wireless debugging, tap Pair device with pairing code", input = false)
    }

    private fun notify(title: String, text: String, input: Boolean) {
        val b = NotificationCompat.Builder(context, Notifications.SETUP)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(!input)
            .setContentIntent(Notifications.openApp(context, "activation"))
        if (input) {
            val remote = RemoteInput.Builder(PairingCodeReceiver.KEY_CODE).setLabel("Pairing code").build()
            val pi = PendingIntent.getBroadcast(
                context, 7, Intent(context, PairingCodeReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(NotificationCompat.Action.Builder(0, "Enter code", pi).addRemoteInput(remote).build())
        }
        context.getSystemService(NotificationManager::class.java).notify(Notifications.ID_PAIRING, b.build())
    }

    private fun cancelNotification() = context.getSystemService(NotificationManager::class.java).cancel(Notifications.ID_PAIRING)

    /** Our ADB identity: an RSA key and self-signed certificate kept in app storage. */
    private class Manager(context: Context) : AbsAdbConnectionManager() {
        private val dir = File(context.filesDir, "adb").apply { mkdirs() }
        private val key: PrivateKey
        private val cert: Certificate

        init {
            api = Build.VERSION.SDK_INT
            val keyFile = File(dir, "key.pk8")
            val certFile = File(dir, "cert.der")
            if (keyFile.exists() && certFile.exists()) {
                key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                cert = CertificateFactory.getInstance("X.509").generateCertificate(certFile.inputStream())
            } else {
                val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                val name = X500Name("CN=Chattifier")
                val now = System.currentTimeMillis()
                val holder = JcaX509v3CertificateBuilder(
                    name, BigInteger.valueOf(now), Date(now - 86_400_000), Date(now + 10L * 365 * 86_400_000), name, kp.public,
                ).build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
                key = kp.private
                cert = JcaX509CertificateConverter().getCertificate(holder)
                keyFile.writeBytes(key.encoded)
                certFile.writeBytes(cert.encoded)
            }
        }

        override fun getPrivateKey(): PrivateKey = key
        override fun getCertificate(): Certificate = cert
        override fun getDeviceName(): String = "Chattifier"
    }

    companion object {
        private const val TAG = "AdbActivator"
    }
}
