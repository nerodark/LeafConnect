# What this app stores on your device

## Nothing leaves the device

The app makes only the API calls listed in [API.md](API.md), with your
credentials, on your behalf. There is no analytics, no crash reporting, no
third-party network traffic beyond the Nissan backend itself.

## What is stored

All persistent state lives in one file: the encrypted preferences
(`SecurePreferences`), backed by the **Android Keystore** (the AES keys live
in hardware-backed Keystore keys, never in the file itself). Keys stored:

| Key                              | Contents                                                                                  | When present                            |
|----------------------------------|-------------------------------------------------------------------------------------------|-----------------------------------------|
| `token`                          | Nissan Connect auth token                                                                 | after login                             |
| `email`                          | your account email                                                                        | only while "Keep credentials" is on     |
| `password`                       | your account password                                                                     | only while "Keep credentials" is on     |
| `accountId`                      | account ID (needed for door commands)                                                     | after login                             |
| `vehicles`                       | trimmed vehicle list (`uvi`, `nickname`, `modelname`, `modelyear`, `interiorTempRecords`) | after login                             |
| `cookie_store`                   | the session cookie set                                                                    | after login                             |
| `nissanconnect_cache`            | last battery payload per vehicle                                                          | after the first successful status fetch |
| `keep_credentials`, `auto_login` | the two checkbox states                                                                   | after you toggle them                   |
| `login_gen`, `cache_gen`         | generation counters (decide whether a fetch is needed)                                    | after login / fetch                     |

The **password** lives in RAM (`CredentialStore`) for the lifetime of the
app process; while "Keep credentials" is on it is additionally written to
this encrypted file, so auto-login and session self-healing survive a process
restart. Unchecking the box removes it. Door commands send a default
all-zero PIN and no user-typed PIN is ever stored.

`android:allowBackup="false"` keeps the preferences file out of Android
backups, and the app declares no permissions beyond network state.

## What is not stored

* the password, unless "Keep credentials" is on (otherwise RAM only),
* any PIN (a default all-zero PIN is sent with door commands; none is typed),
* the location (displayed from the response, not kept),
* any log or telemetry.

## What "Keep credentials" does

Unchecking it removes the stored email AND password immediately (the
on-screen fields are left as they are). "Auto-login" re-logs-in silently on
the login screen from RAM or the encrypted store, so it works after a
process restart too; without any stored password it falls back to the stored
token, and a token the server rejects ends on the login screen.
