# SignalK Companion Android App

A modern Android application that streams smartphone sensor data to SignalK servers via UDP.

## Features

### Current (MVP)

- ✅ Stream GNSS location data (`LocationManager` + `GPS_PROVIDER`, no Play Services)
- ✅ Real-time transmission via UDP to SignalK servers
- ✅ Modern Android UI with Jetpack Compose
- ✅ MVVM architecture with Hilt dependency injection
- ✅ Background service for continuous streaming
- ✅ Start/stop controls with connection status
- ✅ GPS quality indicators (accuracy, provider type)
- ✅ Enhanced precision for Android 8.0+ (speed, bearing, altitude accuracy)
- ✅ JWT authentication for SignalK servers
- ✅ Login/logout functionality with token management

### Planned

The roadmap is the milestone table in [`architectural-plan.md`](architectural-plan.md) §5.
In short:

| | Milestone | What you get |
|---|---|---|
| M0 | Cleanup | One coherent architecture in the repo; honest data on the wire |
| M1 | Raw sensor pipeline + Mahony AHRS | Roll/pitch that stay correct under heel and waves |
| M2 | Ellipsoid magnetometer calibration | Heading you can trust, immune to current by construction |
| M3 | Heading integrity gates | Cable crossings, engine start and marina steel handled honestly |
| M4 | Position/velocity filter | GPS jumps gone; smooth pose at 10–50 Hz |
| M5 | Quality publishing | Every value carries what it's worth |
| M6–M8 | Current vector, diagnostics, heave | Closing the heading/COG/current triangle |

Design documents:
- [`architectural-plan.md`](architectural-plan.md) — principles, milestones, and the
  reasoning behind each decision (including the rejected ones)
- [`frame-conventions.md`](frame-conventions.md) — **normative** frames, signs, units and
  time base; code that disagrees with it is wrong
- [`compass-calibration-design.md`](compass-calibration-design.md) — superseded, kept as
  decision history

## Technical Stack

- **Language**: Kotlin 1.9.20
- **UI**: Jetpack Compose with Material 3
- **Architecture**: MVVM with Hilt for dependency injection
- **Location**: Platform `LocationManager` with `GPS_PROVIDER` (no Play Services — see
  [`architectural-plan.md`](architectural-plan.md) P8; keeps the build free of proprietary
  dependencies for F-Droid)
- **Networking**: UDP sockets for SignalK communication
- **Serialization**: Kotlinx Serialization
- **Background Processing**: Android Foreground Services
- **Build System**: Gradle 8.4 with KSP (Kotlin Symbol Processing)

## SignalK Data Paths

The app transmits data using standard SignalK paths with quality indicators:

All angles are radians and all speeds are m/s, per the SignalK spec and
[`frame-conventions.md`](frame-conventions.md) §8.

### Navigation Data

- `navigation.position` - GNSS coordinates
- `navigation.position.accuracy` - Horizontal accuracy in meters
- `navigation.speedOverGround` - Speed as reported by the GPS provider
- `navigation.speedOverGround.accuracy` - Speed accuracy
- `navigation.courseOverGroundTrue` - Course as reported by the GPS provider
- `navigation.courseOverGroundTrue.accuracy` - Bearing accuracy
- `navigation.gnss.altitude` - Altitude from GNSS *(see caveat below)*
- `navigation.gnss.altitude.accuracy` - Vertical accuracy
- `navigation.gnss.type` - Provider name (now always `gps`)

Speed and course come straight from the GNSS engine and are never derived by differencing
positions — differencing turns multipath into fake velocity
([`architectural-plan.md`](architectural-plan.md) P2). Real receivers compute velocity from
carrier **Doppler**, which is what makes it jump-free, though the Android API itself
guarantees only the value, not its provenance.

### Orientation Data

- `navigation.headingCompass` - Compass heading, **not** adjusted for magnetic deviation
- `navigation.magneticVariation` - Declination at the current position (WMM model), positive east
- `navigation.attitude` - `{ roll, pitch }`; roll positive to starboard, pitch positive bow-up
- `navigation.rateOfTurn` - Vehicle-frame turn rate, **positive to starboard**
- `sensors.magnetometer.accuracy` - Android's own magnetometer accuracy, 0–3

Sign conventions are verified verbatim against the SignalK specification schemas — see
[`frame-conventions.md`](frame-conventions.md) §11.

### Environmental Data

- `environment.outside.pressure` - Atmospheric pressure (Pa)
- `environment.outside.temperature` - Ambient temperature (K), if the device has the sensor
- `environment.outside.relativeHumidity` - Relative humidity (ratio), if the device has the sensor

### Honest caveats on the current data

