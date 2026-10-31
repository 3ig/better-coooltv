# Changelog

All notable changes to Coool TV Lite. The format follows [Keep a Changelog](https://keepachangelog.com/),
and versions follow [Semantic Versioning](https://semver.org/).

Each `vX.Y.Z` tag publishes a GitHub Release whose notes are the matching section below, and the
app's built-in updater shows the same notes.

## [Unreleased]

## [0.3.0] - 2026-10-31

### Added
- Classic player (Settings → Player): the old Coool TV app's player layout and remote keys, in
  the app's own colours. OK shows the progress bar; Down shows it with the strip of channel tiles
  and the programme row, which goes back up to 36 hours on catch-up channels. Up shows the
  previous channel with "Swap back", Left/Right rewind and forward in 10 s steps (faster when
  held), hold OK for the guide. Home and the guide are unchanged.

### Fixed
- Crash when pressing Right in the home channel list after scrolling the category row.

## [0.2.3] - 2026-10-31

### Fixed
- Channels failing with an error right after the TV wakes from standby: the player now quietly
  retries while the network comes back, or when a new session's first request is refused
  ("Connecting…").
- In-app updates on Android 7.0, which didn't trust GitHub's download server.
- Update dialog no longer shows the GitHub page's install instructions.

## [0.2.2] - 2026-10-31

### Fixed
- TV screensavers (e.g. on TCL) no longer start while a channel is playing; they still can when paused.

## [0.2.1] - 2026-10-31

### Fixed
- Guide button on remotes that send a vendor key code instead of the standard one (e.g. TCL).
- Update dialog: changelog lines that wrap in the source are shown as one bullet.

## [0.2.0] - 2026-10-30

First public release.

### Added
- Pairing with a code or QR at coool.tv/device/add; profiles with PIN; sign out.
- Live TV including 4K HEVC 10-bit, with a stream-info line and a 4K capability check.
- Catch-up / timeshift: rewind live TV, replay past programmes from the guide, resume where you left off,
  pause live TV. Channels you can watch back are marked with a replay icon and have their own filter.
- Programme guide grid, also as a semi-transparent overlay over playing video.
- Home screen with categories, favourites (hold OK), what's on now and next.
- Translucent channel list over video (Up/Down/OK); CH+/CH− and number keys to switch channels.
- Audio and subtitle selection per channel; picture Fit / Fill / Stretch.
- Bulgarian and English, following the TV's language with a switch in Settings.
- Selectable main colour (coool green, mint, sky, lavender, coral, amber, rose, silver).
- In-app updates from GitHub Releases.

[Unreleased]: https://github.com/3ig/better-coooltv/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/3ig/better-coooltv/releases/tag/v0.3.0
[0.2.3]: https://github.com/3ig/better-coooltv/releases/tag/v0.2.3
[0.2.2]: https://github.com/3ig/better-coooltv/releases/tag/v0.2.2
[0.2.1]: https://github.com/3ig/better-coooltv/releases/tag/v0.2.1
[0.2.0]: https://github.com/3ig/better-coooltv/releases/tag/v0.2.0
