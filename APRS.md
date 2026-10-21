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
and automatic OsmAnd cloud backups are disabled. Cloud and local backup entries
are removed from Settings and the map drawer. Feature entitlement flags are not forged; unavailable
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
addresses. The PC defaults described below configure map and navigation profiles.
The map search button and map drawer search use a native Photon destination dialog;
choosing a result sets the destination and opens route planning. Other specialized
searches (such as POI categories within route planning) remain upstream interfaces.
Worldwide data may still be building.
There is no compatible PC service for OsmAnd OBF/vector downloads, incremental
live updates, cloud backup, or weather. Their proprietary service URLs are not
redirected to unrelated raster/routing APIs. Existing upstream backend source is
retained for reference, not replaced with a nonfunctional imitation.

XML/resource and source checks passed. A universal Android phone APK has now been
built and installed on a freshly reset S26 Android 16 emulator for runtime review.

## Initial setup and defaults

The initial region-download wizard is replaced with **Welcome to APRSISOSM**.
It describes the PC services and opens the configured map; it does not locate a
region by IP, request the OsmAnd download catalog, or start region downloads.
Map/route defaults are installed on the first map activity launch, including an
upgrade from the earlier preview. A versioned marker prevents overwriting later
user changes. Failed tile-source installation leaves the marker unset for retry.

- APRSISOSM plugin: on; connection and GPS beaconing still require explicit setup.
- Online Maps plugin: on.
- All other plugins, including Weather, Topography (SRTM), Wikipedia, Trip Recording,
  street imagery, and development tools: off.
- Map tiles: `http://pdeffenb.homeip.net:82/raster/light/{z}/{x}/{y}.png`, zoom 0–18.
  Zoom 18 is a viewing limit, not a claim of native archive detail at that zoom.
- **pdeffenb PC maps** is the main map source in each existing profile; overlay
  and underlay are off. No main-raster restriction was found in the checked-out
  `MapActions.onNavigationClick` or OSRM route-start path. Runtime validation remains
  required before treating navigation on this configuration as confirmed.
- Driving, Walking, Cycling: separate OSRM-compatible engines with the base URL
  `http://pdeffenb.homeip.net:82/osmand/route/v1/` and respective `car`, `foot`,
  `bike` modes. No API key, GPX approximation, or external routing fallback.
- Other routing profiles retain their original routing engines.
- Driving is the startup/default profile; Driving, Walking, and Cycling remain available.
- Analytics collection and upload, identifier sharing, and local search/navigation/
  marker history: off. Existing saved history is not deleted.
- Startup messages, suggested downloads, analytics requests, rating prompts,
  release notes, automatic backup and Android system app backup: disabled.
- Speed-camera data is marked uninstalled; map/voice warnings and camera settings
  are disabled/removed. This cannot remove a symbol baked into a PC-rendered tile.
- Built-in TTS in the phone's supported language is selected and unmuted for the
  three navigation profiles, using bundled voice assets. Android's TTS engine and
  precise location permissions remain phone-managed.

The launcher/application labels and splash-screen app title use **APRSISOSM**.
The upstream Android package IDs remain stable so existing installs can update.
The phone APK is named `APRSISOSM.apk`. Fresh-install runtime review confirmed the
PC main source, off overlay/underlay, three OSRM engines, Driving startup profile,
required plugin states, privacy settings, and removed backup menus. Plugin setup
avoids reinitializing plugins already in their requested state.

### Checklist decisions

The supplied stock-OsmAnd setup checklist was reconciled with the fork as follows:

| Checklist item | APRSISOSM decision |
| --- | --- |
| Install official OsmAnd | Install our APRSISOSM APK; retain upstream attribution/package compatibility. |
| Only Online Maps enabled | Enable APRSISOSM as well; disable all other optional plugins by default. |
| Vector base with opaque overlay | Keep the explicitly requested PC main map and no overlay/underlay. Verify navigation on the next build. |
| SQLiteDB cache | Keep the built-in file-per-tile cache used by the source installer, with no expiry. Both support cached tiles; no storage-format migration is needed. |
| Zoom 18 | Set maximum source zoom 18; the PC renderer may overzoom lower native detail. |
| OSRM car/foot/bike, blank API key | Installed and selected automatically; no GPX approximation or public-server fallback. |
| Search not redirected | Main map/drawer search is now connected to Photon; specialized offline search screens are separate. |
| Optional local history | Default off for privacy, independently of analytics. |
| Export configuration backup | Omitted because the later explicit request removes backup options entirely. Configuration lives in source/GitHub. |
| Permissions/notifications | Retain precise location prompts and essential navigation notifications; camera/microphone/contacts are not requested by our integration. |
| Prefetch tiles | Leave manual to avoid automatic large downloads; cached tiles do not provide offline routing. |
| Test Wi-Fi/cellular | Still required after rebuilding and after the PC graph/search services are ready. |

References: [Online Maps](https://osmand.net/docs/user/plugins/online-map/),
[raster maps and caching](https://osmand.net/docs/user/map/raster-maps/).

Source verification passed XML/resource-reference and whitespace checks. Public
`/osmand-setup.html` returned HTTP 200; `/api/search` and a sample `/osmand/route/v1/car/`
request returned HTTP 503 while the PC monitor reported routing graph creation and
search waiting. The S26 runtime review confirmed fresh launch, PC raster display,
APRS markers over the main raster, and GPS delivery. End-to-end Photon search,
all three routing modes, voice during navigation, and physical-phone Wi-Fi/cellular
tests remain pending service readiness and phone testing.

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
The current checkpoint passed 17 APRS tests and 2,407 core tests (37 skipped), with
zero failures/errors, plus a universal Android debug build. A user-authorized live
test injected a fresh ZIP-code representative GPS fix on the emulator. The app
reported verified login; its position beacon entered a Tier 2 server and was seen
on a separate core-server receive-only connection. The app was disconnected after
the test and its temporary credentials cleared. The final branding-only rebuild
was installed and checked again. Physical-phone testing remains unverified.

Reference behavior: [APRSISMO](https://github.com/APRSFoundation/APRSISMO).
Protocol references: [APRS-IS connections](https://www.aprs-is.net/Connecting.aspx)
and [server filters](https://www.aprs-is.net/javAPRSFilter.aspx).
