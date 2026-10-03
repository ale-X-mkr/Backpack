# Backpack 1.0.2

Private, open-source GPS navigation for Android. Nothing from Google:
no Play Services, no Google servers, no Material library.

## New in 1.0.2
- **Speed circle at the top right:** while navigating it sits inside the
  instruction bar (right end, centred on its background); otherwise at the
  same spot. The road's limit sign sits at the circle's bottom-left, touching
  its ring.
- **Bottom, from the bottom up:** Go / clear, the search bar with the
  magnifier on its right, the distance / arrival / cost bar, messages. While
  navigating the search bar is hidden; while typing it floats just over the
  keyboard (voice typing: use your keyboard's mic key).
- **Button columns moved up** to where the search bar used to be (just under
  the top band when the speed circle or the instruction bar is there).
  4 buttons per side: left = travel mode (+ toll), urgency, POI, find
  alternative route (navigating; "resume" takes that slot, or the next one);
  right = locate, add stop / overview, preference, settings.
- **Route chips A / B / C in one row at the top centre** (under the
  instruction bar while navigating), so they no longer fall off the screen in
  landscape. Landscape uses the same layout with tighter rows.
- **Protected zones:** a tap that misses a button by a little, on the bottom
  area or on the instruction bar no longer taps the map.
- A destination where you already are says "You are already there". "km"
  written in lowercase.

## 1.0.1
- **One settings button:** the gear is gone; the sliders button (right
  column) opens Settings by category, also while navigating (one row lower).
- **Trip toggles on the left**, mirroring the right column: travel mode (toll
  button to its right when driving), urgency, POI categories, then "resume".
  POI order: Off, Sights, Food, Lodging, Parking, Fuel/Charging, Toilets,
  Emergency.
- **Communication area at the bottom:** search bar (magnifier on its right)
  just over the route info and the Go / clear buttons, messages just over the
  search bar (over the route info while navigating). While typing, the bar
  and its suggestions sit just over the keyboard. Turn instructions stay at
  the top. The speed circle sits at the right edge of the screen.
- **Choose Backpack to navigate from other apps:** `geo:` links (contacts,
  calendar, browsers...), `google.navigation:` and Google Maps web links with
  the place in them (`?q=`, `?daddr=`, `/maps/dir/`, `/maps/place/…/@lat,lon`,
  `/maps/search/`). The place becomes the destination and the route is shown;
  check vehicle / options, then press Go. A travel mode sent with the link
  (walk / bike / car) is taken over. While navigating, you are asked first.
  Addresses without coordinates are looked up with Photon. Short
  `maps.app.goo.gl` links are not supported (reading them means asking
  Google). Android 12+: to open Google Maps web links in Backpack, allow them
  once in Backpack's app info > Open by default > Add links.

## 1.0.0 (first release)

**Fewer server timeouts**
- **Speed limits per route:** the roads of the next ~20 km of your route are
  loaded in one request (the next stretch before you reach the end), then
  every GPS fix is looked up on the phone. Without a route: one quick lookup
  around you at most every 15 s, as before. The server no longer follows the
  car fix by fix.
- **Places per map square, kept on the phone:** places are fetched per square
  of about 5 km (one light request for all missing squares) and kept 3 days
  (everyday places) or 2 weeks (sights, with their Wikidata ranking for a
  month). Planning again, moving the map back, or a second trip in the same
  area asks no server. The servers only learn which squares, never your route
  line. Sights are searched up to 15 / 60 / 120 km ahead (walk / bike / car),
  the next stretch while you go. Settings > Places: "Clear saved places".
- **Free slot first:** before a query, each Overpass server's status page says
  whether a slot is free; a busy server is skipped instead of waiting for its
  refusal (remembered 30 s).
- **Second source:** when every Overpass server fails, everyday places come
  from openrouteservice's places service (same key, 500 a day).

**Look**
- One button style everywhere (light grey backdrop, red when active); Go / Stop
  in dark red with a white icon. The toll button shows its state by the icon
  colour, the POI button by a red ring.
- **POI button = dropdown** list (icon + name, current one in red).
- **Messages always in the same band**, between the position arrow and the
  bottom buttons; errors just above the routine message.
- **Routes lettered A / B / C** on the map (where the lines part) and on the
  chips; only the selected letter and chip get the red frame. Tap a letter to
  pick that route.
- **Settings in one popup by category** (Route, Navigation, Speed, Places, Map,
  Servers), from the last button of the right column; the route-options
  button opens it on Route. The bottom bar keeps only navigation: clear, Go,
  speed.
- Search field with a thin dark-grey frame. Speed number exactly centred.
- **App icon: red backpack.**

**Places and stops**
- Every place popup (sights and POI categories): what it is, where it is on
  your route and the extra time, a link to read about it (Wikipedia, else
  Wikidata, else the place's website), **Go here** and **Add stop**.
  Tap a fuel station / restaurant / ... on the map for its popup.
- With 2 stops already: **Visit on the way** (temporary detour that does not
  count, then your route goes on) or **Replace last stop**.
- **Resume previous route** (undo button, left): back to the trip before a
  detour, a replaced stop or "Go here", until you arrive. Stops you already
  visited stay visited. "Go here" now works while navigating too.

**Navigation view**
- Settings > Navigation: 3D tilt Auto / Flat / 30° / 45° / 60° (60° is
  MapLibre's maximum on Android), zoom Closer / Normal / Farther, spoken
  instructions on/off. Settings > Speed: alarm sound on/off (rules unchanged).
- **Overview** button while navigating (add-stop slot): flat, north up, the
  rest of the route framed with the places along it; tap one to add it.
  Navigation and voice go on; back to the drive view by the button, after
  20 s untouched, before a turn, or with the locate button.

**Release**
- Version 1.0.0, release build signed with your keystore from
  local.properties (see app/build.gradle.kts), not debuggable.
- No routing key built in (F-Droid builds): the app asks for your own free
  openrouteservice key in Settings > Servers.
- fastlane/metadata (title, descriptions, icon) ready for F-Droid.

## Build a release
1. local.properties (never committed): `ors.key1=`, `ors.key2=` (optional),
   `release.storeFile=`, `release.storePassword=`, `release.keyAlias=`,
   `release.keyPassword=`.
2. `./gradlew assembleRelease` -> app/build/outputs/apk/release/app-release.apk
3. `adb install -r --user 0 app-release.apk`
   (An app signed with another key must be uninstalled first.)

## Privacy, in short
Routing (openrouteservice) gets the trip's points. Places servers get only the
~5 km squares looked at; speed limits the road ahead of your route (or around
you without a route). Search positions are rounded to ~1 km. Wikidata gets
place ids. No account, no tracking; nothing about your trips is stored except
the places cache (Settings > Places > Clear saved places).

## 0.8.0

- **Weekend = well-known places.** Weekend (urgency button) shows the
  well-known places along your route or in view; Work / Emergency hide
  them. Hold the urgency button for the list. The camera button is gone.
  With alternatives, Weekend picks the route passing clearly more
  well-known places if it costs at most the extra time set in Settings
  (default 20 %, 0 = off). It never overrides the Balanced toll choice or
  a route you picked yourself, and never changes the route after Go.
- **Sights** is a new category on the POI button (same places, any mode).
- **Places search more robust:** your own server (Settings, optional), then
  overpass-api.de, then overpass.private.coffee (the former
  kumi.systems); long timeout; a "busy" or hidden-timeout answer moves to
  the next server; one more round after 3 s; errors offer "tap to retry".
  Speed-limit lookups: one at a time, quiet, late answers ignored.
- **Position arrow 170 %.**
- **Compass removed.** While navigating, the locate button switches
  "your direction up" / "north up" (tap again after moving the map by hand
  to follow again).
- **Top row:** urgency button exactly midway between car and POI.
  **Bottom row:** settings, clear, go and speed in 4 equal slots.
- **After Go:** only your route is drawn, alternative chips disappear, and
  a "find alternative route" button appears: it looks for other routes
  from where you are (router alternatives + the other preference) and
  shows them as chips next to what is left of yours; tap one to switch,
  tap the button again to close.


## 0.7.0
- **Tourist mode** (camera button, left side). Tap = on/off, hold = list.
  Shows well-known places along your route (or in the visible area without
  a route): sights, museums, historic places, viewpoints, nature. Only
  places with a Wikidata entry, ranked by how many Wikipedia/Wikimedia
  sites write about them (★ to ★★★). Tap a dot: kind, how well known,
  distance ahead, extra minutes (estimate, then the exact value from the
  router), Wikipedia link, **Add as stop** (put in the right order, max 2
  stops) or **Go here**. While navigating, only places ahead of you.
  Privacy: the route sent to OpenStreetMap's Overpass server is rounded to
  ~100 m and leaves out its first and last part; Wikidata only receives
  the list of place ids.
- **Search with suggestions while typing** (Photon, open source, OSM data);
  Enter / search button takes the first result. Nominatim (OSM Foundation)
  as a fallback on Enter only. Typed coordinates ("45.19, 5.72") work
  without any server. Location bias sent rounded to ~1 km.
- **Night mode by sunset**: Settings → Display → Sunset switches to the night
  map from sunset to sunrise where you are (computed on the phone, checked
  every minute). System = follow the phone's dark theme.
- **Speed**: smaller current-speed circle (your speed, ring colour as
  before) with the road's speed-limit sign on top of it; the limit sign is
  hidden when the road's limit is unknown. Alarm rules unchanged.
- Bottom buttons on one line; toll button now left of the car (car unmoved).


## 0.4.0 (changes since Step 38)
- **Map:** MapLibre Native (BSD) with OpenFreeMap vector maps (no account,
  no key, no cookies). Real night map with Day / Night / Auto. Style
  choice in Settings (Liberty / Bright / Light grey). The
  "© OpenStreetMap contributors © OpenMapTiles" credit is always visible;
  tap it to see all credits.
- **Position:** GPS chip only. Network / Wi-Fi location removed. GPS fixes
  from other apps are reused for free (passive provider, GPS fixes only).
- **Navigation keeps going with the screen off** (notification with the
  next turn, distance, arrival time and Stop). Swiping the app away stops it.
- **Voice:** announced before the turn ("In 300 meters, turn right onto…"),
  then again at the turn. Two turns close together are said together.
  Music gets quieter during instructions.
- **Turn icons** come from the route data, not guessed from English text.
- **Off route** is measured to the road segment, not the nearest point.
  A reroute skips stops you already visited.
- **Route options button** (sliders icon, right column): avoid motorways,
  ferries, fords and steps; car or motorbike; bike type (City / Road /
  Mountain / E-bike) and hills (Flat → Any); Walk or Hike with green or
  quiet streets; prefer paved roads (picks the alternative with the least
  unpaved road, max +25 % time). Route chips show "% unpaved".
- **Fixed:** Start now navigates the route you selected, not always the first.
- The trip survives rotation and day/night switches.
- Colours match RedGRPS (active dark red #8A0000, night #B03A3A).
- The openrouteservice keys moved from the code to `local.properties`
  (`ors.key1`, `ors.key2`). Settings → Advanced: own routing server, own
  key, own map style. The built-in keys are never sent to a custom server.
- App data is no longer included in Android/Google backups (`allowBackup=false`).

## Unchanged on purpose
Toll cost estimate and the Balanced toll-vs-time choice (Weekend / Work /
Emergency), speed-limit sign and alarm, screen dimming, POI categories,
max 2 stops, alternative-route chips, all icons.

## Build
- Kotlin 2.3.20 (needed by MapLibre 13.x), Java 17, compileSdk 35, targetSdk 34.
- If Android Studio offers to update the Android Gradle Plugin, accept.
- Unit tests for the routing, navigation and places core: `app/src/test` (39 tests).

## Known limits
- Routing still goes to openrouteservice.org (it sees the points of each
  route). A self-hosted server can be set in Settings > Servers.
- Overpass can still be slow at busy hours for squares not seen before; what
  is on the phone shows at once, the rest says "some places may be missing".
- openrouteservice cannot hard-avoid unpaved roads and has no motorbike profile.
- Slight / keep turns use the normal left/right icon (no new icons added).
