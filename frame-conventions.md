# Frame & Sign Conventions

Status: normative · Applies from: M1 onward · Referenced by: `architectural-plan.md` §4, §7

This document fixes the coordinate frames, rotation conventions, signs, units and time
base used everywhere in this project. It exists because frame and sign errors are the
dominant failure mode for projects of this shape: they do not crash, they do not show up
in unit tests written against the same wrong assumption, and they surface months later as
"roll is inverted on port tack" or "the compass is fine except when heeled."

**Rule: this document wins.** If code disagrees with it, the code is wrong — fix the code,
or change this document first, deliberately, with the reasoning recorded. Every module
that consumes or produces a vector, rotation or angle states which frame it is in, using
the notation in §3.

---

## 1. Decision: ENU world, starboard-bow-up vehicle

We use an **East-North-Up (ENU) world frame** and a **Starboard-Bow-Up (SBU) vehicle
frame**, both right-handed.

The nautical/aerospace default is the opposite handedness of intuition here: NED world
(North-East-Down) with an FRD body frame (Forward-Right-Down). We deliberately do *not*
use it.

**Reasoning.** Android's entire sensor stack is ENU-native: `SensorManager.getRotationMatrix`
returns a device→world matrix in ENU, and the device axes are X-right, Y-up-the-screen,
Z-out-of-the-screen. Choosing NED would mean a frame flip at every sensor boundary — the
exact place sign errors breed — in exchange for matching textbook formulas. We instead
pay the cost once, in this document, by writing the nautical output formulas explicitly
(§6) and testing them against reference poses (§10).

**The cost, recorded honestly.** Most AHRS and INS literature (including the standard
Mahony and error-state-KF formulations we are implementing in M1 and M4) is written in
NED with gravity along +Z. Formulas ported from papers must be re-derived, not copied.
Where a ported formula appears in code, the comment must state the source frame and the
transformation applied.

---

## 2. The three frames

| Frame | Symbol | Origin | Axes |
|---|---|---|---|
| World | `W` | Local tangent plane at the current position | X = East, Y = North, Z = Up (away from the ellipsoid) |
| Vehicle (boat) | `V` | Boat reference point (see §2.1) | X = starboard, Y = bow, Z = up (masthead) |
| Device (phone) | `D` | Phone IMU | Android native: X = right edge, Y = top edge, Z = out of the screen |

All three are **right-handed**: X × Y = Z. Verify this whenever constructing a rotation
by hand — a matrix whose determinant is −1 is a reflection and will silently mirror one
axis of the output.

The world frame is treated as locally flat and non-rotating. Earth rotation and transport
rate are below the noise floor of a phone MEMS gyro (Earth rate ≈ 15°/h against a bias of
0.5–5°/min) and are ignored; if a future milestone ever adds a gyro-compassing claim, this
assumption must be revisited first.

### 2.1 Vehicle origin and the lever arm

The vehicle frame's *orientation* is what M1 estimates; its *origin* matters only for
position and acceleration. The phone is mounted away from the boat's center of rotation,
so the phone's origin and the boat reference point differ by a lever arm **r**, expressed
in `V`. Rotation of the boat induces real acceleration at the phone that is not
acceleration of the boat (P7). Until the lever arm is estimated (deferred, M8), it is
handled by band-limiting rather than correction — see P7. When lever-arm compensation is
implemented, `r` is defined from the boat reference point *to* the phone.

---

## 3. Rotation notation

### 3.1 Matrices

`R_A_B` maps a vector **from frame B into frame A**:

```
v_A = R_A_B · v_B
```

Read the subscripts right-to-left as "B into A". Consequences that follow mechanically,
and which you should use to check any expression:

- `R_A_B · R_B_C = R_A_C` — adjacent subscripts cancel. If they do not cancel in an
  expression you have written, the expression is wrong.
- `R_A_B⁻¹ = R_A_Bᵀ = R_B_A` (rotation matrices are orthonormal).

The three rotations in this system, and the chain that connects them:

