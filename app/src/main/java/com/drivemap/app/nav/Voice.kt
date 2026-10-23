package com.drivemap.app.nav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Text-to-speech + alert tones, with audio ducking so music gets quieter while speaking. */
class Voice(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var lang = "en-US"
    private val am = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    var muted = false

    private val focus: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        ).build()

    fun init(language: String) {
        lang = language
        if (tts != null) {
            applyLanguage()
            return
        }
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { am?.abandonAudioFocusRequest(focus) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { am?.abandonAudioFocusRequest(focus) }
                })
                applyLanguage()
            }
        }
    }

    private fun applyLanguage() {
        val loc = Locale.forLanguageTag(lang)
        val r = tts?.setLanguage(loc)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.setLanguage(Locale.US)
        }
    }

    fun setLanguage(language: String) {
        if (language == lang) return
        lang = language
        if (ready) applyLanguage()
    }

    fun speak(text: String, flush: Boolean = false) {
        if (muted || !ready || text.isBlank()) return
        am?.requestAudioFocus(focus)
        val params = Bundle()
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        tts?.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, "u" + System.nanoTime())
    }

    /** Short chime for an upcoming camera; [urgent] = triple beep when speeding. */
    fun chime(urgent: Boolean) {
        if (muted) return
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
            val t = tone ?: return
            if (urgent) {
                t.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                main.postDelayed({ t.startTone(ToneGenerator.TONE_PROP_BEEP, 150) }, 250)
                main.postDelayed({ t.startTone(ToneGenerator.TONE_PROP_BEEP, 150) }, 500)
            } else {
                t.startTone(ToneGenerator.TONE_PROP_ACK, 220)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Radar warning: a bright two-tone chirp (twice), or fast high beeps when [urgent]
     * (speeding close to it). Played as navigation audio, so music ducks and it reaches the car.
     */
    fun radarSound(urgent: Boolean) {
        if (muted) return
        Thread {
            try {
                val rate = 44100
                val notes: List<Pair<Double, Int>> = if (urgent) {
                    List(4) { listOf(1760.0 to 90, 0.0 to 70) }.flatten()
                } else {
                    listOf(988.0 to 110, 1319.0 to 170, 0.0 to 120, 988.0 to 110, 1319.0 to 200)
                }
                val total = notes.sumOf { it.second } * rate / 1000
                val pcm = ShortArray(total)
                var o = 0
                for ((hz, ms) in notes) {
                    val n = ms * rate / 1000
                    val fade = minOf(n / 4, rate / 200) // 5 ms ramps: no clicks
                    for (i in 0 until n) {
                        if (o >= total) break
                        val env = when {
                            hz == 0.0 -> 0.0
                            i < fade -> i.toDouble() / fade
                            i > n - fade -> (n - i).toDouble() / fade
                            else -> 1.0
                        }
                        // a touch of the octave makes it ring like a chime
                        val s = Math.sin(2 * Math.PI * hz * i / rate) * 0.8 + Math.sin(4 * Math.PI * hz * i / rate) * 0.2
                        pcm[o++] = (s * env * 0.85 * Short.MAX_VALUE).toInt().toShort()
                    }
                }
                val track = android.media.AudioTrack.Builder()
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                    )
                    .setAudioFormat(
                        android.media.AudioFormat.Builder().setSampleRate(rate)
                            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build(),
                    )
                    .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(pcm.size * 1000L / rate + 150)
                track.release()
            } catch (_: Exception) {
                chime(urgent)
            }
        }.start()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
        tone?.release()
        tone = null
    }
}

/** Small phrase table for the languages Valhalla narrates in. */
object Phrases {
    private fun base(lang: String) = lang.substringBefore('-')

