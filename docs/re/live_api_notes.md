# Live API observations (2026-10-30, after pairing the emulator)

Confirms / corrects the static specs in this folder.

## Pairing
- Works exactly as specced. `generate-auth-code` returns an 8-char alphanumeric `shortCode`; `expiresAt` gives a ~5 min lifetime.
- `establish` returns a UUID `deviceId` (stored as `did`).
- Sending our identity as app 2.0.13 / build 2400126 / `androidtv` is accepted.
- **Android ID is per-app since Android 8**: `adb shell settings get secure android_id` ≠ the app's ID. Using the wrong one for `POST /devices/sessions` returns 401 `DEVICE_NOT_FOUND` (which the client treats as "logged out"), so never mix IDs.

## Profiles — `GET /devices/{did}/profiles?limit=12`
Fields: `uuid, name, imageUrl, whiteImageUrl, avatar, pinCode (null), isProtected, isDefault, defaultOrder, created, modified, account, parent`.
Two default profiles on this account ("Всички", "Детски"), both unprotected. PIN logic should key off `isProtected`, not only `pinCode`.

## Channels — `GET /devices/{did}/profiles/{uuid}/channels`
176 channels, ~85 KB. Per channel:
```json
{"id":"<uuid>","number":1,"name":"БНТ1 HD","logo":"https://tvapi.coool.tv/images/channels/<id>.png?v=…",
 "stream":"http://distrb-t-02.coool.tv:8090/BNT1HD/index.m3u8","dashStream":"…/index.mpd",
 "streamTimeshift":"http://distrb-t-02.coool.tv:8090/BNT1HD","dashStreamTimeshift":"…",
 "hasTimeshift":true,"includedInPlan":true,"category":"information","resolution":"hd"}
```
- `id` is a UUID string; `number` is an int; logos are absolute URLs.
- **Not used by the original app but sent by the server:** `category` (information, science, cinema, music, entertainments, children's, news, sports) and `resolution` (`sd` 69, `hd` 101, `uhd` 6). Category filters and 4K badges come free.
- 11 channels are not in the plan; 65 have timeshift.
- Streams are plain HTTP on `distrb-t-0{1,2}.coool.tv:8090` (Flussonic-style). Cleartext is required.
- Two UHD channels (Diema Sport 2/3 4K) have `stream: null` on this plan.

## 4K
- UHD channels use `…/index.fmp4.m3u8` (fMP4 HLS).
- Love Nature 4K: **3840×2160, HEVC Main 10 (`hvc1.2.4.L153` = level 5.1), 50 fps, ~18.8 Mbps**, a single variant.
- Requirements this implies: a hardware HEVC **Main10** decoder at level 5.1, a 4K/50 display mode, and ~20 Mbps sustained.
- With one variant there is nothing to fall back to: on devices without Main10 4K decode, show a clear "needs a 4K HEVC 10-bit device" message instead of a generic failure (check `MediaCodecList` up front).
- The emulator's software `c2.android.hevc.decoder` fails with `ERROR_CODE_DECODING_FAILED`, as expected. 4K must be tested on real hardware.

## EPG — `GET /epg/filter?from=<ISO>&to=<ISO>`
- Response: `{ "<channelUuid>": [ {id:int, name, description, duration (SECONDS), realId:"", startTime (epoch ms), endTime (epoch ms)} ] }`.
- **A 6-hour window for all channels is ~600 KB**, so 7 days ±7 would be roughly 30 MB. Fetch narrow windows (now/next ±3 h at startup, more on demand) and cache on disk.
- `GET /epg/cache-version` → `{"version":60}`.

## Status — `GET /devices/status`
`{"coooltvStatus":1,"serviceStatus":1,"canWatch":true,"denialReason":null,"denialMessage":null}`
