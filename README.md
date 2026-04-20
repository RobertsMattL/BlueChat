# BlueChat

BlueChat is an Android peer-to-peer chat application that transmits end-to-end encrypted messages with encrypted **Bluetooth Low Energy (BLE) advertising packets** — no cellular, no Wi-Fi, no internet, no central server, no account, no phone number. Two devices running BlueChat with the same shared passphrase can exchange messages as long as they are within BLE range of one another. 

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

## Cryptography

All message encryption is handled by `com.codeflow.bluechat.crypto.MessageEncryption`.

| Property            | Value                                                           |
| ------------------- | --------------------------------------------------------------- |
| Algorithm           | AES-256-CBC with PKCS5 padding                                  |
| Key derivation      | SHA-256 of the UTF-8 bytes of the user's passphrase             |
| IV                  | 16 random bytes, generated fresh per message by `Cipher`        |
| On-wire format      | `Base64(IV ‖ ciphertext)` using `Base64.NO_WRAP`                |

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

Each chunk stays on-air for ~500 ms, then the advertiser is explicitly stopped before the next chunk starts. Starting a new advertisement while another is already active returns `ADVERTISE_FAILED_ALREADY_STARTED`.

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

A message is therefore capped at `255 × 12 = 3060` encrypted bytes. After Base64 and AES overhead this translates to roughly **~2 KB of plaintext** in the worst case.

### Reassembly

The scanner side of `MessageChunker`:

1. Indexes incoming chunks by `MSG_ID` in a `ConcurrentHashMap`
2. Tracks how many of the expected `TOTAL` chunks have arrived
3. Assembles the full payload once all indices are present
4. Deletes the buffer immediately after reassembly
5. Periodically evicts incomplete message buffers older than 5 minutes
