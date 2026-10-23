package com.drivemap.app.ui

/**
 * Google-Maps-like vector style over OpenFreeMap (OpenMapTiles schema). Built in code so day and
 * night share one layer list.
 */
object MapStyle {

    class Palette(
        val bg: String, val residential: String, val wood: String, val grass: String, val park: String,
        val hospital: String, val school: String, val water: String, val waterLabel: String,
        val building: String, val buildingLine: String, val aeroway: String,
        val minor: String, val minorCase: String, val secondary: String, val secondaryCase: String,
        val primary: String, val primaryCase: String, val trunk: String, val trunkCase: String,
        val motorway: String, val motorwayCase: String, val rail: String, val path: String,
        val boundary: String, val roadLabel: String, val roadHalo: String,
        val placeLabel: String, val placeHalo: String, val minorPlace: String,
        val poiFood: String, val poiShop: String, val poiPark: String, val poiHealth: String,
        val poiTransit: String, val poiOther: String, val tunnelAlpha: Double,
    )

    val day = Palette(
        bg = "#F5F5F3", residential = "#EFEFEC", wood = "#CFE7C7", grass = "#DCEFD6", park = "#C8E6C1",
        hospital = "#F9E3E3", school = "#F3EEDF", water = "#A8D1F5", waterLabel = "#3F79B5",
        building = "#E9E9E6", buildingLine = "#DCDCD8", aeroway = "#E2E4E8",
        minor = "#FFFFFF", minorCase = "#DCDEE2", secondary = "#FFFFFF", secondaryCase = "#D3D5D9",
        primary = "#FFFFFF", primaryCase = "#C9CCD1", trunk = "#FFE8A3", trunkCase = "#E6BF63",
        motorway = "#FFD966", motorwayCase = "#E0A32E", rail = "#BFC3C8", path = "#D6D8DC",
        boundary = "#9AA0A6", roadLabel = "#5F6368", roadHalo = "#FFFFFF",
        placeLabel = "#202124", placeHalo = "#FFFFFF", minorPlace = "#5F6368",
        poiFood = "#E8710A", poiShop = "#1A73E8", poiPark = "#188038", poiHealth = "#D93025",
        poiTransit = "#1967D2", poiOther = "#5F6368", tunnelAlpha = 0.55,
    )

    val night = Palette(
        bg = "#242F3E", residential = "#26313F", wood = "#263C3F", grass = "#25393C", park = "#263C3F",
        hospital = "#3A2E3A", school = "#2E3440", water = "#17263C", waterLabel = "#515C6D",
        building = "#2B3544", buildingLine = "#323C4B", aeroway = "#2F3948",
        minor = "#38414E", minorCase = "#212A37", secondary = "#3E4856", secondaryCase = "#212A37",
        primary = "#46505F", primaryCase = "#1F2835", trunk = "#5E5444", trunkCase = "#1F2835",
        motorway = "#746855", motorwayCase = "#1F2835", rail = "#3A4452", path = "#323B48",
        boundary = "#6B7380", roadLabel = "#9CA5B3", roadHalo = "#1F2835",
        placeLabel = "#D59563", placeHalo = "#17202B", minorPlace = "#9CA5B3",
        poiFood = "#D59563", poiShop = "#8AB4F8", poiPark = "#6B9A76", poiHealth = "#E8908A",
        poiTransit = "#8AB4F8", poiOther = "#9CA5B3", tunnelAlpha = 0.5,
    )

    private const val NAME = """["coalesce", ["get", "name:latin"], ["get", "name"]]"""

