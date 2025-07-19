# mux-embed

## 5.9.0

### Minor Changes

- eb2ebfa: Improve scaling calculation accuracy by using more events for tracking

## 5.8.3

### Patch Changes

- 8fa7827: add custom 11 through 20 to types

## 5.8.2

### Patch Changes

- 38eeefe: remove duplicate video_source_mime_type from types

## 5.8.1

### Patch Changes

- cef9e40: fix typo in types for viewer_plan

## 5.8.0

### Minor Changes

- 049be75: Add support for video_creator_id

## 5.7.0

### Minor Changes

- 41b0915: Add keys for new customer-defined dimensions

## 5.6.0

### Minor Changes

- 9cd7dbf: Fix issue where firefox did not send beacons, and some final beacons might not be sent

## 5.5.0

### Minor Changes

- 18af18e: Update mechanism for generating unique IDs, used for `view_id` and others
- 157f957: Use crypto.randomUUID(), when available, for generating UUID values

## 5.4.3

### Patch Changes

- 2d96231: [chore] internal build process fix (no functional changes)

## 5.4.2

### Patch Changes

- e5f3e65: feat(google-ima): Beta implementation of google-ima extension to mux-embed
- fecba0b: feat(mux-embed): Add methods for post-initialization overrides of functionality (for internal use only).
- 40f531d: fix(mux-embed): typecheck for dashjs.getSource is incorrect.

## 5.4.1

### Patch Changes

- 1aa5968: Expose `updateData` globally and fix types
- 723e2e3: Fix an issue where views were not ended cleanly on long resume detection

## 5.4.0

### Minor Changes

- 67e297d: Add updateData function that allows Mux Data metadata to be updated mid-view.

## 5.3.3

### Patch Changes

- 7c77ea4: expose HEARTBEAT and DESTROY under mux.events

## 5.3.2

### Patch Changes

- d5e737e: Fix type issues for error severity and business exception

## 5.3.1

### Patch Changes

- 2f2f885: fix(mux-embed): Remove 3rd party dependencies and replace with appropriately equivalent functionality.

## 5.3.0

### Minor Changes

- 08adb81: Ignore request events when emitting heartbeat events
- Fix an issue where video quality metrics may not be calculated correctly on some devices

## 5.2.1

### Patch Changes

- 8a33cb0: Send hb events regardless of errors

## 5.2.0

### Minor Changes

- 6fc018a: Bug fix to not de-dupe error event metadata
- 5707014: Extend `errorTranslator` to work with `player_error_severity` and `player_error_business_exception`

## 5.1.0

### Minor Changes

- 95fd304: Target ES5 for bundles and validate bundles are ES5

### Patch Changes

- 117d668: fix an issue where seeking time before first play attempt counted towards video startup time

## 5.0.0

### Major Changes

- 70b87d4: Add opt-in TypeScript Types to Mux Embed and use + refactor for other dependent data SDKs. Update published dists to include CJS and ESM.
- Mux Embed now provides (opt in) TypeScript types in its published package, as well as publishes CJS and ESM versions of the package.
- This allows us to provide a lower risk and iterative roll out of official TypeScript types for `mux-embed`. The export types updates were required to ensure actual matches between the dist package and corresponding TypeScript types.
- This _should_ have no direct impact on users, though different build tools will now potentially select one of the new export types (e.g. the ESM "flavor" of `mux-embed`). TypeScript types _should not_ be applied unless they are explicitly referenced in app (discussed in docs updates).

## 4.30.0

### Minor Changes

- e3c4f1e: fix an issue causing certain network metrics to not be available for dashjs v4.x

### Patch Changes

- ad1f41a: fix an issue where certain IDs used may cause a DOM exception to be raised

## 4.29.0

### Minor Changes

- 2d9f466: fix(mux-embed): avoid using element id for muxId. attach muxId to element.

## 4.28.1

### Patch Changes

- dabca78: fix an issue where beaconDomain deprecation line was incorrectly logged

## 4.28.0

### Minor Changes

- 7317411: Deprecate `beaconDomain` in favor of `beaconCollectionDomain`. The `beaconDomain` setting will continue to function, but integrations should change to `beaconCollectionDomain` instead.

## 4.27.0

### Minor Changes

