package com.drivemap.app.nav

/**
 * Voice profiles: *how* navigation is spoken, never *what* or *when*.
 *
 * The navigator decides that something must be announced and hands over the facts as an
 * [Announcement] (turn type, distance, street, exit number…). A [VoiceProfile] only chooses the
 * wording, the TTS voice / rate / pitch and an optional sound. It cannot change the route, the
 * timing or any navigation decision, and every fact it is given must stay in what it says.
 *
 * Adding a character voice later: write one more object implementing [VoiceProfile] and add it to
 * [VoiceProfiles.all]. Nothing in the navigation engine changes. Anything a profile leaves out
 * (returns null for) falls back to the standard voice.
 */
data class Announcement(
    val kind: Kind,
    /** Voice language, e.g. "sk-SK" (the user's selection; the profile must answer in it). */
    val lang: String,
    /** "Full" voice level: street names, exits and directions may be said. */
    val full: Boolean,
    /** Valhalla maneuver type (see [Phrases.turn]); 0 when not about a maneuver. */
    val type: Int = 0,
    val roundaboutExit: Int = 0,
    /** Metres to the maneuver ([Kind.APPROACH]) or of the coming straight ([Kind.CONTINUE]). */
    val distanceM: Double? = null,
    val street: String? = null,
    val exitNumber: String? = null,
    val toward: String? = null,
    /** What the standard voice says for this moment (already in [lang]); null = say nothing. */
    val standardText: String? = null,
    /** [Kind.BEND]: the bend ahead (pace notes). */
    val bend: BendCall? = null,
) {
    enum class Kind {
        /** Start of guidance (first instruction). */
        START,
        /** A turn ahead, said with its distance ("In 300 metres, turn right"). */
        APPROACH,
        /** The turn right now. */
        NOW,
        /** Just after a turn when the next one is far ("Continue for 12 km"). */
        CONTINUE,
        /** Off the route, a new one is being planned. */
        REROUTE,
        /** A bend of the road ahead (only for profiles with [VoiceProfile.paceNotes]). */
        BEND,
    }
}

/** A bend to call: the bend, the one it runs straight into (if any), metres to the next one. */
data class BendCall(val bend: Bend, val into: Bend? = null, val gapToNextM: Double? = null)

/** TTS wishes of a profile; anything the phone can't do is ignored (standard voice). */
data class TtsPrefs(
    /** 1.0 = normal. */
    val rate: Float = 1.0f,
    /** 1.0 = normal. */
    val pitch: Float = 1.0f,
    /** Parts of installed voice names to prefer (engine-specific), first match wins. */
    val voiceHints: List<String> = emptyList(),
)

/** A short sound played just before an announcement. */
sealed class SoundCue {
    /** Generated tones: frequency (Hz, 0 = pause) and duration (ms) pairs. */
    data class Tones(val notes: List<Pair<Double, Int>>, val volume: Double = 0.6) : SoundCue()
    /** A prerecorded clip from the app's assets (e.g. "voices/rally/go.ogg"). */
    data class Clip(val assetPath: String) : SoundCue()
}

interface VoiceProfile {
    /** Stored in settings; never change it once released. */
    val id: String
    /** Shown in settings (English; translated through t()). */
    val name: String
    val description: String
    val tts: TtsPrefs get() = TtsPrefs()
    /** Also call every bend of the road (rally pace notes), not only junction turns. */
    val paceNotes: Boolean get() = false
    /** The words for [a], or null to use the standard voice's words. */
    fun phrase(a: Announcement): String? = null
    /** A sound before [a], or null for none. */
    fun sound(a: Announcement): SoundCue? = null
}

object VoiceProfiles {
    val standard: VoiceProfile = StandardVoice
    /** All profiles, in the order shown in settings. Add new character voices here. */
    val all: List<VoiceProfile> = listOf(StandardVoice, RallyCoDriverVoice)

    fun byId(id: String?): VoiceProfile = all.firstOrNull { it.id == id } ?: standard
}

/** The normal navigation voice: Valhalla's / DriveMap's standard sentences. */
object StandardVoice : VoiceProfile {
    override val id = "standard"
    override val name = "Standard"
    override val description = "Calm, complete instructions"
}

