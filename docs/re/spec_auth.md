# Coool TV: device auth / pairing and HTTP client spec

Line numbers refer to `scratchpad/decompiled.js`.

## 1. HTTP client (Metro module 663, lines ~223040-224430)

- `axios.create({ baseURL: 'https://tvapi.coool.tv/api/v2', timeout: 5000, headers: {...} })` (223170-223175). Constants module (~244240-244330): `TIMEOUT=5000`, `RETRY_MAX_ATTEMPTS=3`, `RETRY_BASE_DELAY_MS=1000`, `RETRY_MAX_DELAY_MS=10000`, `RETRY_JITTER_FACTOR=0.5`.
- Default headers on every request:
  - `Accept: application/json`
  - `Content-Type: application/json`
  - `Accept-Language: bg`
- **Auth header**: `Authorization: JWT <accessToken>`. The prefix is `JWT `, not `Bearer` (223276, 223302).
- No custom `X-*` headers, no app-version header, no custom User-Agent. On Android the RN XHR adapter is used, so the UA is OkHttp's default.

### Request interceptor (223250-223460)
- If the URL contains `/devices/auth/refresh`, no token is attached.
- Otherwise it reads `accessToken` from LocalStorage (`getStoredAccessToken`, 245346) and, if present, sets `Authorization: JWT <token>`. With no token stored the request goes out unauthenticated. Pairing calls work this way.

### Response interceptors, in registration order
1. **Logging** (223462-223566). Skipped for `/debug/message` and `/heartbeat`.
2. **Retry** (223568-223790). `isRetryable` (248327) returns true when the request has a config and either there is no response (network error) or the status is NOT in {400, 401, 403, 404, 429}. So 5xx and timeouts retry. `config.__retryCount` goes up to 3. Delay = `round(min(1000*2^n, 10000) * (0.5 + random*0.5))`. A failure with no response calls `serverReachability.onRequestFailure()`.
3. **401 / refresh** (223790-224330):
   - Error body shape: `response.data.detail = { errorCode, messages }` (`getMessageErrorDetail`, 248470-248560). An errorCode only counts if the status matches its table:
     - DEVICE_NOT_FOUND: 401, 403, 404
     - DEVICE_NOT_LINKED_TO_ACCOUNT: 403
     - SESSION_NOT_FOUND: 404
     - SERVICE_INACTIVE: 403
     - SERVICE_TRIAL_EXPIRED: 403
   - All error codes (224553): MAX_ACTIVE_SESSIONS_REACHED, MINIMUM_VERSION_REQUIRED, DEVICE_NOT_FOUND, DEVICE_NOT_LINKED_TO_ACCOUNT, SESSION_NOT_FOUND, SERVICE_LOCKED, SERVICE_INACTIVE, SERVICE_TRIAL_EXPIRED.
   - If errorCode == DEVICE_NOT_FOUND: no refresh. The app calls `onUnauthorized()` (logout and go to the pairing screen).
   - Else if status == 401 and the URL is not excluded (`/debug/message`, `/auth/logout`; `isAuthRefreshExcludedUrl` 245288), is not `/devices/auth/refresh`, and `!config.__authRetried`:
     - It calls `ensureAccessTokenRefreshed(axiosClient)`, which reuses one in-flight promise (245262).
     - Then it sets `config.__authRetried = true` and replays via `axiosClient(config)`. The request interceptor picks up the new token.
     - If the refresh fails, it calls `onUnauthorized()` and rejects.
   - If the request is still 401 after a retry, it logs "still unauthorized after token refresh", calls `onUnauthorized()` and rejects.
4. **Global message errors** (224330-224380): SERVICE_INACTIVE and SERVICE_TRIAL_EXPIRED go to `onMessageError(detail)` (a message screen). Excluded URLs (248637-248664): `/heartbeat`, `/devices/auth/refresh`, `/auth/logout`, `/debug/message`, `/devices/generate-auth-code`, `/devices/auth/establish`, and anything under `/devices/auth-code`.

### Token refresh: `performTokenRefresh` (module 683, 244933-245250)
```
POST /devices/auth/refresh            (no Authorization header)
{"refresh": "<refreshToken>"}
-> 200 {"access": "...", "refresh": "..."}   both stored (refresh token rotates)
```
- No stored refresh token: warns "[authRefresh] No refresh token available" and throws.
- 401 response: warns "Refresh token rejected", removes `accessToken` and `refreshToken`, then throws. This ends in a logout.

## 2. Storage keys (LocalStorageClient adapter, module 684/685; constants ~244215-244320)