| Matrix | Maps | Source |
|---|---|---|
| `R_W_D` | device → world | Sensor fusion output (Android's, or our Mahony filter from M1) |
| `R_D_V` | vehicle → device | Mount calibration; stored, user-editable (C2 tilt + yaw constant) |
| `R_W_V` | vehicle → world | `R_W_V = R_W_D · R_D_V` — **the pose we publish** |

Storage is a 9-element `FloatArray` in **row-major** order, matching Android:
`R[row*3 + col]`. Therefore **column j of `R_W_V` is the image of vehicle axis j in world
coordinates** — this is the single most useful fact for reading the extraction formulas
in §6:

```
column 0 = R[0], R[3], R[6]  = starboard direction, in ENU
column 1 = R[1], R[4], R[7]  = bow direction,       in ENU
column 2 = R[2], R[5], R[8]  = mast-up direction,   in ENU
```

### 3.2 Quaternions

The M1 filter state is a quaternion, not a matrix or Euler angles.

- **Hamilton convention** (not JPL). `ij = k`.
- **Scalar first**: `q = (w, x, y, z)`.
- Unit norm; renormalize every update.
- `q_W_V` rotates vehicle-frame vectors into the world frame — the same direction as
  `R_W_V`, so the subscript-cancellation rule of §3.1 applies unchanged.
- Sign ambiguity: `q` and `−q` are the same rotation. Canonicalize to `w ≥ 0` before
  storing, logging, or comparing two quaternions, or a continuity check will see a
  spurious 360° jump.

**Android trap:** `SensorManager.getQuaternionFromVector()` outputs **scalar-first**
`[w, x, y, z]`, but the raw `TYPE_ROTATION_VECTOR` event values are **scalar-last**
`[x, y, z, (w)]`, with `w` sometimes absent and needing reconstruction as
`w = √(1 − x² − y² − z²)`. These two orderings appear within a few lines of each other in
typical Android code. Any conversion must be commented with the ordering it assumes.

### 3.3 Angular rates

`ω_V` is the vehicle's angular rate, expressed in the vehicle frame, in rad/s, following
the **right-hand rule** about each axis (Android's convention, §4.2). Converting the
measured device-frame rate into the vehicle frame uses the *transpose*, because `R_D_V`
maps vehicle→device:

```
ω_V = R_D_Vᵀ · ω_D
```

---

## 4. Sensor input conventions

### 4.1 Accelerometer — measures specific force, not acceleration

`TYPE_ACCELEROMETER` returns **specific force**: `a_measured = a_true − g`. At rest, it
therefore reads **+9.81 m/s² along the up axis**, not −9.81. A phone lying flat, screen
up, reads approximately `(0, 0, +9.81)` in device coordinates.

The vector the accelerometer gives you at rest points **up**. This is the single most
common sign error in AHRS implementations, because the quantity is habitually called
"gravity" while pointing opposite to gravity. In the Mahony gravity correction, the
measured (normalized) vector is compared against the *predicted up direction*, which in
this frame convention is world `+Z` rotated into the body frame — i.e. the third row of
`R_W_V`, not the negation of it.

Units: m/s². Gating per P6 uses `| |a| − 9.81 | < threshold` together with low gyro
activity.

### 4.2 Gyroscope — right-hand rule, and the rate-of-turn sign trap

`TYPE_GYROSCOPE` returns rad/s about each device axis, positive by the **right-hand rule**
(counterclockwise when viewed from the positive axis looking back toward the origin).

For the vertical axis this produces a sign inversion against nautical usage, which must be
applied exactly once, at the publishing boundary:

```
ω_z^V  > 0   →   counterclockwise seen from above   →   turning to PORT
rateOfTurn   =   −ω_z^V                                 (+ve to starboard)
```

Note also that `ω_z^V` requires the mount rotation of §3.3 first. Taking the device's raw
Z rate as rate of turn is correct only for a phone mounted perfectly flat and level, and
wrong by `cos(mount tilt)` otherwise — see the audit in §9.

Use `TYPE_GYROSCOPE_UNCALIBRATED` (P3) and estimate bias ourselves via the Mahony integral
term. Note what that sensor actually gives you: `values[0..2]` is the rate **without** drift
compensation, and `values[3..5]` is the HAL's *estimated* drift — factory and temperature
calibration are applied, gyro-drift compensation is not. We use `values[0..2]` and ignore
`values[3..5]`, since the point of P3 is to own the correction rather than stack on an
opaque one.

Do not read `values[0..2]` as already bias-corrected: it is not, and the Mahony integral
term is what removes that bias. Skipping it because the sensor sounds pre-corrected would
leave a 0.5–5°/min drift in the attitude solution and quietly break gate G1, which depends
on the bias being tracked.

### 4.3 Magnetometer — and which way the field points

`TYPE_MAGNETIC_FIELD_UNCALIBRATED` returns µT in device coordinates in `values[0..2]`,
with the HAL's estimated hard-iron bias in `values[3..5]` (ignored, per P3).

The geomagnetic field vector points toward magnetic north **and downward** in the northern
hemisphere. In ENU world coordinates its vertical component is therefore **negative**:

```
B_W ≈ ( B·cos(I)·sin(δ),  B·cos(I)·cos(δ),  −B·sin(I) )
```

where `I` is inclination (dip) and `δ` is declination. Android's
`GeomagneticField.getInclination()` returns dip in **degrees, positive downward**, so the
minus sign above is required to place it in ENU. Gate G2 compares measured `|B|` and
measured dip against this model; getting the vertical sign wrong makes G2 fire constantly
in one hemisphere and never in the other.

Heading is derived from the field component **projected into the horizontal plane** using
the attitude estimate — never from the raw horizontal components of the device frame,
which are only equivalent when the phone is level.

### 4.4 Declination

`GeomagneticField.getDeclination()` returns degrees, **positive east**.

```
headingTrue = headingMagnetic + declination      (both radians, then normalize per §5)
```

---

## 5. Angle ranges and normalization

| Quantity | Range | Zero / positive direction |
|---|---|---|
| Heading (magnetic, true) | `[0, 2π)` | 0 = North, increasing clockwise (East = π/2) |
| Course over ground | `[0, 2π)` | as heading |
| Roll | `(−π, π]` | 0 = upright, **positive = starboard down** (list to starboard) |
| Pitch | `[−π/2, π/2]` | 0 = level, **positive = bow up** |
| Rate of turn | unbounded | **positive = turning to starboard** |
| Declination | `(−π, π]` | positive = east |
| Inclination (dip) | `[−π/2, π/2]` | positive = field points down (northern hemisphere) |

Normalization helpers must be **branch-free and loop-free**. The current
`normalizeHeading` uses `while` loops (`SensorService.kt`), which is an unbounded loop on
a NaN input — a hang, not an error. Use `x - 2π·floor(x/2π)` for `[0, 2π)` and
`atan2(sin x, cos x)` for `(−π, π]`.

**Angle differences are never subtracted directly.** Use `wrapToPi(a − b)`. Every gate in
P6 compares headings; every one of them is a wraparound bug waiting to happen at the
359°/001° boundary.

**Averaging angles** is likewise never arithmetic: use `atan2(mean(sin θ), mean(cos θ))`,
or average the quaternions/unit vectors instead.

---

## 6. Euler extraction (output only)

Euler angles are a **presentation format**, computed at publish time from `R_W_V` or
`q_W_V`. They are never the filter state, never stored, and never fed back into
computation. This is what keeps gimbal lock a display artifact rather than a filter
failure.

Using the column identities of §3.1, with `R = R_W_V` row-major:

```
heading = atan2( R[1], R[4] )          // atan2(East of bow, North of bow)
pitch   = asin ( clamp(R[7], −1, 1) )  // Up component of bow → bow-up positive
roll    = atan2( −R[6], R[8] )         // −Up of starboard, Up of mast → stbd-down positive
```

These are the formulas currently in `SensorService.updateOrientation()`, and they are
correct for this frame convention — verified against the reference poses in §10. The
`clamp` on the `asin` argument is required: floating-point error can push a legitimately
vertical bow to `1.0000001` and produce `NaN`, which then propagates through every
downstream path silently.

**Singularity.** At `pitch = ±90°` (bow vertical) heading and roll are degenerate and
their difference alone is determined. Not a sailing attitude, but reachable during
handling, mounting, or a dropped phone. The extraction must not produce `NaN`; publish the
last valid heading with a degraded quality flag (P5), and let the quaternion state carry
through untouched.

---

## 7. Time base

| Source | Field | Base |
|---|---|---|
| `SensorEvent` | `.timestamp` | Monotonic nanoseconds since boot |
| `Location` (GNSS) | `.getElapsedRealtimeNanos()` | **Same monotonic base** — use for fusion |
| `Location` (GNSS) | `.getTime()` | UTC wall clock from the GNSS constellation |
| System | `System.currentTimeMillis()` | UTC wall clock — **never for integration** |

Rules:

1. **All integration and all filter timing uses the monotonic nanosecond base.** Wall
   clock jumps when NTP or the GNSS receiver corrects it; a jump backwards produces a
   negative `dt` and can invert or explode the filter in one step.
2. `Location.getElapsedRealtimeNanos()` exists precisely so GNSS fixes can be placed on the
   sensor timeline. M4 uses it to align fixes with IMU propagation. It also gives the true
   fix age, which matters because a fix is delivered later than its validity instant.
3. Wall clock is used **only** for SignalK message timestamps and log filenames.
4. Guard every `dt`: reject non-positive values, and clamp implausibly large ones (a gap
   from a suspended sensor) rather than propagating the filter across the gap.
5. Known Android caveat: a minority of devices have shipped `SensorEvent.timestamp` on a
   base other than `elapsedRealtimeNanos` (uptime excluding deep sleep, or even a vendor
   epoch). The M1 logger records both `SensorEvent.timestamp` and
   `SystemClock.elapsedRealtimeNanos()` at ingest so the offset and drift can be measured
   per device from recordings, rather than assumed.

---

## 8. Units and naming

**SI internally, without exception:** radians, metres, m/s, m/s², rad/s, seconds (as
nanoseconds where integer), Pascals, Kelvin, Tesla-derived µT for raw magnetics.

Degrees appear in exactly two places: the UI, and the persisted mount-calibration angles
(α, β, γ), which are stored in degrees because they are user-facing and hand-editable.
Both boundaries convert explicitly.

Naming rules, so a frame error is visible at the call site rather than three stack frames
away:

| Kind | Pattern | Example |
|---|---|---|
| Rotation matrix | `R_<to>_<from>` | `R_W_V`, `R_D_V` |
| Quaternion | `q_<to>_<from>` | `q_W_V` |
| Vector in a frame | `<name>_<frame>` | `a_D`, `mag_D`, `omega_V`, `vel_W` |
| Angle in degrees | `…Deg` | `alphaDeg` |
| Angle in radians | `…Rad`, or bare SI name | `headingRad`, `roll` |
| Speed | `…Mps` | `sogMps` |
| Monotonic time | `…Ns` | `timestampNs` |

A function that takes or returns a vector without a frame suffix is a defect.

---

## 9. Audit of the current code against this document

Recorded during the M0 pass. These are pre-existing conditions, not regressions; each is
either fixed in M0 or carried explicitly into M1.

| # | Finding | Location | Disposition |
|---|---|---|---|
| A1 | `rateOfTurn` publishes the **raw device-frame** gyro Z rate: no mount rotation (§3.3) and no nautical sign flip (§4.2). It is therefore inverted — positive is published for a turn to port — and additionally wrong by the mount tilt whenever the phone is not flat. | `SensorService.updateGyroscopeData()`; `SignalKTransmitter.kt:447` | **Fix in M0** — added to `architectural-plan.md` §4 |
| A2 | `SensorData.yaw` carries a *rate* (rad/s, a copy of gyro Z) but is published inside `navigation.attitude` as an *angle* (rad). Consumers reading attitude yaw receive a rate. | `SensorService.updateGyroscopeData()`; `SignalKTransmitter.kt:437` | **Fix in M0** — publish no yaw until M1 supplies a real one |
| A3 | Euler extraction formulas are **correct** for this convention. | `SensorService.updateOrientation()` | Keep; §10 tests lock them in |
| A4 | `R_W_V = R_W_D · R_D_V` chain is **correct** and matches §3.1. | `DeviceCalibration.kt` | Keep |
| A5 | `normalizeHeading` uses `while` loops — unbounded on NaN. | `SensorService.normalizeHeading()` | Fix with the §5 branch-free form |
| A6 | Attitude timestamps use `System.currentTimeMillis()`; sensor events are ingested without reference to `event.timestamp`. | `SensorService.updateSensorData()` | Carried into M1 (§7) |
| A7 | α = 0.8 IIR low-pass is applied to accelerometer and magnetometer independently, at whatever rate each sensor happens to deliver. The resulting lag is device-dependent and differs between the two channels, so the fused attitude lags by an unknown, unequal amount per axis. | `SensorService.onSensorChanged()` | Replaced wholesale by the M1 filter |

---

## 10. Reference poses (normative test vectors)

These are the acceptance tests for any attitude implementation. Each row gives `R_W_V`
column-wise (the world-frame images of starboard, bow and mast-up) and the Euler output the
§6 formulas must produce. A change that breaks any row is a frame regression.

| Pose | starboard → | bow → | up → | heading | pitch | roll |
|---|---|---|---|---|---|---|
| Level, bow North | `(1, 0, 0)` E | `(0, 1, 0)` N | `(0, 0, 1)` U | 0° | 0° | 0° |
| Level, bow East | `(0, −1, 0)` S | `(1, 0, 0)` E | `(0, 0, 1)` U | 90° | 0° | 0° |
| Level, bow South | `(−1, 0, 0)` W | `(0, −1, 0)` S | `(0, 0, 1)` U | 180° | 0° | 0° |
| Level, bow West | `(0, 1, 0)` N | `(−1, 0, 0)` W | `(0, 0, 1)` U | 270° | 0° | 0° |
| Bow North, heel 20° to starboard | `(cos20, 0, −sin20)` | `(0, 1, 0)` | `(sin20, 0, cos20)` | 0° | 0° | **+20°** |
| Bow North, bow up 10° | `(1, 0, 0)` | `(0, cos10, sin10)` | `(0, −sin10, cos10)` | 0° | **+10°** | 0° |

Two further invariants worth asserting in the same test file:

- **Right-handedness**: for every pose, `col0 × col1 = col2` and `det(R) = +1`.
- **Round trip**: `decompose(compose(θ)) = θ` for a grid of angles away from the
  singularity, and `compose(decompose(R)) = R` including *at* the singularity (the ZXZ
  helpers in `DeviceCalibration` already promise this — keep it).

Sign checks that catch the errors this document exists to prevent, stated in plain
language so they can be verified on a boat rather than only in a test:

- Heel to **starboard** → roll **positive**.
- **Bow up** → pitch **positive**.
- Turning to **starboard** → rate of turn **positive**, heading **increasing**.
- Bow swinging North → East → South → West → heading increases 0° → 90° → 180° → 270°.

---

## 11. Open items

- **SignalK output mapping must be verified against the spec version in use** before M5
  publishes quality metadata. Our internal conventions (§5) are chosen to match what we
  believe the SignalK spec states — `navigation.attitude.roll` positive to starboard,
  `.pitch` positive bow up, `navigation.rateOfTurn` positive to starboard — but the
  attitude object's `yaw` semantics are ambiguous in the spec text, and this has not been
  confirmed against a consuming plotter. Until confirmed, publish `roll` and `pitch` only,
  and carry heading on `navigation.headingMagnetic` / `headingTrue`, which are
  unambiguous. Resolves the related item in `architectural-plan.md` §7.
- **Lever arm `r`** direction and origin definition (§2.1) are fixed here, but the value is
  unmeasured. Deferred to M8 per P7.
- **Ported filter formulas** from NED-frame literature (§1) should each carry a comment
  naming the source convention. Worth a lint or review checklist entry once M1 lands.
