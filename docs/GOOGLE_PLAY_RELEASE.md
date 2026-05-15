# BeatIQ — Google Play (AAB) release

## Preconditions

- `applicationId`: **`com.beatiq.music`** (stable; do not change without Play Console app migration).
- **Release** builds always use **`https://beatiq.onrender.com/api/v1/`** for `BuildConfig.API_BASE_URL`. Debug builds may override via `local.properties` → `beatiq.api.base.url`.
- **Release** uses `app/src/release/res/xml/network_security_config.xml` (no cleartext). Main manifest still references `@xml/network_security_config`; the release source set overrides it.
- **Brand asset:** `beatiq_brand_logo` is stored as **JPEG** (`res/drawable/beatiq_brand_logo.jpg`) so AAPT2 release compiles succeed (mislabeled `.png` fails R8 merge).
- **Upload signing** is required for Play Internal testing.

## Keystore file (important)

Your upload keystore must be a **`.jks` or `.keystore` file**, not a folder.

| Use this | Not this |
|----------|----------|
| `BeatIQ Release file/Untitled.jks` | `BeatIQ Release file` (folder) |
| `beatiq-upload.jks` in the project root | The parent directory |

If Android Studio or Gradle reports `Keystore file '...BeatIQ Release file' not found` or `is not a file`, you selected the **folder** in **Generate Signed Bundle**. Open the folder and pick **`Untitled.jks`** (or copy it to `beatiq-upload.jks` in the repo root).

Find your key alias:

```bash
keytool -list -v -keystore "/path/to/Untitled.jks"
```

## Generate a signed Android App Bundle (AAB)

### Option A — Android Studio

1. **Build → Generate Signed Bundle / APK…**
2. Choose **Android App Bundle**.
3. **Key store path:** browse to the **`.jks` file** (e.g. `Untitled.jks` or `beatiq-upload.jks`), not the containing folder.
4. Enter keystore password, key alias, and key password.
5. Select **release** build variant and finish.

**Output:** the path you chose in the wizard, or by default  
`app/build/outputs/bundle/release/app-release.aab`

### Option B — Command line (recommended for repeat uploads)

1. Copy the example config and edit with your real values:

```bash
cp keystore.properties.example keystore.properties
```

2. Set `storeFile` to a **file** path, for example:

```properties
storeFile=beatiq-upload.jks
storePassword=YOUR_KEYSTORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```

3. Place the keystore at that path (a copy already exists as `beatiq-upload.jks` in the repo root; it is gitignored).

4. Build:

```bash
./gradlew :app:bundleRelease
```

**Output:** `app/build/outputs/bundle/release/app-release.aab`

If `keystore.properties` is missing, the bundle is signed with the **debug** key — fine for local smoke tests only. **Do not upload that to Play.**

## Versioning

- Bump **`versionCode`** (integer, monotonic) and **`versionName`** (user-visible) in `app/build.gradle.kts` before each Play upload.

## AudD (identify feature)

- `AUDD_API_TOKEN` is still injected from `local.properties` for **all** variants. For CI release builds, add `audd.api.token` to `local.properties` or extend Gradle to read from environment secrets.
