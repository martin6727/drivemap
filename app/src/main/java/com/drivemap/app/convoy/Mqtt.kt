package com.drivemap.app.convoy

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.eclipse.paho.mqttv5.client.IMqttToken
import org.eclipse.paho.mqttv5.client.MqttAsyncClient
import org.eclipse.paho.mqttv5.client.MqttCallback
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence
import org.eclipse.paho.mqttv5.common.MqttException
import org.eclipse.paho.mqttv5.common.MqttMessage
import org.eclipse.paho.mqttv5.common.packet.MqttProperties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One shared MQTT 5 connection for convoy groups and radar reports. Payloads are encrypted by the
 * callers, so the public broker only relays opaque bytes.
 */
class MqttHub(private val scope: CoroutineScope, private val brokerUrl: () -> String) {
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private var client: MqttAsyncClient? = null
    private val handlers = ConcurrentHashMap<String, (String, ByteArray) -> Unit>()
    private val users = ConcurrentHashMap.newKeySet<String>()
    private var connectJob: Job? = null
    private var idleJob: Job? = null
    private var currentUrl: String? = null

    /** Registers a user of the connection (e.g. "convoy", "reports"); connects if needed. */
    fun acquire(who: String) {
        users.add(who)
        idleJob?.cancel()
        ensureConnected()
    }

    fun release(who: String) {
        users.remove(who)
        if (users.isEmpty()) {
            idleJob?.cancel()
            idleJob = scope.launch {
                delay(20_000)
                if (users.isEmpty()) disconnect()
            }
        }
    }

    private fun ensureConnected() {
        val url = brokerUrl()
        if (client != null && currentUrl == url) return
        if (connectJob?.isActive == true) return
        connectJob = scope.launch(Dispatchers.IO) {
            if (client != null && currentUrl != url) disconnect()
            var backoff = 2000L
            while (users.isNotEmpty() && client == null) {
                try {
                    val c = MqttAsyncClient(url, "dm-" + UUID.randomUUID().toString().take(12), MemoryPersistence())
                    c.setCallback(callback)
                    val opts = MqttConnectionOptions().apply {
                        isCleanStart = true
                        // no library auto-reconnect: its timer could fire after we closed the client and
                        // crash the app (Paho NPE in attemptReconnect); a dropped connection is handled in
                        // callback.disconnected() with our own back-off loop instead
                        isAutomaticReconnect = false
                        keepAliveInterval = 30
                        connectionTimeout = 15
                    }
                    c.connect(opts).waitForCompletion(20_000)
                    client = c
                    currentUrl = url
                    _connected.value = true
                    resubscribeAll()
                } catch (e: Exception) {
                    Log.w("Mqtt", "connect failed: ${e.message}")
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(60_000)
                }
            }
        }
    }

    private fun disconnect() {
        val c = client ?: return
        client = null
        currentUrl = null
        _connected.value = false
        scope.launch(Dispatchers.IO) {
            try { c.disconnect().waitForCompletion(3000) } catch (_: Exception) {}
            try { c.close() } catch (_: Exception) {}
        }
    }

    /** Reconnects to a different broker after a settings change. */
    fun brokerChanged() {
        disconnect()
        if (users.isNotEmpty()) ensureConnected()
    }

    private val callback = object : MqttCallback {
        override fun disconnected(disconnectResponse: MqttDisconnectResponse?) {
            _connected.value = false
            // connection lost: drop this client and reconnect (with back-off) if still needed
            val c = client
            if (c != null && !c.isConnected) {
                client = null
                currentUrl = null
                scope.launch(Dispatchers.IO) { try { c.close() } catch (_: Exception) {} }
                if (users.isNotEmpty()) scope.launch { delay(2000); ensureConnected() }
            }
        }

        override fun mqttErrorOccurred(exception: MqttException?) {
            Log.w("Mqtt", "error: ${exception?.message}")
        }

        override fun messageArrived(topic: String, message: MqttMessage) {
            val payload = message.payload ?: ByteArray(0)
            for ((filter, h) in handlers) {
                if (matches(filter, topic)) {
                    try { h(topic, payload) } catch (e: Exception) { Log.w("Mqtt", "handler: $e") }
                }
            }
        }

        override fun deliveryComplete(token: IMqttToken?) {}

        override fun connectComplete(reconnect: Boolean, serverURI: String?) {
            _connected.value = true
            if (reconnect) resubscribeAll()
        }

        override fun authPacketArrived(reasonCode: Int, properties: MqttProperties?) {}
    }

    private fun resubscribeAll() {
        val c = client ?: return
        for (f in handlers.keys) {
            try { c.subscribe(f, 0) } catch (e: Exception) { Log.w("Mqtt", "subscribe $f: ${e.message}") }
        }
    }

    fun subscribe(filter: String, handler: (String, ByteArray) -> Unit) {
        handlers[filter] = handler
        val c = client ?: return
        scope.launch(Dispatchers.IO) {
            try { c.subscribe(filter, 0) } catch (e: Exception) { Log.w("Mqtt", "subscribe $filter: ${e.message}") }
        }
    }

    fun unsubscribe(filter: String) {
        handlers.remove(filter)
        val c = client ?: return
        scope.launch(Dispatchers.IO) {
            try { c.unsubscribe(filter) } catch (_: Exception) {}
        }
    }

    /** Publishes; [expirySec] uses MQTT 5 message expiry so stale retained data disappears. */
    fun publish(topic: String, payload: ByteArray, retained: Boolean, expirySec: Long? = null, qos: Int = 0) {
        val c = client ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val msg = MqttMessage(payload)
                msg.qos = qos
                msg.isRetained = retained
                if (expirySec != null) {
                    val props = MqttProperties()
                    props.messageExpiryInterval = expirySec
                    msg.properties = props
                }
                c.publish(topic, msg)
            } catch (e: Exception) {
                Log.w("Mqtt", "publish $topic: ${e.message}")
            }
        }
    }

    companion object {
        fun matches(filter: String, topic: String): Boolean {
            val f = filter.split('/')
            val t = topic.split('/')
            for (i in f.indices) {
                if (f[i] == "#") return true
                if (i >= t.size) return false
                if (f[i] != "+" && f[i] != t[i]) return false
            }
            return f.size == t.size
        }
    }
}
