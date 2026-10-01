# Backpack 0.4.0

Private, open-source GPS navigation for Android. Nothing from Google:
no Play Services, no Google servers, no Material library.

## What changed since Step 38
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