    fun build(p: Palette): String {
        val layers = ArrayList<String>()

        layers += """{"id":"background","type":"background","paint":{"background-color":"${p.bg}"}}"""
        layers += """{"id":"landuse-residential","type":"fill","source":"omt","source-layer":"landuse","minzoom":9,
            "filter":["in",["get","class"],["literal",["residential","suburb","neighbourhood","commercial","retail"]]],
            "paint":{"fill-color":"${p.residential}","fill-opacity":["interpolate",["linear"],["zoom"],9,0.4,13,1]}}"""
        layers += """{"id":"landcover-wood","type":"fill","source":"omt","source-layer":"landcover",
            "filter":["==",["get","class"],"wood"],"paint":{"fill-color":"${p.wood}","fill-opacity":0.9}}"""
        layers += """{"id":"landcover-grass","type":"fill","source":"omt","source-layer":"landcover",
            "filter":["in",["get","class"],["literal",["grass","farmland"]]],"paint":{"fill-color":"${p.grass}","fill-opacity":0.5}}"""
        layers += """{"id":"park","type":"fill","source":"omt","source-layer":"park","paint":{"fill-color":"${p.park}","fill-opacity":0.85}}"""
        layers += """{"id":"landuse-park","type":"fill","source":"omt","source-layer":"landuse",
            "filter":["in",["get","class"],["literal",["park","cemetery","pitch","playground","garden","stadium"]]],
            "paint":{"fill-color":"${p.park}"}}"""
        layers += """{"id":"landuse-hospital","type":"fill","source":"omt","source-layer":"landuse","minzoom":12,
            "filter":["==",["get","class"],"hospital"],"paint":{"fill-color":"${p.hospital}"}}"""
        layers += """{"id":"landuse-school","type":"fill","source":"omt","source-layer":"landuse","minzoom":12,
            "filter":["in",["get","class"],["literal",["school","university","college","kindergarten"]]],"paint":{"fill-color":"${p.school}"}}"""
        layers += """{"id":"waterway","type":"line","source":"omt","source-layer":"waterway",
            "filter":["!=",["get","brunnel"],"tunnel"],
            "paint":{"line-color":"${p.water}","line-width":["interpolate",["exponential",1.4],["zoom"],8,0.5,14,2,18,8]}}"""
        layers += """{"id":"water","type":"fill","source":"omt","source-layer":"water",
            "filter":["!=",["get","brunnel"],"tunnel"],"paint":{"fill-color":"${p.water}"}}"""
        layers += """{"id":"aeroway","type":"fill","source":"omt","source-layer":"aeroway","minzoom":11,
            "filter":["==",["geometry-type"],"Polygon"],"paint":{"fill-color":"${p.aeroway}"}}"""
        layers += """{"id":"aeroway-runway","type":"line","source":"omt","source-layer":"aeroway","minzoom":11,
            "filter":["in",["get","class"],["literal",["runway","taxiway"]]],
            "paint":{"line-color":"${p.aeroway}","line-width":["interpolate",["exponential",1.5],["zoom"],11,2,17,40]}}"""

        // ---- roads: tunnels, then normal, then bridges
        for (brunnel in listOf("tunnel", "road", "bridge")) {
            val f = when (brunnel) {
                "tunnel" -> """["==",["get","brunnel"],"tunnel"]"""
                "bridge" -> """["==",["get","brunnel"],"bridge"]"""
                else -> """["!",["in",["get","brunnel"],["literal",["tunnel","bridge"]]]]"""
            }
            val op = if (brunnel == "tunnel") p.tunnelAlpha else 1.0
            val pairs = listOf(
                roadPair("$brunnel-path", f, """["in",["get","class"],["literal",["path","track"]]]""", p.path, null, 14, 0.5, 1.5, 3.0, op, dash = true),
                roadPair("$brunnel-service", f, """["==",["get","class"],"service"]""", p.minor, p.minorCase, 14, 0.5, 2.0, 9.0, op),
                roadPair("$brunnel-minor", f, """["==",["get","class"],"minor"]""", p.minor, p.minorCase, 12, 0.5, 3.5, 16.0, op),
                roadPair("$brunnel-secondary", f, """["in",["get","class"],["literal",["secondary","tertiary"]]]""", p.secondary, p.secondaryCase, 8, 0.5, 5.0, 22.0, op),
                roadPair("$brunnel-primary", f, """["==",["get","class"],"primary"]""", p.primary, p.primaryCase, 7, 0.7, 6.0, 26.0, op),
                roadPair("$brunnel-trunk", f, """["==",["get","class"],"trunk"]""", p.trunk, p.trunkCase, 5, 0.8, 6.5, 28.0, op),
                roadPair("$brunnel-motorway", f, """["==",["get","class"],"motorway"]""", p.motorway, p.motorwayCase, 4, 1.0, 7.5, 32.0, op),
            )
            // all casings first, then all fills, so junctions don't show grey seams
            pairs.mapNotNull { it.first }.forEach { layers += it }
            pairs.forEach { layers += it.second }
            layers += """{"id":"$brunnel-rail","type":"line","source":"omt","source-layer":"transportation","minzoom":11,
                "filter":["all",$f,["in",["get","class"],["literal",["rail","transit"]]]],
                "paint":{"line-color":"${p.rail}","line-opacity":$op,"line-width":["interpolate",["linear"],["zoom"],11,0.6,18,2.5],"line-dasharray":[4,2]}}"""
        }

        layers += """{"id":"building","type":"fill","source":"omt","source-layer":"building","minzoom":15,
            "paint":{"fill-color":"${p.building}","fill-outline-color":"${p.buildingLine}","fill-opacity":["interpolate",["linear"],["zoom"],15,0.4,16.5,1]}}"""
        layers += """{"id":"boundary-state","type":"line","source":"omt","source-layer":"boundary","minzoom":5,
            "filter":["all",["==",["get","admin_level"],4],["!=",["get","maritime"],1]],
            "paint":{"line-color":"${p.boundary}","line-opacity":0.5,"line-width":0.8,"line-dasharray":[3,2]}}"""
        layers += """{"id":"boundary-country","type":"line","source":"omt","source-layer":"boundary",
            "filter":["all",["==",["get","admin_level"],2],["!=",["get","maritime"],1]],
            "paint":{"line-color":"${p.boundary}","line-width":["interpolate",["linear"],["zoom"],3,0.8,10,2],"line-dasharray":[4,2]}}"""

        // ---- labels
        layers += """{"id":"water-name","type":"symbol","source":"omt","source-layer":"water_name",
            "layout":{"text-field":$NAME,"text-font":["Noto Sans Italic"],"text-size":["interpolate",["linear"],["zoom"],8,11,14,14],"text-max-width":6},
            "paint":{"text-color":"${p.waterLabel}","text-halo-color":"${p.water}","text-halo-width":1}}"""
        layers += """{"id":"waterway-name","type":"symbol","source":"omt","source-layer":"waterway","minzoom":13,
            "filter":["has","name"],
            "layout":{"text-field":$NAME,"text-font":["Noto Sans Italic"],"text-size":12,"symbol-placement":"line","symbol-spacing":350},
            "paint":{"text-color":"${p.waterLabel}","text-halo-color":"${p.bg}","text-halo-width":1.2}}"""
        layers += """{"id":"road-name","type":"symbol","source":"omt","source-layer":"transportation_name","minzoom":13,
            "filter":["!",["in",["get","class"],["literal",["rail","transit","ferry","path","track"]]]],
            "layout":{"text-field":$NAME,"text-font":["Noto Sans Bold"],"symbol-placement":"line","symbol-spacing":320,"text-pitch-alignment":"viewport",
              "text-size":["interpolate",["linear"],["zoom"],13,10.5,16,13,18,16],"text-max-angle":30,"text-padding":4,"text-letter-spacing":0.02},
            "paint":{"text-color":"${p.roadLabel}","text-halo-color":"${p.roadHalo}","text-halo-width":2,"text-halo-blur":0.4}}"""
        layers += """{"id":"road-shield","type":"symbol","source":"omt","source-layer":"transportation_name","minzoom":7,
            "filter":["all",["has","ref"],["<=",["get","ref_length"],6],["in",["get","class"],["literal",["motorway","trunk","primary"]]]],
            "layout":{"icon-image":["concat","road_",["to-string",["get","ref_length"]]],"icon-rotation-alignment":"viewport",
              "symbol-placement":["step",["zoom"],"point",9,"line"],"symbol-spacing":400,"text-field":["get","ref"],
              "text-font":["Noto Sans Bold"],"text-size":10,"text-rotation-alignment":"viewport"},
            "paint":{"text-color":"#3C4043"}}"""
        layers += poiLayer("poi-major", 15, 6, p)
        layers += poiLayer("poi", 17, 99, p)
        layers += """{"id":"housenumber","type":"symbol","source":"omt","source-layer":"housenumber","minzoom":17.5,
            "layout":{"text-field":["get","housenumber"],"text-font":["Noto Sans Regular"],"text-size":10},
            "paint":{"text-color":"${p.minorPlace}","text-halo-color":"${p.placeHalo}","text-halo-width":1}}"""
        layers += placeLayers(p)

        return """{"version":8,"name":"DriveMap",
            "glyphs":"https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
            "sprite":"https://tiles.openfreemap.org/sprites/ofm_f384/ofm",
            "sources":{"omt":{"type":"vector","url":"https://tiles.openfreemap.org/planet",
              "attribution":"© OpenStreetMap contributors · OpenFreeMap · OpenMapTiles"}},
            "layers":[${layers.joinToString(",")}]}"""
    }

