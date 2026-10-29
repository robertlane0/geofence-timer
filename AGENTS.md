# Build an Offline-First Android Geofencing App Using OpenStreetMap

## 1. Objective

Create a native Android application that lets users download a small, user-selected portion of OpenStreetMap (OSM), view that map entirely offline, create geofences on the downloaded map, and automatically determine whether the device is inside or outside each configured geofence according to a user-defined schedule.

The app must prioritize offline functionality, location privacy, reliability, and minimal external dependencies.

## 2. Core Requirements

### A. Select, Download, and View Offline Map Areas

Implement a map interface that allows users to:

- Browse a map and select a geographic area to download for offline use.
- Select very small areas, including individual streets, blocks, or other small geographic regions.
- Clearly preview the selected download area and its approximate size before downloading.
- Download the required OpenStreetMap street and geographic data for the selected area.
- Display download progress and report errors, such as network failures or unsuccessful OSM queries.
- Save downloaded map data locally so the selected region remains available after the app closes or the device restarts.
- View and interact with previously downloaded maps without an internet connection.
- Manage downloaded regions, including viewing their sizes and deleting them when no longer needed.

Use the following Overpass API endpoint for OSM data requests:

`https://overpass.private.coffee/api/interpreter`

Use appropriate Overpass queries to retrieve the OSM features needed to render the selected region, including relevant roads, paths, and geographic features. Handle geographic boundaries and any required feature dependencies correctly.

**Important:** The downloaded OSM data must be sufficient for local map rendering. Do not make the offline map dependent on fetching online map tiles or other remote resources after download. If a library or rendering format requires additional data, download and store that data as part of the offline map workflow.

Use a local map representation that supports smooth navigation and precise geofence creation at high zoom levels. Avoid unnecessary minimum selection sizes, and design the download process to handle small selections efficiently.

### B. Create and Manage Geofences

Users must be able to create geofences directly on a downloaded offline map.

Support the following geofence operations:

- Create a circular geofence by selecting a center point and setting its radius.
- Create a polygon geofence by placing vertices on the map and closing the polygon.
- Edit, rename, enable, disable, and delete existing geofences.
- Visually display geofence boundaries over the map.
- Clearly distinguish enabled geofences from disabled geofences.
- Select which geofence or geofences are associated with a scheduled location check.

Allow users to create and edit geofences without an internet connection, provided the relevant map data has already been downloaded.

Geofence geometry must be stored locally in a persistent format suitable for accurate point-in-circle and point-in-polygon calculations. Avoid relying on the rendered map pixels for geofence evaluation; use geographic coordinates and appropriate geographic calculations.

### C. Configure Scheduled GPS Location Checks

Implement a scheduling interface that allows users to configure when the app determines whether the device is inside or outside a geofence.

Support both of the following scheduling modes:

**1. Specific scheduled times**

- Select one or more times of day.
- Select the days of the week on which each schedule should run.
- Support schedules that repeat weekly.
- Allow schedules to be enabled, disabled, edited, and deleted.

**2. Repeated checks within a time window**

- Configure a start time and an end time.
- Set a repeat interval, such as every 10 minutes.
- Select the days of the week on which the checks should occur.

For example, a user must be able to configure location checks every 10 minutes between 4:00 PM and 5:00 PM on selected days.

The implementation must define the exact behavior at the start and end of a window. For the example above, use the scheduled times 4:00, 4:10, 4:20, 4:30, 4:40, 4:50, and 5:00 PM, unless the user configures a different end-boundary policy.

Support multiple independent schedules and geofences without accidentally creating duplicate checks.

### D. Determine Inside/Outside Status

At each scheduled check:

1. Obtain a location fix using the Android location APIs and available GPS/location providers.
2. Determine whether the fix is sufficiently accurate to make a reliable geofence decision.
3. Evaluate the location against the selected geofence or geofences.
4. Record the result as a binary inside/outside measurement.
5. Release location-related resources when the check is complete.

The app must evaluate the device's location at the scheduled time, rather than continuously tracking it between checks.

Use Android's appropriate scheduling and location mechanisms. Account for modern Android background-execution restrictions, Doze mode, battery optimization, reboot recovery, and location permission requirements.

Use `AlarmManager`, `WorkManager`, or other Android platform facilities where appropriate. Choose the approach based on the required timing guarantees and Android restrictions, rather than assuming that background tasks will run at an exact second.