    fun inDistance(lang: String, meters: Double, instruction: String): String {
        val dist = spokenDistance(lang, meters)
        val instr = instruction.trim().let { if (it.isNotEmpty() && base(lang) != "de") it.replaceFirstChar { c -> c.lowercase() } else it }
        return when (base(lang)) {
            "sk" -> "O $dist $instr"
            "cs" -> "Za $dist $instr"
            "de" -> "In $dist: $instr"
            "hu" -> "$dist múlva $instr"
            "pl" -> "Za $dist $instr"
            else -> "In $dist, $instr"
        }
    }

    private fun spokenDistance(lang: String, m: Double): String {
        if (m < 1000) {
            val r = if (m < 300) ((m / 50).toInt() * 50).coerceAtLeast(50) else (m / 100).toInt() * 100
            return when (base(lang)) {
                "sk" -> "$r metrov"
                "cs" -> "$r metrů"
                "de" -> "$r Metern"
                "hu" -> "$r méter"
                "pl" -> "$r metrów"
                else -> "$r meters"
            }
        }
        val km = Math.round(m / 500.0) / 2.0
        val whole = km == Math.floor(km)
        val num = if (whole) km.toInt().toString() else {
            if (base(lang) == "en") String.format(Locale.US, "%.1f", km) else String.format(Locale.US, "%.1f", km).replace('.', ',')
        }
        val n = km.toInt()
        return when (base(lang)) {
            "sk" -> if (!whole) "$num kilometra" else when (n) { 1 -> "1 kilometer"; in 2..4 -> "$n kilometre"; else -> "$n kilometrov" }
            "cs" -> if (!whole) "$num kilometru" else when (n) { 1 -> "1 kilometr"; in 2..4 -> "$n kilometry"; else -> "$n kilometrů" }
            "de" -> if (whole && n == 1) "einem Kilometer" else "$num Kilometern"
            "hu" -> "$num kilométer"
            "pl" -> if (!whole) "$num kilometra" else when (n) { 1 -> "1 kilometr"; in 2..4 -> "$n kilometry"; else -> "$n kilometrów" }
            else -> if (whole && n == 1) "1 kilometer" else "$num kilometers"
        }
    }

    private val table = mapOf(
        "camera" to mapOf("en" to "Speed camera ahead", "sk" to "Pozor, radar", "cs" to "Pozor, radar", "de" to "Achtung, Blitzer", "hu" to "Traffipax", "pl" to "Uwaga, fotoradar"),
        "section" to mapOf("en" to "Average speed check", "sk" to "Úsekové meranie rýchlosti", "cs" to "Úsekové měření rychlosti", "de" to "Abschnittskontrolle", "hu" to "Szakaszmérés", "pl" to "Odcinkowy pomiar prędkości"),
        "sectionEnd" to mapOf("en" to "End of average speed check", "sk" to "Koniec úsekového merania", "cs" to "Konec úsekového měření", "de" to "Ende der Abschnittskontrolle", "hu" to "Szakaszmérés vége", "pl" to "Koniec odcinkowego pomiaru"),
        "mobile" to mapOf("en" to "Mobile radar reported ahead", "sk" to "Hlásený mobilný radar", "cs" to "Hlášený mobilní radar", "de" to "Mobiler Blitzer gemeldet", "hu" to "Mobil traffipax jelentve", "pl" to "Zgłoszony mobilny radar"),
        "police" to mapOf("en" to "Police reported ahead", "sk" to "Hlásená polícia", "cs" to "Hlášená policie", "de" to "Polizei gemeldet", "hu" to "Rendőrség jelentve", "pl" to "Zgłoszona policja"),
        "hazard" to mapOf("en" to "Hazard reported ahead", "sk" to "Hlásené nebezpečenstvo", "cs" to "Hlášené nebezpečí", "de" to "Gefahr gemeldet", "hu" to "Veszély jelentve", "pl" to "Zgłoszone zagrożenie"),
        "redlight" to mapOf("en" to "Red light camera", "sk" to "Kamera na semafore", "cs" to "Kamera na semaforu", "de" to "Rotlichtblitzer", "hu" to "Piroslámpa-kamera", "pl" to "Kamera na skrzyżowaniu"),
        "reroute" to mapOf("en" to "Recalculating", "sk" to "Prepočítavam trasu", "cs" to "Přepočítávám trasu", "de" to "Route wird neu berechnet", "hu" to "Útvonal újratervezése", "pl" to "Przeliczam trasę"),
        "frequent" to mapOf("en" to "Frequent speed checks ahead", "sk" to "Pozor, často merané miesto", "cs" to "Pozor, často měřené místo", "de" to "Achtung, häufige Geschwindigkeitskontrollen", "hu" to "Figyelem, gyakori sebességmérés", "pl" to "Uwaga, częste kontrole prędkości"),
        "stopAdded" to mapOf("en" to "New convoy stop added", "sk" to "Pridaná nová zastávka konvoja", "cs" to "Přidána nová zastávka konvoje", "de" to "Neuer Konvoi-Stopp", "hu" to "Új konvoj megálló", "pl" to "Nowy przystanek konwoju"),
    )

