# SPASS to CSV Converter

An Android app that converts Samsung Pass `.spass` export files into standard CSV,
entirely on your device.

Samsung Pass can export your saved logins, cards, addresses and notes, but only into an
encrypted `.spass` file that nothing else can read. This app decrypts that file with the
password you chose during the export and writes a plain CSV you can import into Google
Password Manager, Chrome, Bitwarden, 1Password, KeePass, or a spreadsheet.

## Offline and privacy guarantee

**Your vault never leaves your phone.**

- The app declares **no permissions at all** — including no `INTERNET` permission. It is
  not merely "configured" not to call home; Android will not let it open a socket.
- There is no analytics, advertising, telemetry, crash reporting, login, cloud storage or
  remote API, and no library that provides any of them. The only dependencies are
  AndroidX/Jetpack Compose for the UI.
- It works with **airplane mode on**. That is the recommended way to run it.
- Decryption and parsing happen in memory. Nothing is written to disk unless you press
  **Save CSV** or **Share**, and both go to a location you pick.
- Backup and device-to-device transfer are disabled in the manifest, so the app's data is
  never copied off the device by the system either.
- The window is marked `FLAG_SECURE`, so decrypted passwords cannot be screenshotted or
  captured in the recent-apps thumbnail.
- The conversion history stores **metadata only** — file names, row and column counts, and
  timestamps. No credential, no field value, no file path. You can clear it at any time.

The build enforces the first point: `assembleRelease` and `assembleDebug` are followed by
a `verifyNoNetworkPermissions` task that fails the build if `INTERNET`,
`ACCESS_NETWORK_STATE` or `ACCESS_WIFI_STATE` appears in the merged manifest — including
one pulled in by a dependency.

> Treat the CSV it produces as you would a plaintext password list: it is not encrypted.
> Import it where you need it, then delete it.

## Supported input format

The `.spass` file Samsung Pass writes when you choose **Settings → Export data**.

The container is a single Base64 blob. Its bytes are:

```
+-----------------+-----------------+-----------------------------+
| salt (20 bytes) | IV    (16 bytes)| AES-256-CBC ciphertext ...  |
+-----------------+-----------------+-----------------------------+
```

The key is `PBKDF2-HMAC-SHA256(password, salt, 70000 iterations, 32 bytes)`, and the
ciphertext is PKCS#7-padded. Decrypted, the payload is a semicolon-separated text file:

```
25                       <- export format version
true;false;false;true    <- which modules were exported: passwords;cards;addresses;notes
false                    <- present from format v25; meaning unknown
next_table               <- separates one table from the next
id;origin_url;...        <- header row, plain text
MQ==;aHR0cHM6...         <- data rows, every field Base64-encoded
next_table
id;note_title;...
...
```

The app reads all four table types (passwords, cards, addresses, notes) and identifies
each from its header row rather than its position, so partial exports work correctly.
Samsung's `&&&NULL&&&` placeholder becomes an empty CSV field.

An already-decrypted payload (produced by another tool) is also accepted, and needs no
password.

### Robustness

The parser is written for real files, not ideal ones. It handles quoted values, embedded
commas, semicolons, tabs and newlines, `\r\n` / `\n` / `\r` line endings mixed in one
file, empty fields, and full Unicode including emoji. Malformed input never crashes the
app: a short row is padded, a long row keeps its extra values in `extra_column_N`, a field
that is not valid Base64 is kept verbatim, and each such event is reported on screen as a
numbered warning with its line. Nothing is silently discarded.

## Build

Requires JDK 17 or newer and the Android SDK (compileSdk 35). Everything else is
downloaded by Gradle.

```bash
./gradlew assembleRelease
```

The APK lands at `app/build/outputs/apk/release/app-release.apk`.

`assembleRelease` produces an installable APK out of the box: with no signing
configuration present it falls back to the standard debug key. **Do not distribute such a
build.** To sign properly, create `keystore.properties` in the repository root:

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

