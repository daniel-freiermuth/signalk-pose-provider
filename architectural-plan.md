# signalk-pose-provider — Architecture Plan & Decision Record

Status: reviewed & revised (Aug 2026) · Supersedes: `compass-calibration-design.md` (kept as historical record)

This document captures the conclusions of the design review (Aug 2026), records which
previous decisions are superseded and why, and lays out milestones with explicit
dependencies. Style: decisions are recorded with their reasoning so the "why" survives
in the repo, not only in a chat log.

Revision note (Aug 2026 code-verified review): a pass over the actual code
(`LocationService.kt`, `SensorService.kt`, `SignalKTransmitter.kt`) confirmed P2 and P8's
premises, corrected the description of the shipped attitude baseline (§3, correction c),
narrowed one over-claimed benefit in P9, and promoted a raw-logging + offline replay
harness into M1 as its first deliverable. Changes are marked inline.

---

## 1. Scope & Goals

The app is a **pose provider** for a SignalK network: it turns a rigidly mounted Android
phone into a boat attitude + position + velocity instrument.

Deliverables, in priority order:

1. **Attitude** (roll, pitch, heading) at high rate (50–200 Hz), correct under sailing
   dynamics (heel, waves), with heading integrity monitoring.
2. **Position / SOG / COG** that is jump-free: GNSS anchored, IMU-refereed.
3. **Honest quality signals** on the SignalK bus for every published quantity.

Explicit non-goals: indoor navigation, IMU dead reckoning beyond the inter-fix
interval, replacing certified navigation equipment.

## 2. Core Architectural Principles

These are the load-bearing decisions. Everything in the milestones follows from them.

**P1 — The IMU is a referee, not a position source.**
Double integration of MEMS accelerometers diverges quadratically (meters within tens of
seconds). The accelerometer's value is (a) high-rate propagation between GNSS anchors
with a ~1 s leash, reset on every accepted fix, and (b) vetoing GNSS samples that imply
dynamics the IMU didn't see. Never open-loop extrapolation. When GNSS is rejected, the
filter coasts on a constant-velocity model with inflated uncertainty — it does not
"navigate" on integrated acceleration.

**P2 — Velocity comes from Doppler, not position differencing.**
Chip Doppler SOG/COG is cm/s-class and jump-free; differenced positions amplify
multipath into fake velocity. (Already implemented correctly — keep.)

**P3 — Own the sensor pipeline (raw sensors + own fusion).**
Use `TYPE_MAGNETIC_FIELD_UNCALIBRATED`, `TYPE_GYROSCOPE_UNCALIBRATED`,
`TYPE_ACCELEROMETER` and run our own Mahony filter, instead of stacking corrections on
`TYPE_ROTATION_VECTOR`. Reasons: the stock fusion (i) pulls "down" toward apparent
vertical on sustained heel — systematic under-read of roll exactly when sailing,
(ii) depends on Android's opportunistic magnetometer calibration (C1), which starves
when mounted and updates silently, staling any correction stacked on top, (iii) exposes
no gateable correction gain. A Mahony filter with adaptive gain is ~200 LoC; the
ellipsoid calibration is a *linear* least-squares (~100 LoC, one eigendecomposition).
The earlier estimate "12+ params, needs good numerical solver, marginal gain"
(compass-calibration-design.md, Option B) overpriced the cost and underpriced the gain.
Note the shipped code is not even Option A (see §3, correction c): it computes attitude
from C1-calibrated `TYPE_MAGNETIC_FIELD` + `TYPE_ACCELEROMETER` with no gyro in the
attitude loop at all — raw low-passed acceleration is the vertical reference, so heel
and wave dynamics corrupt roll/pitch *more* than stock fusion would. This strengthens
the case for M1; the C1-staleness critique applies unchanged since
`TYPE_MAGNETIC_FIELD` is C1-calibrated.
Implementation constraints that decide success at 100–200 Hz: integrate on
`event.timestamp` (monotonic nanoseconds — never wall-clock; the current pipeline's
`System.currentTimeMillis()` stamping is unusable for integration), register with an
explicit `samplingPeriodUs` rather than `SENSOR_DELAY_*` buckets, and tolerate
asynchronous sensor rates — many phones cap the magnetometer at 50–100 Hz while the
gyro does 200+, so the filter propagates on gyro ticks and corrects on whatever
mag/acc samples arrive. The Mahony filter must carry the integral (PI) term for
explicit gyro-bias estimation: phone gyros drift 0.5–5°/min, and gate G1's usefulness
depends on that bias being tracked.

