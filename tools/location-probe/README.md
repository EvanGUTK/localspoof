# Optional location-delivery observer

Development-only APK, separate from LocalSpoof. Compares framework GPS/network
and Google Fused Location callbacks during the user's chosen journey and manual
airplane-mode transition. It never submits mock locations, changes radios, reads
another app's account, writes files or sends app network requests. Google Play
services and Android behavior remain outside this observer's app boundary.

The screen shows callback counts, sample age, mock marker, speed and fresh paired
distance from GPS. Cached startup readings are labelled separately. Raw
coordinates are held only as the latest three samples in RAM and never displayed
or retained. Stop clears all readings; observation expires after 30 minutes.
The foreground location notification makes observation explicit while checking
other apps. This is consumer-delivery evidence, not Life360 receipt/upload proof.

Build with the existing Android wrapper using `-p ../tools/location-probe
:app:assembleDebug`. Install and operate through OpenMob after observing the
phone. Grant precise location, open the observer, then tap Start. Do not select it
as the mock app: LocalSpoof remains the mock provider. Stop and remove the
observer after qualification. No Chicago or unrelated on-device routes.

Google location dependency is pinned to 21.4.0 and confined to this development
tool. See [official release notes](https://developers.google.com/android/guides/releases)
and [Fused Location reference](https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient).
