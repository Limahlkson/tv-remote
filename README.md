# TV Remote

One Android app for the living room: the Fire TV Stick remote and the Hisense TV's infrared
power, volume, mute, channel and Input/AV buttons together.

Built for a vivo iQOO 12 Pro (has an IR blaster), Android 16.

## How it works

- **Fire TV** – uses the stick's own local remote API (the one Amazon's Fire TV app uses):
  `https://<ip>:8080`, self-signed cert, API key `0987654321`, PIN pairing returns a client token
  in the `description` field. Idle sticks are woken with a DIAL POST to `http://<ip>:8009/apps/FireTVRemote`
  (plus a Wake-on-LAN packet when the stick announced its MAC). Protocol notes:
  AustrianCanvasSociety/firehose-remote `docs/protocol.md`.
- **Hisense TV** – NEC infrared at 38 kHz through `ConsumerIrManager`. Two code sets
  (from Flipper IRDB Hisense captures):
  - Set A (default): NECext address `00 BF` – Power 0D, Input 12, Mute 0E, Vol+ 44, Vol- 43, Ch+ 4A, Ch- 4B,
    Up 16, Down 17, Left 19, Right 18, OK 15, Back 48, Exit 5C
  - Set B: NEC address `04` – Power 08, Input 0B, Mute 09, Vol+ 02, Vol- 03, Ch+ 00, Ch- 01,
    Up 56, Down 57, Left 58, Right 59, OK 5A, Back 04, Exit 4D
- Pressing **Input/AV** switches the pad into *TV menu mode* for 12 s (amber), so swipes, taps and
  Back go to the TV by IR to pick the source. Tap the banner or Home to leave.
- Phone volume buttons change the TV volume by IR while the app is open (can be turned off).
- **Voice search (red mic under Mute):** hold, talk, let go. Speech-to-text is offline inside the app
  (Vosk, `vosk-model-small-en-us-0.15`, downloaded and checksum-checked by CI into
  `app/src/main/assets/vosk-model-en`, unpacked to app storage on first run). The phone's own speech
  service is not used: on Dave's vivo it returned client errors (5).
- **SmartTube search:** launches SmartTube (package from ⋮ > SmartTube app, else detected in
  `/v1/FireTV/appsV2`, else known names), `dpad_left`, `dpad_up` x12 to reach the Leanback title search
  orb, select, types via `/v1/FireTV/text`, then `\n` to submit. Multi-step sequences wake the stick once,
  then retry each step individually (`submitMacro`).
- **Channels tab:** hold 5 s to tick favourite YouTube channels. Subscriptions come from a Google Takeout
  zip / subscriptions.csv (or added by name). Tapping a channel tile runs the SmartTube search for its name.
  Avatars are scraped best-effort from the public channel page (og:image).

## Build

GitHub Actions builds `app-release.apk` on every push to `main` and attaches it to a Release.
The signing key in `app/tvremote.keystore` is a personal sideload key kept in the repo so each
build installs over the previous one.

Layout: `app/src/main/java/com/dave/tvremote/`
- `MainActivity.java` – screen, pairing dialogs, settings
- `FireTvClient.java` – Fire TV API, wake, pairing, apps list
- `IrRemote.java` – Hisense IR codes and NEC encoding
- `Discovery.java` – SSDP scan for Fire TVs
- `VoiceEngine.java` – offline hold-to-talk speech recognition
- `Widgets.java`, `Icons.java` – custom drawn controls
