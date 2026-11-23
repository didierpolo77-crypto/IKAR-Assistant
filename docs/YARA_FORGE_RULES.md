# YARA Forge rules in IKAR

## Source and package

IKAR uses the **Core** package published by [YARA Forge](https://github.com/YARAHQ/yara-forge/releases). It is preferred over Extended and Full on 32-bit Android devices to limit memory and compilation costs. The selected package is downloaded from the latest GitHub Release asset named `yara-forge-rules-core.zip`.

YARA Forge curates and standardizes rules from vetted upstream repositories. Rule-level licenses and attribution remain those declared by the original rule sources; IKAR does not relicense those rules. The update process retains supported license and metadata sidecar files from the package alongside the rules.

## Update behavior

- A one-time update is queued when IKAR starts.
- A periodic WorkManager job checks for new releases every 24 hours when a network is available.
- The Antivirus local files card also exposes **Actualiser la base YARA Forge**.
- IKAR reads the latest release metadata over HTTPS, selects only the official Core asset, checks the declared archive size and SHA-256 digest, and rejects unexpected URLs.
- The ZIP extractor rejects path traversal, oversized entries, excessive file counts, and expanded archives larger than 40 MiB.
- Only YARA rule files and documentation/license metadata are extracted.
- The candidate rule directory is compiled with the bundled official libyara JNI engine before it can replace the active database.
- Activation uses a staging directory and a previous-directory rollback. An invalid or incomplete update must not replace the last working rules.
- The active rules remain in app-private storage and can be used offline after download.

## Scanner behavior

- Official libyara 4.5.0 ARMv7 is the primary scanner when its JNI library loads.
- The native engine compiles the active rule directory once per rules generation and reuses the compiled rules for subsequent file scans.
- If libyara is unavailable, the app explicitly reports that it is using the limited Kotlin YARA-compatible fallback; it does not label that fallback as official YARA.
- File scanning remains read-only. A YARA match is an indicator that requires review, not proof by itself that a file is malicious.
- The existing 16 MiB YARA file-scan limit and 20-second native scan timeout remain in place for ARM32 safety.
- The app does not inspect all private files of other apps on non-rooted Android devices.

## Important limitations

YARA is a rule-matching engine, not a complete antivirus database or a guarantee that every malware family will be detected. Core package updates need an Internet connection; the last successfully installed rule set remains available offline. YARA rules complement, but do not replace, SHA-256 threat-intelligence feeds or a separately integrated antivirus engine. **ClamAV is not bundled or claimed to run in IKAR.**

## CI validation

The Android workflow downloads the current Core package, verifies its SHA-256 digest and size, and compiles the extracted rule files with YARA 4.5.0 built with the same `--without-crypto` configuration used for Android. The APK workflow separately verifies that `lib/armeabi-v7a/libikar_yara.so` is present and is an ARM library.

A successful CI build validates packaging and compilation. Runtime execution on the user's physical Android device is still required before claiming device-level runtime validation.