- 6d868a0: Fix an issue where playback time was incorrectly counted during seeking and other startup activities
- 32f78cb: Add events for the collection of ad clicks
- b295a7f: fix an issue where seek latency could be unexpectedly large
- b295a7f: fix an issue where seek latency does not include time at end of a view
- 903f8a8: Add events for the collection of ad skips

## 4.26.0

### Minor Changes

- 15f2461: muxData cookie expiration should be one year

## 4.25.1

### Patch Changes

- 2cd1d81: Do not deduplicate ad IDs in ad events

## 4.25.0

### Minor Changes

- 3bdf5e8: Include ad watch time in playback time

## 4.24.0

### Minor Changes

- a8e725e: Fix an issue where beacons over a certain size could get hung and not be sent

## 4.23.0

### Minor Changes

- 4af958d: Collect Request Id from the response headers, when available, for HLS.js (`requestcompleted` and `requestfailed`) and Dash.js (`requestcompleted`). The following headers are collected: `x-request-Id`, `cf-ray` (Cloudflare), `x-amz-cf-id` (CloudFront), `x-akamai-request-id` (Akamai)
- 60634da: Fix an issue where tracking rebuffering can get into an infinite loop

### Patch Changes

- df8b73a: Update Headers type

## 4.22.0

### Minor Changes

- 92ac6dc: Send errors, `requestfailed`, and `requestcancelled` events on Dash.js. Because of this change, you may see the number of playback failures increase as we now automatically track additional fatal errors.

## 4.21.0

### Minor Changes

- 197eab8: Include Ad metadata in ad events

## 4.20.0

### Minor Changes

- 7243d2e: Support for new dimension, `view_has_ad`

## 4.19.0

### Minor Changes

- 567814c: End views after 5 minutes of rebuffering

## 4.18.0

### Minor Changes

- 2e187c3: Add audio, subtitle, and encryption key request failures for HLS.js
- e91e64c: Capture ad metadata for Video.js IMA
- b37e888: Capture detailed information from HLS.js for fatal errors in the Error Context

## 4.17.0

### Minor Changes

- 81ffe36: Extend `errorTranslator` to work with `player_error_context`

## 4.16.0

### Minor Changes

- cb4813e: Add new `renditionchange` fields to Shaka SDK
- 842167e: Adds support for new and updated fields: `renditionchange`, error, DRM type, dropped frames, and new custom fields
- d802f30: Add frame drops to Shaka SDK
- 73a23c2: Add new `renditionchange` info to Web SDKs
- 4464398: Adds the new Media Collection Enhancement fields

## 4.15.0

### Minor Changes

- b1b25a4: update `mux.utils.now` to use `navigationStart` for timing reference

### Patch Changes

- 87027e7: fix issue where views after `videochange` might incorrectly accumulate rebuffering duration
- dafa288: Resolved issue sending beacons when view is ended
- 1dd4cc1: Record `request_url` and `request_id` with network events

## 4.14.0

### Minor Changes

- 99ab50c: Tracking FPS changes if specified in Manifest

## 4.13.4

### Patch Changes

- faefb2e: Resolved issue sending beacons when paused

## 4.13.3

### Patch Changes

- 3b44caa: Fixed issue with monitoring network events for hls.js monitor

## 4.13.2

### Patch Changes

- b21e86a: Fix an issue with sending unnecessary heartbeat events on the window `visibilitychange` event

## 4.13.1

### Patch Changes

- df36c55: Fixes an issue with accessing the global object

## 4.13.0

### Minor Changes

- 4b78021: Collect the `x-request-id` header from segment responses to make it easier to correlate client requests to other logs
- 388e558: Upgraded internal webpack version

### Patch Changes

- a534321: Flush events on window `visibilitychange` event

## 4.12.1

### Patch Changes

- aaf3b01: Use Fetch API for sending beacons

## 4.12.0

### Minor Changes

- 856b2df: Generate a new unique view if the player monitor has not received any events for over an hour.

## 4.11.0

### Minor Changes

- b2499f4: Detect fullscreen and player language

## 4.10.0

### Minor Changes

- a59a3ee: Replace query string dependency to reduce package size
- a59a3ee: Remove `ImageBeacon` fallback, removing support for IE9

## 4.9.4

### Patch Changes

- 91c4794: Generate all `view_id`'s internally

## 4.9.3

### Patch Changes

- ecf1447: Use common function for generating short IDs