- **Heading is uncalibrated, and the path name says so.** SignalK distinguishes
  `headingCompass` ("not adjusted for magneticDeviation") from `headingMagnetic`
  ("headingCompass adjusted for magneticDeviation") from `headingTrue`. We publish
  `headingCompass`, because we correct for mount alignment only — the boat's own magnetic
  signature (engine, keel, rigging) is not calibrated out until M2, and there is no
  disturbance gating until M3. **`headingMagnetic` and `headingTrue` are deliberately not
  published**; both would assert a correction we have not made. `magneticVariation` is
  published because it is a property of position, not of our compass — but note it does
  **not** let you reconstruct true heading: `headingCompass + variation` still lacks the
  deviation term, and deviation is exactly what nobody has until M2. At M2 heading promotes
  to `headingMagnetic`, with `magneticDeviation` alongside it, and true heading becomes
  computable for the first time.
- **Roll and pitch degrade under way.** The current attitude comes from a low-passed
  tilt-compensated compass with no gyro in the loop, so wave and heel dynamics corrupt it.
  M1 replaces this. Note the published roll is *instantaneous inclination* — steady heel
  plus wave-driven roll oscillation — not heel alone.
- **`navigation.gnss.altitude` is height above the WGS84 ellipsoid**, which is what Android
  reports — not height above sea level. The difference (geoid undulation) is tens of metres
  in many places. M4 publishes a corrected altitude per plan P9.
- **No yaw is published, and none will be.** Beyond the old field having carried a gyro
  rate in an angle slot, SignalK defines no datum for `attitude.yaw`: with north as datum it
  merely duplicates the heading paths, with mean heading as datum it is a yawing
  oscillation, and the spec never says which. Heading rides on the heading paths, which are
  unambiguous. See [`frame-conventions.md`](frame-conventions.md) §11.3.

## Configuration

- **Default UDP Port**: 55555 (SignalK standard)
- **Default Server**: 192.168.1.100:3000
- **Update Frequency**: 1Hz (configurable to 0.5-2Hz)
- **Location Source**: GNSS only. Fixes arrive at the chip's native rate, typically 1 Hz;
  a shorter configured interval will not produce more fixes. Higher-rate pose output is
  M4's job, from the filter, not from the provider.
- **DNS Refresh**: Automatic every 5 minutes (handles dynamic IPs)
- **Hostname Support**: Full support for mDNS/Bonjour (e.g., `signalk.local`)

## Building

### Prerequisites

- **Java 17 or higher** (OpenJDK recommended)
- **Android SDK** — via Android Studio or `cmdline-tools`, with `ANDROID_HOME` set or
  `sdk.dir` in `local.properties`
- **No Gradle installation needed** — `gradle-wrapper.jar` is committed, so `./gradlew`
  bootstraps the declared distribution on a fresh clone
- **Linux/macOS/Windows** supported

### Quick Build (Command Line)

```bash
# Clone the repository
git clone <repository-url>
cd signalk-pose-provider

# Build debug APK (for development/testing)
./gradlew assembleDebug

# Build release APK (for production)
./gradlew assembleRelease
```

### APK Output Locations

- **Debug APK**: `./app/build/outputs/apk/debug/app-debug.apk`
- **Release APK**: `./app/build/outputs/apk/release/app-release.apk`

### Installation

#### Option 1: ADB (if you have Android SDK)

```bash
# Install debug version
adb install ./app/build/outputs/apk/debug/app-debug.apk

# Install release version  
adb install ./app/build/outputs/apk/release/app-release.apk
```

#### Option 2: Manual Installation

1. Copy the APK file to your Android device
2. Enable "Install from Unknown Sources" in Android Settings
3. Tap the APK file to install

### Additional Build Commands

```bash
# Compile Kotlin sources only (faster for development)
./gradlew compileDebugKotlin

# Run tests
./gradlew test

# Clean build directory
./gradlew clean

# Build both debug and release
./gradlew assemble
```

### Android Studio (Optional)

If you prefer using Android Studio:

1. **Open existing project** and select the `Android-SignalK-Companion` folder
2. **Wait for Gradle sync** to complete
3. **Build → Generate Signed Bundle/APK** for release builds
4. **Run** button for direct device installation

> **Note**: The command-line build is often faster and doesn't require Android Studio installation.

## Usage

1. Enter your SignalK server address (IP:port or hostname like `signalk.local:3000`)
2. **Optional**: Click "Login" to authenticate with your SignalK server
   - Uses **HTTP(S) protocol** for secure authentication
   - Enter your username and password
   - Login form expands inline (no separate screens)
3. Grant location permissions when prompted
4. Tap "Start Streaming" to begin data transmission
   - **UDP protocol** for real-time sensor data streaming
   - JWT tokens included automatically if authenticated
5. Monitor connection status, authentication status, and sensor data in real-time
6. Use "Stop Streaming" to halt transmission
7. **Optional**: Click "Logout" to clear authentication

## Architecture