    private fun poiLayer(id: String, minzoom: Int, maxRank: Int, p: Palette): String {
        val rankFilter = if (maxRank >= 99) """["has","name"]""" else """["<=",["get","rank"],$maxRank]"""
        val zoomRange = if (maxRank >= 99) "" else ""","maxzoom":16"""
        return """{"id":"$id","type":"symbol","source":"omt","source-layer":"poi","minzoom":$minzoom$zoomRange,
            "filter":["all",["has","name"],$rankFilter],
            "layout":{"icon-image":["get","class"],"icon-size":0.85,"text-field":$NAME,"text-font":["Noto Sans Regular"],
              "text-size":11,"text-anchor":"top","text-offset":[0,0.9],"text-max-width":8,"text-optional":true},
            "paint":{"text-color":["match",["get","class"],
                ["restaurant","fast_food","cafe","bar","beer","ice_cream","bakery"],"${p.poiFood}",
                ["shop","grocery","clothing_store","alcohol_shop","furniture","gift","florist"],"${p.poiShop}",
                ["park","garden","campsite","playground","picnic_site","zoo"],"${p.poiPark}",
                ["hospital","doctors","pharmacy","dentist","veterinary"],"${p.poiHealth}",
                ["bus","railway","railway_metro","airport","ferry","fuel","parking"],"${p.poiTransit}",
                "${p.poiOther}"],
              "text-halo-color":"${p.placeHalo}","text-halo-width":1.2}}"""
    }

