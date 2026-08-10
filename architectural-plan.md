# signalk-pose-provider — Architecture Plan & Decision Record

Status: draft for review · Supersedes: `compass-calibration-design.md` (kept as historical record)

This document captures the conclusions of the design review (Aug 2026), records which
previous decisions are superseded and why, and lays out milestones with explicit
dependencies. Style: decisions are recorded with their reasoning so the "why" survives
in the repo, not only in a chat log.

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
| | | | |

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
`GPS_PROVIDER` gives unadulterated fixes with per-fix accuracy, Doppler
speed/bearing, and satellite metadata. Side effect: removes the proprietary Play
Services dependency — unblocking an F-Droid-clean build. Deferred (recorded honestly,
like old Option B): the raw `GnssMeasurement` layer (carrier smoothing, dual-frequency
processing) offers further headroom but is high effort for modest gain on a phone
antenna; revisit only if meter-class proves insufficient.

**P9 — The sea is flat: altitude is a constraint, not a measurement.**
GNSS altitude (5–15 m wander, the weakest GNSS output) is not navigation data for a
boat. Height above the ellipsoid = geoid undulation (model) + tide (decimeters, known)
+ heave (periodic, zero-mean) — a sub-meter prior. Use it two ways: publish honest
altitude, and altitude-aid the position filter (clamping the vertical channel tightens
the horizontal solution via satellite geometry). The barometer (~10 cm relative
resolution) is useless for absolute altitude but is a heave-band sensor: fuse into the
M8 heave estimate; its primary marine job (pressure trend for weather) is unaffected.

## 3. Superseded Decisions (and why)

| Old decision | Status | Reason |
|---|---|---|
| Option A/C: stack corrections on `TYPE_ROTATION_VECTOR` + Android C1 | **Superseded** by P3 | Stale-C1 leakage, heeled-reach vertical error, no gateable gain. Option C's quality gating survives — generalized into P6 — but on top of our own pipeline. |
| C4 Fourier deviation curve **as correction** | **Superseded** — demoted to validation report | Fatal degeneracy: with COG as reference at a single speed, a constant current produces a crab angle ≈ arcsin(v_c/v_b · sin(θ−θ_c)) — first-order identical to semicircular (B, C) deviation. A 0.5 kn current at 4 kn forges a clean, well-fitted, spurious ~7° deviation curve. Ellipsoid calibration needs no external heading reference (truth criterion is internal: constant field magnitude), so it is immune. Fourier machinery survives as a *diagnostic*: residual semicircular structure after calibration ⇒ heeling error or unmodeled current. |
| C3 single-point azimuth as separate layer | **Absorbed** | Becomes the single yaw-alignment constant of the mount rotation (determined via reciprocal runs / slack-water leg / sighting). One correction pipeline, one provenance record — not two layers that can silently disagree. |
| Gyro detector as standalone "warning UI" feature | **Repurposed** | Becomes gate G1 feeding the Mahony gain (P6). The UI warning is a side effect of the gate state, not the product. |

Corrections to the *conversation* record too, for symmetry: (a) a flat calibration
circle constrains the ellipsoid weakly in the vertical axis — heeled data from both
tacks must be folded into the fit, or vertical terms regularized toward a device-only
calibration; stated limitation, not a surprise residual. (b) The D1–D7 distortion
taxonomy and the P5 honesty principle from the old doc were better than anything in the
chat and are retained.

## 4. Cleanup (current-version hygiene)

Items to fix/remove **before** new construction, so the repo doesn't carry two
architectures:

