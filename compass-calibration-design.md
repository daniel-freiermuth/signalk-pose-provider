# Compass Calibration — Design Notes

## Magnetic Distortion Sources

| ID   | Source                      | Frame    | Varies with…                | Correctable? |
|------|-----------------------------|----------|-----------------------------|----|
| D1   | Magnetometer hardware bias  | Device   | Temperature, time           | C1 |
| D2   | Hard-iron (device)          | Device   | Fixed (speaker, battery)    | C1 |
| D3   | Soft-iron (device)          | Device   | Fixed (metal chassis)       | C1 |
| D4a  | Hard-iron (boat, fixed)     | Boat     | Fixed (keel, engine block)  | C4 |
| D4b  | Hard-iron (boat, variable)  | Boat     | Electrical loads, engine RPM | C4 (per-state) |
| D5   | Soft-iron (boat)            | Boat     | Fixed (steel hull, rigging) | C4 |
| D6   | Mounting orientation        | Device→Boat | Fixed (bracket)          | C2+C3 |
| D6b  | External environment        | World    | Position (transient)        | Detection only |
| D7   | Magnetic declination        | World    | Geographic position, year   | C5 |

## Calibration Layers

| ID   | Name                    | Counters     | Method | Status |
|------|-------------------------|-------------|--------|--------|
| C1   | Android auto-cal        | D1+D2+D3    | Figure-8 → HAL ellipsoid fit. Starves when mounted. | Built-in |
| C2   | Tilt calibration        | D6 (tilt)   | `calibrateTilt` solves α,β from gravity at dock | ✅ |
| C3   | Single-point azimuth    | D6 (heading) + D4 at one heading | `calibrateAzimuth` sets γ from GPS. Special case of C4 (A-only). | ✅ |
| C4   | Deviation curve         | D4a+D4b+D5  | Sail ~360°, collect (GPS heading, mag heading) pairs, fit 5 Fourier coefficients: δ(θ) = A + B·sin(θ) + C·cos(θ) + D·sin(2θ) + E·cos(2θ). Linear least-squares (5×5). | ❌ Not yet |
| C5   | Declination             | D7          | Android GeomagneticField from position+date | ✅ |

## Interference Between Layers

- **C2 ↔ C3/C4**: Independent by construction (tilt=α,β; heading=γ/deviation curve)
- **C3 ↔ C4**: C4 subsumes C3. If C4 is active, C3 is redundant (A coefficient = γ)
- **C1 ↔ C4**: Different frames (device vs world), don't interfere in principle. BUT: stale C1 errors leak into C4 as extra apparent deviation. If Android's HAL later updates C1 background, C4 becomes stale. Mitigation: re-swing after any magnetic environment change.
- **Alternative**: Use TYPE_MAGNETIC_FIELD_UNCALIBRATED to bypass C1 entirely, do our own full ellipsoid + deviation fit. Much more complex (12+ params). Deferred.

## Magnetic Interference Detection (Gyro-Based)

**Principle**: Gyroscope (Coriolis-based, immune to magnetic fields) gives angular rate.
Compare integrated gyro Δheading to magnetometer Δheading over sliding window.

- $\Delta\theta_{\text{gyro}} = \int \omega_z \, dt$ vs $\Delta\theta_{\text{mag}} = \theta_{\text{mag}}(t) - \theta_{\text{mag}}(t - \Delta t)$
- If $|\Delta\theta_{\text{gyro}} - \Delta\theta_{\text{mag}}| > \text{threshold}$ consistently → flag "magnetic interference"

**Advantages over GPS comparison:**
- Works at any speed including stationary (GPS needs >0.5 kn)
- Higher sample rate (50–200 Hz vs 1 Hz)
- Detects transient events (passing steel boat) within seconds
- GPS heading is noisy at low speeds

**Limitation:** Gyro drifts 0.5–5°/min on phone MEMS — good for relative (interference detection), not absolute heading.

