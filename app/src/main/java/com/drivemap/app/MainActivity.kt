package com.drivemap.app

import com.drivemap.app.i18n.t
import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.drivemap.app.convoy.ConvoyCrypto
import com.drivemap.app.core.Core
import com.drivemap.app.geo.LatLon
import com.drivemap.app.net.Search
import com.drivemap.app.route.Place
import com.drivemap.app.ui.MainScreen
import com.drivemap.app.ui.Screen
import com.drivemap.app.ui.Ui
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Core.location.checkPermission()) {
            Core.location.start()
        } else {
            Core.message(t("DriveMap needs location access for navigation and radar alerts"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MainScreen() }

        val wanted = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        if (!Core.location.checkPermission()) permissions.launch(wanted.toTypedArray())
        else if (Build.VERSION.SDK_INT >= 33) permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))

        lifecycleScope.launch {
            Core.navigator.state.map { it != null }.distinctUntilChanged().collect { navigating ->
                if (navigating) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        Core.onForeground(true)
    }

    override fun onStop() {
        Core.onForeground(false)
        super.onStop()
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.getStringExtra("open") == "vignettes") Ui.screen.value = Screen.VIGNETTES
        val data = i.data ?: return
        when (data.scheme) {
            "drivemap" -> if (data.host == "convoy") {
                val code = data.lastPathSegment ?: return
                if (!ConvoyCrypto.isValid(code)) return
                if (Core.settings.value.userName.isBlank()) {
                    Ui.pendingJoinCode.value = ConvoyCrypto.pretty(code)
                    Ui.screen.value = Screen.CONVOY
                    Core.message(t("Enter your name, then tap Join"))
                } else {
                    Core.convoy.join(code)
                    Core.updateService()
                    Ui.screen.value = Screen.CONVOY
                }
            }
            "geo" -> openGeo(data.schemeSpecificPart ?: return)
        }
    }

    /** geo:lat,lon  /  geo:0,0?q=lat,lon(label)  /  geo:0,0?q=some address */
    private fun openGeo(ssp: String) {
        val coords = ssp.substringBefore('?')
        val q = ssp.substringAfter("q=", "").let { java.net.URLDecoder.decode(it, "UTF-8") }
        val num = Regex("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)")
        val fromQ = num.find(q)
        val label = Regex("\\(([^)]*)\\)").find(q)?.groupValues?.get(1)
        val m = fromQ ?: num.find(coords)
        if (m != null) {
            val pos = LatLon(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
            if (pos.lat != 0.0 || pos.lon != 0.0) {
                Ui.selectPlace(Place(label ?: t("Shared location"), pos)
)
                return
            }
        }
        if (q.isNotBlank()) {
            lifecycleScope.launch {
                try {
                    Search.autocomplete(q, Core.location.fix.value?.pos, 1).firstOrNull()?.let { Ui.selectPlace(it) }
                } catch (_: Exception) {
                }
            }
        }
    }
}
