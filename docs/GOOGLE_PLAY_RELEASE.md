# BeatIQ — Google Play (AAB) release

## Preconditions

- `applicationId`: **`com.beatiq.music`** (stable; do not change without Play Console app migration).
- **Release** builds always use **`https://beatiq.onrender.com/api/v1/`** for `BuildConfig.API_BASE_URL`. Debug builds may override via `local.properties` → `beatiq.api.base.url`.
- **Release** uses `app/src/release/res/xml/network_security_config.xml` (no cleartext). Main manifest still references `@xml/network_security_config`; the release source set overrides it.
- **Brand asset:** `beatiq_brand_logo` is stored as **JPEG** (`res/drawable/beatiq_brand_logo.jpg`) so AAPT2 release compiles succeed (mislabeled `.png` fails R8 merge).
- **Upload signing** is required for Play Internal testing. Command-line `./gradlew bundleRelease` reads **`keystore.properties`** at the repo root (see `keystore.properties.example`). Android Studio’s **Generate Signed Bundle** can use any keystore path without this file.

## Generate a signed Android App Bundle (AAB)

### Option A — Android Studio (recommended for first upload)

1. **Build → Generate Signed Bundle / APK…**
2. Choose **Android App Bundle**.
3. Select your **upload keystore** (or create one with **Create new…**).  
   - Store this keystore and passwords securely; Play App Signing will keep a Google-managed key for distribution.
4. Select **release** build variant.
5. Finish the wizard.

**Output (default):**  
`app/release/app-release.aab` **or** the folder you chose in the wizard.  
Gradle’s default CLI output is:  
**`app/build/outputs/bundle/release/app-release.aab`**

### Option B — Command line

1. `cp keystore.properties.example keystore.properties` and fill in real values.
2. Put your `.jks` / `.keystore` where `storeFile` points (e.g. `release/beatiq-upload.jks`).
3. From repo root:

```bash
./gradlew :app:bundleRelease
```

**Output:**  
`app/build/outputs/bundle/release/app-release.aab`

If `keystore.properties` is missing, the release build still produces a bundle signed with the **debug** key (useful for CI smoke tests only) — **do not upload that to Play**.

## Versioning

- Bump **`versionCode`** (integer, monotonic) and **`versionName`** (user-visible) in `app/build.gradle.kts` before each Play upload.

## AudD (identify feature)

- `AUDD_API_TOKEN` is still injected from `local.properties` for **all** variants. For CI release builds, add `audd.api.token` to `local.properties` or extend Gradle to read from environment secrets.
