# APRSISOSM

APRSISOSM is our OsmAnd-based Android project with a shared APRS engine.
Enable **APRSISOSM** in the Plugins screen, then open **APRSISOSM** from
the map menu. Enter your callsign/SSID and connect. Passcode `-1` is receive-only.
GPS beaconing is off by default and requires a valid APRS-IS passcode plus server
verification. The map location button enables OsmAnd's normal GPS handling.

Settings have separate callsign and numeric SSID (0–15) inputs. SSID 0 sends the
base callsign without `-0`; SSID 9 produces, for example, `KJ4DXK-9`. The APRS-IS
passcode is masked. **Save without connecting** stores settings and stops any
existing connection; **Connect** saves and opens the selected connection.

The server selector defaults to `rotate.aprs2.net:14580`. Its bundled directory
contains 12 presets checked on 2026-10-20, in practical priority order:
`rotate.aprs2.net`, `noam.aprs2.net`, `euro.aprs2.net`, `asia.aprs2.net`,
`aunz.aprs2.net`, `soam.aprs2.net`, `rotate.aprs.net`, `germany.aprs2.net`,
`japan.aprs2.net`, `nordic.aprs2.net`, `swiss.aprs2.net`, `china.aprs2.net`.
The official directories do not provide an audited popularity ranking; this order
puts the recommended worldwide pool first, followed by major regional pools,
the core fallback, and country pools. Sources are `https://status.aprs2.net/api/full`
and `https://www.aprs-is.net/aprsservers.aspx`. Availability may change.
Existing saved server selections are preserved; a removed preset becomes custom.

Choose **Custom server…** to enter a hostname, `host:port`, `tcp://host:port`, or
`aprs://host:port`. IPv6 literals use brackets, for example `tcp://[::1]:14580`.
An explicit URL port overrides the separate port field. HTTP/HTTPS/WS/WSS,
embedded credentials, URL paths, and query strings are rejected because this
client currently implements APRS-IS over plaintext TCP only.

The Gradle project, Android app label, menu entry, and plugin are named APRSISOSM.
The universal phone preview build outputs `APRSISOSM.apk`; other variants use
`APRSISOSM-<variant>.apk`. Internal upstream module/package names and plugin ID
remain stable to preserve compatibility and installed settings.

## PC services instead of purchase screens

**APRSISOSM services** replaces the Maps+/Pro plan dialogs, purchase history and
purchase actions, paid-plugin acquisition, cloud enrollment, and Live Updates
entry points. It is also available from the map menu when APRSISOSM is enabled.
Upstream billing initialization, inventory validation, automatic sales promotions,
and automatic OsmAnd cloud backups are disabled. Local file backup/export remains
available in Settings. Feature entitlement flags are not forged; unavailable
features lead to the services information instead of payment.

The user's **OSM** chat and the PC's Nginx configuration/phone setup guide confirm
these HTTP endpoints:

- Map and search viewer: `http://pdeffenb.homeip.net:82/raster.html`
- Phone setup: `http://pdeffenb.homeip.net:82/osmand-setup.html`
- Build monitor: `http://pdeffenb.homeip.net:82/monitor.html`
- Raster tile template: `http://pdeffenb.homeip.net:82/raster/light/{z}/{x}/{y}.png`
- OSRM-compatible Valhalla base: `http://pdeffenb.homeip.net:82/osmand/route/v1/`
- Photon search: `http://pdeffenb.homeip.net:82/api/search`

The services screen opens the viewer/setup/monitor and copies the tile/routing
addresses for configuration. It does not silently change existing map sources or
navigation profiles. Search is available in the PC viewer; this change does not
add a native Photon search provider. Worldwide data may still be building.
There is no compatible PC service for OsmAnd OBF/vector downloads, incremental
live updates, cloud backup, or weather. Their proprietary service URLs are not
redirected to unrelated raster/routing APIs. Existing upstream backend source is
retained for reference, not replaced with a nonfunctional imitation.

This source-only change was checked with XML parsing, source/diff review, and HTTP
200 responses from the PC's viewer, setup, and monitor pages. No APK was rebuilt
and no new Android runtime verification was performed.

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