    private fun placeLayers(p: Palette): List<String> {
        val layers = ArrayList<String>()
        layers += """{"id":"place-minor","type":"symbol","source":"omt","source-layer":"place","minzoom":12,
            "filter":["in",["get","class"],["literal",["suburb","quarter","neighbourhood","hamlet","isolated_dwelling"]]],
            "layout":{"text-field":$NAME,"text-font":["Noto Sans Regular"],"text-size":["interpolate",["linear"],["zoom"],12,10,16,13],"text-max-width":8},
            "paint":{"text-color":"${p.minorPlace}","text-halo-color":"${p.placeHalo}","text-halo-width":1.5}}"""
        layers += """{"id":"place-village","type":"symbol","source":"omt","source-layer":"place","minzoom":10,
            "filter":["==",["get","class"],"village"],
            "layout":{"text-field":$NAME,"text-font":["Noto Sans Bold"],"text-size":["interpolate",["linear"],["zoom"],10,11,15,15,17,17],"text-max-width":8,"text-pitch-alignment":"viewport","text-letter-spacing":0.02},
            "paint":{"text-color":"${p.placeLabel}","text-halo-color":"${p.placeHalo}","text-halo-width":2,"text-halo-blur":0.5}}"""
        layers += """{"id":"place-town","type":"symbol","source":"omt","source-layer":"place","minzoom":7,
            "filter":["==",["get","class"],"town"],
            "layout":{"text-field":$NAME,"text-font":["Noto Sans Bold"],"text-size":["interpolate",["linear"],["zoom"],7,11,14,17,17,19],"text-max-width":8,"text-pitch-alignment":"viewport","text-letter-spacing":0.02},
            "paint":{"text-color":"${p.placeLabel}","text-halo-color":"${p.placeHalo}","text-halo-width":2.2,"text-halo-blur":0.5}}"""
        layers += """{"id":"place-city","type":"symbol","source":"omt","source-layer":"place","minzoom":4,
            "filter":["==",["get","class"],"city"],
            "layout":{"text-field":$NAME,"text-font":["Noto Sans Bold"],"text-size":["interpolate",["linear"],["zoom"],4,12,12,20,16,23],"text-max-width":8,"text-pitch-alignment":"viewport","text-letter-spacing":0.02},
            "paint":{"text-color":"${p.placeLabel}","text-halo-color":"${p.placeHalo}","text-halo-width":2.4,"text-halo-blur":0.5}}"""
        layers += """{"id":"place-country","type":"symbol","source":"omt","source-layer":"place","maxzoom":9,
            "filter":["==",["get","class"],"country"],
            "layout":{"text-field":$NAME,"text-font":["Noto Sans Bold"],"text-size":["interpolate",["linear"],["zoom"],3,11,8,16],"text-transform":"uppercase","text-letter-spacing":0.08},
            "paint":{"text-color":"${p.minorPlace}","text-halo-color":"${p.placeHalo}","text-halo-width":1.5}}"""
        return layers
    }

