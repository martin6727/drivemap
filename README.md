# DriveMap

A free navigation app for Android and Android Auto, built for driving in Slovakia and the rest of Europe. It does turn-by-turn navigation with speed camera alerts, live fuel prices, vignette-aware routing and a private convoy mode for driving together with friends.

![Navigation with a convoy member](docs/screenshots/navigation-convoy.png)

## Download and install

1. On your phone, open the [latest release](https://github.com/martin6727/drivemap/releases/latest) and download **DriveMap.apk**.
2. Open the downloaded file. If Android asks, allow your browser to **install unknown apps**, then tap **Install**.
3. Open DriveMap and allow location access. That's it.

Requires Android 8.0 or newer. Updates install the same way, over the old version, and keep your settings and saved places.

### Android Auto

Android Auto only shows apps from the Play Store unless you allow other sources. Turn that on once:

1. Open **Android Auto settings** on your phone (Settings → Connected devices → Android Auto, or search "Android Auto" in Settings).
2. Scroll to the bottom and tap **Version** about 10 times, until it asks to allow development settings. Tap **OK**.
3. Tap the **⋮** menu (top right) → **Developer settings** → turn on **Unknown sources**.
4. Reconnect your phone to the car. DriveMap appears in the Android Auto app list. If you don't see it, use **Customize launcher** in Android Auto settings.

On the car screen you get the map, navigation with lanes, speed camera alerts, search ("Where to?", fuel, parking), hazard and police reports, and the convoy. Convoy members show their speed, you can send them a challenge, and incoming invites pop up with Accept and Decline.

## Features

- **Navigation**: Google-Maps-style turn-by-turn with lane guidance, roundabout exits, voice in Slovak, Czech, English, German, Hungarian and Polish, and three voice levels (full, normal, alerts only).
- **Route modes**: fastest, economical, sporty (twisty roads) and cheapest. Fuel cost uses each country's real pump prices, plus tolls and vignettes.
- **Vignettes**: add the ones you've bought. Routes avoid motorways where you don't have one, and you get a reminder before one expires.
- **Speed cameras**: fixed cameras, average-speed sections, red-light cameras, frequently-checked spots and live police or radar reports from other drivers. Sound and voice alerts, plus a fine estimate when you're over the limit.
- **Speedometer**: Waze-style. The ring fills toward the limit, and the limit sign pops up only when you're speeding.
- **Fuel prices**: daily official station prices in Spain, Austria and France, EU weekly averages everywhere else, and driver-reported prices in Slovakia.
- **Search**: addresses, places and businesses by name, nearest first. You can also long-press the map to add a stop or save a pin with your own name.
- **Convoy**: private groups with a shared code, end-to-end encrypted. You see your friends' cars live on the map with their speed and can share stops and the destination.
  - **Drive with me** sends your destination to the whole convoy.
  - **Challenge** sends a member your exact route. Whoever invites is the host, and everyone who accepts navigates the host's route. Accepting starts navigation straight away.
  - Challenge routes are coloured by their bends: green for straight, orange for medium and red for sharp bends and turns.
- **Your car**: tap your arrow to pick a 3D car. The choices are a navigation arrow, a white Daewoo Nexia, a sedan, an SUV or a sports car. Your convoy sees it too.
- **Looks**: OLED black night mode, 2D/3D view, landscape layout, and four themes: standard, liquid glass, light glass and clear glass.

| Route options | Challenge route | Pick your car |
|---|---|---|
| ![](docs/screenshots/route-options.png) | ![](docs/screenshots/challenge-route.png) | ![](docs/screenshots/car-picker.png) |

## Good to know

- **Drive safely and follow the law.** Set your route before you drive and keep your eyes on the road. Challenges are about driving the same route together, not racing.
- **Speed camera warnings are illegal in some countries**, for example France, Germany and Switzerland. DriveMap hides them where it knows they're not allowed, but checking local rules is up to you.
- DriveMap uses free public services. If one is busy, a search or route can take a moment or fail. Try again.
- This is a hobby project, provided as is, without any warranty.

## Privacy

There's no account, no ads and no tracking. Your location stays on your phone, apart from these exceptions:

- **Convoy:** when you join a convoy, your position is shared with that group only. It's encrypted with a key derived from the group code, so the relay server can't read it.
- **Driver reports:** reports you send (police, radar, hazards and fuel prices) are shared anonymously with other DriveMap users near that spot.
- **Searches and route requests:** these go to the map services listed below.

## Credits

- Map data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors (ODbL), which also covers speed cameras and places
- Map tiles: [OpenFreeMap](https://openfreemap.org) · rendering: [MapLibre](https://maplibre.org)
- Routing: [Valhalla](https://github.com/valhalla/valhalla) on the FOSSGIS servers
- Search: [Photon](https://photon.komoot.io) by komoot, [Nominatim](https://nominatim.org) and the [Overpass API](https://overpass-api.de)
- Fuel prices: EU Weekly Oil Bulletin, Spanish Ministry for the Ecological Transition, Austrian E-Control, and the French government open data on fuel prices
- Convoy relay: the public [HiveMQ](https://www.hivemq.com/mqtt/public-mqtt-broker/) MQTT broker

## Building from source

You need Android Studio (or its bundled JDK). Then run:

```
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`.

---

## Slovensky: inštalácia

1. V telefóne otvor [najnovšie vydanie](https://github.com/martin6727/drivemap/releases/latest) a stiahni **DriveMap.apk**.
2. Otvor stiahnutý súbor. Ak sa Android opýta, povoľ prehliadaču **inštalovať neznáme aplikácie** a ťukni na **Inštalovať**.
3. Otvor DriveMap a povoľ prístup k polohe.

**Android Auto:**

1. V telefóne otvor nastavenia Android Auto a dole 10× ťukni na **Verzia**. Potvrď vývojárske nastavenia.
2. V menu **⋮** otvor **Nastavenia pre vývojárov** a zapni **Neznáme zdroje**.
3. Znova pripoj telefón k autu. DriveMap sa objaví medzi aplikáciami.
