# Ecobee Connect for Hubitat

Control ecobee thermostats and read their room sensors from a Hubitat
Elevation hub, signed in with your ecobee email and password. No ecobee
developer API key is needed; ecobee stopped issuing those in 2024.

> **Status: v0.1, not yet tested on a hub.** The sign-in and API calls follow
> the flow that Home Assistant's ecobee integration (2026.3+) and the Homey
> "ecobee Comfort" app use. The code passes the offline tests in `tests/`
> against a fake Auth0 and a fake ecobee API. See [First run on a hub](#first-run-on-a-hub).

## How it works

- **Ecobee Connect** (app) holds the sign-in and one poll timer. It finds the
  thermostats and room sensors on the account and creates a device for each
  one you pick. All ecobee mapping lives in the app.
- **Ecobee Connect Thermostat** and **Ecobee Connect Sensor** are thin drivers.
  They show what the app hands them and send commands back through it.

### Sign-in

The app signs in the way ecobee.com does: Auth0 at `auth.ecobee.com` with
PKCE, then your email, password and, if your account uses one, a code from an
authenticator app or a text message. Hubitat keeps only the refresh token;
the password is cleared from the app's settings once sign-in finishes
(successfully or not).

- The access token is renewed 5 minutes before it expires, and again whenever
  ecobee answers with an auth error (status 1, 2, 14 or 16), with one retry.
  This is the failure that took Hubitat's built-in integration offline in
  December 2025.
- When ecobee rotates the refresh token, the new one is saved right away.
- If ecobee ends the sign-in (a password change, for example), the devices
  show `healthStatus: offline`, polling stops, and the app page asks you to
  sign in again. Your devices and rules stay in place.
- Push notifications and email codes aren't supported as a second step.
  Switch two-step verification to an authenticator app or text message.

### Few requests

ecobee asks apps not to poll faster than every 3 minutes. Each poll asks only
for the **thermostat summary**: a few revision strings per thermostat and the
equipment that's running.

| What changed at ecobee | Requests this poll |
| --- | --- |
| Nothing | 1 (summary) |
| Only running equipment (heat on, fan off…) | 1 (summary; the operating state comes with it) |
| Runtime: temperature, humidity, sensors | 2 (summary, then runtime and sensors only) |
| Settings, schedule or holds | 2 (summary, then settings, program, events, runtime and sensors) |

- All thermostats on the account share one summary request and one fetch.
- Commands are gathered for 1.5 seconds, so tapping a setpoint up three times
  sends one request, and a heat and cool change together become one hold.
  The thermostat is read back 5 seconds after each change.
- After an HTTP 429 or an outage, polls pause for twice the poll interval,
  doubling up to 30 minutes. After 3 failed polls in a row the devices show
  `healthStatus: offline`.

## Devices

**Ecobee Connect Thermostat**: `Thermostat`, `TemperatureMeasurement`,
`RelativeHumidityMeasurement`, `MotionSensor` (the thermostat's own occupancy
sensor) and `Refresh`.

- Modes: `auto`, `cool`, `heat`, `emergency heat` (aux heat only, heat pumps
  with aux) and `off`, offered only when your equipment supports them.
- Fan: `auto`, `on`, and `circulate`, which sets ecobee's "fan minimum on
  time" to the minutes per hour in the app (default 20). `auto` sets it back
  to 0.
- Setpoints keep ecobee's minimum gap between heat and cool by moving the
  other setpoint.
- Comfort profiles: `setClimate(name)` takes Home, Away, Sleep or any of your
  own profiles by name. `setHome`, `setAway`, `setSleep` and `resumeProgram`
  are shortcuts.
- Holds last until the next scheduled change, until you resume, or for a
  number of hours, as set in the app.
- Extra attributes: `currentClimate`, `supportedClimates`, `holdStatus`
  (schedule, hold, vacation, smart, utility), `holdUntil`, `equipmentStatus`
  (ecobee's own list, like `heatPump,fan`), `fanMinOnTime`, `connected`,
  `healthStatus`.

**Ecobee Connect Sensor**: `TemperatureMeasurement`, `MotionSensor`,
`RelativeHumidityMeasurement` (on sensors that report it) and `Refresh`, plus
`inUse` (whether the sensor counts in the current comfort profile) and
`healthStatus` (offline while ecobee reports its temperature as unknown).

## Install

### Hubitat Package Manager (recommended)

In **Hubitat Package Manager**, choose **Install → From a URL** and paste:

```
https://raw.githubusercontent.com/digital-homesteaders/hubitat-ecobee/main/packageManifest.json
```

### Manually

Copy and paste, in this order:

1. **Drivers Code → New Driver**: paste
   `drivers/ecobee-connect-thermostat.groovy`, then **Save**. Do the same for
   `drivers/ecobee-connect-sensor.groovy`.
2. **Apps Code → New App**: paste `apps/ecobee-connect.groovy`, then **Save**.
3. **Apps → Add User App → Ecobee Connect**.
4. Enter your ecobee email and password and press **Sign in**. If ecobee asks
   for a code, enter it and press **Submit code**.
5. The page lists the thermostats and room sensors it found, all checked.
   Uncheck any you don't want, choose the options, and press **Done** to add
   the devices.

Thermostats or sensors added to your ecobee account later show up in the
pickers unchecked. Unchecking one and pressing **Done** removes its device.

You can run this next to Hubitat's built-in Ecobee Integration while you
compare them; the devices are separate.

## First run on a hub

These parts depend on how the hub's HTTP client behaves and can only be
checked on a hub:

- **Sign-in redirects and cookies.** The app reads each redirect itself
  (`followRedirects: false`) and keeps Auth0's cookies between steps. With
  debug logging on, each step logs its HTTP status, where it redirected and
  the cookie *names* (never values), for example
  `Sign-in password: HTTP 302 to /authorize/resume; cookies [did, did_compat, auth0, auth0_compat]`.
  If sign-in fails, those lines show where.
- **Verification codes**: authenticator app and text message.
- **Commands**: each mode, fan mode, setpoints in °F and °C, comfort profiles.

Turn on **Debug logging** in the app before signing in, and send the log lines
if anything fails. They never include your password, tokens, codes or cookie
values.

## Tests

The tests run without a hub, against stubs of the Hubitat runtime and fakes
of Auth0 and the ecobee API:

```bash
tests/setup-tools.sh   # once: private JDK and Groovy 2.4 in .tools/
tests/run.sh
```

- `CompileCheck`: everything compiles, driver ↔ app calls exist and are
  public, and no log line touches secrets.
- `MappingTest`: summary parsing, partial fetches, °F/°C, modes, holds,
  sensors, write bodies, URL, cookie and PKCE helpers.
- `FlowTest`: sign-in with and without codes, rejected sign-ins, summary-first
  polling, device creation, gathered writes, token renewal, an ended sign-in,
  429 backoff, and a scan of every log line for secrets.

## Caveats

ecobee doesn't officially support third-party sign-in with your account
password. Home Assistant's integration and Hubitat's built-in app take the
same approach. If ecobee changes its sign-in pages, the sign-in part of this
app will need an update; the API part is ecobee's documented API.

## Credits

The sign-in flow follows [python-ecobee-api](https://github.com/nkgilley/python-ecobee-api)
(used by Home Assistant) and [ecobee Comfort for Homey](https://github.com/johndellarciprete/ecobeecomfort),
both MIT licensed (see [NOTICE](NOTICE)). The test harness comes from SolarEdge Connect for Hubitat.

## License

MIT License, Copyright (c) 2026 Digital Homesteaders; see [LICENSE](LICENSE).
Not affiliated with or endorsed by ecobee.
