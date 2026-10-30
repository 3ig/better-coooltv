# Coool TV content and playback spec (from decompiled.js)

Line numbers refer to `scratchpad/decompiled.js`.

## 0. HTTP basics
- axios instance (line ~223170): `baseURL: https://tvapi.coool.tv/api/v2`. Default headers are `Accept: application/json`, `Content-Type: application/json` and `Accept-Language: bg`. A request interceptor sets `Authorization` (line ~223300; auth flow is out of scope here).
- The generic query factory `createQueryFactory` (269392) always sends `{url: path, method: method ?? 'get', params, paramsSerializer, headers}` and returns `response.data`.
- `ParamsSerializer.serialize` (268593) uses URLSearchParams. It skips `null`/`undefined` values. **An array value is sent as a repeated key** (`channel=1&channel=2`). Every other value goes through `String(v)`.

## 1. Profiles
- Request: `GET /devices/{encodeURIComponent(serverDeviceId)}/profiles?limit=12` (`getProfiles` 378645; caller `initializeProfiles` 379871. The default `limit` is 12, see 380242 and 1413). `serverDeviceId` comes from the device-info store, and the SSE `device_stream_ready` event can update it (660843).
- Response: a **plain array** of profiles. Fields used: `uuid` (the identifier everywhere), `name`, `imageUrl` (avatar, 650038), `pinCode` (4-digit string, compared **client-side** with the typed PIN, 640220/640341).
- Selection logic (initializeProfiles plus initializeChannelsAndProfile, 630847):
  1. LocalStorage `current_profile` holds a profile uuid. If it matches a returned profile, that profile becomes current.
  2. Otherwise, if exactly one profile is returned, it becomes current ("only one profile, setting as current").
  3. LocalStorage `last_active_profile` (uuid) sets `lastActiveProfile`, which is used for focus on the picker.
  4. Otherwise the Profiles picker screen is shown. Picking a profile with a non-empty `pinCode` opens PIN_SELECTOR first. `selectProfile` (449410) sets the current profile and saves its uuid to `current_profile` (379658).
- The profile **uuid** is the `profileId` in channel URLs (630569: `profileId: profile.uuid`).
- The endpoint map also has `profiles.channels(profileId) => /profiles/{id}/channels`, but no caller for it was found. The app uses the device-scoped variant.

## 2. Channels
- Request: `GET /devices/{encodeURIComponent(deviceId)}/profiles/{profileUuid}/channels` (`getProfileChannels` 335953; called from `initializeChannels` 380589). It sends `params: {limit}`, but the caller passes no limit, so no query string is sent. Query key `['channels', profileUuid]`.
- Response: a **plain array** of channel objects, kept **in server order**. `setChannels` (337245) does no sorting, filtering or grouping. Fields used in code:
  | field | use |
  |---|---|
  | `id` | key everywhere; key of the EPG map; saved as the current channel |
  | `name` | label |
  | `number` | displayed; numeric zapping via `number.toString() === typed` (`findChannelByNumber` 381028) |
  | `logo` | image URL, passed directly as expo-image `source` (396061, 451465) |
  | `stream` | HLS live URL base (Android, non-Tizen) |
  | `streamTimeshift` | HLS timeshift base |
  | `dashStream` / `dashStreamTimeshift` | DASH variants, Tizen only |
  | `hasTimeshift` | bool; enables catch-up, timeshift icon, play/pause |
  | `includedInPlan` | bool; when false the channel is shown dimmed ("channelNotIncluded", opacity 0.6) and is not playable (WATCH_RESTRICTION `CHANNEL_NOT_IN_PLAN`) |
- No categories, groups, favorites, DRM or per-channel archive-depth fields were found. The bundle contains no favorites or VOD code.
- Current channel: LocalStorage `current_channel_profile_{profileUuid}` stores the channel `id` (336112 prefix, 380785 read). It falls back to the first channel if the key is missing or not found.
- Live updates come over SSE (path `/devices/sse/device/{deviceId}`, 382591). Events `channel_created`, `channel_updated`, `channel_deleted` and `profile_channels_updated` trigger a refetch of the channel list (660878 onward). Profile events (`profile_updated`, `profile_deleted`, `profile_linked_to_device`, `profile_unlinked_from_device`, `profile_device_order_updated`) trigger `refreshProfiles`. The server is therefore the source of truth for channel order.

## 3. EPG
- Request: `GET /epg/filter` with query params (useGetEPG 330116; query key `['epg']`):
  - `from`: ISO-8601 string (`Date.toISOString()`)
  - `to`: ISO-8601 string
  - `channel`: optional. Either one channel id, or an **array of ids** sent as repeated `channel=` params (521497 passes `channelIds`). If omitted, all channels are returned (initial load, 389043).
  - `v`: added by a request interceptor (330135) when `url === '/epg/filter'` and the store's `cacheVersion !== null`. This is cache-busting only.
  - No paging params. The window is chosen on the client.
- Cache version: `GET /epg/cache-version`, response `{ version }` (`fetchCacheVersion` 388652). It is called before channels and profiles are initialized (630990). The SSE event `epg_updated` carries `{ cache_version }`. Its handler calls `setCacheVersion(cache_version)` and `setShouldUpdateEpg(true)` (660187), and the UI then re-runs `initializeEPG` (641243).
- **No persistent EPG cache.** The zustand `useEpgStore` (1118, 330250) is in memory only; no persist middleware is used by any app store. `cacheVersion` is used only as the `v` URL param, presumably so the CDN or HTTP cache can serve the same URL until the version changes.
- Windows:
  - `MAX_DAYS_FROM_NOW = 7` (333230).
  - Initial (`calculateInitialSectionedCoverageWindow` 389169, `timeWindowHours` default 24):
    `from = startOfHour(max(min(currentTime-12h, liveTime-12h), liveTime-7d))` and `to = startOfHour(min(max(currentTime+12h, liveTime+12h), liveTime+7d))`.
  - Per-channel refresh (`useReplaceChannelEPG` 388380): `channel=<id>`, `from=startOfHour(liveTime-7d)`, `to=startOfHour(liveTime+7d)`.
  - Infinite scroll (`requestEpg` 521590 / 388100): fetches more past or future for a list of `channelIds`, clamped to ±7 days, using `prefetchThresholdHours`/`prefetchHours` (values not traced).
