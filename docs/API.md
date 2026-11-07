# Nissan Connect (Janus) API notes

The app talks to the same undocumented backend the official Nissan LEAF app
uses. This document describes exactly what the app sends so the constants can
be audited. If Nissan changes the backend, these notes go stale.

## Base URL

```
https://icm.infinitiusa.com/NissanLeafJanus/rest
```

Every request is appended to this URL. All request bodies are JSON.

## Request headers

Sent on every request (`NissanConnectAuth.sendRequest`):

| Header           | Value                                                              | Notes                                                                                                                                                       |
|------------------|--------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `Content-Type`   | `application/json`                                                 |                                                                                                                                                             |
| `APP-Check`      | `Android CA` \| `Android US`                                       | Official client sends `Android CA` verbatim; the backend ignores it (CA, US and an absent header all authenticate), so this app follows the selected region |
| `API-Key`        | `RbypnRCTrt6LVkm78s/67Xl5yPVXhFnZStRjyUvT03PsKi540xhfl+Xxn/aGZsaq` | Public constant baked into the official app; cannot be hidden in any APK                                                                                    |
| `User-Agent-Key` | `AIzaSyDb-KX_YXWzYFchv-IDTsVAmADIBPvA8vg`                          | Same as above (a Google API key of the official client)                                                                                                     |
| `Authorization`  | `<authToken>` verbatim                                             | Only when a token is stored; no `Bearer ` prefix                                                                                                            |
| `Cache-Control`  | `no-cache, no-store, must-revalidate`                              |                                                                                                                                                             |
| `Pragma`         | `no-cache`                                                         |                                                                                                                                                             |

The two `*-Key` values are **not user secrets**: like every constant in an
Android app, they are readable by anyone who unpacks the APK. They identify
the client to the backend; they do not authenticate a user.

## Cookies

The backend sets several `Set-Cookie` headers per response (session cookies
plus load-balancer sticky-routing cookies). The app keeps the **full** cookie
set and merges per `domain + path + name` — replacing the whole set per
response would drop the sticky-routing cookies and the WAF answers `401`
(`CookieStore`). OkHttp parses the headers; no manual parsing.

## Authentication

### `POST /auth/authenticationForAAS` — login

```json
{ "authenticate": {
    "userid":   "<email>",
    "password": "<password>",
    "brand-s":  "N",
    "language-s": "EN|FR",
    "country":  "CA|US"
} }
```

Response: `authToken`, `accountID`, and a `vehicles` array carrying `uvi`
(Nissan's term for the VIN), `nickname`, `modelname`, `modelyear`,
`interiorTempRecords`, … . The app stores the token, the account ID and a
trimmed copy of the vehicle list; the password is held in RAM only.

### `POST /auth/softLoginforAAS` — soft login

Same body as login. Used when a request comes back `401` while the password is
still in memory (`NissanConnectAuth.makeRequest` retries once or twice with
the fresh token). After a process restart there is nothing to soft-login with,
so the stored token is reused as-is.

A `401` that even the login endpoint rejects means the session is dead: the
app logs out to the login screen.

## Per-vehicle endpoints

All under the base URL, `POST` with empty `{}` body unless noted:

| Endpoint                                                      | Method | Purpose                                                  |
|---------------------------------------------------------------|--------|----------------------------------------------------------|
| `/auth/vehicles/{vin}/getVehicleData`                         | GET    | vehicle data                                             |
| `/battery/vehicles/{vin}/getChargingStatusRequest`            | GET    | battery %, cabin temp, plug state, range (HVAC on / off) |
| `/battery/vehicles/{vin}/remoteChargingRequest`               | POST   | start charging                                           |
| `/hvac/vehicles/{vin}/activateHVAC`                           | POST   | start cabin temp                                         |
| `/hvac/vehicles/{vin}/deactivateHVAC`                         | POST   | stop cabin temp                                          |
| `/vehicleLocator/vehicles/{vin}/refreshVehicleLocator`        | POST   | last-known location (DMS coordinates)                    |
| `/remote/vehicles/{vin}/accounts/{accountId}/rdl/createRDL`   | POST   | door lock                                                |
| `/remote/vehicles/{vin}/accounts/{accountId}/rdul/createRDUL` | POST   | door unlock                                              |

Door commands carry a 5-digit authorization key as
`{"remoteRequest": {"authorizationKey": "<pin>"}}`. The user is not asked to
type one — a default all-zero PIN (`00000`) is sent (mirroring the original
app, which hardcoded it).

The battery endpoint is slow on the server side (16–60+ s); the client allows
a 90 s read timeout and a 120 s call timeout, and every request can be
canceled for real (`CancellationToken` → OkHttp `Call.cancel()`).

## Coordinates

The location endpoint returns `latitudeDMS` / `longitudeDMS` as strings holding
decimal degrees. HomeActivity
passes the strings to the `geo:` URI used to open a maps' app, rejecting
non-numeric values.
