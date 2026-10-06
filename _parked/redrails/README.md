# RedRails (parked)

The public-transport wake-up alarm, taken out of Backpack in 1.2.0 to keep
the app simple. Kept here, outside the build, for a future own app.

- `TransitAlarmService.kt` -- foreground service: GPS by distance to the stop,
  alarm-volume ring + vibration, backup ring via AlarmManager.setAlarmClock,
  TransitSession (save/restore in prefs "transit").
- `TransitAlarm.kt` -- pure logic: Lead (minutes / km), shouldRing,
  trackDistance (straight line x 1.25), nextPaceMs, Speed smoother.
- `TransitAlarmTest.kt` -- its JVM tests.
- `ic_mode_transit.xml` -- mode icon.
- `reference/` -- Backpack 1.1.1 MainActivity (search "Transit"),
  AndroidManifest (VIBRATE, exact-alarm and mediaPlayback permissions, the
  service entry) and LocationHub (LEASE_TRANSIT, setTransitPace) as they were
  when it worked. Git tag `v1.1.1` has the whole working version.