    /** Casing + fill line layers for one road class. */
    private fun roadPair(
        id: String, brunnelFilter: String, classFilter: String, fill: String, casing: String?,
        minzoom: Int, wMin: Double, w14: Double, w18: Double, opacity: Double, dash: Boolean = false,
    ): Pair<String?, String> {
        // zoom stops must be strictly ascending: skip the z14 stop for layers that start at z14+
        val mid = if (minzoom < 14) ",14,$w14" else ""
        val midCase = if (minzoom < 14) ",14,${w14 + 1.6}" else ""
        val width = """["interpolate",["exponential",1.5],["zoom"],$minzoom,$wMin$mid,18,$w18]"""
        val caseWidth = """["interpolate",["exponential",1.5],["zoom"],$minzoom,${wMin + 0.6}$midCase,18,${w18 + 3.0}]"""
        val filter = """["all",$brunnelFilter,$classFilter]"""
        val dashPart = if (dash) ""","line-dasharray":[2,1.5]""" else ""
        val fillLayer = """{"id":"$id","type":"line","source":"omt","source-layer":"transportation","minzoom":$minzoom,
            "filter":$filter,"layout":{"line-cap":"round","line-join":"round"},
            "paint":{"line-color":"$fill","line-width":$width,"line-opacity":$opacity$dashPart}}"""
        if (casing == null) return null to fillLayer
        val caseLayer = """{"id":"$id-case","type":"line","source":"omt","source-layer":"transportation","minzoom":${minzoom + 1},
            "filter":$filter,"layout":{"line-cap":"round","line-join":"round"},
            "paint":{"line-color":"$casing","line-width":$caseWidth,"line-opacity":$opacity}}"""
        return caseLayer to fillLayer
    }
}
