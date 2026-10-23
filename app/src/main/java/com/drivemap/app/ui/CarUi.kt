package com.drivemap.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drivemap.app.core.Core
import com.drivemap.app.i18n.t

/** "Choose your car" — opened by tapping your own car / arrow on the map, like Google Maps. */
@Composable
fun CarPickerDialog() {
    val open by Ui.carPicker.collectAsState()
    if (!open) return
    val settings by Core.settings.state.collectAsState()
    val ctx = LocalContext.current
    val landscape = isLandscape()
    // 3/4 view previews, drawn once
    val previews = remember {
        Car3D.skins.associate { it.id to cropTransparent(Car3D.render(ctx, it.body, it.color, 35.0, Car3D.TILT, 110f)).asImageBitmap() }
    }
    AlertDialog(
        onDismissRequest = { Ui.carPicker.value = false },
        title = { Text(t("Your car")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Car3D.skins.chunked(if (landscape) 3 else 2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { sk ->
                            val selected = settings.carSkin == sk.id
                            Surface(
                                onClick = {
                                    Core.settings.update { it.copy(carSkin = sk.id) }
                                    Ui.carPicker.value = false
                                },
                                shape = RoundedCornerShape(16.dp),
                                color = if (selected) Tints.blue else MaterialTheme.colorScheme.surfaceVariant,
                                border = if (selected) BorderStroke(2.dp, Palette.Blue) else null,
                                modifier = Modifier.weight(1f),
                            ) {
                                Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Image(previews.getValue(sk.id), sk.label, modifier = Modifier.size(if (landscape) 84.dp else 110.dp))
                                    Text(
                                        sk.label, fontSize = 13.sp, textAlign = TextAlign.Center,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                        repeat((if (landscape) 3 else 2) - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
                Text(
                    t("Your convoy sees your car too."), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { Ui.carPicker.value = false }) { Text(t("Close")) } },
    )
}

/** Round map button with a short text label, e.g. "3D". */
@Composable
fun RoundTextButton(text: String, contentDescription: String, onClick: () -> Unit, size: Dp = 48.dp, tint: Color = MaterialTheme.colorScheme.onSurface) {
    Surface(
        onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = glassShadow(4.dp),
        modifier = Modifier.size(size), border = panelBorder,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = tint, modifier = Modifier.semantics { this.contentDescription = contentDescription })
        }
    }
}


/** Cuts the empty margin off a sprite so previews fill their card. */
private fun cropTransparent(b: android.graphics.Bitmap): android.graphics.Bitmap {
    val w = b.width
    val h = b.height
    val px = IntArray(w * h)
    b.getPixels(px, 0, w, 0, 0, w, h)
    var l = w; var t = h; var r = -1; var btm = -1
    for (y in 0 until h) for (x in 0 until w) {
        if ((px[y * w + x] ushr 24) > 40) {
            if (x < l) l = x
            if (x > r) r = x
            if (y < t) t = y
            if (y > btm) btm = y
        }
    }
    if (r < l || btm < t) return b
    val pad = 4
    l = (l - pad).coerceAtLeast(0); t = (t - pad).coerceAtLeast(0)
    r = (r + pad).coerceAtMost(w - 1); btm = (btm + pad).coerceAtMost(h - 1)
    return android.graphics.Bitmap.createBitmap(b, l, t, r - l + 1, btm - t + 1)
}
