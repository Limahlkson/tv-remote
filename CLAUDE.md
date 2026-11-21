# TV Remote: handover

Dave's Android app (vivo iQOO 12 Pro, Android 16, has IR blaster) that replaces two apps:
Amazon's Fire TV remote and an IR remote for his Hisense TV. YouTube on the TV is watched in
**SmartTube**, so search and channels go to SmartTube, never the YouTube app.

## How Dave likes to work
- Wants a finished APK each round: push to `main`, GitHub Actions builds it and publishes a Release
  `v1.0.<run>`, download the asset and send it. Use `[skip ci]` for doc-only commits.
- Direct, practical answers. Never use em dashes in anything written for him.
- **No fallbacks.** If an action fails, show "Failed: <reason>" (the `failed()` helper). Don't quietly
  try another method; he finds it jarring.
- **Nothing may push the layout around.** Status messages go in the header bar over the Fire TV name
  (`showStatus` / `showVoiceBanner` green, `failed` red, TV-menu amber, `flash` short green).
- When a TV-side route keeps failing, ask for **his manual remote sequence** and implement exactly
  that. His sequences have worked every time; my guesses often didn't.
- Last version sent: **v1.0.25**. Comments button confirmed working in v1.0.24.
- v1.0.25 added (not yet confirmed by Dave): header status bar, new reorder, tile colours, swap button,
  share-to-add errors now visible (they were hidden by the 1.5 s toast throttle).

## Build
- No Android SDK in the sandbox: build only on CI (`.github/workflows/build.yml`). CI downloads the
  Vosk model (sha256-checked) into `app/src/main/assets/vosk-model-en`. APK is ~59 MB.
- Local compile check: `javac` against an `android.jar` with stub `R` and `org.vosk` classes. Get the jar
  with `git clone --depth 1 --filter=blob:none --sparse https://github.com/Sable/android-platforms` then
  `git sparse-checkout set --no-cone /android-34/android.jar` (plain downloads are blocked).
- Signed with `app/tvremote.keystore` (password `tvremote`) so updates install over the old app.

## Files (`app/src/main/java/com/dave/tvremote/`)
- `MainActivity.java`: all UI, settings menu (⋮), pairing, voice, channels, edit mode, ADB actions.
- `FireTvClient.java`: Fire TV local API (`https://IP:8080`, API key `0987654321`, PIN pairing,
  DIAL wake on `:8009`), keep-alive connections, macros (`submitMacro`, `step`, `stepSelect`).
- `IrRemote.java`: Hisense NEC IR codes, set A (`00 BF`, default) and set B (`04`).
- `AdbClient.java`: minimal ADB over Wi-Fi (port 5555, RSA key in prefs). Dave has ADB on.
- `VoiceEngine.java`: offline hold-to-talk speech (Vosk). The phone's own speech service failed (error 5).
- `YouTubeAccount.java`: Google device sign-in + YouTube Data API subscriptions (needs his own client ID;
  he had to add himself as a test user).
- `TvScreen.java`: uiautomator screen reading. No longer used by comments (it was unreliable).
- `Widgets.java`, `Icons.java`, `Discovery.java`.

## What each control does now
- Top: sleep, Fire TV name (tap to pair), ⋮ settings. Media / Apps / Channels tabs (compact).
- Pad: trackpad or D-pad. Corners: keyboard (top-left), mute (top-right). Right column: mic, comments.
  Bottom-right: Home with rewind / play-pause / fast-forward arc, **Back above fast forward**.
- Bottom row: Menu, Settings, Input/AV, TV power. CH and VOL rockers (IR) above it.
- **Mic tap** (ADB on): opens `https://www.youtube.com/tv?launch=voice` in SmartTube (search, cursor in
  box). Without ADB: Back, Up, Select, Select.
- **Mic hold**: offline speech, then (ADB on) opens `youtube.com/results?search_query=...` in SmartTube,
  else types into the open box + Select.
- Every link sent via ADB also auto-closes SmartTube's account picker (`AppDialogActivity`, Back).
- **Comments** (Dave's route): check `PlaybackActivity` in front, ADB `input keyevent`: Down, Left x9,
  Right x(pos-1, default pos 8), Up, Up in one burst; then the final OK via the Fire TV API `select`
  (ADB DPAD_CENTER did not press it). Confirmed working in v1.0.24.
- **Settings button**: `am start -a android.settings.SETTINGS` over ADB.
- **Keyboard**: live typing; letters sent as you type, deletes via ADB backspace (67), Enter = Select.
- **Channels tab**: hold the tab 5 s for the favourites list. Sources: share a channel/video from the
  YouTube app to TV Remote (no setup), add by name, YouTube sign-in, or Takeout file. Tiles open
  the channel directly in SmartTube via ADB link (`/channel/UC...` or `/@handle`).
- **Hold any Apps/Channels tile**: wiggle edit mode. Own drag code (not Android drag-and-drop, which
  lost tiles): the picked tile follows the finger, others slide, row auto-scrolls at the edges, order
  saved on each drop. Done sits over the Fire TV name. **Double tap a tile** in edit mode: colour picker
  (saved in `colours_order_apps` / `colours_order_channels`; app banners hide while a colour is set).
- **Swap button** between CH and VOL: mirrors every pad button left/right (pref `mirrored`).
- Phone volume keys change TV volume by IR while the app is open.

## Open ideas Dave raised
- Ideas list offered: Now Playing card, notification/lock-screen controls + widget, IR sleep timer,
  Pebble controls, settings backup. He picked live typing (done).
- **Modified SmartTube** (MIT licensed): add a built-in remote-control API, keep the patch small,
  daily GitHub Action to pull upstream fixes, rebuild and install on the Fire Stick. Waiting on Dave
  to fork github.com/yuliskov/SmartTube into his account. Alternative: small helper app on the TV.
