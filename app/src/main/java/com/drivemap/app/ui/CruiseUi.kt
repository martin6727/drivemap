package com.drivemap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drivemap.app.i18n.t
import com.drivemap.app.route.CruiseInvite

private fun lengthText(min: Int) = if (min < 60) t("{0} min", min) else t("{0} h", min / 60)

/** "Challenge to a cruise": pick length and style, then the app plans a loop to preview and send. */
@Composable
fun CruiseSetupDialog() {
    val setup by Ui.cruiseSetup.collectAsState()
    val planning by Ui.cruisePlanning.collectAsState()
    val s = setup ?: return
    var minutes by remember { mutableIntStateOf(60) }
    var scenic by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = { if (!planning) Ui.cruiseSetup.value = null },
        title = { Text(if (s.toName != null) t("Challenge {0}", s.toName) else t("Challenge for the whole convoy")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t("A relaxed round trip from here, back to the same place. They get a notification to accept or decline."), fontSize = 14.sp)
                Text(t("Length"), fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30, 60, 120).forEach { m -> FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text(lengthText(m)) }) }
                }
                Text(t("Roads"), fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = scenic, onClick = { scenic = true }, label = { Text(t("Scenic & twisty")) })
                    FilterChip(selected = !scenic, onClick = { scenic = false }, label = { Text(t("Relaxed")) })
                }
                if (planning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = !planning, onClick = { Ui.planCruise(s, minutes, scenic) }) { Text(t("Plan the challenge")) }
        },
        dismissButton = { TextButton(enabled = !planning, onClick = { Ui.cruiseSetup.value = null }) { Text(t("Cancel")) } },
    )
}

/** Shown while previewing a planned cruise: send it as a challenge. */
@Composable
fun CruiseSendCard(inv: CruiseInvite, forName: String?) {
    Surface(shape = RoundedCornerShape(16.dp), shadowElevation = glassShadow(6.dp), border = panelBorder, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (forName != null) t("Challenge with {0}", forName) else t("Challenge for the whole convoy"), fontWeight = FontWeight.Bold)
                Text(
                    lengthText(inv.minutes) + " · " + (if (inv.scenic) t("Scenic & twisty") else t("Relaxed")),
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = { Ui.sendCruise() },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Purple, contentColor = Color.White),
            ) { Text(t("Send challenge")) }
        }
    }
}

/** Incoming challenge: accept (opens the route) or decline. */
@Composable
fun CruiseInviteCard(inv: CruiseInvite) {
    Surface(shape = RoundedCornerShape(16.dp), shadowElevation = glassShadow(6.dp), border = panelBorder, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(Palette.Purple), contentAlignment = Alignment.Center) {
                    Icon(Glyphs.Group, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (inv.isJoin && inv.toId == null) t("Drive with me") else t("Challenge"), color = Palette.Purple, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    if (inv.isJoin && inv.toId != null) {
                        Text(t("{0} challenges you to drive together to {1}", inv.fromName, inv.destName ?: ""), fontSize = 15.sp)
                    } else if (inv.isJoin) {
                        Text(t("{0} wants you to drive with them to {1}", inv.fromName, inv.destName ?: ""), fontSize = 15.sp)
                    } else {
                        Text(t("{0} challenges you to a {1}-minute drive", inv.fromName, inv.minutes), fontSize = 15.sp)
                        Text(if (inv.scenic) t("Scenic & twisty") else t("Relaxed"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { Ui.declineCruise(inv) }) { Text(t("Decline")) }
                Button(
                    onClick = { Ui.acceptCruise(inv) },
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Purple, contentColor = Color.White),
                ) { Text(t("Accept")) }
            }
        }
    }
}


/** Compact convoy member card during navigation: speed, distance and a one-tap challenge. */
@Composable
fun NavMemberCard(id: String) {
    val convoy by com.drivemap.app.core.Core.convoy.state.collectAsState()
    val m = convoy?.members?.firstOrNull { it.id == id } ?: return
    val fix by com.drivemap.app.core.Core.location.fix.collectAsState()
    Surface(shape = RoundedCornerShape(16.dp), shadowElevation = glassShadow(6.dp), border = panelBorder, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(Color(m.color)), contentAlignment = Alignment.Center) {
                Text(m.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(m.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                val parts = mutableListOf("${Math.round(m.speedKmh)} km/h")
                fix?.let { parts += com.drivemap.app.geo.Geo.formatDistance(com.drivemap.app.geo.Geo.dist(it.pos, m.pos)) }
                Text(parts.joinToString(" · "), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = { Ui.inviteDriveWithMe(m.id, m.name); Ui.selectedMember.value = null }) { Text(t("Challenge")) }
            TextButton(onClick = { Ui.selectedMember.value = null }) { Text(t("Close")) }
        }
    }
}