    fun get(lang: String, key: String): String = table[key]?.let { it[base(lang)] ?: it["en"] } ?: key

    // ---- short turn prompts for the "Normal" voice level (no street names)

    private val turns = mapOf(
        "left" to mapOf("en" to "Turn left", "sk" to "Odbočte doľava", "cs" to "Odbočte vlevo", "de" to "Links abbiegen", "hu" to "Forduljon balra", "pl" to "Skręć w lewo"),
        "right" to mapOf("en" to "Turn right", "sk" to "Odbočte doprava", "cs" to "Odbočte vpravo", "de" to "Rechts abbiegen", "hu" to "Forduljon jobbra", "pl" to "Skręć w prawo"),
        "slightLeft" to mapOf("en" to "Bear left", "sk" to "Mierne doľava", "cs" to "Mírně vlevo", "de" to "Halblinks halten", "hu" to "Tartson enyhén balra", "pl" to "Skręć lekko w lewo"),
        "slightRight" to mapOf("en" to "Bear right", "sk" to "Mierne doprava", "cs" to "Mírně vpravo", "de" to "Halbrechts halten", "hu" to "Tartson enyhén jobbra", "pl" to "Skręć lekko w prawo"),
        "sharpLeft" to mapOf("en" to "Turn sharp left", "sk" to "Odbočte ostro doľava", "cs" to "Odbočte ostře vlevo", "de" to "Scharf links abbiegen", "hu" to "Forduljon élesen balra", "pl" to "Skręć ostro w lewo"),
        "sharpRight" to mapOf("en" to "Turn sharp right", "sk" to "Odbočte ostro doprava", "cs" to "Odbočte ostře vpravo", "de" to "Scharf rechts abbiegen", "hu" to "Forduljon élesen jobbra", "pl" to "Skręć ostro w prawo"),
        "uturn" to mapOf("en" to "Make a U-turn", "sk" to "Otočte sa", "cs" to "Otočte se", "de" to "Bitte wenden", "hu" to "Forduljon vissza", "pl" to "Zawróć"),
        "keepLeft" to mapOf("en" to "Keep left", "sk" to "Držte sa vľavo", "cs" to "Držte se vlevo", "de" to "Links halten", "hu" to "Tartson balra", "pl" to "Trzymaj się lewej"),
        "keepRight" to mapOf("en" to "Keep right", "sk" to "Držte sa vpravo", "cs" to "Držte se vpravo", "de" to "Rechts halten", "hu" to "Tartson jobbra", "pl" to "Trzymaj się prawej"),
        "straight" to mapOf("en" to "Continue straight", "sk" to "Pokračujte rovno", "cs" to "Pokračujte rovně", "de" to "Geradeaus weiterfahren", "hu" to "Haladjon egyenesen", "pl" to "Jedź prosto"),
        "rampLeft" to mapOf("en" to "Take the ramp on the left", "sk" to "Odbočte vľavo na nájazd", "cs" to "Odbočte vlevo na nájezd", "de" to "Links auf die Auffahrt", "hu" to "Hajtson balra a felhajtóra", "pl" to "Skręć w lewo na wjazd"),
        "rampRight" to mapOf("en" to "Take the ramp on the right", "sk" to "Odbočte vpravo na nájazd", "cs" to "Odbočte vpravo na nájezd", "de" to "Rechts auf die Auffahrt", "hu" to "Hajtson jobbra a felhajtóra", "pl" to "Skręć w prawo na wjazd"),
        "exitLeft" to mapOf("en" to "Take the exit on the left", "sk" to "Zíďte vľavo", "cs" to "Sjeďte vlevo", "de" to "Links abfahren", "hu" to "Hajtson ki balra", "pl" to "Zjedź w lewo"),
        "exitRight" to mapOf("en" to "Take the exit on the right", "sk" to "Zíďte vpravo", "cs" to "Sjeďte vpravo", "de" to "Rechts abfahren", "hu" to "Hajtson ki jobbra", "pl" to "Zjedź w prawo"),
        "merge" to mapOf("en" to "Merge", "sk" to "Zaraďte sa", "cs" to "Zařaďte se", "de" to "Einfädeln", "hu" to "Csatlakozzon a forgalomba", "pl" to "Włącz się do ruchu"),
        "ferry" to mapOf("en" to "Board the ferry", "sk" to "Nastúpte na trajekt", "cs" to "Nastupte na trajekt", "de" to "Auf die Fähre fahren", "hu" to "Hajtson fel a kompra", "pl" to "Wjedź na prom"),
        "arrive" to mapOf("en" to "You have arrived", "sk" to "Ste v cieli", "cs" to "Jste v cíli", "de" to "Sie haben Ihr Ziel erreicht", "hu" to "Megérkezett", "pl" to "Jesteś na miejscu"),
        "roundabout" to mapOf(
            "en" to "At the roundabout, take the # exit", "sk" to "Na kruhovom objazde použite # výjazd",
            "cs" to "Na kruhovém objezdu použijte # výjezd", "de" to "Im Kreisverkehr die # Ausfahrt nehmen",
            "hu" to "A körforgalomból a # kijáraton hajtson ki", "pl" to "Na rondzie zjedź # zjazdem",
        ),
    )

