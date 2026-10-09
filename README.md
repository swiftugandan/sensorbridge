# SensorBridge and Tilt

Two Android apps built on the same sensor code:

- **[Tilt](#tilt-a-marble-labyrinth-game)**, a marble labyrinth you steer by tilting the phone.
- **SensorBridge**, which reads every sensor live, on the phone and from your computer over adb.

[Website](https://swiftugandan.github.io/sensorbridge/) ·
[Download Tilt](https://github.com/swiftugandan/sensorbridge/releases/latest/download/Tilt.apk) ·
[Download SensorBridge](https://github.com/swiftugandan/sensorbridge/releases/latest/download/SensorBridge.apk)

## SensorBridge

Read an Android phone's sensors live, on the phone and from your computer over adb.

- **On the phone:** every hardware and virtual sensor, grouped by category, with live values. Each sensor has
  a detail screen with a 10-second live chart (touch and drag to inspect), a sample-rate picker, measured rate,
  accuracy and hardware specs.
- **Microphone:** a live spectrogram (log or linear frequency, touch to read frequency and level), an RMS
  level meter with peak hold and clipping warning, the strongest frequency, and the musical note of any clear,
  steady tone (narrow, loud and well above the surrounding spectrum, 50 Hz–4.2 kHz; noise and rumble show "—").
  Audio is analysed on the phone and never recorded or sent. The microphone opens only while its screen is
  visible and is released on pause or when you leave the screen.
- **Sketch camera:** the back or front camera rendered live as pencil line art (difference-of-Gaussians edges),
  with detail and line-weight sliders; graphite on paper in light mode, chalk on slate in dark mode. **Save
  sketch** writes the drawing as shown to `Pictures/SensorBridge` as a PNG (no permission needed on Android 10+;
  Android 8–9 asks for storage access). Like the microphone, the camera is open only while its screen is
  visible; frames are processed in memory and discarded unless you save one.
- **On your computer:** `sensorctl` lists, reads and streams any sensor as a table, CSV or JSON lines. It sets
  up the adb port forward and starts the bridge on the phone automatically.

```
$ tools/sensorctl read accelerometer light
accelerometer  x     6.9619  y     0.0861  z     6.8471  m/s²
light          illuminance    47.1775  lx  (+7 vendor values; --json shows all)

$ tools/sensorctl stream accelerometer gyroscope --rate 100 --duration 5 --format csv > motion.csv
```

## Requirements

- JDK 17 and the Android SDK (platform 35). Set `JAVA_HOME` and `ANDROID_HOME`.
- A phone on Android 8.0+ with USB debugging enabled.
- Python 3.9+ for `sensorctl` (standard library only).

## Build and install

```sh
./gradlew :app:installDebug          # build and install on the connected phone
./gradlew test                       # JVM unit tests (domain, protocol, chart model)
./gradlew :app:lintDebug             # Android lint
./gradlew :app:assembleRelease :tilt:app:assembleRelease   # signed, shrunk release APKs
```

Release builds are signed when these Gradle properties are set outside the repository, for example in
`~/.gradle/gradle.properties`: `sensorbridge.signing.storeFile`, `sensorbridge.signing.storePassword`,
`sensorbridge.signing.keyAlias` and `sensorbridge.signing.keyPassword`. The website lives in `docs/` and is
published with GitHub Pages.

Optionally put the CLI on your `PATH`: `ln -s "$PWD/tools/sensorctl" /usr/local/bin/sensorctl`.

## Using sensorctl

| Command | What it does |
|---|---|
| `sensorctl list [--all] [--json]` | Sensors and their keys. Wake-up variants are hidden unless `--all` is passed. |
| `sensorctl read SENSOR...` | One reading from each sensor. `--timeout` defaults to 3 s. |
| `sensorctl stream SENSOR... [-r HZ] [-d SECONDS] [-n SAMPLES] [-f table\|csv\|jsonl]` | Streams until Ctrl-C, the duration or the sample limit. CSV has a fixed set of columns with each sensor's documented values; `jsonl` also includes undocumented vendor values. |
| `sensorctl info` | Device, app version and protocol version. |
| `sensorctl start` / `stop` | Starts or stops the bridge service on the phone. |

Global options: `-s SERIAL` (or `$ANDROID_SERIAL`) picks a device, `-p PORT` changes the local port (default
7337), `--no-start` fails instead of starting the bridge, and `$ADB` overrides the adb binary.

**Rates.** `--rate` applies to continuous sensors (accelerometer, gyroscope and so on) and is enforced:
Android treats requested rates as hints, so the app oversamples and thins on a fixed time grid. On-change
sensors (light, proximity, step counter) report only when their value changes. Event sensors (step detector,
motion detect) report when the event happens.

**Permissions.** The step counter and step detector need *Physical activity* permission. Grant it in the app
or with `adb shell pm grant dev.sensorbridge android.permission.ACTIVITY_RECOGNITION`.

## Architecture

```
 phone                                                       computer
 ─────────────────────────────────────────────────────       ─────────────────────────
 :app
   ui/        SensorList ─► SensorDetail ─► LiveChart
   bridge/    BridgeService (foreground)
                └─ BridgeServer ◄─ localabstract:sensorbridge ◄─ adb forward ◄─ sensorctl
                      │ JSON lines                              (tcp:7337)
 :core:domain   SensorRepository · RequestHandler · Protocol · RateLimit   (pure Kotlin)
 :core:sensors  AndroidSensorRepository: SensorManager ─► cold Flow
 :core:audio    AndroidMicrophone   :core:camera  AndroidCamera
```

| Module | Responsibility |
|---|---|
| `:core:domain` | Pure Kotlin/JVM: the line-art renderer (parallel separable Gaussians, rotation and mirroring), audio DSP (FFT, short-time spectrum analysis with a Hann window, level metering), the sensor model and catalog, the `SensorRepository` contract, rate limiting, the wire protocol and `RequestHandler`, which turns requests into response flows. All bridge logic lives here and is unit-tested without Android. |
| `:core:sensors` | `AndroidSensorRepository`: discovers sensors, assigns stable keys, and exposes each one as a cold `Flow` that registers on collection and unregisters on cancellation. Handles continuous, on-change, special-trigger and one-shot sensors. |
| `:core:audio` | `AndroidMicrophone`: `AudioRecord` as a cold `Flow` of PCM blocks, preferring the unprocessed source (no gain control or noise suppression). Also used by the Tilt game. |
| `:core:camera` | `AndroidCamera`: CameraX image analysis (brightness plane, ~640×480) as a cold `Flow`, bound to a lifecycle owned by each collection so the camera is open exactly while collected. |
| `:app` | Compose UI (Material 3, dynamic colour, dark theme, edge-to-edge), `BridgeService` and `BridgeServer` (socket transport), `BridgeController` (single source of truth for bridge state) and the hand-written DI container. |

Design notes:

- **The UI and the bridge share one repository.** Sensors are registered only while a screen or client is
  collecting, so off-screen list rows and idle connections cost nothing.
- **Frame-paced UI.** The detail screen feeds readings into a rolling history at full rate but recomposes at
  about 30 fps, so a 200 Hz sensor costs 30 recompositions a second, not 200.
- **Bridge security.** Abstract sockets are visible to every app, so the server checks each peer's UID and
  only accepts adb shell, root and the app itself. The exported service and its trampoline activity are guarded
  by `android.permission.DUMP`, which adb holds and ordinary apps cannot.
- **Headless start.** `sensorctl` starts the service with `am start-foreground-service`. If a vendor task
  manager or Android 12+ refuses a background start, it falls back to an invisible activity, which may always
  start a foreground service.

The microphone is app-only for now: the bridge does not serve audio. Serving it would need the foreground service
to declare the `microphone` type, because Android 11+ only lets a service record if it was started while the
app was in the foreground.

## Protocol

Newline-delimited JSON. Each object's `type` field selects the message. Requests on one connection are
handled in order, and a request is finished after its terminal response (`hello`, `sensors`, `error` or
`done`; `read` returns exactly one response).

| Request | Responses |
|---|---|
| `{"type":"hello"}` | `{"type":"hello","protocol":1,"app":"SensorBridge 1.0.0","device":{...}}` |
| `{"type":"list"}` | `{"type":"sensors","sensors":[{"key":"accelerometer","axes":["x","y","z"],"unit":"m/s²",...}]}` |
| `{"type":"read","sensor":"light","timeoutMs":3000}` | `{"type":"reading","reading":{...}}` or an error |
| `{"type":"stream","sensors":["accelerometer"],"rateHz":50,"durationMs":5000,"maxSamples":1000}` | `reading` objects, then `{"type":"done","samples":250,"reason":"duration"}` if a limit was set |

A reading looks like
`{"sensor":"accelerometer","timestampNanos":2273660480184387,"wallTimeMillis":1791487626496,"accuracy":3,"values":[6.96,0.11,6.88]}`.
`timestampNanos` is on the device's monotonic clock (use it for intervals), and `wallTimeMillis` is for display.

Errors are `{"type":"error","code":"...","message":"..."}`, where `code` is one of `malformed`,
`invalid-request`, `unauthorized`, `unknown-sensor`, `permission-required`, `sensor-unavailable`, `timeout`
or `internal`.

From any language: `adb forward tcp:7337 localabstract:sensorbridge`, connect to `127.0.0.1:7337`, write a
request line and read response lines.

## Tilt: a marble labyrinth game

`tilt/` is a second app in this build (`dev.sensorbridge.tilt`): a wooden labyrinth you steer by tilting the
phone, with a steel marble, holes, checkpoints and a goal. Blow into the microphone or clap to jump over holes
that fill a corridor (or choose tapping).

```sh
./gradlew :tilt:app:installDebug     # install the game
./gradlew :tilt:engine:test          # engine, level and sound tests (JVM, no device)
```

**Sensors.** Steering uses the best fused orientation sensor available (game rotation vector, then gravity, then
a low-passed accelerometer), read on its own thread at game rate. The pose the phone is held in during the
countdown counts as level, so it never has to lie flat; it is re-measured after every pause. The microphone is
open only on levels that need a jump, and only while playing; it detects loud onsets against a tracked
background level and nothing is recorded. Touch steering (drag) is available as an alternative.

**Content.** Four hand-made training levels (`tilt/engine/src/main/resources/levels/training/*.tilt`, a plain
text grid format) introduce rolling, holes, jumping and checkpoints. Twenty-four labyrinths are generated from
fixed seeds, so they are identical on every device, plus a daily challenge seeded by date. Every level, authored
or generated, must be finished without a fall by a built-in autopilot that steers through the same tilt input as
a player, both steadily and with an unsteady hand; generated candidates that fail are discarded. The steady run
sets the level's par time (stars: at par, within 1.5× par, finished).

| Module | Responsibility |
|---|---|
| `:tilt:engine` | Pure Kotlin/JVM: fixed-step physics (240 Hz rolling, rim pull and capture, wall collisions with restitution, jumps), the game session state machine, tilt mapping and neutral-pose calibration, blow detection, level format, validation, half-cell route planning, the autopilot, the seeded generator, the catalog, and a real-time sound synthesiser (steel-on-wood knocks, a resonant rolling rumble, cues). |
| `:tilt:app` | A `SurfaceView` drawn on its own Choreographer-paced thread (static board layer plus per-frame walls that lean with the tilt, the marble and effects), Compose UI for menus and HUD, sensor, `AudioTrack`, vibration and microphone adapters, and DataStore for settings and progress. |
| `:core:audio` | Shared with SensorBridge: the microphone as a cold `Flow`. |