**P4 — Calibration is a measurement, not a background process.**
Static calibration, performed on demand under controlled conditions, stored with
timestamp and history. Continuous auto-calibration cannot distinguish "my offset was
wrong" from "the field changed" and silently absorbs disturbances (engine start, cable
crossings) into the calibration. Instead: **fixed calibration + continuous
validation**. Validators *flag and gate*; only an explicit user action recalibrates.

**P5 — Detection + honesty over sophisticated correction.**
(Carried over verbatim from the old design doc — its best idea.) External disturbances
(D6b: cables, steel piles, marinas, geological anomalies) are uncorrectable by
construction. The system's job is to know when it doesn't know, freeze the affected
correction, coast on the gyro, and publish degraded quality.

**P6 — Heading integrity comes from three gates with complementary blind spots.**

| Gate | Reference | Catches | Blind to |
|---|---|---|---|
| G1: Gyro innovation | gyro rate vs. mag Δheading, ~5 s window | fast disturbances (seconds) | slow onset, pre-existing offsets |
| G2: WMM magnitude + inclination | GeomagneticField model vs. measured field | quasi-static disturbances at any onset speed; stale calibration | pure-rotation disturbances preserving |B| and dip (rare) |
| G3: COG differential (at speed) | Doppler COG, differential form | anything magnetic (COG is immune) | low SOG; degenerate with current changes unless STW available |

All three feed the same lever: the Mahony magnetometer gain. Freeze on any gate firing;
re-admit only after sustained agreement (hysteresis), never on a single clean sample —
otherwise fluctuating disturbances flap the gate and inject the very jumps we're
removing. Same pattern on the accelerometer axis: gate the gravity correction on
|a| ≈ 1 g and low gyro activity; use a long correction time constant (tens of seconds)
so periodic wave accelerations cancel instead of contaminating roll/pitch.

**P7 — Boat dynamics are a prior.**
An Allegro 27 does 0–7 kn and ~10°/s in a hard tack. GNSS innovations implying
impossible dynamics are gated. The mounted phone is off the boat's center of motion:
roll-induced lever-arm accelerations are real for the phone, not the boat — low-pass
below the wave/roll band for SOG/COG purposes (or estimate the lever arm later).

**P8 — Pure GNSS source, not the Fused Location Provider.**
Switch from FLP to `LocationManager` + `GPS_PROVIDER`. FLP blends GNSS with WiFi/cell
database positions using opaque, pedestrian/car-tuned logic — it can snap to a marina
access point's database location or switch providers mid-track, i.e. it *injects* the
discontinuities this project exists to remove, and its error characteristics (database
teleports) violate M4's filter assumptions (multipath-like, gateable, ~zero-mean).
`GPS_PROVIDER` gives unadulterated fixes with per-fix accuracy, speed/bearing with
their accuracies, and satellite metadata. (Real receivers derive velocity from carrier
Doppler — that is what makes it jump-free — but the Android API guarantees the value, not
its provenance; verifying it directly needs the deferred raw `GnssMeasurement` layer.) Side effect: removes the proprietary Play
Services dependency — unblocking an F-Droid-clean build. Deferred (recorded honestly,
like old Option B): the raw `GnssMeasurement` layer (carrier smoothing, dual-frequency
processing) offers further headroom but is high effort for modest gain on a phone
antenna; revisit only if meter-class proves insufficient.

**P9 — The sea is flat: altitude is a constraint, not a measurement.**
GNSS altitude (5–15 m wander, the weakest GNSS output) is not navigation data for a
boat. Height above the ellipsoid = geoid undulation (model) + tide (decimeters, known)
+ heave (periodic, zero-mean) — a sub-meter prior. Use it two ways: publish honest
altitude, and gate fix quality (a fix whose altitude sits far from geoid + tide is
suspect — down-weight or reject it alongside the P7 dynamics gates).
*Corrected on review:* the originally claimed third use — clamping the vertical channel
to tighten the horizontal solution via satellite geometry — happens *inside* the
position solution, where the geometry lives. With finished `GPS_PROVIDER` fixes the
cross-axis covariance is already collapsed to per-axis accuracy scalars, so a
loosely-coupled filter gains essentially nothing horizontally from an altitude
constraint. That benefit is real but belongs to the deferred raw `GnssMeasurement`
layer (P8 / M8). The barometer (~10 cm relative
resolution) is useless for absolute altitude but is a heave-band sensor: fuse into the
M8 heave estimate; its primary marine job (pressure trend for weather) is unaffected.