| const | key | value |
|---|---|---|
| DEVICE_ID_KEY | `did` | **server** device id, from the establish response (`setServerDeviceId`, ~273440) |
| LEGACY_REFRESH_TOKEN_KEY | `authToken` | old key, only read by the storage migration (`runStorageMigration`, `migrated_<key>`) |
| ACCESS_TOKEN_KEY | `accessToken` | access JWT |
| REFRESH_TOKEN_KEY | `refreshToken` | refresh token |

At startup, `checkAuthentication` (658106) runs the migration. If `refreshToken` exists it goes to INITIALIZATION; otherwise it goes to VERIFY_CODE (pairing). Logout (448977) calls `POST /auth/logout` with an empty body, then removes `accessToken`, `refreshToken`, `did` and the last-active-profile key, even if the call failed.

## 3. Device info (Device.init 212181-212480; store module 776 ~272990)

These come from react-native-device-info:
- `deviceId` = `getUniqueId()` (the Android ID). This is the hardware id, not `did`.
- `vendor` = `getManufacturer()`
- `name` = `getDeviceName()`
- `model` = `getModel()` (falls back to `getDeviceId()` / 'unknown')
- `osVersion` = `getSystemVersion()`
- `appBuildNumber` = `getBuildNumber()` (string)
- `appVersion` = `getVersion()`
- `os` = `CooolPlatform.OS`:
  - `'androidtv'` on Android TV, `'android'` on phones.
  - Rewritten to `'firetv'` when `isTV` and (manufacturer upper-case == 'AMAZON' or model starts with 'AFT') (212370-212400).

## 4. Pairing flow (`useVerifyCode`, 650607-650960; hooks module 1189, 382326-382420)

Pairing URL shown on screen: `https://www.coool.tv/device/add` (650313), plus the short code.

### Step 1: generate a code (runs once device info is initialized)
```
POST /devices/generate-auth-code
{
  "deviceId": "<uniqueId>", "os": "androidtv", "osVersion": "11", "model": "...",
  "name": "...", "vendor": "...", "appBuildNumber": 123,
  "appVersion": "x.y.z", "identifier": "<uniqueId>"
}
-> { "shortCode": "...", "expiresAt": <epoch ms> }
```
- `appBuildNumber` is a Number. It is sent as 0 if missing or 'unknown' (650698-650710).
- `identifier` equals `deviceId`.
- A timer of `expiresAt - Date.now()` calls generate again when the code expires.
- On error the app goes to the MESSAGE screen.

### Step 2: wait for approval over SSE (no Authorization; `useAuthenticate` builds its factory without `authRefresh`)
```
GET https://tvapi.coool.tv/api/v2/devices/auth-code/<shortCode>/events
Accept: text/event-stream
Cache-Control: no-cache
```
- The app parses events until one carries `token.access`. Expected payload: `{"deviceId": "...", "token": {"access": "..."}}` (650830-650850). The SSE event name becomes `type` (see section 6). Which event name the server uses is not checked.

### Step 3: establish (fires as soon as SSE data has `token.access`)
```
POST /devices/auth/establish
{"access": "<token.access from SSE>", "deviceId": "<deviceId from SSE>"}
-> { "authenticated": true, "deviceId": "<serverDeviceId>", "token": {"access": "...", "refresh": "..."} }
```
- When `authenticated` is true, the app stores `refreshToken` and `accessToken`, calls `setServerDeviceId(deviceId)` (which writes `did`), and navigates to INITIALIZATION (650870-650935).

## 5. Other device endpoints (all use `Authorization: JWT <access>`)

| Purpose | Request | Notes |
|---|---|---|
| Heartbeat | `GET /heartbeat` every **15000 ms** (`POLLING_INTERVAL`, 638246-638300) | No body/params; only success or failure is used (server reachability). |
| Min version | `GET /devices/minimum-versions/check?os=<os>` (271948-272000, 274423) | Response `{version, appLink}`. Update required if `Number(appBuildNumber) < Number(version)`. |
| Device status | `GET /devices/status` (382455; used in AppNavigator ~202506) | Response `{canWatch, coooltvStatus, serviceStatus, denialReason, denialMessage}`, stored as the "entitlement". |
| Debug check | `GET /devices/<did>/is-debug-mode-enabled` (useDebugModeCheck 272212) | Response `{isInDebugMode, debugModeExpireDate}`. |
| Session create | `POST /devices/sessions` with body `{"deviceId": "<uniqueId>", "deviceOsVersion": "<osVersion>", "appBuildNumber": "<raw string>", "appVersion": "..."}` (startSession 385747-385900) | Response `{uuid}`, kept as `sessionId`. Called before playback (~631143). Concurrent starts and ends are serialized. |
| Session last-active | `PATCH /devices/sessions/<uuid>/last-active`, no body (385477-385600) | Checked every **30000 ms**; sent only when **300000 ms** (5 min) have passed since the last update. Response `{closed}`: if true, or on error, `onClosed()` runs. |
| Session end | `PATCH /devices/sessions/<uuid>/end`, no body (386069-386300) | Called on player error, onClosed, and app background (~663749). |
| Device updates SSE | `GET /devices/sse/device/<did>`, same SSE headers plus `Authorization: JWT <access>` (382536-382900) | Enabled when `did` is set. |
| Logout | `POST /auth/logout`, empty body | |
| Profiles (for reference) | `GET /devices/<did>/profiles`, `/devices/<did>/profiles/<profileId>/channels`, `/profiles/<id>/channels`, `/epg/filter`, `/epg/cache-version` | |

