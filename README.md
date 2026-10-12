# Backpack 1.6.0

Private GPS navigation for Android, for travellers and backpackers.
OpenStreetMap maps, nothing from Google, no account, no tracking, no ads.

## What it does
- **Navigation** for walking, cycling and driving, with spoken turn-by-turn
  instructions (English, Spanish, French, Italian, German) that keep going
  with the screen off.
- **Screen in 5 languages:** English, Español, Français, Italiano, Deutsch.
- **Maps:** OpenFreeMap / VersaTiles vector maps, day and night styles.
- **Routes:** avoid motorways, ferries, fords, steps; bike type and hills;
  walk or hike; prefer paved roads; tolls weighed against the value of your
  time (Weekend / Work / Emergency); a shorter or no toll route when it costs only a
  few minutes more.
- **Speed:** speed ring with the road's limit and alarms, optional fixed
  speed cameras (sound only; France: danger zones only; silent where
  warnings are illegal).
- **Sights:** well-known places along your route (Wikipedia / Wikidata),
  tips while driving, Wikipedia readings, add a sight as a stop.
- **Services:** fuel and charging, food, lodging, parking, toilets, water,
  pharmacy, emergency… along the route or around you.
- **Trips:** resume a navigation you stopped, record your track, save as GPX.

## Privacy
- Position from the phone's GPS chip only. No Google Play Services.
- Places are fetched per ~5 km map square and kept on the phone; searches
  are rounded to ~1 km. Recorded trips stay on the phone.
- No account, no analytics, no ads.

## Routing key
Routes come from [openrouteservice](https://openrouteservice.org) (HeiGIT,
non-profit). Each person uses their own free key: the app's key guide opens
the sign-up page, picks up the copied key and checks it (2–3 minutes).
Settings ▸ Advanced also accepts your own routing server.

## Building
Android Studio, JDK 17. Keys are never in the source: put them in
`local.properties` (`ors.key1=...`, `ors.key2=...`). The **share** build
variant builds an APK without your keys (for giving to others).

## Licence
Backpack is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License version 3 (or, at your option,
any later version). See [LICENSE](LICENSE).

## Credits
Map data © OpenStreetMap contributors (ODbL). MapLibre, OpenFreeMap,
VersaTiles, openrouteservice, Overpass API, Photon, Wikipedia / Wikidata,
Material icons (Apache 2.0). Full list in the app: Settings ▸ Credits.
