# Backpack -- Step 38: POI mixing bug fixed properly, toll colors, wider alt route

## Available POI categories (restating, as asked)
Fuel/Charging, Food, Lodging, Parking, Toilets, Emergency
(hospital/police/fire station).

## The "categories mixing" bug: real cause, fixed properly
A side effect of last round's fix: I'd removed the strict category
check so a slow-but-successful response could still render (fixing
"always shows nothing"), but that meant a STALE response for a
category you'd already switched away from could arrive late and
overwrite what the newer selection had just shown -- exactly the
"mixed when changing" symptom, and also explains phantom "not found"
messages when the real, current category actually had results.

Fixed with a proper request-generation counter: every query gets a
sequence number, and only the single most recent one is ever allowed
to render, full stop. An older, slower response is now silently
discarded the moment a newer one has been requested, regardless of
whether that older one happened to succeed.

## Toll icon: colored now, backdrop not icon (learned from POI)
Reddish backdrop when tolls are allowed (the "on"/active state),
blueish-gray when avoided (deselected) -- matching the same convention
as every other toggle. Applied to the backdrop rather than tinting the
icon itself, since that's exactly what caused the POI icon bug a few
rounds back (tinting flattens internal detail).

## Alternative route line widened
9f, up from 6f -- should be much easier to actually see on the map now.

## On the P2P location-sharing module -- separate message follows
This is a genuinely large new feature with a real technical constraint
worth discussing before I write any code -- see next message.
