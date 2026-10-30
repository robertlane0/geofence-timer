# GeoFence Offline

A native Android app for downloading small OpenStreetMap areas, drawing geofences on them, and
running private inside/outside location checks on a weekly schedule. Everything after a map download
works without a network connection.

The visual design follows [`ai-mockup.png`](ai-mockup.png).

## What it does

- **Offline maps** — Browse an OpenStreetMap picker, drag out a small area, preview the data, and
  download it from `https://overpass.private.coffee/api/interpreter`. Saved regions render from
  local JSON with no online tiles and remain available after restart.
- **Geofences** — Draw circular geofences (centre + radius) or polygon geofences (3+ vertices).
  Edit, rename, enable/disable, and delete them. All geometry is stored as WGS84 coordinates.
- **Schedules** — Run checks at specific weekly times, or repeatedly inside a time window. For
  example, every 10 minutes from 4:00 PM through 5:00 PM on weekdays.
- **Private history** — One row per geofence per check: scheduled time, execution time, geofence and
  schedule names/ids, and `INSIDE`, `OUTSIDE`, or `UNKNOWN` with a failure reason.
- **Settings** — Accuracy threshold, location timeout, late-check grace period, notification
  preference, permission status, and a full local-data delete action.

## Online vs offline

| Action | Network required |
|---|---|
| Load a preview while choosing a download area | Yes (unless a saved map is used as context) |
| Download a map area | Yes |
| Pan, zoom, and render a saved map | No |
| Create, edit, enable, disable, or delete geofences | No |
| Create and edit schedules | No |
| Scheduled or manual geofence checks | No |
| View and delete check history | No |
| Delete maps or all local data | No |

There is no account, backend, cloud sync, analytics, advertising SDK, or tile server.

## Architecture

```
ui/          Activities, fragments, permission and location helpers
map/         Overpass transport, size limits, OSM JSON parser, Canvas renderer
geo/         WGS84 geometry and point-in-circle / point-in-polygon calculations
data/        SQLite schema, repositories, models, encodings, settings
scheduling/  Pure weekly schedule expansion
checks/      AlarmManager integration, foreground service, location fix, evaluation, notifications
```

- **UI:** Kotlin, XML/Material views, one `MainActivity` with fragments and a navigation drawer.
- **Persistence:** Android `SQLiteOpenHelper`; no Room dependency. Downloaded datasets live in
  `files/offline_maps/<uuid>.json`. The raw Overpass JSON is retained so the renderer can be
  improved or reparsed later.
- **Rendering:** A custom `View` parses the stored Overpass JSON and draws roads, paths, buildings,
  water, land use, railways, labels, and geofences on a Canvas in Web Mercator projection. Pinch,
  pan, double-tap, fling, zoom buttons, and a scale bar are supported. There are no bitmap tiles to
  download and no online map dependency.
- **Background work:** `AlarmManager` one-shot exact alarms (`setExactAndAllowWhileIdle`, with an
  inexact `setAndAllowWhileIdle` fallback when exact-alarm access is denied) start a short
  `foregroundServiceType="location"` service. Only the next occurrence is registered per schedule.
- **Location:** `LocationManager` GPS/network/fused providers are registered only for the duration
  of one check, then removed in all paths. No continuous tracking, geofence APIs, or background
  location polling.

### Rendering trade-offs

Overpass returns vector data, not raster tiles. Storing the JSON and rendering it directly keeps the
app dependency-free and fully offline, but it is not a full cartographic engine: relations and
complex multipolygons are not assembled, and very large extracts would be slow to parse and draw.
The app therefore targets small, street-level downloads with a hard cap of **25 km²** and **8 km per
side**, plus a **30 MB** transport cap. Buildings are intentionally excluded from the picker preview
but included in actual downloads.

## Download behavior

1. The picker queries only the current selection plus a 350 m context margin.
2. The estimated size scales from the preview size when available, otherwise from a conservative
   dense-urban heuristic. It is deliberately approximate.
3. `HttpOverpassTransport` POSTs a URL-encoded Overpass query with a descriptive User-Agent,
   handles `429` using `Retry-After`, retries transient failures, caps responses at 30 MB, and writes
   to a temporary file.
4. The response is parsed and validated. Only then is the file atomically moved into place and its
   metadata inserted. Failed or cancelled downloads delete the temporary file.
5. Startup cleanup removes orphaned `.download-*.tmp` files.
6. To respect the public Overpass service, requests are scoped to the user's selection, have a
   server-side timeout, and are never prefetched.

