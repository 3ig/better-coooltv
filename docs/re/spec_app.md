# Coool TV 2.0.13 (Android TV) — App structure & performance spec

All line numbers refer to `scratchpad/decompiled.js`. APK details come from `scratchpad/apk`. The manifest was parsed with `scratchpad/tools/axml.py`.

## 0. Platform facts
- Expo SDK 57.0.0, React 19.2.3 (L35112), Hermes, and the New Architecture/Fabric (`nativeFabricUIManager`, rnscreens "gamma" Fabric classes). The only ABI is armeabi-v7a. Native libs total 15 MB; `libreactnative.so` alone is 4.6 MB.
- The JS bundle `assets/index.android.bundle` is 4.6 MB of HBC and holds about 2,718 Metro modules.
- `app.config`: scheme `routertv`, package `com.cooolbox.coooltv`, versionCode 2400126, `usesCleartextTraffic: true`, plugin `@react-native-tvos/config-tv`, and splash background `#0E0E12`.
- The codebase is cross-platform. It ships Tizen, webOS and Web adapters that are dead code on Android: `TizenExitAdapter`, `WebOSDeviceInfoAdapter`, `HardwareBackWebosAdapter`, `WebOSAdapterRemoteKeys`, and magic-remote cursor handling.

## 1. Navigation, screens, features

### Navigation
- **No expo-router and no react-navigation.** The app has its own `ScreensManager` (L662634) plus a zustand `useScreensManagerStore`.
  - `AppNavigator` (L202390) swaps between `initializationStackScreens` and `mainStackScreens` (L202600–202650). It renders `<ScreensManager screens modalScreens>`.
- Screen IDs are at L441600:
  - `initialization`
  - `verify-code`
  - `profiles`
  - `player`
  - `pin-selector`
  - `message`
- Modal IDs are at L441602:
  - `confirmation-dialog`
  - `no-connection-dialog`
  - `no-server-reachability-dialog`
  - `idle-dialog`
  - `player-overlay`
  - `menu`
  - `swap-current-channel`
  - `channel-number-selector`
  - `channel-list`
  - `channel-epg`
  - `epg-grid`
- Screen settings are at L441192ff and set `allowModals` per screen. The **Player Overlay has `preload: true`** and the No Connection dialog has `lockInput: true`.
- Focus is handled by a custom JS spatial-navigation engine (L429000–432000):
  - Providers: `SpatialNavigationProvider`, `ScreenMeasureProvider`, `ScreenActiveProvider`.
  - Navigators: `GlobalNavigator`, `GridNavigator`, `ListNavigator`, `RowNavigator`, `EpgGridNavigator`.
  - Geometry scoring uses `BEAM_MIN_OVERLAP_RATIO`, `PERPENDICULAR_PENALTY` and similar (L430750).
  - `TVPressable` key-repeat is 150 ms (`DEFAULT_REPEAT_INTERVAL_MS`, L425612).
- Keys arrive through the custom Expo module `expo.modules.coooltvkeyhandler` and the `tvRemoteEvent` event.

### Screens and components (by component name)