## 3. Superseded Decisions (and why)

| Old decision | Status | Reason |
|---|---|---|
| Option A/C: stack corrections on `TYPE_ROTATION_VECTOR` + Android C1 | **Superseded** by P3 | Stale-C1 leakage, heeled-reach vertical error, no gateable gain. Option C's quality gating survives — generalized into P6 — but on top of our own pipeline. |
| C4 Fourier deviation curve **as correction** | **Superseded** — demoted to validation report | Fatal degeneracy: with COG as reference at a single speed, a constant current produces a crab angle ≈ arcsin(v_c/v_b · sin(θ−θ_c)) — first-order identical to semicircular (B, C) deviation. A 0.5 kn current at 4 kn forges a clean, well-fitted, spurious ~7° deviation curve. Ellipsoid calibration needs no external heading reference (truth criterion is internal: constant field magnitude), so it is immune. Fourier machinery survives as a *diagnostic*: residual semicircular structure after calibration ⇒ heeling error or unmodeled current. |
| C3 single-point azimuth as separate layer | **Absorbed** | Becomes the single yaw-alignment constant of the mount rotation. The intended determination — reciprocal runs averaged, a slack-water leg, or a surveyed sighting — is the **M2 target**, not today's behaviour: the shipped flow still accepts a single GPS course above the 2.5 kn gate (§4), which reduces crab bias without cancelling it. One correction pipeline, one provenance record — not two layers that can silently disagree. **Absorbed into the mount rotation, not into C4** — the old doc's "special case of C4 (A-only)" holds for the heading offset only. C4 is a scalar δ(θ) and produces no tilt information, so it cannot replace mount calibration; and γ (mechanical, fixed until the bracket moves) is a different physical quantity from C4's A (magnetic, varies with the boat's state) even where their effects coincide. |
| Gyro detector as standalone "warning UI" feature | **Repurposed** | Becomes gate G1 feeding the Mahony gain (P6). The UI warning is a side effect of the gate state, not the product. |

Corrections to the *conversation* record too, for symmetry: (a) a flat calibration
circle constrains the ellipsoid weakly in the vertical axis — heeled data from both
tacks must be folded into the fit, or vertical terms regularized toward a device-only
calibration; stated limitation, not a surprise residual. (b) The D1–D7 distortion
taxonomy and the P5 honesty principle from the old doc were better than anything in the
chat and are retained. (c) *Added on code review:* the old doc labeled Option A
(`TYPE_ROTATION_VECTOR` + corrections) as "current approach" — the shipped code never
actually used it. `SensorService.kt` computes attitude from C1-calibrated
`TYPE_MAGNETIC_FIELD` + `TYPE_ACCELEROMETER` through an α = 0.8 IIR low-pass and
`SensorManager.getRotationMatrix`, with the gyro published but unused in the attitude
loop — i.e. a tilt-compensated compass, weaker under dynamics than stock fusion. The
"before" reference for M1's comparison trace is this pipeline, not
`TYPE_ROTATION_VECTOR`; keep both as comparison traces in dev builds so the improvement
is measured against what actually shipped.

Verified against code during the same review (claims that hold): all published SOG/COG
originate from `Location.getSpeed()`/`getBearing()` with their accuracy fields
(`SignalKTransmitter.kt` — the §4 audit item is confirmed done); at the time of that
review the position source was still FLP (`LocationService.kt`,
`FusedLocationProviderClient`), confirming the P8 switch below was live work rather than
hypothetical — that switch has since landed, and §4's checklist is the current state, not
this paragraph; Doppler speed/bearing/accuracies are `Location` fields and survived the
switch.

## 4. Cleanup (current-version hygiene)

Items to fix/remove **before** new construction, so the repo doesn't carry two
architectures:

