# rescue-sim — Telecom Module Design Decisions (Authoritative)

> Single source of truth for telecom-module decisions. Made 2026-09-11 via
> structured grilling session (transcript in papers repo session). Any agent
> working here MUST read this file first and adhere. Conflicts resolve in
> favor of this file. Update ONLY with new grilling/user decision — never
> silently.

## Goal

Make telecom module **fully functional** inside RoboCup Rescue Simulation
server (RCRS): BTS (cell-site) entities, disaster damage model, and — the
core feature — **agent communications routed through BTSs; when a BTS dies,
comms die for uncovered agents**. Repo public at
https://github.com/saahmadnejad/rescue-sim. Drives papers 3/4
(papers repo: `paper3-rescue`, `paper4-telecom-oss`).

## Hard constraints (never violate)

1. **Upstream-mergeable**: telecom code lives in `modules/telecom` ONLY.
   NEVER edit `modules/standard`, `modules/kernel`, `modules/rescuecore2`
   in place. Upstream = roborescue/rcrs-server, remote `upstream`, BSD-3.
2. **Config-gated**: classic scenarios (maps/*/config without telecom
   keys) behave IDENTICALLY with telecom module present. All telecom
   activation via config keys under `telecom.*` in scenario cfg files.
3. **Pluggable, no kernel surgery**: comms via `kernel.communication`
   config key (kernel reads it at Kernel.java:136 via
   KernelStartupOptions); scoring via `score.function`; simulators via
   `kernel.simulators.auto +`. Zero kernel edits required by design.
4. **Single BSD-3 LICENSE** covers whole tree incl. telecom (upstream
   inherited + mergeable). No separate licensing.
5. **Every claim carries its ref** (papers-repo rule): code as
   `File.java:line`, calibration as grounding-facts.md entries
   (Maria/Sandy/COW-COLT numbers + sources).

## Versioning

- **v1.0** = T3 (damage) + T4 (brigade) + T5 (scoring) + minimal T6
  (plain REST JSON telemetry out / work orders in, TMF-shaped later).
  Must demo standalone: damage hits → coverage drops → comms vanish →
  work order → COW deploys → comms return.
- **v1.1** = TMF-shaped T6: telemetry/work-orders re-shaped as
  TMF639 (resource inventory) / TMF642 (alarms) / TMF697 (work orders),
  events per TMF630 notification pattern (hub/subscription). Blocked on
  telecom-oss O1 (papers repo work-plan item 2). NOT before.
- T7 map extensions (BTS layer test→kobe→berlin) post-v1.0, as needed.

## Decision log (all locked, per grilling rounds)

| # | Decision | Detail |
|---|---|---|
| Q1 | True GitHub fork | Squat repo (no shared git ancestry) was public 2026-09-11 (user action, ahead of plan). Fork-replay option CLOSED — accept squat repo; upstream sync via manual tree-diff (fetch upstream, diff trees, port). Record: root commit `9deec95` = full rcrs-server tree @ upstream HEAD (BSD-3), then `949a669` README, `b5d8ff3` T1-T2 telecom. |
| Q2 | Publish gate passed | Repo already public (see Q1). Push only green-`test` commits from now on. |
| Q3 | v1.0 scope | T3-T5 + minimal REST T6 (above). No TMF in v1.0. |
| Q4 | Comms-through-BTS | The defining feature. Implemented as pluggable communication model; see M2 below. |
| Q5 | Public repos | rescue-sim only. telecom-oss + mas-papers stay private. Never push jade/keys/secrets here. |
| Q6 | CI | GitHub Actions: Java 21 Temurin, `./gradlew test`, gradle-wrapper distro cache. PR + push. `completeBuild` manual only. |
| Q7 | License | Single BSD-3 (constraint 4). |
| Q8 | Comms semantics | **Binary v1**: agent covered by ≥1 serving BTS → hears normally; uncovered → hears nothing. Flag `telecom.comms.bts-required` (default true when telecom comms model active). Radio-vs-voice nuance + noise-graded degradation = future (existing Noise classes can express later). |
| Q9 | Who loses comms | ALL humans: platoon agents AND civilians (uncovered civilian can't call for help = coverage-aware-civilian effect papers want). Centres (buildings) judged by centroid location. |
| Q10 | BTS placement | Config: explicit `telecom.bts.list: x,y,radius;x,y,radius;...` and/or `telecom.bts.grid: cols,rows,dx,dy,x0,y0,radius` seeded grid. Ownership: in-process `TelecomRegistry` (kernel runs single-threaded per timestep; registry = plain singleton, immutable-snapshot reads). Map-file layer = T7. |
| Q11 | Damage model | Scenarios `telecom.damage.scenario: maria|sandy|none` (localized later). Time scaling `telecom.damage.steps-per-day: N` (RCRS tick=500mm ≈ 1min, so N=1440 raw pace; smaller for experiments). Day-1 curve from grounding-facts: maria 95% down day 1 (fiber cuts + power), sandy ~25% peak. Mechanisms: backhaul cut, powerMode→NONE on grid loss, generator fuel hours countdown, tower collapse. Deterministic RNG seeded from config seed. |
| Q12 | Restoration policy | Rule-based baseline = paper-4 experiment-matrix row (i), NOT throwaway: govt/911 sites → dense population → remote (the pattern Maria/Sandy/9-11 actually followed, grounding-facts.md). Pluggable `RestorationPolicy` interface so LLM-OSS policy (telecom-oss) drops in later. |
| Q13 | CI detail | As Q6. Workflow file `.github/workflows/ci.yml`. |

## Architecture (as-built + planned)

```
Kernel (unmodified)
 ├─ kernel.simulators.auto +: telecom.TelecomSimulator      [M1]
 │    owns BTS lifecycle: load from config → TelecomRegistry
 │    applies DamageModel per timestep → BTS state changes
 │    (owns RestorationBrigade later, M4)
 ├─ kernel.communication: telecom.comms.TelecomCommunicationModel  [M2]
 │    wraps/delegates ChannelCommunicationModel; filters getHearing:
 │    agent location NOT covered (registry + CoverageModel) → no hearing
 └─ score.function: telecom.score.TelecomScoreFunction        [M4]
      CompositeScoreFunction: RSL21-equivalent + population-coverage%

TelecomRegistry (telecom package singleton)
 └─ snapshot BTSs; readers: comms model, coverage, telemetry, scoring
```

Execution order is safe WITHOUT kernel edits: simulators process + push
state changes BEFORE `Kernel.sendAgentUpdates` (Kernel.java:483) calls
`communicationModel.process()`/`getHearing()` — comms model reads
registry fresh each timestep.

## Milestones (build order; M-numbers stable identifiers)

- **M1 T3 damage** [DONE 5be3286]: TelecomRegistry, TelecomSimulator
  (config → BTSs), DamageModel (maria/sandy), kernel-telecom.cfg.
- **M2 comms integration** [DONE 5be3286]: TelecomCommunicationModel,
  hearing filter, `telecom.comms.bts-required`; headless exit-demo green.
- **M3 repo hygiene** [DONE 090380f]: CI workflow, README, AGENTS.md.
  (Fork-replay closed by Q1 — repo already public as a copy.)
- **M4 T4 brigade** [DONE c9abd0b]: COW entity (TelecomEntityURN.COW),
  WorkOrder, RestorationPolicy + RuleBasedRestorationPolicy, brigade
  mechanics (stock + setup countdowns), policy config-gate
  (telecom.policy.enabled, default off).
- **M5 T5 scoring** [DONE 6f458b8]: PopulationCoverageScoreFunction +
  TelecomScoreFunction (RSL21+coverage composite, configurable weights),
  wired via score.function.
- **M6 minimal T6** [DONE 6f458b8]: TelemetryServer — GET
  /telecom/sites|coverage|alarms, POST /telecom/workorders → brigade;
  telecom.http.port gate (0=off). **v1.0 released here (tag v1.0).**
  → v1.1 TMF-shape (blocked on telecom-oss O1).

## Verified plugin seams (file:line, commit b5d8ff3 baseline)

- Kernel.java:136 — `config.setValue(COMMUNICATION_MODEL_KEY, ...)`;
  KernelStartupOptions.java:54 — comms model instantiated from
  `kernel.communication` config key.
- Kernel.java:483-494 — sendAgentUpdates: comms model processes, then
  per-agent `getHearing(controlledEntity)` → perception update.
- StartKernel.java:434 — COMMUNICATION_LOADABLE_TYPE config updater;
  simulators auto via `kernel.simulators.auto +` (kernel-inline.cfg
  pattern, maps/test/config/kernel-inline.cfg:61-66).
- score.cfg:1 — `score.function: rescuecore2.standard.score.RSL21ScoreFunction`
  (swap key for TelecomScoreFunction).
- ChannelCommunicationModel.java:103-152 — process() shape to delegate;
  StandardWorldModel.createStandardWorldModel(model) for wrapping raw model.

## Papers linkage

- Work plan: papers/paper4-telecom-oss/proposal.md (item 1 done @
  b5d8ff3; this file unblocks items 3-5).
- Calibration: papers/paper4-telecom-oss/grounding-facts.md (Maria/Sandy/
  COW/COLT numbers with sources).
- TMF slice (v1.1): TMF639/642/697 + TMF630 events — corrected mapping
  in papers research-2026-09-08.md §2; NEVER use old wrong numbers
  (638/621/634-for-events).
- jade repo (private, io.donbee) is the OSS agent platform — referenced
  by telecom-oss later; rescue-sim itself stays jade-free.
