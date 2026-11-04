# LeafConnect

Android client for the Nissan Connect (Janus) remote API for Nissan LEAF
vehicles: battery/charge status, cabin temperature, remote climate start/stop,
remote charging start, door lock/unlock and vehicle locating.

The app talks to the same endpoints the official Android app uses. It is not
affiliated with Nissan; the API is undocumented, the constants below are taken
from the official client, and the backend can change at any time.

## Features

* Login, auto-login, "keep credentials" (stores email and password — see
  [Privacy](docs/PRIVACY.md)).
* Battery %, cabin temperature, plug state, estimated range (HVAC on / off),
  per vehicle, refreshed by pull-to-refresh and by periodic watchdogs.
* Remote commands: START TEMP, STOP TEMP, START CHARGE, LOCATE (opens Google
  Maps), LOCK, UNLOCK — the door endpoints send a default all-zero PIN.
* Vehicle name built from the VIN (offline decoder in `VinDecoder.kt`), plus
  the account nickname.
* Session self-healing: a 401 re-authenticates once and retries; a session
  that cannot be healed ends on the login screen.

## Build

Requirements: Android SDK (API 35), a JDK — `gradle-daemon-jvm.properties`
pins the toolchain (no JDK path is hardcoded in `gradle.properties`; the
Gradle toolchain provisions what is missing).

```sh
./gradlew :app:assembleDebug            # debug APK, signed with the local debug key
./gradlew :app:assembleRelease          # release APK, minified; signed only when
                                        # keystore.properties exists
./gradlew packageLeafConnect            # copies the release APK to build/outputs/apk/release/LeafConnect.apk
```

Output: `app/build/outputs/apk/debug/app-debug.apk` and
`app/build/outputs/apk/release/LeafConnect.apk`.

### Signing a release

Signing material is **not** in this repository. Create a keystore on your
machine and describe it in `keystore.properties` at the repository root
(gitignored, never committed):

```properties
storeFile=leafconnect.keystore
storePassword=<your keystore password>
keyAlias=<your key alias>
keyPassword=<your key password>
```

`storeFile` is resolved relative to the repository root. Without the file,
`assembleRelease` produces an **unsigned** release APK instead of failing with
empty passwords; sign it yourself (`apksigner`) or add the file.

## Tests, lint, CI

```sh
./gradlew :app:testDebugUnitTest   # unit tests (VinDecoder, CookieStore)
./gradlew :app:lintDebug           # lint gate; suppressions and reasons in app/lint.xml
```

`.github/workflows/ci.yml` runs the same gates (unit tests, lint) and then
`packageLeafConnect` on every push and pull request. Each push to `main`/
`master` publishes a GitHub release for that commit (`release-<short sha>`),
and each pull request gets a prerelease (`pr-<number>`) replaced on every new
push to the PR. It signs when the repository defines the secrets
`LEAF_KEYSTORE_BASE64`, `LEAF_STORE_PASSWORD` and `LEAF_KEY_PASSWORD`;
without them the published APK is unsigned.

## Architecture

| File                      | Role                                                                            |
|---------------------------|---------------------------------------------------------------------------------|
| `LoginActivity.kt`        | login form, credential preferences, auto-login                                  |
| `HomeActivity.kt`         | vehicle cards, status, remote commands, logout, refresh/watchdogs               |
| `NissanConnectSession.kt` | session state, login, re-authentication, 401 routing                            |
| `NissanConnectClient.kt`  | one vehicle's endpoints; the single 401 self-heal path                          |
| `NissanConnectAuth.kt`    | HTTP transport (OkHttp), headers, constants, cookie jar                         |
| `CookieStore.kt`          | full session cookie set, merged per domain+path+name, encrypted persistence     |
| `SecurePreferences.kt`    | Android-Keystore-backed encrypted preferences                                   |
| `CredentialStore.kt`      | credentials in RAM; encrypted store is the fallback when Keep credentials is on |
| `CancellationToken.kt`    | real cancellation of in-flight OkHttp calls                                     |
| `VinDecoder.kt`           | VIN → maker/model/year (offline)                                                |
| `NissanConnectCache.kt`   | last battery payload per VIN, keyed by login generation                         |

Design rules the code follows:

* Every network call runs on the single worker `ExecutorService` and owns a
  `CancellationToken`; `Call.cancel()` aborts a blocking read, which
  `Thread.interrupt()` cannot do.
* Shared mutable state is confined: per-request 401 suppression,
  `AtomicInteger` soft-login counter, `CopyOnWriteArrayList` of tokens,
  `@Volatile` fields, and one guarded logout path (`logOut` with
  `logoutStarted`).
* `onDestroy` removes Handler callbacks, cancels every token, and shuts the
  executor down.

## API notes

See [docs/API.md](docs/API.md) for the endpoints, headers and request bodies,
and [docs/PRIVACY.md](docs/PRIVACY.md) for exactly what is stored on the
device.

## Known limitations

* `API-Key` / `User-Agent-Key` are constants baked into the official client;
  they cannot be hidden on a device that ships the app, so they are
  documented in code and in `docs/API.md`.
* Soft-login and re-authentication need the password: RAM first, then the
  encrypted store (Keep credentials). Only when neither is available after a
  process restart does the app keep using the stored token, and a 401 it
  cannot heal logs out to the login screen.
* Nissan's servers rate-limit remote commands; a command may be rejected while
  a previous one is still running (the UI shows that note).