## 4.9.2

### Patch Changes

- 0323215: Fixed an issue around the `disablePlayheadRebufferTracking` option

## 4.9.1

### Patch Changes

- e4b3b16: Fix issue where `getStartDate` does not always return a date object

## 4.9.0

### Minor Changes

- 6fc81db: Support PDT and player_live_edge_program_time for Native Safari

### Patch Changes

- 28af90d: Set a max payload size in mux-embed

## 4.8.0

### Minor Changes

- 5b57b38: Add option `disablePlayheadRebufferTracking` to allow players to disable automatic rebuffering metrics.
  Players can emit their own `rebufferstart` or `rebufferend` events and track rebuffering metrics.

### Patch Changes

- 2572844: Fix an issue with removing `player_error_code` and `player_error_message` when the error code is `1`.
  Also stops emitting `MEDIA_ERR_ABORTED` as errors.
- 6bd4336: Now leaving Player Software Version for HTML5 Video Element unset rather than "No Versions" as it is no longer needed.

## 4.7.0

### Minor Changes

- d2c2670: Add an option to specify beaconCollectionDomain for Data custom domains

## 4.6.2

### Patch Changes

- 4f2187c: Fix an issue with emitting heartbeat events while the player is not playing

## 4.6.1

### Patch Changes

- 51476cc: Fix an issue with removing event listeners from window after the player monitor destroy event

## 4.6.0

- Update hls.js monitor to record session data with fields prefixed as `io.litix.data.`
- Update the manifest parser to parse HLS session data tags

## 4.5.0

- Add short codes to support internal video experiments
- Collect request header prefixed with `x-litix-*`
- Capture fatal hls.js errors
- Make `envKey` an optional parameter

## 4.4.4

- Add a player events enum on the `mux` object (e.g. `mux.events.PLAY`)
- Use the browser `visibilitychange` listener instead of `unload` to handle destructuring the player monitor.

## 4.4.3

- Fix: Specify `video_source_is_live` for HLS.js monitor

## 4.4.2

- Group events into 10 second batches before sending a beacon

## 4.4.1

- Exclude latency metrics from beacons if `video_source_is_live` is not `true`

## 4.4.0

- Add a lightweight HLS manifest parser to capture latency metrics for player's that don't expose an API for accessing the manifest.
- Allow players to emit `player_program_time` instead of calculating internally

## 4.3.0

- Add support for calculating latency metrics when streaming using HLS

## 4.2.5

- Remove default `video_id` when not specified by the developer.

## 4.2.4

- Add minified keys for latency metrics

## 4.2.3

- Add minified keys for new program time metrics

## 4.2.2

- Fix bug causing missing bitrate metrics using HLS.js {'>'}v1.0.0

## 4.2.1

- (video element monitor) Fix an issue where some non-fatal errors thrown by the video were tracked as playback failures

## 4.2.0

- Fix an issue where views triggered by `programchange` may not report metrics correctly
- Fix an issue where calling `el.mux.destroy()` multiple times in a row raised an exception

## 4.1.1

- Fix an issue where `player_remote_played` wasn't functioning correctly

## 4.1.0

- Add support for custom dimensions

## 4.0.1

- Support HLS.js v1.0.0

## 4.0.0

- Enable sending optional ad quartile events through.
- Move device detection server-side, improving data accuracy and reducing client SDK size.
- Fix an issue where jank may be experienced in some web applications when the SDK is loaded.

## 3.4.0

- Setting to disable rebuffer tracking `disableRebufferTracking` that defaults to `false`.

## 3.3.0

- Adds `viewer_connection_type` detection.

## 3.2.0

- Adds support for `renditionchange`.

## 3.1.0

- Add checks for window being undefined and expose a way for SDKs to pass in platform information. This work is necessary for compatibility with react-native-video.

## 3.0.0

- Setting to disable Mux Data collection when Do Not Track is present now defaults to off
- Do not submit the source URL when a video is served using the data: protocol

## 2.10.0

- Use Performance Timing API, when available, for view event timestamps

## 2.9.1

- Fix an issue with server side rendering

## 2.9.0

- Support for Dash.js v3

## 2.8.0

- Submit Player Instance Id as a unique identifier

## 2.7.3

- Fixed a bug when using `mux.monitor` with Hls.js or Dash.js the source hostname was not being properly collected.