Provide clear information to users about any permissions or system settings needed for scheduled checks to work reliably.

### E. Handle Unavailable or Unreliable Location Fixes

A missing or inaccurate location fix must never be incorrectly recorded as an inside or outside result.

Define explicit behavior for cases such as:

- GPS temporarily unavailable.
- Location permissions denied or revoked.
- Location accuracy below the configured threshold.
- The device being offline.
- The scheduled check being delayed by Android.
- The device restarting or missing a scheduled execution.

When a valid inside/outside decision cannot be made, record a separate check status such as `UNKNOWN`, `LOCATION_UNAVAILABLE`, or `ERROR`. Do not convert an unknown result into either binary outcome.

If a scheduled check is delayed, document and implement a consistent policy for whether to execute a late check, skip it, or catch up after the device becomes available. Avoid uncontrolled catch-up runs or duplicate measurements.

## 3. Privacy and Local Data Storage

Privacy is a fundamental requirement, not an optional feature.

The app must store only the information needed to manage schedules, geofences, offline maps, and check results.

For successful geofence checks, store:

- The scheduled or actual check timestamp.
- The geofence identifier.
- A binary result: `INSIDE` or `OUTSIDE`.
- Minimal non-location metadata needed to interpret the result, if necessary.

For unsuccessful checks, store a distinct status without inventing a binary result.

**Never persist the device's actual latitude, longitude, raw GPS fixes, route history, or movement history.** Actual coordinates may be held temporarily in memory to perform the geofence calculation, but they must not be written to the database, debug logs, analytics, crash-reporting systems, or exported diagnostic data.

Store the result of the geographic calculation, not the location used to calculate it.

Additional privacy requirements:

- Keep schedules, geofences, and check history on the device.
- Do not require user accounts, a remote server, or cloud synchronization.
- Do not transmit location measurements or geofence results to external services.
- Do not introduce analytics or advertising SDKs.
- Minimize Android permissions and request location access only as required.
- Provide a way to inspect and delete stored check history.
- Avoid logging sensitive information during development and production.

The app may access the internet when explicitly downloading OSM data, but scheduled geofence evaluation and result storage must work offline.

## 4. Offline Architecture and Persistence

Use a local architecture that separates:

1. Map-area selection and OSM data downloading.
2. Offline map storage and rendering.
3. Geofence creation and geographic calculations.
4. Schedule management and background execution.
5. Location acquisition and inside/outside evaluation.
6. Persistent storage of geofences, schedules, and check results.
7. User interface and permission management.

Downloaded map data, geofences, schedules, and completed measurements must survive ordinary app restarts. Scheduling must be restored after a device reboot where Android permits it.

Use local persistence appropriate for the data, such as SQLite or Room for structured records and the app's local filesystem for downloaded map datasets.

Treat map downloads as independent, recoverable operations. Handle interrupted downloads, partial files, corrupted data, and storage shortages safely.

Document which functions require an initial internet connection and which functions are fully offline.

## 5. Technical Constraints

- Build a native Android application using an appropriate Android development stack.
- Prefer Kotlin and standard Android APIs unless the project has an existing stack that makes another choice more appropriate.
- Keep external dependencies to a minimum.
- Do not introduce a backend or cloud service.
- Use the specified Overpass API endpoint for OSM requests.
- Respect the usage limits and operational constraints of the Overpass service. Handle rate limiting, timeouts, large responses, and failed requests gracefully.
- Avoid downloading an unnecessarily large geographic dataset when the user selects a small area.
- Use geographic calculations that account for coordinate systems and provide appropriate accuracy for small geofences.
- Keep the app responsive while downloading, parsing, rendering, or querying map data.
- Include automated tests for core calculations, schedule generation, data persistence, and failure handling.

Do not assume that the Overpass API provides ready-to-render offline raster tiles. Select and document a practical offline rendering approach for the downloaded OSM data, including any required conversion, indexing, or local preprocessing. Explain important trade-offs before making architectural choices that materially affect offline functionality or dependency count.

## 6. User Experience

Use the AI-generated UI mockup at [`ai-mockup.png`](ai-mockup.png) as the visual reference for layout, navigation, and styling. It shows the intended screens (home menu, download map area, download progress, downloaded maps, create geofence, geofences, schedules, and history) together with their empty, enabled/disabled, and inside/outside/unknown states. Treat it as design guidance: follow its structure and visual language, but prioritize usability, accessibility, and consistency with the requirements below where they conflict.