If the endpoint is unavailable, the UI reports a retryable error; already downloaded maps remain
usable. The configured endpoint is the one required by the project specification.

## Schedule semantics

- Weekdays use the device's current time zone and are evaluated per local calendar day.
- **Specific times:** each distinct time produces one check per selected weekday.
- **Repeating windows:** occurrences begin at the start time and advance by the interval while the
  generated time is **less than or equal to the end time**. Both boundaries are inclusive.
  - `16:00–17:00, every 10 min` → `16:00, 16:10, 16:20, 16:30, 16:40, 16:50, 17:00`.
  - If the end does not align, the last aligned time before the end is used.
- Windows must end after they start; windows spanning midnight are not supported (documented
  limitation).
- A schedule must have a name, at least one weekday, valid times, and at least one geofence.

## Check execution policy

- The next future occurrence is registered before the current check starts, so a crash cannot
  silently break the weekly schedule.
- After reboot, package replacement, a clock change, or a time-zone change, alarms are rebuilt for
  the next future occurrence. Missed occurrences are **not** back-filled, avoiding bursts of stale
  checks.
- A check that starts more than the configured grace period (default 15 minutes) after its scheduled
  time is recorded as `UNKNOWN` with `CHECK_DELAYED`; no location is acquired for a stale occurrence.
- Each check acquires **one fresh fix** and requires reported accuracy at or below the configured
  threshold (default 50 m). If no sufficiently accurate fix arrives within the timeout (default
  90 s), the result is `UNKNOWN`. The last-known location is never substituted.
- Overlapping checks are serialized by a mutex in the service.
- Database uniqueness on `(schedule, scheduled_at, geofence)` prevents duplicate results. A
  re-delivered alarm also checks for an existing occurrence before acquiring another location.
- Manual checks are available for enabled schedules and are stored like scheduled checks.

### Permission and location outcomes

| Condition | Recorded status |
|---|---|
| Fresh fix within accuracy threshold | `INSIDE` or `OUTSIDE` per geofence |
| Permission revoked or missing | `UNKNOWN` / `PERMISSION_DENIED` |
| System location disabled | `UNKNOWN` / `LOCATION_DISABLED` |
| No fix within timeout | `UNKNOWN` / `NO_FIX` |
| Fix exists but is too inaccurate | `UNKNOWN` / `INACCURATE_FIX` |
| Check runs beyond grace | `UNKNOWN` / `CHECK_DELAYED` |
| No enabled geofences remain | `UNKNOWN` / `NO_GEOFENCES` |
| Unexpected failure | `UNKNOWN` / `INTERNAL_ERROR` |

## Privacy

- Device coordinates exist only in memory while a single membership calculation runs.
- The `check_results` table has no latitude, longitude, accuracy, provider, route, or movement
  columns. Tests assert this.
- Logs contain only class names/error types and never coordinates, request URLs, or response bodies.
- Notifications contain counts and failure reasons, not place names, coordinates, or map data.
- Maps, geofences, schedules, and history are stored in app-private storage. `android:allowBackup`
  is disabled.
- OSM requests necessarily reveal the selected download area to the Overpass service. Scheduled
  checks do not contact the network.
- Permissions are limited to network access for downloads, precise/background location for scheduled
  checks, notifications, foreground service, wake lock, boot recovery, and exact alarms. Battery
  optimization exemption is optional but recommended.

## Build and test

Requirements: JDK 17, Android SDK platform 36, and an emulator/device for instrumentation tests.
The Gradle wrapper is included.

```bash
./gradlew :app:assembleDebug

# JVM tests: geometry, schedule expansion, parsing, transport, evaluation, codecs
./gradlew :app:testDebugUnitTest

# Device/emulator tests: SQLite persistence, duplicate guard, offline map install/delete
./gradlew :app:connectedDebugAndroidTest
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Known limitations

- No relation/multipolygon assembly; ways are rendered as returned by Overpass.
- Map data is parsed from raw JSON on open. Very large areas are intentionally rejected.
- Geofence evaluations use local planar approximations appropriate for small areas; they are not
  geodesic polygon operations near the poles or across the antimeridian.
- Windows do not cross midnight.
- Exact delivery depends on Android battery optimization, Doze, and exact-alarm access. The app
  surfaces missing settings and falls back to inexact delivery, but cannot guarantee second-level
  execution on every OEM build.
- If the Overpass endpoint is slow or unavailable, previews and new downloads fail; offline
  features remain available.
- Map data © OpenStreetMap contributors, available under the ODbL.
