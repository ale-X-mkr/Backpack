# Backpack 0.8.0

## New in 0.8.0
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


Private, open-source GPS navigation for Android. Nothing from Google:
no Play Services, no Google servers, no Material library.

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
- Unit tests for the routing and navigation core: `app/src/test` (13 tests).

## Known limits
- Routing and address search still go to openrouteservice.org (it sees the
  start and end of each route). A self-hosted server can be set in Settings.
- openrouteservice cannot hard-avoid unpaved roads and has no motorbike profile.
- Slight / keep turns use the normal left/right icon (no new icons added).