| Screen / modal | Components | Feature |
|---|---|---|
| initialization | `Initialization` (L630092), custom `CooolSplashScreen` (native `expo.modules.cooolsplashscreen`) | Bootstrap: auth check, minimum-version check, profile load |
| verify-code | `VerifyCode` (L650125); QR generator in JS (L656452) | Device pairing. Steps: open URL / scan QR → log in to My Cooolbox → "add device" → enter `{{userCode}}` → stay on screen (auto-refresh, presumably over SSE) |
| profiles | `Profiles` / `Profile` (L649469) | Profile picker: "Select a profile" / "Continue with your profile" |
| pin-selector | `PinSelector` (L639771) | Profile PIN entry |
| player | `Player` (L643354), `Index` (L640816), `EpgManager` (L642144), `TimeshiftIndicator`, `PlayerRestrictedMessage` | Full-screen playback through react-native-video/ExoPlayer, timeshift, watch restrictions |
| player-overlay (preloaded) | `ChannelBar`, `ChannelsListV2` (L620860), `EPGListV2` (L622739), `EPGPressableV2`, `PlayerControl` (L625887: progress, live, skip-to), `ProgressIndicator`, `Thumbnail` (L627529: seek preview from `streamURI`+`skipToTime`+`token`), `SettingsBar` (L628360), `ActiveChannelHint`, `PlayerIndicator` | Layers `SETTINGS_BAR`, `PROGRESS_BAR`, `CHANNELS_LIST`, `EPG_LIST` (L378150). Auto-hide after 7 s |
| menu | `MenuModal`, `MenuButton` (L618811) | Grid / Profiles / List, plus Exit and Logout |
| channel-list | `ListChannelScreen` (L615538), `ChannelList` → `PaginatedGrid` (L443005), `ChannelPressableV2`, `ChannelEPGInfo` | Channel grid with logos and current-EPG info |
| channel-epg | `ChannelEPGList` → `WindowedList` (L444683), `ChannelEPGListItem`, `ChannelEPGListInfo` | Full EPG list for one channel, 7 days (`MAX_DAYS_FROM_NOW = 7`, L333230), day separators `separator-` |
| epg-grid | `Grid` (L450495), `GridChannelItem`, `GridEpgRow`, `GridEpgRowItem`, `GridEpgTimeline(Container)`, `GridCurrentTimeLine`, `GridEpgDayInfo` | Paged by channel page (page-up/down) and by time range (prev/next) |
| swap-current-channel | `SwapCurrentChannel` (L629457) | Jump back to the previous channel; 5 s timeout |
| channel-number-selector | `ChannelNumberSelector` (L447735) | Numeric channel entry |
| idle-dialog | `IdleDialog` (L526079) + SVG `AnimatedCircularProgress` | "Are you still watching?" Idle timeout is 4 h, checked every 30 s (L383598) |
| confirmation / no-connection / no-server-reachability | `ConfirmationDialog`, `NoConnectionDialog`, `NoServerReachabilityDialog` | Exit and logout confirmations; network status |
| message | `Message` (L632625), `TaggedText` | Errors: max sessions reached, update required, device not found or not linked, session not found, trial expired (3 days), account locked or inactive, additional plan required |

- Background monitors: `ConnectivityMonitor` (L637336) and `ServerReachabilityMonitor` (L638142). Idle monitoring uses `useIdleMonitor`.
- Server SSE pushes `*_ACTION_TYPE` events (L383168–383242): profile updated/deleted/linked, channel CRUD, `device_debug_mode_changed`, `session_ended`, `device_stream_ready`, package changes, and EPG updates.
- Burn-in protection: a pixel shift every 120 s (L642920).

### i18n
- i18next with react-i18next, configured at L617998.
- Languages: **en** (fallback) and **bg**. Resources are inline: bg at L618100, en at L618150.
- Namespaces: `common`, `auth`, `noConnection`, `noServerReachability`, `message`, `profiles`, `settings`, `messages`, `epgGrid`, `menu`, `subtitles`, `audio`.
- `settings` has only Audio, Subtitles and Resize (contain/cover/stretch). There is no broader settings screen.
- `subtitles` and `audio` map language codes to display names. `menu` contains grid, profiles and list.
- Persisted player settings: `savePlayerSettings` / `updatePlayerSettings`, current channel per profile (`current_channel_profile_`), last profile, and resume-player-at (`rpa`).

## 2. Third-party libraries

**JS libraries (evidence)**
- react-native-reanimated 4.5.1 (L561293) and react-native-worklets 0.10.1 (L532043)
- react-native-screens, react-native-svg (all RNSVG* components)
- expo-image (`ExpoImage`; Glide on the native side) and expo-linear-gradient
- zustand, including the devtools middleware strings (L376781) and `useShallow` (16 uses). Stores: `useChannelsStore`, `useConnectivityStore`, `useDeviceStore`, `useEpgStore`, `useLoadingStore`, `usePlayerStore`, `useProfilesStore`, `useScreensManagerStore`, `useUserStore`
- @tanstack/react-query (`QueryClientProvider` L249806; custom `createEndpointFactory`/`createMutation` wrappers)
- axios 1.20.0 (L243122)
- i18next and react-i18next
- lodash 4.18.1 (L373967) and date-fns
- @react-native-async-storage/async-storage and @react-native-community/netinfo
- react-native-device-info (memory and API-level getters)
- react-native-video (brentvatne) on media3 ExoPlayer with HLS/DASH/DRM
- expo-crypto (with AES), expo-font (Open Sans), expo-constants, expo-splash-screen, expo/fetch (used for SSE streaming with TextDecoder; L270572 `Accept: text/event-stream`)
- An inline JS QR-code generator
- Not present: Sentry, MMKV, FlashList/LegendList, redux (only devtools strings), Lottie, Skia, expo-blur.