- [ ] Mark `compass-calibration-design.md` as superseded (banner + link here); do not delete — it's the decision history.
- [ ] Remove/park any C4-as-correction scaffolding if present (nothing shipped yet — cheap now, expensive later).
- [ ] Audit that **all** published COG/SOG originate from `Location.getSpeed()`/`getBearing()` (Doppler) with their accuracy fields — never from position differencing anywhere in the pipeline (README suggests this is already true; verify in code).
- [ ] `test_signalk_json.kt` at repo root → move under proper test sourceset or remove.
- [ ] README roadmap: rewrite "Planned" section against the milestones below; remove Option A/C language.
- [ ] Decide fate of the current C3 azimuth implementation: keep functioning as the interim heading correction until M2 lands, then fold into mount-rotation constant.
- [ ] Publish nothing on `navigation.headingMagnetic` yet that implies calibrated quality — until M2, mark heading data with explicit low-quality/uncalibrated flag rather than silence.
- [ ] Replace Fused Location Provider with `LocationManager` + `GPS_PROVIDER` (P8). This is cleanup, not a milestone: it changes the *input*, needs no new filtering, and every day on FLP is a day of database-teleport jumps in the data. Verify Doppler speed/bearing + accuracy fields survive the switch (they do — they're `Location` fields, not FLP features). Note the removed Play Services dependency in the README (F-Droid path).

## 5. Milestones

Designed so that each milestone leaves the app shippable. Dependency column shows hard
prerequisites; unlisted milestones are mutually independent and stackable.

| # | Milestone | Contents | Depends on | Ship value |
|---|---|---|---|---|
| M0 | **Cleanup** | Section 4 checklist | — | Repo tells one story |
| M1 | **Raw sensor pipeline + Mahony AHRS** | Raw sensor ingestion at 100–200 Hz; Mahony with explicit, gateable acc/mag gains; adaptive gravity gating (wave logic); mount rotation (C2 tilt + yaw constant, replacing C3); declination (C5) on top; stock `TYPE_ROTATION_VECTOR` kept as parallel comparison trace (dev builds) | M0 | Correct roll/pitch under heel — the core instrument |
| M2 | **Magnetometer calibration (ellipsoid)** | Calibration mode: log a slow circle (+ heeled segments both tacks), linear LSQ ellipsoid fit, before/after residual display, timestamped storage **with history**; explicit re-swing procedure documented in-app | M1 | Heading becomes trustworthy; current-immune by design |
| M3 | **Heading integrity gates (G1–G3)** | Three gates per P6, hysteresis re-admission, single gain lever; quality state machine | M1 (G1, G2); M2 for meaningful absolute heading; G3 needs Doppler COG (already present) | Cable crossings, engine start, marina steel handled honestly |
| M4 | **Position/velocity filter** | Loosely-coupled error-state KF (or gated complementary filter as v1): Doppler velocity + position as measurements, IMU propagation on 1 s leash (P1), innovation gating with boat-dynamics priors (P7), lever-arm band-limiting; sea-level altitude constraint per P9 (geoid + tide prior, altitude-aided horizontal solution) | M0 (P8 source switch), M1 | GPS jumps gone; smooth 10–50 Hz pose out; honest altitude |
| M5 | **Quality publishing** | Per-path quality/uncertainty on SignalK (heading gate state, position mode: anchored/coasting, calibration age); consumers inherit the honesty | M3 or M4 (publishes their states) | Whole-boat benefit; anchor alarm & plotter get trust levels |
| M6 | **STW subscription** | SignalK WebSocket *consumer* for `navigation.speedThroughWater` → continuous current vector estimate (ground velocity − heading·STW); sharpens G3 into corrected-residual form; enables current display | M3, M4 | Closes the heading/COG/current triangle every second |
| M7 | **Validation & diagnostics** | Fourier residual report post-calibration (the demoted C4): per-tack residuals ⇒ heeling-error estimate; calibration history trending (magnetic biography of the boat); maneuver-based current fix (every tack = free current estimate) as fallback where no STW | M2 (+M6 optional) | Turns residuals into boat knowledge |
| M8 | **Nice-to-haves** | Heave from band-limited single integration (wave-periodicity anchored), fused with barometer heave-band signal (P9); geofenced suspicion near charted cable corridors; two-speed calibration sail to fit current as nuisance params where STW absent; raw `GnssMeasurement` layer only if meter-class proves insufficient (deferred per P8) | M1–M4 | Polish |

**Stacking notes.** M1→M2→M3 is the attitude track; M4 is the position track and only
needs M1 (attitude for gravity removal + frame rotation) — the two tracks can proceed
in parallel after M1. M5 attaches to whichever track lands first. M6/M7/M8 are
independent add-ons. The minimal "better than today" release is M0+M1; the minimal
"instrument-grade" release is M0–M4.

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
  paths; align with what common consumers (plotters, anchor alarms) actually read.
- Frame conventions document (sign conventions, quaternion order, boat frame
  definition) — write **before** M1 coding starts; frame sign errors are where projects
  like this die.