```
├── ui/
│   ├── main/          # Main screen and ViewModel
│   └── theme/         # Compose theme and styling
├── service/
│   ├── LocationService.kt           # GNSS fixes via LocationManager/GPS_PROVIDER
│   ├── SensorService.kt             # IMU/environmental sensors, attitude
│   ├── SignalKTransmitter.kt        # SignalK message handling & UDP transmission
│   ├── SignalKStreamingService.kt   # Foreground streaming service
│   └── AuthenticationService.kt     # JWT login/token handling
├── util/
│   └── DeviceCalibration.kt         # Frame math: mount rotation, attitude extraction
├── data/
│   └── model/         # Data classes and SignalK models
└── di/
    └── AppModule.kt   # Hilt dependency injection
```

Frame, sign and unit conventions for everything under `service/` and `util/` are normative
in [`frame-conventions.md`](frame-conventions.md); `FrameConventionsTest` encodes its
reference poses as tests.

## Permissions Required

- `ACCESS_FINE_LOCATION` - GNSS fixes
- `ACCESS_COARSE_LOCATION` - Declared because Android 12+ requires it alongside
  `ACCESS_FINE_LOCATION`; no network-based location is used
- `INTERNET` - UDP transmission
- `FOREGROUND_SERVICE` - Background operation
- `POST_NOTIFICATIONS` - Service notifications

## Development Roadmap

See the milestone table under [Planned](#planned) above, and
[`architectural-plan.md`](architectural-plan.md) §5 for the full version with dependencies
and ship value per milestone. The plan is the single source of truth for what is next;
this README describes only what the app does today.

## Troubleshooting

### Build Issues

**Java Version**: Ensure you have Java 17+
```bash
java -version
```

**Gradle Permissions**: On Linux/macOS, make Gradle wrapper executable:
```bash
chmod +x ./gradlew
```

**Build Cache**: Clear if you encounter strange errors:
```bash
./gradlew clean
```

### Runtime Issues

**Location Permissions**: The app requires precise location access
**Network Access**: Ensure your device can reach the SignalK server
**UDP Port**: Default is 55555, ensure SignalK server is listening on this port
**Hostname Resolution**: If using hostnames like `signalk.local`, ensure mDNS is working on your network

### Marina Networks & DNS

The app is designed for maritime environments where network configurations change frequently:

- **mDNS Hostnames**: Use `signalk.local` instead of fixed IP addresses when possible
- **Dynamic IP Handling**: The app automatically refreshes DNS resolution every 5 minutes
- **Network Changes**: When moving between marina slips, the app will adapt to new IP addresses
- **Connection Recovery**: Failed connections are automatically retried with updated DNS resolution

For optimal performance in marinas:
1. Use hostname-based configuration (`signalk.local:3000`) 
2. Ensure your SignalK server broadcasts mDNS
3. Monitor the app's connection status for network changes

## Authentication

The app supports SignalK JWT authentication for secure connections:

### Features

- **HTTP(S)-based login** via `/signalk/v1/auth/login` endpoint
- **JWT token management** with automatic expiry handling
- **Token inclusion** in all SignalK UDP messages for authenticated access
- **Protocol separation** - HTTP(S) for auth, UDP for data streaming
- **Optional authentication** - works with both authenticated and open servers
- **Secure logout** with server-side token invalidation via HTTP(S)

### Server Compatibility

- ✅ **OpenPlotter/SignalK Node Server** - Full authentication support
- ✅ **Wilhelmsk** - Authentication supported
- ✅ **SignalK Python Server** - Authentication supported  
- ✅ **Open servers** - Authentication optional, app works without login

### Security Notes

- **HTTPS preferred** - Uses HTTPS when available for secure credential transmission
- **HTTP fallback** - Works with HTTP for development/local servers  
- **Dual protocol design** - HTTP(S) for authentication, UDP for data
- **Tokens in memory only** - No persistent token storage for security
- **Automatic logout** on app restart for security
- **Server-side validation** - JWT tokens validated on each UDP message

## Contributing

1. Fork the repository
2. Create a feature branch
3. Build and test your changes
4. Submit a pull request

### What NOT to Commit

The project includes a comprehensive `.gitignore` file. **Do NOT commit these:**

- **Build artifacts**: `*.apk`, `*.aab`, `*.dex` files (~70MB)
- **Build directories**: `build/`, `app/build/` (contains generated code)
- **Gradle cache**: `.gradle/`, `app/.gradle/` (~2.7MB)
- **Gradle distribution**: `gradle-dist/` (~139MB, use standard wrapper instead)
- **IDE files**: `.idea/`, `*.iml`, `.vscode/`
- **Local config**: `local.properties` (may contain API keys)
- **Signing files**: `*.jks`, `*.keystore` (contains certificates)

### What TO Commit

- **Source code**: `app/src/` directory
- **Build configuration**: `build.gradle.kts`, `settings.gradle.kts`
- **Gradle wrapper**: `gradle/wrapper/` (for reproducible builds)
- **Resources**: `app/src/main/res/`
- **Manifest**: `app/src/main/AndroidManifest.xml`
- **This README** and documentation

> **Note**: The standard Gradle wrapper (`./gradlew`) is used instead of including a full Gradle distribution, keeping the repository lightweight.

## License

This project is licensed under the **GNU General Public License v3.0** (GPL-3.0).