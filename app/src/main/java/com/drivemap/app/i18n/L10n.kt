package com.drivemap.app.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** App language: English or Slovak. */
object L10n {
    /** "en" or "sk". Compose state, so on-screen text switches as soon as it changes. */
    var lang by mutableStateOf("en")
        private set

    val isSk get() = lang == "sk"

    /** [setting]: "auto" (follow the phone), "en" or "sk". Czech phones get Slovak too. */
    fun apply(setting: String) {
        lang = when (setting) {
            "en", "sk" -> setting
            else -> if (Locale.getDefault().language in setOf("sk", "cs")) "sk" else "en"
        }
    }
}

/**
 * The English UI text [en] in the app language. Placeholders {0}, {1}… are filled from [args].
 * Strings without a translation stay English.
 */
fun t(en: String, vararg args: Any?): String {
    var s = if (L10n.lang == "sk") SK[en] ?: en else en
    args.forEachIndexed { i, a -> s = s.replace("{$i}", a.toString()) }
    return s
}

/** Count-dependent text, e.g. tn(n, "{0} radar", "{0} radars"); Slovak has 1 / 2–4 / 5+ forms. */
fun tn(n: Int, one: String, many: String): String {
    val forms = if (L10n.lang == "sk") SK_PLURAL[many] else null
    val s = when {
        forms != null -> when (n) { 1 -> forms[0]; in 2..4 -> forms[1]; else -> forms[2] }
        n == 1 -> one
        else -> many
    }
    return s.replace("{0}", n.toString())
}