- Response: an object **keyed by channel id**, each value an array of entries. Merging is done by `mergeSortedUniqueEpg`, sorted by `new Date(startTime).getTime()` and deduplicated by `id` (330367).
- Entry fields: `id`, `name`, `description`, `startTime`, `endTime`, `duration` (minutes; client placeholders compute `ms/1000/60`). `startTime` is passed to `new Date()` and compared with ms numbers, so it is probably epoch ms, possibly ISO (not certain). `realId` exists on client-generated placeholder or separator entries (`local-placeholder-<hash>`, name `'separator'`). It is not confirmed to come from the server.

## 4. Playback
### Token
`getToken(sessionId)` (381676) returns `` `${Date.now()}:${sessionId}` ``. The value is **not URL-encoded** and contains a raw `:`. `sessionId` is `uuid` from `POST /devices/sessions` with body `{deviceId, deviceOsVersion, appBuildNumber, appVersion}` (385830 onward; mutation key `['session']`). End the session with `PATCH /devices/sessions/{id}/end`; keep-alive is `PATCH /devices/sessions/{id}/last-active`.

### URL building (`useStreamURI` 386430)
`fromTime` comes from player-store `useFromTime`; null means live. `CooolPlatform.OS === 'tizen'` selects DASH, everything else (Android TV) uses HLS:
- Live HLS: `${channel.stream}?token=${token}`
- Timeshift / catch-up HLS: `${channel.streamTimeshift}/timeshift_abs-${getUnixTime(fromTime)}.m3u8?token=${token}` (386654). `getUnixTime` is date-fns and returns **seconds**. No duration is given, so playback runs from that point toward live (Flussonic `timeshift_abs`).
- The timeshift URL is used only when `fromTime` is set **and** `channel.hasTimeshift`. Otherwise the live URL is used.
- Tizen DASH: `${dashStream}?token=${token}&text=wvtt`, and `${dashStreamTimeshift}/timeshift_abs-${unix}.mpd?token=${token}&text=wvtt`.
- Selecting an EPG entry to watch (`useEpgSelection` 386960) requires `entry.startTime <= liveTime && channel.hasTimeshift && channel.includedInPlan`. It then sets `fromTime = new Date(entry.startTime)`. Catch-up depth is effectively 7 days on the client.
- Thumbnails (`generateThumbnailUrl` 628143): `${dirname(streamURI)}/${YYYY}/${MM}/${DD}/${HH}/${mm}/${ss}.jpg?token=${token}` in UTC, or `-preview.jpg` when `hasErrored` (Flussonic DVR thumbnails).

### Player
- **react-native-video v6** (`com.brentvatne` with androidx.media3 ExoPlayer; HLS and DASH media sources are present in the dex files).
- Usage (643777): `<Video source={{uri: streamURI}} controls={false} playWhenInactive focusable={false} resizeMode=CONTAIN paused selectedAudioTrack selectedTextTrack controlsStyles={{hideLiveBadge:true}} onError onLoad onLoadStart onReadyForDisplay/>`.
- **No headers, no `drm`, no `bufferConfig`, no `minLoadRetryCount`.** The library defaults apply, and the auth token travels only in the query string.
- Error code `'22004'` gets special handling (643521).
- Per-channel audio and subtitle choice is saved in LocalStorage `chs-{profileUuid}-{channelId}` (648903). It holds the `{id, lang}` of the selected tracks.
- Resume: LocalStorage `rpa` (RESUME_PLAYER_AT, epoch ms) is written about every 3 s while playing in timeshift (625175). On start it is ignored if older than 7 days (630257).

## 5. Full endpoint inventory (no other content endpoints exist)
All path strings in the bundle (grep for `'/...'`):
`/devices/{id}/profiles`, `/profiles/{id}/channels` (unused), `/devices/{id}/profiles/{uuid}/channels`, `/epg/filter`, `/epg/cache-version`, `/devices/sessions` (POST), `/devices/sessions/{id}/end` (PATCH), `/devices/sessions/{id}/last-active` (PATCH), `/devices/status`, `/devices/auth/refresh`, `/devices/auth-code/{code}/events`, `/devices/generate-auth-code` (POST), `/devices/auth/establish` (POST), `/auth/logout` (POST), `/devices/minimum-versions/check`, `/devices/{id}/is-debug-mode-enabled`, `/devices/sse/device/{id}` (SSE), `/debug/message`, `/heartbeat`. The web link `https://www.coool.tv/device/add` is shown on the pairing screen.
**No VOD, favorites, search or server-side settings endpoints.** Favorites, settings and recents do not exist in this app version.

## Uncertainties
- The exact format of `startTime`/`endTime` (epoch ms or ISO string) and the type of channel `id` (number or string) were not confirmed. Capture one real response to check.
- Whether `logo` and `imageUrl` are absolute URLs.
- The values of `prefetchThresholdHours`/`prefetchHours` and the exact infinite-scroll step size.
- How the SSE event envelope is parsed (`{type, data}` is assumed from `r4.type`).
- `profile_channels_updated` handler internals were only skimmed.