**Native (dex/so)**
- From the native libs: `libreanimated`, `libworklets`, `librnscreens`, `libgesturehandler`, `libreact_codegen_rnsvg`, `safeareacontext`, `libexpo-modules-core`, Fresco imagepipeline/gif/webp/avif.
- From dex classes:
  - Images: Glide (expo-image) and Fresco (RN Image) — two image stacks; androidsvg
  - Video: media3 exoplayer, session and ui
  - Expo modules: filesystem, webview, webbrowser, keep-awake, application, system-ui, linking, logbox
  - Other: install-referrer, zstd, brotli
  - App-specific: `expo.modules.coooltvkeyhandler` and `expo.modules.cooolsplashscreen`

## 3. Likely performance problems on weak TV boxes (ranked)

1. **Remote logging of every INFO, WARN and ERROR line over HTTP.**
   - `GLOBAL_LEVEL = INFO` (L271585–271598).
   - Each `logger.info` runs an async generator, builds a Loki-style `{streams:[...]}` payload, and POSTs it to `/debug/message` (L244520) through react-query `useCreateLog` (L274959) on axios (L211529–211535). There is no batching.
   - DEBUG lines are sent only when the server flips `enableDebugLogs` (key `EDL`, L272107; gate at L211438–211449).
   - Failed sends go to AsyncStorage (`UNSENT_LOGS_KEY`, at most 50; L209280) and are re-flushed later.
   - The bundle has about 830 `[fn] message` log strings, including hot paths such as key handling, channel change and focus changes.
   - Device info including used/total memory is attached; memory is polled every 60 s (`UPDATE_USED_MEMORY_INTERVAL`, L272097/L274576).
   - Net effect: network, JSON and bridge traffic on almost every user action.
2. **A 1 Hz JS timer on the player that writes to a global store and to storage.**
   - `UPDATE_TIMES_INTERVAL = 1000` (L445664ff) starts when the player mounts (L641071).
   - `updateTimes` (L625029) creates Date objects and calls `setLiveTime`/`setCurrentTime`/`setSkipToTime` in `usePlayerStore` every second.
   - It also writes `RESUME_PLAYER_AT` to LocalStorage/AsyncStorage, throttled to 3 s (L625175).
   - Subscribers of `useCurrentTime`/`useLiveTime` re-render every second: `useEpgManager` (L334837, runs `findIndex` over EPG entries), `ProgressIndicator`, `PlayerControl`, `TimeshiftIndicator`.
3. **The player overlay is always mounted** (`preload: true`). `ScreensManager` keeps it as `warmPreloadedModal {opacity:0}` (L662634ff, near `hiddenModal {display:'none'}`).
   - The hidden overlay (channel list, EPG list, progress bar, settings bar, thumbnails) therefore keeps re-rendering and laying out on top of the video surface while invisible.
4. **Focus logic lives entirely in JS.**
   - Every D-pad press, and every 150 ms repeat on long-press, runs geometric candidate scoring in JS (L430750 constants).
   - It updates zustand focus state (`focusedEpg`, `hoveredEpg` in `useEpgStore`, L330252) and re-renders the pressables.
   - `measureInWindow` is called on focus (L424862, L626697).
5. **Reanimated animates layout properties, not transforms.**
   - `PlayerControl` animates `width:%` and `left:%` (worklets `PlayerControlTsx1–3`).
   - `PlayerRestrictedMessage` animates `top`.
   - `AnimatedProgress` animates the SVG `strokeDashoffset`.
   - Each frame forces a Yoga layout or SVG redraw. Other animations are cheap opacity/scale (`ActiveChannelHint`, `ChannelEPGListInfo`, `Thumbnail` cross-fade, `GlobalActivityIndicator`).
   - Reanimated 4 and worklets add a second JS runtime and about 1.7 MB of native code for roughly 10 tiny animations.