/**
 * A rally co-driver: short, punchy calls ("300 metres, right, Štúrova" / "Right, now!"), real
 * distances, street names and exit numbers kept, a quick double click before "now" calls.
 * Never tells you to go faster. Languages without phrases here use the standard sentences.
 */
object RallyCoDriverVoice : VoiceProfile {
    override val id = "rally"
    override val name = "Rally co-driver"
    override val description = "Calls every bend like a rally co-driver: left 4, right 2 tightens, hairpin…"
    override val tts = TtsPrefs(rate = 1.06f, pitch = 1.0f)
    override val paceNotes = true

    private fun l(lang: String) = lang.substringBefore('-').let { if (it in calls) it else null }

    private val calls: Map<String, Map<String, String>> = mapOf(
        "en" to mapOf(
            "left" to "Left", "right" to "Right", "slightLeft" to "Easy left", "slightRight" to "Easy right",
            "sharpLeft" to "Hard left", "sharpRight" to "Hard right", "uturn" to "U-turn",
            "keepLeft" to "Keep left", "keepRight" to "Keep right", "straight" to "Straight on",
            "rampLeft" to "Ramp left", "rampRight" to "Ramp right", "exitLeft" to "Exit left", "exitRight" to "Exit right",
            "merge" to "Merge", "ferry" to "Ferry", "roundabout" to "Roundabout, # exit",
            "arrive" to "Finish! You've arrived", "now" to "now", "exit" to "exit", "toward" to "towards",
            "continue" to "Long straight", "reroute" to "New line, recalculating", "start" to "Let's go.",
            "bLeft" to "left", "bRight" to "right", "hairpinLeft" to "hairpin left", "hairpinRight" to "hairpin right",
            "long" to "long", "tightens" to "tightens", "opens" to "opens", "into" to "into", "caution" to "caution",
        ),
        "sk" to mapOf(
            "left" to "Doľava", "right" to "Doprava", "slightLeft" to "Mierne doľava", "slightRight" to "Mierne doprava",
            "sharpLeft" to "Ostro doľava", "sharpRight" to "Ostro doprava", "uturn" to "Otočka",
            "keepLeft" to "Drž sa vľavo", "keepRight" to "Drž sa vpravo", "straight" to "Rovno",
            "rampLeft" to "Nájazd vľavo", "rampRight" to "Nájazd vpravo", "exitLeft" to "Výjazd vľavo", "exitRight" to "Výjazd vpravo",
            "merge" to "Zaraď sa", "ferry" to "Trajekt", "roundabout" to "Kruháč, # výjazd",
            "arrive" to "Cieľ! Sme na mieste", "now" to "teraz", "exit" to "výjazd", "toward" to "smer",
            "continue" to "Dlhá rovinka", "reroute" to "Nová stopa, prepočítavam", "start" to "Ideme.",
            "bLeft" to "ľavá", "bRight" to "pravá", "hairpinLeft" to "vracák vľavo", "hairpinRight" to "vracák vpravo",
            "long" to "dlhá", "tightens" to "zatvára", "opens" to "otvára", "into" to "do", "caution" to "pozor",
        ),
        "cs" to mapOf(
            "left" to "Doleva", "right" to "Doprava", "slightLeft" to "Mírně doleva", "slightRight" to "Mírně doprava",
            "sharpLeft" to "Ostře doleva", "sharpRight" to "Ostře doprava", "uturn" to "Otočka",
            "keepLeft" to "Drž se vlevo", "keepRight" to "Drž se vpravo", "straight" to "Rovně",
            "rampLeft" to "Nájezd vlevo", "rampRight" to "Nájezd vpravo", "exitLeft" to "Výjezd vlevo", "exitRight" to "Výjezd vpravo",
            "merge" to "Zařaď se", "ferry" to "Trajekt", "roundabout" to "Kruháč, # výjezd",
            "arrive" to "Cíl! Jsme na místě", "now" to "teď", "exit" to "výjezd", "toward" to "směr",
            "continue" to "Dlouhá rovinka", "reroute" to "Nová stopa, přepočítávám", "start" to "Jedeme.",
            "bLeft" to "levá", "bRight" to "pravá", "hairpinLeft" to "vracák vlevo", "hairpinRight" to "vracák vpravo",
            "long" to "dlouhá", "tightens" to "utahuje", "opens" to "otevírá", "into" to "do", "caution" to "pozor",
        ),
    )

