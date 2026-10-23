package com.drivemap.app.convoy

import android.content.Context
import com.drivemap.app.data.SettingsStore
import com.drivemap.app.geo.LatLon
import com.drivemap.app.location.Fix
import com.drivemap.app.nav.NavState
import com.drivemap.app.radar.ReportsRepository
import com.drivemap.app.route.Place
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

data class Member(
    val id: String,
    val name: String,
    val color: Int,
    val pos: LatLon,
    val bearing: Double,
    val speedKmh: Double,
    val ts: Long,
    val destName: String?,
    val etaMs: Long?,
    val remainingKm: Double?,
    val isMe: Boolean,
    /** Car look they picked (Car3D skin id). */
    val skin: String? = null,
) {
    val ageS get() = (System.currentTimeMillis() - ts) / 1000
    val stale get() = ageS > 60
}

data class ConvoyStop(
    val id: String,
    val name: String,
    val pos: LatLon,
    val addedBy: String,
    val addedByName: String,
    val ts: Long,
    val removed: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("n", name).put("la", pos.lat).put("lo", pos.lon)
        .put("by", addedBy).put("bn", addedByName).put("ts", ts).put("rm", removed)

    companion object {
        fun fromJson(o: JSONObject) = ConvoyStop(
            o.getString("id"), o.optString("n"), LatLon(o.getDouble("la"), o.getDouble("lo")),
            o.optString("by"), o.optString("bn"), o.optLong("ts"), o.optBoolean("rm"),
        )
    }
}

data class ConvoyState(
    val code: String,
    val myId: String,
    val members: List<Member>,
    /** Active (not removed) stops in the order they were added. */
    val stops: List<ConvoyStop>,
    val destination: ConvoyStop?,
    val ready: Boolean,
    /** Changes every few seconds so "live / last seen" labels refresh. */
    val tick: Long = System.currentTimeMillis() / 5000,
)

sealed class ConvoyEvent {
    data class StopAdded(val stop: ConvoyStop) : ConvoyEvent()
    data class StopRemoved(val stop: ConvoyStop) : ConvoyEvent()
    data class DestinationSet(val stop: ConvoyStop) : ConvoyEvent()
    data class MemberJoined(val name: String) : ConvoyEvent()
    data class CruiseInvited(val invite: com.drivemap.app.route.CruiseInvite) : ConvoyEvent()
    data class CruiseAnswered(val invite: com.drivemap.app.route.CruiseInvite, val name: String, val accepted: Boolean) : ConvoyEvent()
}

/**
 * Private convoy groups: live positions (every ~3 s), shared stops and a shared destination, relayed
 * over MQTT and end-to-end encrypted with a key derived from the group code.
 */