6. **Full-screen LinearGradients composited over video.**
   - Used in `Modal` (L620636), `SettingsBar` (L629227), `SwapCurrentChannel`, `MenuModal`, `ListChannelScreen`, `Grid` (L450947), and per-row in `GridChannelItem` (L451427).
   - On weak GPUs, alpha overdraw above a SurfaceView costs fill rate.
   - Shadows and blur are minimal: a single text-shadow and `SHADOW_BLUR` in the timeshift indicator. No expo-blur.
7. **Lists use a custom windowing layer, not FlatList.**
   - `PaginatedGrid` (channel grid; `visibleItems`, `startRow`/`endRow`) and `WindowedList` (channel EPG list, uses rAF and setTimeout) are both JS-windowed.
   - `ChannelsListV2` and `EPGListV2` use `.map` over a window: about 11 channels (`MAX_HALF_VISIBLE_CHANNELS = 5`) and the EPG row.
   - The EPG grid is paged, not virtualized: `GridEpgRow` maps every entry in the time range, and each `GridEpgRowItem` has `overflow:hidden` and its own `findIndex`.
   - Windowing is reasonable, but every page change is a full React re-render with no recycling.
8. **EPG data is kept in JS memory.**
   - Covers 7 days for all channels: `epg`, `sectionedCoverage`, `cacheVersion` in `useEpgStore`.
   - It is fetched from `/epg/filter` and `/epg/cache-version` (L244424), so JSON parsing and date math run on the JS thread.
   - `updateChannelsEpg`/`replaceChannelEpg` run when SSE pushes arrive. Refetch details belong to the API agent.
9. **Background polling and timers.**
   - Heartbeat `GET /heartbeat` every 15 s via react-query `refetchInterval` (L638244–638260).
   - Session last-active check every 30 s (L385619).
   - Idle check every 30 s; memory poll every 60 s.
   - Connectivity fallback poll every 10 s (L637249; webOS/fallback path).
   - Pixel shift every 120 s.
   - A persistent SSE stream parsed in JS through expo/fetch with TextDecoder (L268941, L270572).
10. **Images.**
    - Channel logos use expo-image with `cachePolicy:'memory-disk'`, `priority:'high'`, `contentFit:'contain'` and a `transition` (L396060).
    - The Grid channel item also uses `transition` (L451463).
    - No resizing or sizing hints are given, so full-size logos are decoded.
    - Two image stacks are loaded (Glide plus Fresco).
11. **Startup cost.** Loading the 4.6 MB HBC bundle, the New Architecture, two JS runtimes (worklets), expo-font Open Sans loading, i18n init, the device-info async calls, and the custom splash all add up.

**Recommendations for the replacement**
- Keep the JS thread idle while watching.
- Use native focus handling (Leanback/RecyclerView or Compose for TV) instead of a JS geometry engine.
- Render the overlay only when it is open.
- Animate with transforms only.
- Drive progress from the player position, not a 1 Hz global-store tick.
- Batch logs, or keep only errors.
- Use one image loader with sized requests.
- Parse the EPG off the main thread and cache it per channel and day.

## 4. AndroidManifest (parsed)
- `minSdkVersion 24`, `targetSdkVersion 36`, compileSdk 36 (Android 16). versionName 2.0.13, versionCode 2400126.
- Permissions:
  - `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`
  - `READ_EXTERNAL_STORAGE`/`WRITE_EXTERNAL_STORAGE` (maxSdk 32)
  - `SYSTEM_ALERT_WINDOW`, `VIBRATE`
  - `BIND_GET_INSTALL_REFERRER_SERVICE`, plus its own `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
- Features: `android.software.leanback` required=false; `touchscreen` and `faketouch` required=false.
- Application: `MainApplication`, `allowBackup=false`, `usesCleartextTraffic=true`, `extractNativeLibs=true`, a TV `banner` is set, `supportsRtl`.
- `MainActivity`: singleTask (launchMode 2), exported. Intent filters: MAIN with **LAUNCHER + LEANBACK_LAUNCHER**, and a VIEW deep link with scheme `routertv`.
- Other components:
  - expo-updates meta-data present but `ENABLED=false`
  - `FileSystemFileProvider` and the webbrowser `BrowserProxyActivity`
  - Glide OkHttp module
  - androidx.startup (emoji2, lifecycle, profileinstaller) and `ProfileInstallReceiver`
- Distributed through Play: stamp `https://play.google.com/store`.
- `largeHeap` and `hardwareAccelerated` are not set.