- [x] **Write the frame-conventions document** — frames, rotation notation, sign conventions, angle ranges, time base, unit/naming rules, and normative reference poses. Promoted from §7 into M0 on review: it gates M1 coding exactly the way the rest of this checklist does, and frame sign errors are where projects like this die. → [`frame-conventions.md`](frame-conventions.md) *(done)*
- [x] Fix `navigation.rateOfTurn` sign and frame (audit A1): it published the **raw device-frame** gyro Z rate — inverted against SignalK's +ve-to-starboard convention, and additionally wrong by the mount tilt whenever the phone isn't mounted flat. Now `DeviceCalibration.rateOfTurnFromGyro`, applying `ω_V = R_D_Vᵀ ω_D` then `−ω_z^V`, with sign tests.
- [x] Stop publishing `navigation.attitude.yaw` (audit A2): the field carried a gyro *rate* (rad/s) in a slot consumers read as an *angle*. `SensorData.yaw` is removed outright so it cannot be repopulated by accident; `roll`/`pitch` only. Subsequently confirmed **permanent** rather than pending M1: SignalK defines no datum for `attitude.yaw`, so no value has an unambiguous meaning there (`frame-conventions.md` §11.3).
- [x] Replace `normalizeHeading`'s `while` loops with the branch-free form (audit A5) — the old version was an unbounded loop, i.e. a hang, on NaN. Now `DeviceCalibration.wrapTo2Pi`, with a non-finite-input test.
- [x] Mark `compass-calibration-design.md` as superseded (banner + link here); do not delete — it's the decision history.
- [x] Remove/park any C4-as-correction scaffolding if present. *Verified absent (Aug 2026): no Fourier/deviation-curve code was ever written; the only calibration code is C2/C3 mount alignment. Nothing to remove.*
- [x] Audit that **all** published COG/SOG originate from `Location.getSpeed()`/`getBearing()` with their accuracy fields — never from position differencing anywhere in the pipeline. *Verified in code review (Aug 2026): `SignalKTransmitter.kt` publishes Doppler speed/bearing + accuracies; no position differencing found.*
- [x] `test_signalk_json.kt` at repo root → **removed.** It was a scratch `main()` demo outside every source set (so it never compiled with the project), duplicating what `SignalKTransmitterTest` already covers. Git history keeps it.
- [x] README roadmap: rewrite "Planned" section against the milestones below; remove Option A/C language. *Also documents the honest caveats on today's data (uncalibrated heading, dynamics-degraded roll/pitch, ellipsoidal altitude). The `gradle-wrapper.jar` gap it originally flagged is closed — the jar is now committed, so `./gradlew` works on a fresh clone.*
- [x] **Decided:** keep the C3 azimuth implementation, with a speed guardrail. Note the earlier wording here ("interim… until M2") was misleading: **mount calibration is permanent architecture**, not a stopgap. C4 could never replace it — C4 is a scalar δ(θ) on heading and cannot produce the mount's tilt angles at all, so a tilted mount corrupts roll and pitch with or without a deviation curve. What M2 changes is only *how well γ can be measured*, not whether it is needed.
  - **Guardrail added:** minimum 2.5 kn SOG for any GPS-referenced calibration, up from 0.5 m/s (≈1 kn). γ is set by equating heading with GPS *course*, so leeway and current-induced crab go straight into it; crab ≈ asin(cross-current / boat speed), which is ~30° at 1 kn in a 0.5 kn stream versus ~12° at 2.5 kn. The gate now fails loudly rather than silently doing nothing.
  - **Procedure, in-app:** figure-of-eight the phone *before* mounting (Android's own magnetometer calibration starves once it's in a bracket, and re-swinging after mounting is impossible without disturbing γ), then calibrate motoring straight and level, clear of the marina. γ is measured *through* the compass, so a bad compass yields a bad mechanical constant — and a marina is the worst magnetic environment there is.
  - **The gate does not cancel current, it only makes it matter less.** A current-cancelling reference — reciprocal runs averaged, a slack-water leg, or a surveyed sighting (§3) — is the actual fix, and was considered here and deliberately deferred to M2, where the calibration UI gains the provenance and history storage to hold two runs. Recorded so a later reader sees a decision, not an oversight.
  - **Follow-ups not taken here** (say the word): gate on magnetometer accuracy, require low rate-of-turn, and store γ with provenance (timestamp, SOG, COG, accuracy) so a γ measured before M2 is identifiable. Any γ set today is provisional not because C3 is going away, but because it was measured with an uncalibrated instrument — **re-measure γ once M2 lands.**