Device-updates event types (the SSE `event:` name becomes `type`; 383234-383244):
- `profile_updated`, `profile_deleted`, `profile_linked_to_device`, `profile_unlinked_from_device`, `profile_channels_updated`, `profile_device_order_updated`
- `epg_updated`
- `device_deleted`, `session_ended`, `device_debug_mode_changed`, `device_stream_ready`
- `account_entitlement_changed`
- `channel_created`, `channel_updated`, `channel_deleted`
- `extra_package_changed`

## 6. SSE client (createSSEFactory 269706-271390, parser modules 752/754 ~268380-268990)

- It uses `fetch(baseURL + path, {method: 'GET', headers, signal})` and reads the body with a stream reader and TextDecoder. It does not use EventSource.
- **Headers**: `{Accept: 'text/event-stream', 'Cache-Control': 'no-cache', ...opts.headers}`. `Authorization: JWT <token>` is added only if the factory was given `authRefresh`.
- **Parsing**:
  - Blocks are split on blank lines (`\r\n\r\n|\r\r|\n\n`); lines are matched with `/([^:]+)(?:: ?(.*))?/`.
  - It handles `event:`, `data:` (multi-line, joined with `\n`) and `id:`. The default event name is `message`.
  - Event names `ping` and `keepalive` go to `onPing(Date.now())`.
  - Anything else: `onData({ type: <eventName>, ...JSON.parse(data) })`. A JSON parse failure raises SSE_PARSE_ERROR. A leading BOM is stripped.
- **Errors**: if the response is 401 and `authRefresh` is present, the client calls `waitForRefresh()` and retries. Otherwise it raises SSE_UNAUTHORIZED ("Authentication failed with status N"). A non-ok response raises SSE_CONNECTION_ERROR.
- **Reconnect**: `getBackoffDelay` = `min(base*2^attempt, max) * (0.5 + rand*0.5)`, with base 1000 ms and max 30000 ms. The watchdog (`useSseWatchdog` 271647) defaults to `pingIntervalMs=25000`; when there is no activity it reconnects.

## 7. curl sketch
```sh
B=https://tvapi.coool.tv/api/v2; H=(-H 'Accept: application/json' -H 'Content-Type: application/json' -H 'Accept-Language: bg')
curl "${H[@]}" -X POST $B/devices/generate-auth-code -d '{"deviceId":"abcd1234ef567890","os":"androidtv","osVersion":"11","model":"SHIELD Android TV","name":"SHIELD","vendor":"NVIDIA","appBuildNumber":0,"appVersion":"1.0.0","identifier":"abcd1234ef567890"}'
curl -N -H 'Accept: text/event-stream' -H 'Cache-Control: no-cache' $B/devices/auth-code/$CODE/events
curl "${H[@]}" -X POST $B/devices/auth/establish -d '{"access":"<sse token.access>","deviceId":"<sse deviceId>"}'
curl "${H[@]}" -H "Authorization: JWT $ACCESS" $B/devices/status
curl "${H[@]}" -X POST $B/devices/auth/refresh -d "{\"refresh\":\"$REFRESH\"}"
```

## Uncertainties
- The SSE event name for auth-code approval is not checked by the client; any event whose data has `token.access` triggers establish.
- `expiresAt` is assumed to be epoch milliseconds, because it is subtracted from `Date.now()`.
- Whether the server enforces a specific minimum `appBuildNumber` or `os` value at pairing is unknown. Get real values from the APK (`versionCode` / `versionName`) to be safe.
- `dispatchDeviceUpdate` (382533) failed to decompile; the event handling above comes from the switch inside `useDeviceUpdates`.
- The last-active comparison direction (`elapsed >= 300000`) is inferred from the constant names; the control flow was not fully traced.
