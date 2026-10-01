# Release process

1. Update the version in app/build.gradle.
2. Update CHANGELOG.md.
3. Run ./gradlew assembleDebug.
4. Install and verify on a parked test vehicle.
5. Confirm N/A behavior and fuel fallback behavior.
6. Create a Git tag using vX.Y.Z.
7. Create a GitHub Release and attach the APK there, not in the source tree.
8. Record the APK SHA-256 in the release notes.
9. Never upload a keystore or signing password.
