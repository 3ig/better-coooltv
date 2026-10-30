# better-coooltv — rebuild plan

Lightweight native replacement for Coool TV 2.0.13 (React Native/Hermes). Specs from reverse engineering:
[auth & pairing](re/spec_auth.md) · [channels, EPG, playback](re/spec_content.md) · [app structure & performance](re/spec_app.md).

## Stack
- Kotlin, Jetpack Compose (+ androidx.tv where useful), minSdk 24 (same as original).
- Media3 ExoPlayer (HLS) on a `SurfaceView`.
- OkHttp + kotlinx.serialization; OkHttp SSE for pairing / device events.
- Room (or a flat file) for the EPG cache; DataStore for tokens and preferences.
- No DI framework, no image library beyond Coil with explicit size hints.
- R8 full mode, Baseline Profiles. Build armeabi-v7a + arm64-v8a.

## 4K channels (hard requirement)
4K channels must play at native resolution. The original does nothing special here (react-native-video defaults), so this is ours to get right:
- **SurfaceView only** — hardware decoder renders straight to the display plane; TextureView would copy 4K frames through the GPU and drop frames on weak boxes. Also required for HDR.
- **Don't let the track selector cap at 1080p** — many boxes render their UI at 1080p while outputting 4K. Size the viewport from the physical display mode (`Display.Mode` / `Util.getCurrentDisplayModeSize`, which reads the TV vendor props), not the UI size.
- **Decoder capability check** — only offer 2160p variants the device can decode in hardware (HEVC/VP9/AV1 profile + level via `MediaCodecInfo`); fall back to the best supported variant instead of failing.
- **Display mode switching** — on Android 11+ request a matching display mode / frame rate (`Surface.setFrameRate`, `preferredDisplayModeId`) so 4K/50p content isn't scaled or judders on boxes sitting at 1080p60.
- **HDR** — pass HDR10/HLG through untouched; never draw the overlay in a way that forces composition of the video layer.
- **Buffers** — bound the ExoPlayer buffer in bytes, not just time, so a 25–40 Mbps 4K stream doesn't blow the heap on 1–2 GB devices.
- **Overlay over 4K video** — overlay stays lightweight (no full-screen blur/gradients) so the GPU isn't fighting the video plane.
- **Testing** — the emulator can't validate 4K decode; verify on at least one real 4K box. A debug info panel (codec, resolution, bitrate, dropped frames) ships from milestone 4.

## What we deliberately drop vs. the original
| Original | Ours |
|---|---|
| One POST to `/debug/message` per log line | No remote logging (maybe batched errors only, opt-in) |
| 1 s ticker re-rendering the whole player tree | Time only read by the progress widget, minute-level elsewhere |
| Overlay always mounted at opacity 0 | Overlay composed only when visible |
| JS geometry focus engine | Native focus (Compose focus/D-pad) |
| Layout-property animations, gradients everywhere | Scale/border focus states, one static scrim |
| EPG held in JS memory, refetched every launch | On-disk EPG keyed by `cache-version`, refreshed on `epg_updated` |
| 10 s / 15 s / 30 s timers + SSE | Heartbeat 15 s + session last-active (server-required), SSE; nothing else |

## Milestones
1. **Skeleton** — Gradle project, theme, D-pad navigation, runs on `tv_api31` emulator.
2. **Pairing** — generate-auth-code → QR + code screen → SSE wait → establish → token storage; refresh/401 interceptor.
3. **Profiles & channels** — profile picker (+ client PIN), channel list, last channel restore.
4. **Playback** — session create, tokenized HLS, last-active/heartbeat, zapping (up/down, number entry), error screens (`MAX_ACTIVE_SESSIONS_REACHED`, `SERVICE_INACTIVE`, …).
5. **EPG** — cached guide, now/next in overlay, full guide grid (custom-drawn), catch-up via `timeshift_abs-{unix}.m3u8`.
6. **Polish** — channel preview thumbnails, favorites/categories (local), audio/subtitle selection, Baseline Profile, testing on real low-end hardware.

## Status (2026-10-30)
Milestones 1–2 done, plus a first cut of 3 and 4: pairing against production works; profiles and channel list load; live HLS playback with session/heartbeat and Up/Down zapping works on the emulator. Real response shapes are in [re/live_api_notes.md](re/live_api_notes.md), which answers the questions below. Verified on a real Philips 2021/22 UHD TV (MediaTek MT5895, Android 11, 32-bit): 4K HEVC Main10 2160p50 plays on the hardware decoder with no dropped frames; ~108 MB PSS during 4K with the 32 MB buffer cap.

Done since: number-key zapping (home + player), category filters + 4K badges, what's-on line per channel, now/next (home preview + player banner), EPG 6 h rolling window with disk cache keyed on cache-version, Main10 capability check, error card with server messages (sessions limit, inactive service).

Then: full guide grid (one focus node + one Canvas; opens from home or as an overlay over playing video), catch-up from the guide (`timeshift_abs`), pause live TV and resume from the archive, ∓30 s seek, PIN pad for protected profiles, device SSE events (confirmed format: named `event:` + JSON `data:`, `: ping` comments), EPG reworked into 3 h on-demand blocks cached on disk.

Release build (R8, debug-signed for now) on the Philips TV: **1.9 MB APK** (original 36 MB), **~0.3 s cold start**, **~70 MB PSS** during HD playback.

Next: real release signing; channel preview thumbnails (`dirname(stream)/YYYY/MM/DD/HH/mm/ss.jpg`); favourites; audio/subtitle track selection; resume position for catch-up; idle-timeout prompt; settings (language, clear cache, log out).

## Open questions (answered — see re/live_api_notes.md)
- `startTime`/`endTime` format, channel `id` type, whether logo URLs are absolute.
- SSE event envelope and the pairing-stream event name.
- Which channels are 4K and their codec/bitrate (inspect the HLS master playlists once paired).
- Whether the server validates `appBuildNumber`/`os` at pairing (we send the original's values: 2400126 / 2.0.13 / androidtv).