That file and `*.jks` / `*.keystore` are git-ignored, so signing material is never
committed.

Code shrinking is off for release builds (the app is small and uses no reflection). To
turn it on, set `isMinifyEnabled = true` in `app/build.gradle.kts`; keep rules are already
in `app/proguard-rules.pro`.

## Install the APK

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Or copy the APK to the phone and open it, allowing installation from unknown sources when
prompted.

## Use the app

1. In Samsung Pass, choose **Settings → Export data** and set an export password.
2. Turn on airplane mode, if you want to see the offline guarantee for yourself.
3. Open **SPASS to CSV Converter** and tap **Select SPASS File**. The Android system file
   picker opens; choose your `.spass` file.
4. Enter the export password from step 1.
5. Check the preview: row and column counts, the column names, and the first 50 rows.
   Password and card-number columns are masked until you tap **Reveal secrets**.
6. If the export contains more than one table, pick which one to convert with the chips
   above the preview. Each table becomes its own CSV.
7. Tap **Save CSV** to choose where to write it, or **Share** to hand it to another app
   through the Android share sheet.

Reading and writing both go through the Storage Access Framework
(`ACTION_OPEN_DOCUMENT` / `ACTION_CREATE_DOCUMENT`), so the app needs no storage
permission and never uses a hardcoded path.

## Testing

```bash
./gradlew :core:test
```

All conversion logic lives in the `:core` module, which is a plain Kotlin/JVM library with
no Android dependency, so the suite runs on any machine — no emulator or Android SDK
needed:

```bash
./gradlew :core:test --configure-on-demand
```

73 tests cover the decryptor, the delimited-text reader, the CSV writer and the end-to-end
converter: normal, empty, single-row and multi-row exports; empty fields; Unicode; commas,
semicolons, quotes and newlines inside values; missing and surplus values; all three line
endings; malformed and non-`.spass` input; and a 20,000-row export.

Two things are checked from outside the project, so the tests cannot merely agree with
themselves:

- `CryptoKnownAnswerTest` pins PBKDF2-HMAC-SHA256 to the RFC 7914 §11 vectors and AES-256-CBC
  to NIST SP 800-38A §F.2.5, and cross-checks the key derivation against the JDK's own
  `PBKDF2WithHmacSHA256`.
- `SampleFileTest` converts `samples/sample.spass`, which was encrypted by Python's
  `hashlib` and the `openssl` command line rather than by this code.

`samples/sample.spass` (password: `sample-password`) contains invented credentials only.
Use it to try the app without touching your real vault.

## Project layout

| Path | What it is |
| --- | --- |
| `core/` | Pure Kotlin/JVM: decryption, parsing, CSV generation, and all tests |
| `app/` | Android app: Jetpack Compose UI, Storage Access Framework I/O, history |
| `samples/` | A sample `.spass` file with fake data |

## Limitations and assumptions

- Samsung does not publish the `.spass` format. The implementation follows the
  community-reverse-engineered structure described above, which several independent open
  source tools agree on, and is verified end to end against a file encrypted by unrelated
  software. A future Samsung Pass release could change it.
- Format version 25 is what the sample and the public descriptions cover. Other versions
  should still parse — the version line is read but not enforced, and tables are matched by
  their headers — but they are untested.
- The third preamble line is preserved and reported but its meaning is unknown.
- Fields Samsung itself stores encrypted (`pw_tz_enc`, `id_tz_enc`,
  `card_number_encrypted`) pass through as stored. They are protected by the phone's
  TrustZone keystore, not by the export password, so this app cannot decrypt them and does
  not try.
- CSV holds one table, so a multi-table export becomes several CSVs, one per table, chosen
  from the preview.
- Column names are Samsung's own (`origin_url`, `username_value`, `password_value`, …).
  Importers generally let you map columns; the app does not reshape data into any
  particular importer's schema, so nothing is dropped in translation.
- Files up to 64 MB are accepted, far above any realistic export.

## License

MIT — see [LICENSE](LICENSE).