class ConvoyManager(
    context: Context,
    private val hub: MqttHub,
    private val settings: SettingsStore,
    private val reports: ReportsRepository,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("convoy", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<ConvoyState?>(null)
    val state: StateFlow<ConvoyState?> = _state
    private val _events = MutableSharedFlow<ConvoyEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<ConvoyEvent> = _events

    private var code: String? = null
    private var key: SecretKey? = null
    private var prefix: String? = null
    private val members = ConcurrentHashMap<String, Member>()
    private val stops = ConcurrentHashMap<String, ConvoyStop>()
    private var destination: ConvoyStop? = null
    private var lastPublishMs = 0L
    /** When we (re)subscribed: retained stops/members older than this are not "news". */
    @Volatile private var subscribedAt = Long.MAX_VALUE
    private fun isNews(ts: Long) = ts > subscribedAt - 2000
    private var lastFix: Fix? = null
    private var lastNav: NavState? = null
    private var ticker: Job? = null

    val myId get() = settings.value.memberId
    val active get() = code != null

    companion object {
        // grey is reserved for members who went offline
        val palette = intArrayOf(
            0xFF1A73E8.toInt(), 0xFFD93025.toInt(), 0xFF188038.toInt(), 0xFFF29900.toInt(),
            0xFF9334E6.toInt(), 0xFF00897B.toInt(), 0xFFE52592.toInt(), 0xFF795548.toInt(),
        )

        fun colorFor(id: String) = palette[Math.floorMod(id.hashCode(), palette.size)]
    }

    fun resume() {
        val saved = prefs.getString("code", null) ?: return
        start(saved)
    }

    fun create(): String {
        val c = ConvoyCrypto.newCode()
        start(c)
        return c
    }

    fun join(input: String): Boolean {
        if (!ConvoyCrypto.isValid(input)) return false
        start(ConvoyCrypto.normalize(input))
        return true
    }

    private fun start(c: String) {
        if (code == c) return
        if (code != null) leave()
        code = c
        prefs.edit().putString("code", c).apply()
        members.clear()
        stops.clear()
        destination = null
        emit(ready = false)
        scope.launch {
            val k = withContext(Dispatchers.Default) { ConvoyCrypto.deriveKey(c) }
            if (code != c) return@launch
            key = k
            val p = "dm1/c/" + ConvoyCrypto.topicId(c)
            prefix = p
            reports.convoyChannel = p to k
            hub.acquire("convoy")
            subscribedAt = System.currentTimeMillis()
            hub.subscribe("$p/#") { topic, payload -> onMessage(topic, payload) }
            emit(ready = true)
            publishMe(force = true)
            ticker = scope.launch {
                while (isActive) {
                    delay(3000)
                    publishMe(force = false)
                    emit(ready = true)
                }
            }
        }
    }

    fun leave() {
        val p = prefix
        if (p != null) {
            hub.publish("$p/m/$myId", ByteArray(0), retained = true)
            hub.unsubscribe("$p/#")
        }
        ticker?.cancel()
        hub.release("convoy")
        reports.convoyChannel = null
        code = null
        key = null
        prefix = null
        members.clear()
        stops.clear()
        destination = null
        prefs.edit().remove("code").apply()
        _state.value = null
    }

    // ---------------------------------------------------------------- my position

    fun onFix(fix: Fix, nav: NavState?) {
        lastFix = fix
        lastNav = nav
        publishMe(force = false)
    }

    private fun publishMe(force: Boolean) {
        val p = prefix ?: return
        val k = key ?: return
        val f = lastFix ?: return
        if (!settings.value.shareLocation) return
        val now = System.currentTimeMillis()
        val interval = if (f.speedMps > 1.0) 3000 else 10_000
        if (!force && now - lastPublishMs < interval) return
        lastPublishMs = now
        val s = settings.value
        val nav = lastNav
        val o = JSONObject()
            .put("id", myId)
            .put("name", s.userName.ifBlank { "Me" })
            .put("la", f.pos.lat).put("lo", f.pos.lon)
            .put("b", f.bearing).put("v", f.speedKmh).put("ts", now)
            .put("sk", s.carSkin)
        if (nav != null) {
            o.put("dest", nav.request.stops.lastOrNull()?.name ?: "")
            o.put("eta", nav.etaMs)
            o.put("rem", nav.remainingM / 1000.0)
        }
        hub.publish("$p/m/$myId", ConvoyCrypto.encrypt(k, o.toString().toByteArray()), retained = true, expirySec = 900)
        members[myId] = Member(
            myId, s.userName.ifBlank { "Me" }, colorFor(myId), f.pos, f.bearing, f.speedKmh, now,
            nav?.request?.stops?.lastOrNull()?.name, nav?.etaMs, nav?.remainingM?.div(1000.0), true,
        )
    }

    // ---------------------------------------------------------------- stops

    fun addStop(place: Place) {
        val s = ConvoyStop(
            id = UUID.randomUUID().toString().replace("-", "").take(10),
            name = place.name, pos = place.pos, addedBy = myId,
            addedByName = settings.value.userName.ifBlank { "Me" }, ts = System.currentTimeMillis(),
        )
        stops[s.id] = s
        publishStops()
        emit(ready = true)
    }

    fun removeStop(id: String) {
        val s = stops[id] ?: return
        stops[id] = s.copy(removed = true, ts = System.currentTimeMillis())
        publishStops()
        emit(ready = true)
    }

    fun setDestination(place: Place?) {
        val p = prefix ?: return
        val k = key ?: return
        val d = place?.let {
            ConvoyStop("dest", it.name, it.pos, myId, settings.value.userName.ifBlank { "Me" }, System.currentTimeMillis())
        }
        destination = d
        if (d == null) hub.publish("$p/d", ByteArray(0), retained = true)
        else hub.publish("$p/d", ConvoyCrypto.encrypt(k, d.toJson().toString().toByteArray()), retained = true, expirySec = 2 * 86400)
        emit(ready = true)
    }

    private fun publishStops() {
        val p = prefix ?: return
        val k = key ?: return
        val cutoff = System.currentTimeMillis() - 86_400_000
        val arr = JSONArray()
        stops.values.filter { !it.removed || it.ts > cutoff }.sortedBy { it.ts }.forEach { arr.put(it.toJson()) }
        hub.publish("$p/s", ConvoyCrypto.encrypt(k, arr.toString().toByteArray()), retained = true, expirySec = 7 * 86400)
    }

    // ---------------------------------------------------------------- incoming

    // ---------------------------------------------------------------- cruise invites

    private val myInvites = ConcurrentHashMap<String, com.drivemap.app.route.CruiseInvite>()

    /** Sends a cruise invite to one member (or everyone); it stays up for an hour. */
    fun inviteCruise(inv: com.drivemap.app.route.CruiseInvite) {
        val p = prefix ?: return
        val k = key ?: return
        myInvites[inv.id] = inv
        hub.publish("$p/cr/${inv.id}", ConvoyCrypto.encrypt(k, inv.toJson().toString().toByteArray()), retained = true, expirySec = 3600)
    }

    fun answerCruise(inv: com.drivemap.app.route.CruiseInvite, accept: Boolean) {
        val p = prefix ?: return
        val k = key ?: return
        val o = JSONObject().put("a", accept).put("n", settings.value.userName.ifBlank { "?" }).put("ts", System.currentTimeMillis())
        hub.publish("$p/cra/${inv.id}/$myId", ConvoyCrypto.encrypt(k, o.toString().toByteArray()), retained = true, expirySec = 3600)
    }

    private fun onMessage(topic: String, payload: ByteArray) {
        val p = prefix ?: return
        val k = key ?: return
        val rest = topic.removePrefix("$p/")
        when {
            rest.startsWith("m/") -> {
                val id = rest.substring(2)
                if (id == myId) return
                if (payload.isEmpty()) {
                    members.remove(id)
                } else {
                    val o = ConvoyCrypto.decrypt(k, payload)?.let { JSONObject(String(it)) } ?: return
                    val isNew = !members.containsKey(id)
                    val m = Member(
                        id = id,
                        name = o.optString("name", "?"),
                        color = colorFor(id),
                        pos = LatLon(o.getDouble("la"), o.getDouble("lo")),
                        bearing = o.optDouble("b", 0.0),
                        speedKmh = o.optDouble("v", 0.0),
                        ts = o.optLong("ts"),
                        destName = o.optString("dest").ifBlank { null },
                        etaMs = o.optLong("eta", 0L).takeIf { it > 0 },
                        remainingKm = o.optDouble("rem", -1.0).takeIf { it >= 0 },
                        isMe = false,
                        skin = o.optString("sk").ifBlank { null },
                    )
                    if (System.currentTimeMillis() - m.ts > 15 * 60_000) return
                    members[id] = m
                    if (isNew && System.currentTimeMillis() - subscribedAt > 8000) _events.tryEmit(ConvoyEvent.MemberJoined(m.name))
                }
            }
            rest == "s" -> {
                if (payload.isEmpty()) return
                val arr = ConvoyCrypto.decrypt(k, payload)?.let { JSONArray(String(it)) } ?: return
                var localNewer = false
                val remoteIds = HashSet<String>()
                for (i in 0 until arr.length()) {
                    val s = ConvoyStop.fromJson(arr.getJSONObject(i))
                    remoteIds.add(s.id)
                    val cur = stops[s.id]
                    if (cur == null || s.ts > cur.ts) {
                        stops[s.id] = s
                        if (s.addedBy != myId && isNews(s.ts)) {
                            if (cur == null && !s.removed) _events.tryEmit(ConvoyEvent.StopAdded(s))
                            else if (cur != null && s.removed && !cur.removed) _events.tryEmit(ConvoyEvent.StopRemoved(s))
                        }
                    } else if (cur.ts > s.ts) localNewer = true
                }
                if (stops.keys.any { it !in remoteIds && !(stops[it]?.removed ?: true) }) localNewer = true
                if (localNewer) publishStops()
            }
            rest == "d" -> {
                if (payload.isEmpty()) {
                    destination = null
                } else {
                    val d = ConvoyCrypto.decrypt(k, payload)?.let { ConvoyStop.fromJson(JSONObject(String(it))) } ?: return
                    val changed = destination?.let { it.ts != d.ts } ?: true
                    destination = d
                    if (changed && d.addedBy != myId && isNews(d.ts)) _events.tryEmit(ConvoyEvent.DestinationSet(d))
                }
            }
            rest.startsWith("cra/") -> {
                // someone answered one of my cruise invites
                val parts = rest.split('/')
                val inv = myInvites[parts.getOrNull(1)] ?: return
                if (parts.getOrNull(2) == myId || payload.isEmpty()) return
                val o = ConvoyCrypto.decrypt(k, payload)?.let { JSONObject(String(it)) } ?: return
                if (isNews(o.optLong("ts"))) _events.tryEmit(ConvoyEvent.CruiseAnswered(inv, o.optString("n", "?"), o.optBoolean("a")))
            }
            rest.startsWith("cr/") -> {
                if (payload.isEmpty()) return
                val inv = ConvoyCrypto.decrypt(k, payload)?.let { com.drivemap.app.route.CruiseInvite.fromJson(JSONObject(String(it))) } ?: return
                if (inv.fromId == myId) myInvites[inv.id] = inv
                else if ((inv.toId == null || inv.toId == myId) && isNews(inv.ts) && System.currentTimeMillis() - inv.ts < 3_600_000) {
                    _events.tryEmit(ConvoyEvent.CruiseInvited(inv))
                }
            }
            rest.startsWith("r/") -> {
                val id = rest.substring(2)
                reports.onConvoy(id, if (payload.isEmpty()) null else ConvoyCrypto.decrypt(k, payload) ?: return)
            }
        }
        emit(ready = true)
    }

    private fun emit(ready: Boolean) {
        val c = code ?: return
        val now = System.currentTimeMillis()
        members.entries.removeIf { !it.value.isMe && now - it.value.ts > 15 * 60_000 }
        _state.value = ConvoyState(
            code = c,
            myId = myId,
            members = members.values.sortedWith(compareBy({ !it.isMe }, { it.name })),
            stops = stops.values.filter { !it.removed }.sortedBy { it.ts },
            destination = destination,
            ready = ready,
        )
    }
}
