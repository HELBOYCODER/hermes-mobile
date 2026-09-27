# hermes-mobile

Minimal Android WebView client for a self-hosted [Hermes WebUI](https://github.com/nesquena/hermes-webui)
instance. Open source (MIT), no Play Store, no third-party SDK.

Not affiliated with Nous Research or with any third-party "Hermes" app.

## What it does

- Loads `BuildConfig.HERMES_URL` in a full-screen WebView
- JavaScript, DOM storage, cookies (including third-party) enabled
- External links open in the system browser
- File downloads go to the system DownloadManager
- Hardware back navigates WebView history

## Requirements

- A running Hermes WebUI reachable from the phone (public domain, LAN IP, Tailscale, or `adb reverse`)
- Android 7.0 (API 24) or newer

## Build locally

```bash
export KEYSTORE_PATH=$PWD/release.keystore
export KEYSTORE_PASSWORD=... KEY_ALIAS=hermes KEY_PASSWORD=...
./gradlew assembleRelease -PHERMES_URL=https://your-host.example.com
```

Output: `app/build/outputs/apk/release/app-release.apk`

## Build on GitHub Actions

1. Create a repo from this folder and push to `main`.
2. Generate a keystore and upload it as a secret:

   ```bash
   KEYSTORE_PASSWORD=... ./scripts/make-keystore.sh
   ```

3. Add repo secrets under *Settings -> Secrets and variables -> Actions*:
   `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, `HERMES_URL`.
4. Run the *Build APK* workflow (push to `main`, or *Actions -> Run workflow*).
5. Grab the APK from the run's *Artifacts*, or create a permanent download link:

   ```bash
   git tag v1.0 && git push --tags
   ```

If `KEYSTORE_BASE64` is missing, the workflow generates a throwaway keystore so the
build still produces an installable (but not upgrade-safe) APK.

## Install

Download the APK, allow *install from unknown sources* for the browser/files app,
then install. Android asks for the same confirmation iOS does.

## Notes

- `usesCleartextTraffic="true"` allows plain HTTP; drop it once `HERMES_URL` is HTTPS.
- A file chooser (attachment upload) is not wired up; add an `onShowFileChooser`
  implementation in `WebChromeClient` if the WebUI needs uploads.
- `HERMES_URL` is baked into the APK at build time. Rebuild to point at a different host.
