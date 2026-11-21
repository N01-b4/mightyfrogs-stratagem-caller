# Contributing

1. Keep changes focused and compatible with Android 11 and Windows 11.
2. Run Android unit tests and release build:
   ```bat
   gradlew.bat testDebugUnitTest assembleRelease
   ```
3. If the receiver changes, publish it and run `scripts\test_receiver.ps1`.
4. If catalog data changes, run both catalog refresh and validation scripts and review the generated diff.
5. Include reproduction details for input or connection fixes. Never include pairing secrets, personal network details, or private logs.

By submitting a contribution, you agree that it may be distributed under the repository's MIT License. Branding assets are excluded from that license.