- [x] Publish nothing on `navigation.headingMagnetic` that implies calibrated quality. **Resolved spec-natively**, better than the custom marker first written for this: SignalK already distinguishes `headingCompass` ("not adjusted for magneticDeviation") from `headingMagnetic` ("headingCompass adjusted for magneticDeviation") from `headingTrue`. We publish `navigation.headingCompass` — whose spec description *is* our situation — plus `navigation.magneticVariation`, and neither `headingMagnetic` nor `headingTrue`, which would assert a correction we haven't made. The custom `.quality` path is dropped. See `frame-conventions.md` §11.2.
- [x] Replace Fused Location Provider with `LocationManager` + `GPS_PROVIDER` (P8). This is cleanup, not a milestone: it changes the *input*, needs no new filtering, and every day on FLP is a day of database-teleport jumps in the data. Verify Doppler speed/bearing + accuracy fields survive the switch (they do — they're `Location` fields, not FLP features). Note the removed Play Services dependency in the README (F-Droid path). Expectation to manage: `GPS_PROVIDER` delivers fixes at the GNSS chip's native ~1 Hz; FLP's chattier sub-second callbacks were mostly interpolation/repeats, but users may perceive a rate downgrade until M4 upsamples — one README sentence.

## 5. Milestones

Designed so that each milestone leaves the app shippable. Dependency column shows hard
prerequisites; unlisted milestones are mutually independent and stackable.

| # | Milestone | Contents | Depends on | Ship value |
|---|---|---|---|---|
| M0 | **Cleanup** | Section 4 checklist | — | Repo tells one story |
| M1 | **Raw sensor pipeline + Mahony AHRS** | **First deliverable: raw logging + offline replay harness** — record uncalibrated mag/gyro/acc (at `event.timestamp`, monotonic ns) + GNSS fixes; replay recordings through the filter offline. Every sail becomes a regression dataset; filter tuning happens against recordings, not on the water. Then: raw sensor ingestion at 100–200 Hz (explicit `samplingPeriodUs`, asynchronous mag/gyro rates handled — see P3); Mahony **PI** (integral term = gyro-bias estimation) with explicit, gateable acc/mag gains; adaptive gravity gating (wave logic); mount rotation (C2 tilt + yaw constant, replacing C3); declination (C5) on top; stock `TYPE_ROTATION_VECTOR` **and** the current tilt-compensated-compass pipeline kept as parallel comparison traces (dev builds) | M0 | Correct roll/pitch under heel — the core instrument |
| M2 | **Magnetometer calibration (ellipsoid)** | Calibration mode: log a slow circle (+ heeled segments both tacks), linear LSQ ellipsoid fit, before/after residual display, timestamped storage **with history**; explicit re-swing procedure documented in-app. **Publishing promotes** from `navigation.headingCompass` to `headingMagnetic`, adding `navigation.magneticDeviation` (the correction actually applied) and `headingTrue` (`frame-conventions.md` §11.2). **Prompt a γ re-measurement** on completion: the mount yaw constant is measured through the compass, so every γ set before this milestone carries the uncalibrated magnetometer's error (§4) | M1 | Heading becomes trustworthy; current-immune by design |
| M3 | **Heading integrity gates (G1–G3)** | Three gates per P6, hysteresis re-admission, single gain lever; quality state machine | M1 (G1, G2); M2 for meaningful absolute heading; G3 needs Doppler COG (already present) | Cable crossings, engine start, marina steel handled honestly |
| M4 | **Position/velocity filter** | Loosely-coupled error-state KF (or gated complementary filter as v1): Doppler velocity + position as measurements, IMU propagation on 1 s leash (P1), innovation gating with boat-dynamics priors (P7), lever-arm band-limiting; sea-level altitude plausibility gate per P9 (geoid + tide prior) and honest altitude publishing — horizontal tightening from the altitude constraint is deferred with the raw-GNSS layer (P9 correction) | M0 (P8 source switch), M1 | GPS jumps gone; smooth 10–50 Hz pose out; honest altitude |
| M5 | **Quality publishing** | Per-path quality/uncertainty on SignalK (heading gate state, position mode: anchored/coasting, calibration age); consumers inherit the honesty | M3 or M4 (publishes their states) | Whole-boat benefit; anchor alarm & plotter get trust levels |
| M6 | **STW subscription** | SignalK WebSocket *consumer* for `navigation.speedThroughWater` → continuous current vector estimate (ground velocity − heading·STW); sharpens G3 into corrected-residual form; enables current display | M3, M4 | Closes the heading/COG/current triangle every second |
| M7 | **Validation & diagnostics** | Fourier residual report post-calibration (the demoted C4): per-tack residuals ⇒ heeling-error estimate; calibration history trending (magnetic biography of the boat); maneuver-based current fix (every tack = free current estimate) as fallback where no STW | M2 (+M6 optional) | Turns residuals into boat knowledge |
| M8 | **Nice-to-haves** | Heave from band-limited single integration (wave-periodicity anchored), fused with barometer heave-band signal (P9); geofenced suspicion near charted cable corridors; two-speed calibration sail to fit current as nuisance params where STW absent; raw `GnssMeasurement` layer only if meter-class proves insufficient (deferred per P8; also where P9's altitude-aided horizontal tightening actually lives) | M1–M4 | Polish |

**M1 in progress.** Landed so far: `Quaternion` and `MahonyAhrs` (the attitude filter),
plus `Recording` and `ReplayRunner` (the harness). `MahonyAhrs` is the PI filter with
gyro-bias estimation, gateable acc/mag gains, TRIAD attitude seeding, and the §7 time
guards. The harness records raw uncalibrated sensors with the HAL's own bias estimates
stored *alongside* rather than pre-subtracted, so a recording stays replayable when the
correction strategy changes at M2, and replays are deterministic — same recording plus same
configuration gives the same trace, which is what makes a sail a regression test rather than
an anecdote. All pure JVM, 36 behavioural tests against synthetic sensors derived from a
known truth, so a sign error in the ENU re-derivation shows up as divergence rather than a
plausible number.

`RawSensorSource` now provides the Android ingestion half: uncalibrated magnetometer and
gyroscope plus accelerometer, at an explicit `samplingPeriodUs` (200 Hz default) on a
dedicated `HandlerThread` rather than the main looper, stamped with `event.timestamp`. It
falls back to the calibrated sensor types where a device lacks the uncalibrated ones and
reports that in `availability`, so a recording cannot misrepresent its own provenance.
`RecordingWriter` carries the file side, with a byte budget — 200 Hz across three sensors
is ~35 kB/s, so a six-hour passage is on the order of 750 MB, which is affordable but not
something to leave running by accident.

**Still missing from M1:** wiring the recorder and filter into the running app —
`SensorService` still runs the legacy tilt-compensated compass, and nothing publishes from
`MahonyAhrs` yet; UI to start and stop a recording; and the parallel comparison traces
against stock fusion and today's pipeline. No gain is tuned — the harness exists precisely
so tuning happens against recorded sails rather than against my guesses.

Two departures from the milestone text, both deliberate:
- **The filter came before the logging/replay harness**, inverting the stated order. The
  harness earns its keep when tuning against recorded sails; writing the filter needed only
  synthetic data, and synthetic data is what can be executed in an environment without an
  Android SDK. The harness is still the next piece, and nothing here is tuned yet.
- **Attitude is seeded algebraically** (TRIAD from the first accelerometer/magnetometer
  pair) rather than iterated up from identity. Testing found the iterative cold start sits
  on the antipodal unstable point — still reading 0° after 90 s of simulated time when truth
  was 180° — because the correction is a cross product of two nearly-opposed vectors.
  Seeding is exact immediately, and is gated on the same plausibility checks as the
  correction so a slam or a distrusted magnetometer cannot seed a confident wrong answer.

**Open decision, needed before M1 lands on a boat.** M1 reads
`TYPE_MAGNETIC_FIELD_UNCALIBRATED` but the ellipsoid fit that replaces Android's C1 does not
arrive until M2, leaving a window with *no* hard-iron correction at all — worse heading than
today, since we would have dropped Android's correction without having our own. Three ways
out: accept it (M1's ship value is roll/pitch under heel, and heading already publishes as
`headingCompass` with the caveat attached); subtract `values[3..5]`, the HAL's own bias
estimate, as an M1-only stopgap; or pull the ellipsoid fit forward into M1. The filter takes
already-corrected magnetometer input precisely so this stays a caller decision.

**Stacking notes.** M1→M2→M3 is the attitude track; M4 is the position track and only
needs M1 (attitude for gravity removal + frame rotation) — the two tracks can proceed
in parallel after M1. M5 attaches to whichever track lands first. M6/M7/M8 are
independent add-ons. The minimal "better than today" release is M0+M1; the minimal
"instrument-grade" release is M0–M4. The M1 logging/replay harness is a standing
dependency of everything after it: M2's fit quality, M3's gate thresholds, and M4's
filter tuning are all developed and regression-tested against recorded sails — it is
the single cheapest de-risking artifact in the plan and is built first for that reason.

## 6. Known Limitations (stated up front)

- **Heeling error**: ellipsoid fit with mostly-level data under-constrains the vertical
  axis; mitigated by heeled calibration segments, detected by per-tack residuals (M7),
  never fully eliminated without a heeling-error model.
- **Low-SOG COG**: intrinsically ill-defined below ~0.5 kn; do not over-smooth drift
  into fiction — at anchor, the wander *is* the signal.
- **Configuration-dependent hard iron (D4b)**: engine/instrument state can shift the
  offset; verify magnitude of the effect once (engine on vs. off swing) and either
  ignore, or store per-state calibrations.
- **G2 evasion**: a disturbance that rotates the field while preserving magnitude and
  inclination slips gate G2; G1 catches it if fast, G3 if at speed. Union of gates is
  hard to fool; no single gate is.
- **Mount is fixed-ish**: plastic flexes, temperature moves things — cheap re-zero of
  tilt at the dock each season/sail.
- **Power & thermal**: sustained 100–200 Hz sensor processing in a foreground service
  is a real battery and thermal load on an always-on mounted phone (often in the sun).
  Measure early in M1; expect to want a reduced-rate mode, and verify wakelock/Doze
  behavior on target devices — a filter that silently stops sampling at 2 a.m. on
  anchor watch is a designed-in failure, not a corner case.
- **Not an indoor system**: gates firing everywhere + wrong motion priors = designed,
  honest failure. Degraded-GNSS *outdoor* scenarios (harbor approaches, bridges,
  steel neighbors) are the actual payoff zone.

## 7. Open Questions

- ESKF vs. gated complementary filter for M4 v1 — recommendation: ship the
  complementary variant first (a weekend, removes most jumps), keep the ESKF as the
  refactor target once M1's frames/conventions are stable.
- Per-state calibration (D4b) — needed at all at this mounting distance? Decide after
  one engine-on/off comparison measurement.
- Quality path naming on SignalK — standard `.accuracy`-style companions vs. custom
  paths vs. `meta`; align with what common consumers (plotters, anchor alarms) actually
  read. **Narrowed, not closed.** The heading-honesty half is settled and needs no custom
  vocabulary at all: the spec's own `headingCompass` → `headingMagnetic` → `headingTrue`
  chain already encodes how much correction has been applied, so we publish at the rung we
  have actually earned (`frame-conventions.md` §11.2). What remains open is genuine
  *metadata* — per-value uncertainty and gate state for M5 — which the path vocabulary
  does not cover.
- ~~Frame conventions document~~ — **resolved**: promoted into M0 and written, see
  [`frame-conventions.md`](frame-conventions.md). It is normative: code that disagrees
  with it is wrong. Its §9 audit found five defects in shipped code (rate-of-turn sign and
  frame, attitude yaw carrying a rate, NaN-unbounded heading normalization, non-finite
  values reaching the JSON encoder, a no-op rate change), all fixed in M0, and confirmed
  the Euler extraction and `R_W_V` chain are correct.
- ~~SignalK sign conventions~~ — **resolved**: verified verbatim against the specification
  schemas (`frame-conventions.md` §11.1). Ours match exactly — roll positive to starboard,
  pitch positive bow-up, rate of turn positive to starboard, variation positive east and
  additive. The rate-of-turn line is what makes audit A1 a defect rather than a preference.
- ~~`navigation.attitude.yaw` semantics~~ — **resolved as unresolvable, and declined.**
  SignalK defines no datum for it: with north as datum it duplicates the heading paths,
  with mean heading as datum it is a yawing oscillation, and the spec's own wording points
  each way in different places. We publish `roll` and `pitch` only — permanently, not
  pending M1 (`frame-conventions.md` §11.3). If yawing motion is ever wanted, it goes on a
  custom path with a stated datum.