**Design:**
- Sliding window ~5 seconds of integrated gyro Δheading vs mag Δheading
- RMS divergence exceeds threshold → "Magnetic interference detected"
- Independent of all calibration layers — pure consistency check

## Deviation Curve (C4) — Calibration Sail

During a calibration sail the user motors/sails slowly through ~360° of heading:

1. Collect pairs $(h_{\text{GPS}}, h_{\text{mag}})$ at ~1 Hz when SOG > threshold
2. Compute deviation: $\delta_i = h_{\text{mag},i} - h_{\text{GPS},i}$
3. Fit 5 Fourier coefficients via linear least-squares:

$$\delta(\theta) = A + B \sin\theta + C \cos\theta + D \sin 2\theta + E \cos 2\theta$$

4. At runtime, correct heading: $h_{\text{corrected}} = h_{\text{mag}} - \delta(h_{\text{mag}})$

The $A$ coefficient absorbs what single-point azimuth calibration (C3) currently does. The higher-order terms ($B, C, D, E$) capture hard-iron and soft-iron effects that vary with heading.

**Coefficients explained (traditional compass terms):**
- $A$ — constant offset (index error)
- $B, C$ — semicircular deviation (hard-iron: permanent magnets, keel)
- $D, E$ — quadrantal deviation (soft-iron: steel hull distorting field lines)

## Implementation Strategy Options

Three architectures for how calibration layers stack:

### Option A — Stack on top of Android C1 (current approach)
Use `TYPE_ROTATION_VECTOR` (which already incorporates Android's auto-calibration C1).
Apply C2 (tilt), C3/C4 (azimuth/deviation), C5 (declination) on top.

- ✅ Simple — Android handles ellipsoid fitting transparently
- ✅ Works well when phone has fresh C1 from recent figure-8
- ❌ If Android's HAL silently updates C1 in the background, C4 coefficients become stale
- ❌ No visibility into what C1 is doing — can't tell if it's fresh or degraded
- ❌ C1 "starves" when phone is mounted (no figure-8 possible) — may use a cached calibration of unknown age

### Option B — Raw magnetometer pipeline
Use `TYPE_MAGNETIC_FIELD_UNCALIBRATED` to bypass C1 entirely.
Fit our own 12-parameter ellipsoid (bias + scale + cross-coupling) then apply C4 on top.

- ✅ Fully controlled — no silent HAL updates
- ✅ Can trigger calibration on demand (e.g., after engine on/off)
- ❌ Much more complex: 12+ parameters, needs good numerical solver
- ❌ Requires exposing rotation vector pipeline (currently uses fused `TYPE_ROTATION_VECTOR`)
- ❌ Phone gyro + mag fusion requires reimplementing what Android already does well
- 🔲 Deferred — high complexity, marginal gain over Option A + C4

### Option C — Quality-gated correction (recommended near-term)
Stay with Option A but gate correction application on quality signals:

- Apply C4 deviation curve **only when** `magnetometerAccuracy == HIGH`
- Apply C4 **only when** gyro interference detector says "clean"
- If either condition fails: fall back to C3 (single-point γ) and warn user
- Report correction quality via SignalK (`navigation.headingMagnetic.quality` or custom path)

This gives Option A's simplicity while being honest about when corrections are unreliable.
It also makes the gyro detector directly useful (gates C4 application, not just a UI warning).

## Implementation Priority

1. ✅ C2 (tilt) + C3 (single-point azimuth) + C5 (declination) — Done
2. ✅ Magnetometer accuracy display + warning banner — Done
3. 🔲 Gyro-based interference detector — Next (high value, moderate effort)
4. 🔲 C4 deviation curve (calibration sail) — After gyro detector
5. 🔲 Raw magnetometer pipeline — Deferred (high complexity, marginal gain over C4)

## Key Insight: D6b is Uncorrectable

External magnetic interference (marinas, steel piles, cables, bridges) is transient and position-dependent. No calibration can fix it. The correct response is **detection + honesty about uncertainty**, not more sophisticated correction. This makes the gyro detector arguably more valuable than the deviation curve.