Create a straightforward interface with the following main areas:

- **Offline Maps:** Select regions, download map data, inspect download progress, and manage saved maps.
- **Map and Geofences:** View downloaded map data, zoom and pan, create circular or polygon geofences, and manage existing geofences.
- **Schedules:** Configure weekly schedules, specific check times, recurring intervals, and associated geofences.
- **History:** Review timestamped inside/outside results and distinguish them from checks that could not be completed.
- **Settings:** Configure relevant accuracy thresholds and behavior, inspect permissions, and manage stored data.

Provide useful empty states, validation messages, error handling, and confirmation prompts for destructive actions.

The interface must clearly communicate that a downloaded map is available offline and identify whether location permission and background execution settings are sufficient for scheduled checks.

## 7. Git Workflow and Incremental Milestones

Work directly in the project's Git repository. Commit each important milestone as soon as it is complete and verified. Use small, focused commits instead of waiting until the entire application is finished.

Every commit message must be a single concise line with the following exact prefix:

`DeepSeek V4.1 Flash (max): `

Examples:

- `DeepSeek V4.1 Flash (max): Initialize Android project`
- `DeepSeek V4.1 Flash (max): Add offline map storage`
- `DeepSeek V4.1 Flash (max): Implement OSM region downloads`
- `DeepSeek V4.1 Flash (max): Add local map rendering`
- `DeepSeek V4.1 Flash (max): Implement circular and polygon geofences`
- `DeepSeek V4.1 Flash (max): Add weekly location schedules`
- `DeepSeek V4.1 Flash (max): Implement private binary check history`
- `DeepSeek V4.1 Flash (max): Add reboot recovery and background checks`
- `DeepSeek V4.1 Flash (max): Add offline and scheduling tests`

Keep commits focused on one logical change. Verify the relevant tests or behavior before committing. Never commit secrets, credentials, private location data, generated sensitive logs, or unrelated user changes.

Do not create one large final commit containing all implementation work.

## 8. Testing and Acceptance Criteria

The implementation is considered complete when the following scenarios have been addressed and verified:

1. A user can select and download a very small OSM region using the specified Overpass endpoint.
2. The downloaded region can be opened, zoomed, and viewed after internet access is disabled and the app is restarted.
3. A user can create, edit, and delete circular and polygon geofences on the offline map.
4. Geofence membership calculations produce correct results for locations inside, outside, and on or near a boundary.
5. A user can schedule checks at specific times on selected weekdays.
6. A user can schedule repeated checks, such as every 10 minutes from 4:00 PM through 5:00 PM on selected days, with documented boundary behavior.
7. Scheduled checks execute using appropriate Android background mechanisms, subject to documented operating-system restrictions.
8. Location permissions, inaccurate fixes, unavailable providers, reboot recovery, and delayed executions are handled without creating false measurements or duplicate results.
9. The persisted check history contains only the necessary timestamp, geofence reference, and binary outcome or failure status. No actual device coordinates or location history are persisted or logged.
10. Schedules, geofences, and stored results survive normal app restarts, and saved maps remain available offline.
11. Core logic has automated tests, and the project builds successfully.
12. Important development milestones are committed incrementally using the required commit-message prefix.

## 9. Implementation Approach

Begin by inspecting the existing repository, Android SDK configuration, build system, and Git status. Preserve existing user work.

Then implement the app incrementally in working, testable milestones:

1. Establish the application structure and confirm that it builds.
2. Choose and document the offline map data format, rendering approach, and local storage strategy.
3. Implement OSM region selection, downloading, persistence, and offline rendering.
4. Add geofence creation, editing, visualization, and geographic evaluation.
5. Implement schedule configuration and generation of scheduled check times.
6. Add Android location acquisition, background execution, and reboot recovery.
7. Implement privacy-preserving result storage and history.
8. Add automated tests, resolve integration issues, and document limitations.

Make reasonable implementation decisions without blocking on unnecessary questions. If a requirement cannot be achieved exactly because of Android platform restrictions or limitations of the Overpass service, implement the most practical supported behavior and document the limitation clearly.

At completion, provide a concise summary of the implemented functionality, key architectural decisions, external dependencies, tests performed, remaining limitations, and Git commits created.
