# Contributing

## Commit messages: Conventional Commits

Commits in this repository follow [Conventional Commits](https://www.conventionalcommits.org/):

```text
<type>[optional scope]: <short summary>

[optional body]

[optional footer(s)]
```

**Why:** the summary line alone should tell a reader what kind of change
happened without opening the diff, and the body is where the *why* goes — the
constraint, the measurement, the thing that would otherwise only survive in a
chat log. That is the same reasoning behind `architectural-plan.md` and
`frame-conventions.md`; commit messages are decision records at a smaller
scale.

### Types used in this repository

| Type | For |
|---|---|
| `feat` | A new capability (a new sensor path, a new milestone deliverable) |
| `fix` | Correcting a defect — including one introduced earlier in the same branch |
| `docs` | Documentation only: `README.md`, `architectural-plan.md`, `frame-conventions.md`, code comments |
| `test` | Adding or correcting tests, with no production-code behaviour change |
| `refactor` | Restructuring code with no behaviour change |
| `perf` | A performance improvement |
| `ci` | GitHub Actions workflow changes |
| `build` | Build configuration (`build.gradle.kts`, Gradle wrapper) |
| `chore` | Everything else that doesn't fit above |

### Scope

Names the area of the codebase the commit changes — a package or a clear
subsystem, e.g. `feat(ahrs): ...`, `fix(location): ...`,
`test(calibration): ...`. In use so far: `location`, `sensors`, `signalk`,
`calibration`, `ahrs`, `replay`, `conventions`, `build`. Add a new one when
an existing scope doesn't fit rather than stretching one to cover it.

Omit the scope rather than force it onto a commit that genuinely spans
several areas at once (a cross-cutting cleanup, wiring a new subsystem into
the app) — a scope naming only one of several touched areas is worse than
none, because it reads as a claim about what the commit is *not* about.

Milestone context (`M0`, `M1`, …) is not a scope. It goes in the summary
text instead when it matters: `feat(ahrs): add the M1 Mahony AHRS core with
quaternion attitude and bias estimation`, not `feat(m1): ...`. The
milestones in `architectural-plan.md` are the unit this project is
organized around, but they're a fact about the change worth reading in
prose, not a routing tag — and a change can only have one scope at a time,
while it may need to say both what area it touched *and* what milestone
it belongs to.

### Breaking changes

If a commit changes what a published SignalK path means or removes one a
consumer might already be reading, say so with a `BREAKING CHANGE:` footer
naming the affected path(s) and what changed — not just "fixes a bug," since
"fixes" undersells a difference a running plotter will actually notice.

### Curating history before merge

Within a feature branch, a fix for a defect that the *same branch* introduced
should be squashed into the commit that introduced it before merging — the
history should read as if the bug had never shipped, not as a diary of
catching it. A fix for something that predates the branch (already on `main`,
unrelated to the branch's own changes) stays as its own commit: it isn't the
branch's mistake to erase, and keeping it separate is more honest about what
the branch actually changed.

When curating, verify the rewrite changed nothing but history: the tree at
the new branch tip should be identical to the tree at the old one
(`git diff <old-tip> <new-tip>` should be empty). A curated history that
silently drops or alters a hunk is worse than no curation at all.