    /** The maneuver as a short call, e.g. "Doprava" or "Kruháč, tretí výjazd"; null if none. */
    private fun call(lang: String, a: Announcement): String? {
        val key = Phrases.turnKey(a.type) ?: return null
        val text = calls[lang]?.get(key) ?: return null
        return if (key == "roundabout") text.replace("#", Phrases.ordinal(lang, a.roundaboutExit)) else text
    }

    /** Street / exit / direction details (only at the "Full" voice level). */
    private fun details(lang: String, a: Announcement): String {
        if (!a.full) return a.exitNumber?.let { ", " + calls[lang]!!["exit"] + " " + it }.orEmpty()
        val parts = ArrayList<String>()
        a.exitNumber?.let { parts += calls[lang]!!["exit"] + " " + it }
        a.street?.let { parts += it }
        a.toward?.let { parts += calls[lang]!!["toward"] + " " + it }
        return if (parts.isEmpty()) "" else ", " + parts.joinToString(", ")
    }

    /** One pace note: "dlhá pravá 3 zatvára", "vracák vľavo". */
    private fun note(c: Map<String, String>, b: Bend): String {
        if (b.grade == 0) return c[if (b.left) "hairpinLeft" else "hairpinRight"]!!
        val parts = ArrayList<String>()
        if (b.long) parts += c["long"]!!
        parts += c[if (b.left) "bLeft" else "bRight"]!! + " " + b.grade
        if (b.tightens) parts += c["tightens"]!! else if (b.opens) parts += c["opens"]!!
        return parts.joinToString(" ")
    }

    /** Gap to the next bend as co-drivers say it: rounded to 50 m ("100", "150"). */
    private fun gap(m: Double) = (Math.round(m / 50.0) * 50).coerceAtLeast(50).toString()

    override fun phrase(a: Announcement): String? {
        val lang = l(a.lang) ?: return null // no phrases in this language: standard voice
        val c = calls[lang]!!
        return when (a.kind) {
            Announcement.Kind.BEND -> {
                val call = a.bend ?: return null
                val b = call.bend
                val n2 = call.into
                var s = when {
                    n2 == null -> note(c, b)
                    // the same way twice in a row is one bend to a co-driver
                    n2.left == b.left && b.grade > 0 && n2.grade > 0 -> when {
                        n2.grade == b.grade -> note(c, b.copy(long = true, tightens = false, opens = false))
                        n2.grade < b.grade -> note(c, b.copy(tightens = false, opens = false)) + " " + c["tightens"] + " " + c["into"] + " " + n2.grade
                        else -> note(c, b.copy(tightens = false, opens = false)) + " " + c["opens"] + " " + c["into"] + " " + n2.grade
                    }
                    else -> note(c, b) + " " + c["into"] + " " + note(c, n2)
                }
                // the tightest calls get a "caution" first: understandable and safe at any speed
                val tight = listOfNotNull(call.bend, call.into).any { it.grade <= 1 }
                if (tight) s = c["caution"]!!.replaceFirstChar { it.uppercase() } + ", " + s
                else s = s.replaceFirstChar { it.uppercase() }
                call.gapToNextM?.let { s += ", " + gap(it) }
                s
            }
            Announcement.Kind.APPROACH -> {
                val d = a.distanceM ?: return null
                val turn = call(lang, a) ?: return null
                Phrases.distance(a.lang, d) + ", " + turn.replaceFirstChar { it.lowercase() } + details(lang, a)
            }
            Announcement.Kind.NOW -> {
                val turn = call(lang, a) ?: return null
                if (a.type in 4..6) turn else "$turn, ${c["now"]}!" + details(lang, a)
            }
            Announcement.Kind.CONTINUE -> a.distanceM?.let { c["continue"] + ", " + Phrases.distance(a.lang, it) }
            Announcement.Kind.REROUTE -> c["reroute"]
            Announcement.Kind.START -> a.standardText?.let { c["start"] + " " + it }
        }
    }

    // no clicks: with a call at every bend they'd only clutter the notes
}
