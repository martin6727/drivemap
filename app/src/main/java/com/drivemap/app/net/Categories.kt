package com.drivemap.app.net

/**
 * Typed words that mean "places of this kind near me", like Google Maps: "lekáreň", "autoservis",
 * "benzínka"… Each maps to the English term(s) the map search (Nominatim) understands.
 */
object Categories {
    private val words: Map<String, List<String>> = buildMap {
        fun add(terms: List<String>, vararg keys: String) = keys.forEach { put(it, terms) }
        add(listOf("pharmacy"), "lekaren", "lekarne", "lekarna", "pharmacy", "pharmacies", "apoteka")
        add(listOf("restaurant"), "restauracia", "restauracie", "restaurant", "restaurants", "jedalen", "obed", "jedlo", "food")
        add(listOf("cafe"), "kaviaren", "kaviarne", "kavicka", "kava", "cafe", "coffee", "cukraren")
        add(listOf("fast food"), "fast food", "fastfood", "rychle obcerstvenie", "obcerstvenie", "kebab", "burger", "bufet")
        add(listOf("pub"), "krcma", "krcmy", "hostinec", "pub", "pivo", "bar", "bary")
        add(listOf("hairdresser"), "kadernictvo", "kadernik", "kadernicka", "holic", "holicstvo", "barber", "barbershop", "hairdresser")
        add(listOf("supermarket"), "supermarket", "supermarkety", "hypermarket", "obchod", "nakup")
        add(listOf("supermarket", "convenience store"), "potraviny", "vecierka", "convenience")
        add(listOf("atm"), "bankomat", "bankomaty", "atm")
        add(listOf("bank"), "banka", "banky", "bank")
        add(listOf("post office"), "posta", "posty", "post office", "post")
        add(listOf("hospital"), "nemocnica", "nemocnice", "hospital", "pohotovost")
        add(listOf("doctors"), "lekar", "lekari", "ambulancia", "doctor", "doctors", "obvodny lekar")
        add(listOf("dentist"), "zubar", "zubari", "zubna ambulancia", "dentist")
        add(listOf("vet"), "veterinar", "veterina", "vet", "veterinary")
        add(listOf("hotel"), "hotel", "hotely", "ubytovanie", "penzion", "hostel", "motel")
        add(listOf("parking"), "parkovisko", "parkoviska", "parkovanie", "parking", "garaz")
        add(listOf("car wash"), "autoumyvaren", "umyvaren", "umyvacka", "umyvanie auta", "car wash", "samoobsluzna umyvaren")
        add(listOf("charging station"), "nabijacka", "nabijacky", "nabijacia stanica", "nabijanie", "charging", "charging station", "ev charger", "elektronabijacka")
        add(listOf("police"), "policia", "police")
        add(listOf("bakery"), "pekaren", "pekarne", "bakery")
        add(listOf("butcher"), "masiarstvo", "masiaren", "maso", "butcher")
        add(listOf("florist"), "kvetinarstvo", "kvety", "florist")
        add(listOf("hardware store"), "zeleziarstvo", "hardware")
        add(listOf("electronics shop"), "elektro", "elektronika", "electronics")
        add(listOf("mobile phone shop"), "mobily", "mobilne telefony", "mobile shop")
        add(listOf("gym"), "posilnovna", "fitko", "fitness", "gym")
        add(listOf("sports centre"), "sportove centrum", "sportova hala", "sports centre")
        add(listOf("fuel"), "benzinka", "benzinky", "benzinova pumpa", "pumpa", "cerpacia stanica", "cerpacka", "tankovanie", "nafta", "fuel", "gas station", "petrol station")
        add(listOf("car repair"), "autoservis", "autoservisy", "servis", "mechanik", "autoopravovna", "pneuservis", "vulkanizacia", "pneu", "car repair", "garage", "pneumatiky")
        add(listOf("car parts"), "autodiely", "nahradne diely", "car parts")
        add(listOf("car shop"), "autobazar", "predajna aut", "autosalon", "car dealer")
        add(listOf("pet shop"), "zverimex", "chovatelske potreby", "pet shop")
        add(listOf("optician"), "optika", "ocna optika", "optician")
        add(listOf("clothes shop"), "oblecenie", "odevy", "clothes")
        add(listOf("shoe shop"), "obuv", "topanky", "shoes")
        add(listOf("furniture"), "nabytok", "furniture")
        add(listOf("mall"), "nakupne centrum", "obchodne centrum", "mall", "shopping centre")
        add(listOf("bicycle shop"), "bicykle", "cykloservis", "bike shop")
        add(listOf("taxi"), "taxi", "taxik", "taxisluzba")
        add(listOf("school"), "skola", "skoly", "school")
        add(listOf("kindergarten"), "skolka", "materska skola", "kindergarten")
        add(listOf("cinema"), "kino", "kina", "cinema")
        add(listOf("museum"), "muzeum", "museum")
        add(listOf("church"), "kostol", "kostoly", "church")
        add(listOf("toilets"), "wc", "zachod", "toalety", "toilets")
        add(listOf("ice cream"), "zmrzlina", "zmrzlinaren", "ice cream")
        add(listOf("playground"), "ihrisko", "detske ihrisko", "playground")
    }

    private fun norm(s: String) = java.text.Normalizer.normalize(s.trim().lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("\\s+"), " ")

    /** Map-search terms when the whole query names a kind of place ("lekáreň", "autoservis"), else null. */
    fun match(query: String): List<String>? {
        val q = norm(query)
        if (q.length < 2) return null
        words[q]?.let { return it }
        // "autoservis blízko", "lekáreň pri mne": a kind of place plus filler words
        val p = q.split(" ")
        for (n in 2 downTo 1) {
            if (p.size <= n) continue
            val t = words[p.take(n).joinToString(" ")] ?: continue
            if (p.drop(n).all { it in fillers }) return t
        }
        return null
    }

    private val fillers = setOf("blizko", "nablizku", "near", "me", "nearby", "pri", "mne", "okolo", "v", "okoli", "otvorene", "open")
}
