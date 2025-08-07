# SignalK Companion Android App

A modern Android application that streams smartphone sensor data to SignalK servers via UDP.

## Features

### Current (MVP)
- ✅ Stream GPS location data (fused location provider)
- ✅ Real-time transmission via UDP to SignalK servers
- ✅ Modern Android UI with Jetpack Compose
- ✅ MVVM architecture with Hilt dependency injection
- ✅ Background service for continuous streaming
- ✅ Start/stop controls with connection status
- ✅ GPS quality indicators (accuracy, provider type)
- ✅ Enhanced precision for Android 8.0+ (speed, bearing, altitude accuracy)
- ✅ JWT authentication for SignalK servers
- ✅ Login/logout functionality with token management

### Planned (Full Feature Set)
- 🔄 Additional sensors: magnetometer, accelerometer, gyroscope, barometer
- 🔄 Sensor fusion for improved heading/course/speed
- 🔄 Server discovery on local network
- 🔄 Configurable update rates
- 🔄 Notification system
- 🔄 Enhanced error handling and reconnection logic

## Technical Stack

- **Language**: Kotlin 1.9.20
- **UI**: Jetpack Compose with Material 3
- **Architecture**: MVVM with Hilt for dependency injection
- **Location**: Google Play Services Fused Location Provider
- **Networking**: UDP sockets for SignalK communication
- **Serialization**: Kotlinx Serialization
- **Background Processing**: Android Foreground Services
- **Build System**: Gradle 8.4 with KSP (Kotlin Symbol Processing)

## SignalK Data Paths

The app transmits data using standard SignalK paths with quality indicators:

### Navigation Data
- `navigation.position` - GPS coordinates
- `navigation.position.accuracy` - Horizontal accuracy in meters
- `navigation.speedOverGround` - Speed from GPS
- `navigation.speedOverGround.accuracy` - Speed accuracy (Android 8.0+)
- `navigation.courseOverGroundTrue` - Course from GPS  
- `navigation.courseOverGroundTrue.accuracy` - Bearing accuracy (Android 8.0+)
- `navigation.gnss.altitude` - Altitude from GPS
- `navigation.gnss.altitude.accuracy` - Vertical accuracy (Android 8.0+)
- `navigation.gnss.type` - GPS provider (GPS, Network, Fused)

### Planned Sensor Data
- `environment.outside.pressure` - Atmospheric pressure (planned)
- `navigation.headingMagnetic` - Magnetic heading (planned)
- `navigation.headingTrue` - True heading (planned)

## Configuration

- **Default UDP Port**: 55555 (SignalK standard)
- **Default Server**: 192.168.1.100:3000
- **Update Frequency**: 1Hz (configurable to 0.5-2Hz)
- **Location Priority**: High accuracy with sensor fusion
- **DNS Refresh**: Automatic every 5 minutes (handles dynamic IPs)
- **Hostname Support**: Full support for mDNS/Bonjour (e.g., `signalk.local`)

## Building

### Prerequisites

- **Java 17 or higher** (OpenJDK recommended)
- **No Android Studio required** - uses embedded Gradle wrapper
- **Linux/macOS/Windows** supported

### Quick Build (Command Line)

The project includes the standard Gradle wrapper, so no additional setup is needed:

```bash
# Clone the repository
git clone <repository-url>
cd Android-SignalK-Companion

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
│   ├── LocationService.kt           # GPS data collection
│   ├── SignalKTransmitter.kt        # SignalK message handling & UDP transmission
│   └── SignalKBackgroundService.kt  # Foreground service
├── data/
│   └── model/         # Data classes and SignalK models
└── di/
    └── AppModule.kt   # Hilt dependency injection
```

## Permissions Required

- `ACCESS_FINE_LOCATION` - High-accuracy GPS
- `ACCESS_COARSE_LOCATION` - Network-based location
- `INTERNET` - UDP transmission
- `FOREGROUND_SERVICE` - Background operation
- `POST_NOTIFICATIONS` - Service notifications

## Development Roadmap

### Phase 1: MVP ✅
- [x] Basic project structure
- [x] GPS location streaming
- [x] UDP SignalK transmission
- [x] Basic UI with start/stop
- [x] Command-line build system

### Phase 2: Core Features 🔄
- [ ] Additional sensor support
- [ ] Sensor fusion algorithms
- [ ] Data quality metrics
- [ ] Enhanced configuration UI

### Phase 3: Polish 🔄
- [ ] JWT authentication
- [ ] Server discovery
- [ ] Advanced error handling
- [ ] Performance optimization

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

[Add your license here]