    private val ordinals = mapOf(
        "en" to listOf("first", "second", "third", "fourth", "fifth", "sixth"),
        "sk" to listOf("prvý", "druhý", "tretí", "štvrtý", "piaty", "šiesty"),
        "cs" to listOf("první", "druhý", "třetí", "čtvrtý", "pátý", "šestý"),
        "de" to listOf("erste", "zweite", "dritte", "vierte", "fünfte", "sechste"),
        "hu" to listOf("első", "második", "harmadik", "negyedik", "ötödik", "hatodik"),
        "pl" to listOf("pierwszym", "drugim", "trzecim", "czwartym", "piątym", "szóstym"),
    )

    /** Short prompt for a maneuver ("Turn right"), or null when there's nothing worth saying. */
    fun turn(lang: String, type: Int, roundaboutExit: Int): String? {
        val key = when (type) {
            9 -> "slightRight"; 10 -> "right"; 11 -> "sharpRight"; 12, 13 -> "uturn"
            14 -> "sharpLeft"; 15 -> "left"; 16 -> "slightLeft"
            17, 22 -> "straight"; 18 -> "rampRight"; 19 -> "rampLeft"; 20 -> "exitRight"; 21 -> "exitLeft"
            23 -> "keepRight"; 24 -> "keepLeft"; 25, 37, 38 -> "merge"; 26 -> "roundabout"; 28 -> "ferry"
            4, 5, 6 -> "arrive"
            else -> return null
        }
        val l = base(lang).takeIf { it in ordinals } ?: "en"
        val text = turns[key]?.get(l) ?: return null
        if (key != "roundabout") return text
        val ord = ordinals[l]!!.getOrNull(roundaboutExit - 1) ?: "$roundaboutExit."
        return text.replace("#", ord)
    }
}
