# BlueChat

BlueChat is an Android peer-to-peer chat application that transmits end-to-end encrypted messages over **Bluetooth Low Energy (BLE) advertising packets** — no cellular, no Wi-Fi, no internet, no central server, no account, no phone number. Two devices running BlueChat with the same shared passphrase can exchange messages as long as they are within BLE range of one another (roughly 10–100 meters depending on hardware and environment).

The app is written in **Kotlin** and built on **Jetpack Compose** with a **Material 3** design system themed in a strict black-and-gray palette.

---

## Table of Contents

1. [How It Works](#how-it-works)
2. [Feature Overview](#feature-overview)
3. [Cryptography](#cryptography)
4. [BLE Transport Layer](#ble-transport-layer)
5. [Message Chunking Protocol](#message-chunking-protocol)
6. [Project Structure](#project-structure)
7. [Architecture](#architecture)
8. [Permissions](#permissions)
9. [Theming](#theming)
10. [Logging & Observability](#logging--observability)
11. [Build & Run](#build--run)
12. [Limitations & Known Constraints](#limitations--known-constraints)
13. [Security Considerations](#security-considerations)
14. [Troubleshooting](#troubleshooting)

---

## How It Works

BlueChat does **not** use a Bluetooth GATT connection. Instead, it piggy-backs message payloads onto BLE **advertising packets** — the same broadcast frames used for beacons, pairing announcements, and proximity services.

The high-level flow when sending a message:

1. The user types a message and hits send.
2. The plaintext is encrypted with AES-256-CBC using a key derived from the shared passphrase.
3. The ciphertext (Base64-encoded) is split into tiny chunks that fit inside a BLE advertising frame's `service data` field.
4. Each chunk is broadcast sequentially as a short-lived advertisement.
5. Other BlueChat devices scanning for the BlueChat service UUID pick up the advertisements, reassemble the chunks by message ID, decrypt the result, and display it.

Because advertising is one-way and connectionless, there is no handshake, no pairing, no delivery confirmation, and no back-channel. Every device within range that shares the passphrase will receive the message.

---

## Feature Overview

- **Connectionless peer-to-peer chat** over BLE advertising frames
- **AES-256-CBC encryption** with a passphrase-derived symmetric key
- **Automatic chunking and reassembly** for messages larger than a single advertising packet
- **Jetpack Compose UI** with real-time message list and status indicator
- **Settings dialog** for changing the shared passphrase at runtime
- **Strict dark-only theme** — black background, gray surfaces, no color accents
- **Structured logging** via `CodeFlowLogger` for remote diagnostics
- **Runtime permission handling** for Android 12+ BLE permission model

---

## Cryptography

All message encryption is handled by `com.codeflow.bluechat.crypto.MessageEncryption`.

| Property            | Value                                                           |
| ------------------- | --------------------------------------------------------------- |
| Algorithm           | AES-256-CBC with PKCS5 padding                                  |
| Key derivation      | SHA-256 of the UTF-8 bytes of the user's passphrase             |
| IV                  | 16 random bytes, generated fresh per message by `Cipher`        |
| On-wire format      | `Base64(IV ‖ ciphertext)` using `Base64.NO_WRAP`                |
| Default passphrase  | `BlueChat2026` (change this in Settings before using seriously) |

Both peers must agree on the passphrase out-of-band. There is no key exchange — BlueChat treats the passphrase as a pre-shared secret.

---

## BLE Transport Layer

Transport is implemented by two peer classes:

- `com.codeflow.bluechat.ble.BleAdvertiser` — starts and stops `BluetoothLeAdvertiser` sessions, one per chunk
- `com.codeflow.bluechat.ble.BleScanner` — runs a `BluetoothLeScanner` filtered on the BlueChat service UUID

### Service UUID

The app uses a single 16-bit Bluetooth service UUID, expanded against the Bluetooth SIG base UUID:

```
0000fff0-0000-1000-8000-00805f9b34fb   // short form: 0xFFF0
```

### Advertising Settings

Each chunk is advertised with:

- Mode: `ADVERTISE_MODE_LOW_LATENCY`
- TX power: `ADVERTISE_TX_POWER_HIGH`
- Connectable: `false` (pure broadcast)
- Timeout: `0` (we stop it manually)

Each chunk stays on-air for ~500 ms, then the advertiser is explicitly stopped before the next chunk starts. This is important — starting a new advertisement while another is already active returns `ADVERTISE_FAILED_ALREADY_STARTED`.

### Scanning Settings

Scans run with:

- Mode: `SCAN_MODE_LOW_LATENCY`
- Report delay: `0` (real-time callbacks)
- Filter: `ScanFilter` matching the BlueChat service UUID
- A background cleanup job runs every 60 seconds to drop incomplete message buffers older than 5 minutes.

Scanning automatically starts on `onResume()` and stops on `onPause()` to save battery.

---

## Message Chunking Protocol

BLE legacy advertising packets are limited to **31 bytes total**, of which the service UUID and headers consume a chunk. `com.codeflow.bluechat.ble.MessageChunker` splits messages into fixed-size fragments, each with a 6-byte header.

### Per-chunk wire format

```
+--------+--------+--------+--------+--------+--------+--------+...
| MSG_ID | MSG_ID | MSG_ID | MSG_ID |  IDX   | TOTAL  |  DATA  |
| byte0  | byte1  | byte2  | byte3  | 1 byte | 1 byte |  ≤12B  |
+--------+--------+--------+--------+--------+--------+--------+...
```

| Field    | Size    | Purpose                                                         |
| -------- | ------- | --------------------------------------------------------------- |
| MSG_ID   | 4 bytes | `contentHashCode()` of the encrypted payload — groups chunks    |
| IDX      | 1 byte  | Zero-based chunk index (0–254)                                  |
| TOTAL    | 1 byte  | Total number of chunks in this message                          |
| DATA     | ≤12 B   | Slice of the encrypted payload                                  |

A message is therefore capped at `255 × 12 = 3060` encrypted bytes. After Base64 and AES overhead this translates to roughly **~2 KB of plaintext** in the worst case — plenty for chat-sized messages.

### Reassembly

The scanner side of `MessageChunker`:

1. Indexes incoming chunks by `MSG_ID` in a `ConcurrentHashMap`
2. Tracks how many of the expected `TOTAL` chunks have arrived
3. Assembles the full payload once all indices are present
4. Deletes the buffer immediately after reassembly
5. Periodically evicts incomplete message buffers older than 5 minutes

---

## Project Structure

```
app/src/main/
├── AndroidManifest.xml                 # Permissions + activity + custom theme
├── kotlin/com/codeflow/bluechat/
│   ├── CodeFlowApplication.kt          # Application class (logger init)
│   ├── CodeFlowLogger.kt               # Structured logging facade
│   ├── ComposeActivity.kt              # Single activity, hosts Compose UI
│   ├── ble/
│   │   ├── BleAdvertiser.kt            # Broadcasts encrypted chunks
│   │   ├── BleScanner.kt               # Receives & reassembles chunks
│   │   └── MessageChunker.kt           # Splits / reassembles messages
│   ├── crypto/
│   │   └── MessageEncryption.kt        # AES-256-CBC + SHA-256 key derivation
│   ├── model/
│   │   └── ChatMessage.kt              # UI message + BLE status enum
│   ├── ui/
│   │   ├── ChatScreen.kt               # Main chat UI
│   │   ├── SettingsDialog.kt           # Passphrase editor
│   │   └── theme/
│   │       ├── Color.kt                # Black/gray palette
│   │       ├── Theme.kt                # Material3 DarkColorScheme
│   │       └── Type.kt                 # Typography
│   └── viewmodel/
│       └── ChatViewModel.kt            # State, send/receive orchestration
└── res/values/
    ├── strings.xml
    └── themes.xml                      # Theme.BlueChat — black status/nav bars
```

---

## Architecture

BlueChat follows a single-activity, MVVM-ish layout.

```
┌────────────────────────────────────────────────────────────────┐
│                      ComposeActivity                           │
│          (permissions, lifecycle, Compose entrypoint)          │
└────────────────────────────────────────────────────────────────┘
                          │ observes
                          ▼
┌────────────────────────────────────────────────────────────────┐
│                      ChatViewModel                             │
│  StateFlows: messages, bleStatus, errorMessage, passphrase     │
│  Actions:    sendMessage(), startScanning(), stopScanning(),   │
│              updatePassphrase(), clearError(), clearMessages() │
└────────────────────────────────────────────────────────────────┘
          │                                        ▲
 broadcasts via                               receives via
          ▼                                        │
┌────────────────────────┐              ┌────────────────────────┐
│     BleAdvertiser      │              │      BleScanner        │
│ encrypt → chunk → adv  │              │ scan → parse → reasm.  │
└────────────────────────┘              └────────────────────────┘
          │                                        ▲
          ▼                                        │
┌────────────────────────┐              ┌────────────────────────┐
│  MessageEncryption     │              │  MessageEncryption     │
│       (encrypt)        │              │       (decrypt)        │
└────────────────────────┘              └────────────────────────┘
          │                                        ▲
          ▼                                        │
┌────────────────────────┐              ┌────────────────────────┐
│     MessageChunker     │──── BLE ────▶│     MessageChunker     │
│       (chunk)          │    radio     │      (reassemble)      │
└────────────────────────┘              └────────────────────────┘
```

`ChatViewModel` owns the single source of truth. The UI is fully stateless and renders `StateFlow`s via `collectAsStateWithLifecycle()`.

---

## Permissions

BlueChat requests the BLE permissions appropriate for the running Android version.

**All API levels**

- `INTERNET` — for remote log upload only
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` — required on pre-Android-12 to receive BLE scan results

**Android 11 (API 30) and below**

- `BLUETOOTH`
- `BLUETOOTH_ADMIN`

**Android 12 (API 31) and above**

- `BLUETOOTH_SCAN`
- `BLUETOOTH_ADVERTISE`
- `BLUETOOTH_CONNECT`

The manifest also declares `<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />`, so Google Play will hide the app from devices without BLE radios.

Permissions are requested at first launch via `ActivityResultContracts.RequestMultiplePermissions`. If any BLE permission is denied, advertising and scanning silently fail and an error message surfaces via `errorMessage`.

---

## Theming

The UI is locked to a monochrome dark palette:

| Token           | Hex        |
| --------------- | ---------- |
| Black           | `#000000`  |
| DarkGray        | `#121212`  |
| MediumGray      | `#1E1E1E`  |
| LightGray       | `#2A2A2A`  |
| CharcoalGray    | `#303030`  |
| Gray            | `#424242`  |
| SlateGray       | `#616161`  |
| Silver          | `#757575`  |
| LightSilver     | `#9E9E9E`  |
| White           | `#E0E0E0`  |
| BrightWhite     | `#FFFFFF`  |

The custom `Theme.BlueChat` style (defined in `res/values/themes.xml`) pins the system status bar, navigation bar, and window background to pure black so no Material3 primary color bleeds into system chrome.

---

## Logging & Observability

All logging is routed through `com.codeflow.bluechat.CodeFlowLogger`. No `android.util.Log`, `println`, or `Timber` is allowed anywhere in the codebase.

API:

```kotlin
CodeFlowLogger.debug(tag, message, metadata)
CodeFlowLogger.info(tag, message, metadata)
CodeFlowLogger.warning(tag, message, metadata)
CodeFlowLogger.error(tag, message, throwable, metadata)
CodeFlowLogger.fatal(tag, message, throwable, metadata)
```

Logs are batched locally and uploaded to the CodeFlow logging API in batches of 1000. Uploads are triggered automatically on lifecycle transitions, on memory pressure, and when the buffer fills. Errors and fatals trigger immediate uploads.

---

## Build & Run

> **Note:** Per the project's `CLAUDE.md`, automated agents should not run `./gradlew build` locally — CI handles build validation.

Human developers can build and install normally:

```bash
./gradlew assembleDebug
./gradlew installDebug
```

Requirements:

- JDK 17+
- Android SDK with API 34 platform
- Two physical Android devices with BLE (emulators cannot advertise)
- Both devices on the same passphrase

**Testing end-to-end:**

1. Install the APK on two Android 12+ devices.
2. Grant all Bluetooth and Location permissions when prompted.
3. Ensure both devices have Bluetooth turned on and are within ~10 meters.
4. Confirm both devices display the same passphrase in the Settings dialog.
5. Send a message from one; it should appear on the other within a second or two.

---

## Limitations & Known Constraints

- **Legacy advertising only.** Extended advertising (Android 8+, hardware-dependent) is not used, so payload per frame is capped at 31 bytes total and we must chunk aggressively.
- **No delivery guarantees.** Advertising is connectionless and unacknowledged. If a chunk is missed because of radio collision or range, the whole message is lost.
- **No ordering across messages.** Multiple simultaneous senders can interleave chunks; reassembly relies on the `MSG_ID` hash to keep them separate, but collisions are theoretically possible.
- **Hardware support varies.** Not every Android device supports BLE peripheral/advertising mode — the app checks `isMultipleAdvertisementSupported` and surfaces a user-visible error when unsupported.
- **Emulator does not work.** Advertising requires real Bluetooth hardware.
- **Range.** Real-world range is typically 10–30 meters indoors.
- **Battery.** Low-latency scanning is power-hungry; the app stops scanning on `onPause()` to limit drain.
- **No forward secrecy.** All messages under the same passphrase use the same key; compromise of the passphrase decrypts all past and future traffic.

---

## Security Considerations

BlueChat is a hobby/demo app. It is **not** audited, and should not be used for anything that actually needs to stay secret. Specific caveats:

- **Pre-shared key only.** No Diffie-Hellman, no ratcheting, no forward secrecy.
- **No authentication.** Anyone with the passphrase can impersonate anyone else — there is no notion of identity.
- **No replay protection.** An attacker in range can record advertising packets and replay them verbatim.
- **Metadata leaks.** The BlueChat service UUID is advertised in the clear. Anyone scanning nearby can tell that BlueChat is in use, and how often. Message lengths are also observable.
- **CBC mode.** AES-CBC is vulnerable to padding oracle attacks in some contexts; BlueChat does not authenticate ciphertexts, so a more mature design would use AES-GCM with an AAD.
- **Passphrases are strings.** Weak passphrases can be brute-forced offline by anyone who captures traffic.

If you want a real secure messenger, use Signal.

---

## Troubleshooting

**"Failed to send message via BLE"**

- Ensure Bluetooth is turned on on both devices.
- Ensure all BLE permissions have been granted (Settings → Apps → BlueChat → Permissions).
- Confirm the device supports BLE advertising — many older or budget phones do not. Check the logs for `"BLE advertising not supported on this device"`.
- Check that advertising is being stopped between chunks. The app explicitly calls `stopAdvertising()` before each `startAdvertising()` to avoid `ADVERTISE_FAILED_ALREADY_STARTED`.

**Messages not arriving on the other device**

- Confirm both devices are using the same passphrase. The Settings dialog is how you change it.
- Confirm the receiver has granted `BLUETOOTH_SCAN` and location permissions.
- Bring the devices closer — BLE advertising range is limited, especially through walls.
- Check the receiver's logs for `"No service data in scan result"` — if you see this often, chunks are being dropped.

**Purple status bar**

- Already fixed: the app ships with a custom `Theme.BlueChat` that pins status/navigation bars to black. If you still see purple, make sure the manifest's `android:theme` attribute points at `@style/Theme.BlueChat`, not `@style/Theme.Material3.Dark`.

**Scanning never starts**

- Location services must be enabled at the OS level on pre-Android-12. BLE scanning silently returns no results otherwise.
