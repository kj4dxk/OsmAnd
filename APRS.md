# APRS-IS preview

The optional APRS-IS plugin adds a first Android integration of the shared APRS
engine. Enable **APRS-IS** in OsmAnd's Plugins screen, then open **APRS-IS** from
the map menu. Enter your callsign/SSID and connect. Passcode `-1` is receive-only.
GPS beaconing is off by default and requires a valid APRS-IS passcode plus server
verification. The map location button enables OsmAnd's normal GPS handling.

This preview connects only while the map is in the foreground. It disconnects
when the map pauses and reconnects when it resumes if the connection was enabled.
It does not yet implement an Android foreground service or background beaconing.

## Behavior

- Default server: `rotate.aprs2.net:14580`.
- Stations within 100 km of the map center at connection time.
- Station markers with callsign labels; tap to inspect coordinates, APRS symbol
  code, comment, and last-heard age. Full APRS symbol artwork is not implemented.
- Beacon interval: two minutes. Fixes must be at most 30 seconds old and accurate
  within 100 meters. The first eligible fix can beacon after verified login.
- Thirty-minute station expiry and a 2,000-station memory bound.
- Reconnect backoff from two to sixty seconds; bounded packet reads and idle timeout.
- APRS uncompressed, timestamped, compressed, object, and item positions.
  Mic-E, position ambiguity, and explicit object/item deletion are not yet supported.
- APRS-IS only; no radio/TNC, RF gating, or messaging.

The TCP protocol is APRS-IS plaintext. Credentials are stored in app-private
preferences and excluded from diagnostic strings. No callsign/passcode is built
into the app, and enabling the plugin alone does not connect or transmit.

## Code and validation

`OsmAnd-shared/src/commonMain/kotlin/net/osmand/shared/aprs` contains protocol,
station storage, beacon policy, and Ktor TCP transport. It has no Android imports.
Android UI and the map overlay live under `OsmAnd/.../plugins/aprs`.
The shared module already has Android, JVM, and iOS targets; iOS integration has
not been built or verified on Windows.

Run the APRS tests with:

```powershell
.\gradlew.bat :OsmAnd-shared:jvmTest --tests "net.osmand.shared.aprs.*"
```

The transport tests use a loopback TCP server. They verify station reception,
stopping, and that no beacon is sent before verified login. They never transmit
to public APRS-IS.

The initial checkpoint passed 14 APRS tests and an Android debug build. On the
S26 Android 16 emulator, a local receive-only server supplied a test station;
the settings count, map marker, callsign label, and station details were checked.
Live GPS beaconing to public APRS-IS and physical-phone testing remain unverified.

Reference behavior: [APRSISMO](https://github.com/APRSFoundation/APRSISMO).
Protocol references: [APRS-IS connections](https://www.aprs-is.net/Connecting.aspx)
and [server filters](https://www.aprs-is.net/javAPRSFilter.aspx).
