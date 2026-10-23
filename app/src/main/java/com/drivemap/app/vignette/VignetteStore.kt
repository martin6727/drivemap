package com.drivemap.app.vignette

import com.drivemap.app.i18n.t
import com.drivemap.app.i18n.tn
import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.drivemap.app.MainActivity
import com.drivemap.app.R
import com.drivemap.app.data.Countries
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

data class OwnedVignette(
    val id: String = UUID.randomUUID().toString(),
    val country: String,
    val productId: String?,
    val start: LocalDate,
    val end: LocalDate,
    val note: String = "",
) {
    fun validOn(d: LocalDate) = !d.isBefore(start) && !d.isAfter(end)
    fun label(): String = productId?.let { VignetteCatalog.product(it)?.label } ?: t("Custom")
}

enum class VignetteStatus { VALID, EXPIRING, FUTURE, EXPIRED, NONE }

class VignetteStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("vignettes", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<OwnedVignette>> = _items

    private fun load(): List<OwnedVignette> = try {
        val arr = JSONArray(prefs.getString("items", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            OwnedVignette(
                id = o.getString("id"),
                country = o.getString("country"),
                productId = o.optString("product").ifBlank { null },
                start = LocalDate.parse(o.getString("start")),
                end = LocalDate.parse(o.getString("end")),
                note = o.optString("note"),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun save(list: List<OwnedVignette>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("country", it.country).put("product", it.productId ?: "")
                    .put("start", it.start.toString()).put("end", it.end.toString()).put("note", it.note)
            )
        }
        prefs.edit().putString("items", arr.toString()).apply()
        _items.value = list.sortedWith(compareBy({ it.country }, { it.start }))
        scheduleReminders(context, list)
    }

    fun upsert(v: OwnedVignette) = save(_items.value.filter { it.id != v.id } + v)
    fun remove(id: String) = save(_items.value.filter { it.id != id })

    fun forCountry(code: String) = _items.value.filter { it.country == code }

    /** True if owned vignettes cover every day from [from] to [to] in [country]. */
    fun covers(country: String, from: LocalDate, to: LocalDate): Boolean {
        var d = from
        while (!d.isAfter(to)) {
            if (_items.value.none { it.country == country && it.validOn(d) && it.productId != "hucounty" }) return false
            d = d.plusDays(1)
        }
        return true
    }

    fun status(country: String, today: LocalDate = LocalDate.now()): Pair<VignetteStatus, OwnedVignette?> {
        val list = forCountry(country)
        val current = list.filter { it.validOn(today) }.maxByOrNull { it.end }
        if (current != null) {
            val left = ChronoUnit.DAYS.between(today, current.end)
            return (if (left <= 3) VignetteStatus.EXPIRING else VignetteStatus.VALID) to current
        }
        val future = list.filter { it.start.isAfter(today) }.minByOrNull { it.start }
        if (future != null) return VignetteStatus.FUTURE to future
        val past = list.maxByOrNull { it.end }
        return if (past != null) VignetteStatus.EXPIRED to past else VignetteStatus.NONE to null
    }

    companion object {
        const val CHANNEL = "vignette_reminders"

        fun scheduleReminders(context: Context, list: List<OwnedVignette>) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val zone = ZoneId.systemDefault()
            val now = System.currentTimeMillis()
            list.forEachIndexed { i, v ->
                for ((slot, daysBefore) in listOf(0 to 3L, 1 to 0L)) {
                    val whenMs = v.end.minusDays(daysBefore).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
                    val intent = Intent(context, ReminderReceiver::class.java)
                        .putExtra("country", v.country)
                        .putExtra("end", v.end.toString())
                        .putExtra("days", daysBefore)
                    val pi = PendingIntent.getBroadcast(
                        context, v.id.hashCode() * 4 + slot, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    if (whenMs > now) am.set(AlarmManager.RTC_WAKEUP, whenMs, pi) else am.cancel(pi)
                }
                if (i > 200) return
            }
        }

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, t("Vignette reminders"), NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val country = intent.getStringExtra("country") ?: return
        val end = intent.getStringExtra("end") ?: return
        val days = intent.getLongExtra("days", 0)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        VignetteStore.ensureChannel(context)
        val title = "${Countries.flag(country)} " + t("{0} vignette", Countries.name(country)) + " " +
            if (days == 0L) t("expires today") else tn(days.toInt(), "expires in {0} day", "expires in {0} days")
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).putExtra("open", "vignettes"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, VignetteStore.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_car)
            .setContentTitle(title)
            .setContentText(t("Valid until {0}. Routes will avoid its motorways after that.", end))

            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(("vig$country$end").hashCode(), n)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            VignetteStore.scheduleReminders(context, VignetteStore(context).items.value)
        }
    }
}
