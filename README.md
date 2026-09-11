# rescue-sim — Telecom Disaster Extension for RoboCup Rescue Simulation

Telecom disaster scenarios for the [RoboCup Rescue Simulation (RCRS)]
server: cell-site (BTS) infrastructure, hurricane-calibrated damage
models, and — the defining feature — **agent communications routed
through the cellular network: when BTSs die, comms die for uncovered
agents and civilians.**

Public artifact for the papers portfolio (papers 3/4: rescue coordination
and AI-native telecom OSS). Sibling consumer: `telecom-oss` (AI-native
OSS speaking TMF Open APIs, drives restoration work orders back into the
sim at v1.1).

## Status

T1-T3 + core comms integration implemented (`modules/telecom`,
unit-tested, headless-run verified): BTS entities, radial coverage v0,
Maria/Sandy-calibrated damage model, BTS-gated communication model.
Roadmap below for the rest. **Design decisions are locked in
[DECISIONS.md](DECISIONS.md) — read it before contributing.**

## Relationship to upstream

This is a copy of [roborescue/rcrs-server](https://github.com/roborescue/rcrs-server)
(BSD-3-Clause) at upstream HEAD (root commit `9deec95`), extended by the
`modules/telecom` module. It is **not** a GitHub fork of upstream (no
shared git ancestry): upstream changes are merged by tree-diff porting.
All upstream code is untouched except `build.gradle` (wired the telecom
source dir + jar tasks, mirroring how every other module is wired).

**Extension rules** (see DECISIONS.md): telecom code lives only in
`modules/telecom`; `modules/standard`, `modules/kernel`,
`modules/rescuecore2` are never edited in place; classic scenarios
without telecom config keys behave identically to upstream.

## What the telecom module adds

1. **BTS entities** (cell sites): position, coverage radius, operational
   state (operational / damaged / destroyed), power mode (grid /
   generator / none) with generator fuel hours, backhaul (fiber /
   microwave / satellite / none). BTSs serve coverage iff operational
   AND powered AND backhaul live. BTSs live module-side (own URN space,
   `urn:rescuecore2.telecom:*`, ids 0x2100/0x2200) — deliberately not in
   the standard RCRS world model, which keeps `modules/standard`
   untouched (DECISIONS.md ADR-001).
2. **Disaster damage model** calibrated to public data
   (papers/paper4-telecom-oss/grounding-facts.md): Hurricane Maria 2017 —
   95% of PR's cell network down day 1 (mix: ~25% tower collapse, ~60%
   backhaul cuts, ~15% grid power loss); Hurricane Sandy 2012 — ~25%
   out, power-dominated. Generators run on a fuel countdown (Maria:
   fuel logistics was the binding constraint).
3. **BTS-gated comms**: a drop-in communication model that delegates to
   the standard channel model, then filters what each agent hears by
   BTS coverage. Uncovered agents — and uncovered civilians — hear
   nothing. This is the coverage-aware-civilian effect: people outside
   coverage cannot call for help.
4. **Telecom Restoration Brigade** (roadmap T4): COW/COLT deployment,
   repairs, refuelling, driven by a pluggable restoration policy; the
   rule-based policy mirrors what Maria/Sandy/9-11 operators actually
   did (government/911 sites first, dense population second, remote
   last) and serves as the classical baseline for agent-based (LLM)
   policies.
5. **Coverage scoring** (roadmap T5): population-covered-% (FCC DIR
   style) as an RCRS score component.
6. **Telemetry / work-order endpoint** (roadmap T6): plain REST out of
   the sim, work orders in — TMF Open API shaped at v1.1.

## Quick start

Requires Java 21 and the Gradle wrapper (first run downloads ~130MB).

```bash
./gradlew completeBuild          # builds all module jars into jars/ + libs into lib/
./gradlew test                   # telecom module unit tests

# Headless telecom scenario on the test map (300 timesteps, Maria curve)
CP="$(printf '%s:' jars/*.jar)$(printf '%s:' lib/*.jar)"
java -Xmx512m -Dlog4j.log.dir=/tmp/telecom \
  -cp "$CP" kernel.StartKernel \
  -c maps/test/config/kernel-telecom.cfg \
  --gis.map.dir=maps/test/map \
  --kernel.logname=/tmp/telecom/rescue.log.7z \
  --loadabletypes.inspect.dir=jars \
  --nogui --nomenu --autorun
```

Watch the log for `TelecomSimulator connected: N BTSs` and
`applied initial damage`. Without `--loadabletypes.inspect.dir=jars`
and with the wrong map dir the kernel will fail to find GIS/jars — those
flags matter when launching from the repo root (upstream `start.sh`
assumes cwd `scripts/`).

Classic upstream scenarios run unchanged: `bash scripts/start.sh -m
maps/test/map -c maps/test/config -g` (see `scripts/functions.sh`).

## Telecom config keys (opt-in)

| Key | Meaning |
|---|---|
| `kernel.simulators.auto +: telecom.TelecomSimulator` | activates the telecom sim (BTS load + damage) |
| `kernel.communication: telecom.comms.TelecomCommunicationModel` | BTS-gated comms (delegates channel model) |
| `telecom.bts.list: x,y,radius;...` | explicit BTS placement (takes precedence) |
| `telecom.bts.grid: cols,rows,dx,dy,x0,y0,radius` | seeded grid placement |
| `telecom.damage.scenario: maria\|sandy\|none` | day-1 damage curve |
| `telecom.damage.steps-per-day: N` | kernel steps per simulated day (1440 = 1-min steps) |
| `telecom.damage.generator-hours: H` | generator fuel tank (hours) |
| `telecom.comms.bts-required: true\|false` | hearing requires BTS coverage (false = passthrough) |

See `maps/test/config/kernel-telecom.cfg` for a working example.

## Roadmap

- [x] T1: telecom module + BTS entities (config-gated, upstream-mergeable)
- [x] T2: radial coverage model (v0; path-loss later)
- [x] T3: disaster damage model (Maria/Sandy-calibrated)
- [x] Comms-through-BTS integration (kernel pluggable communication model)
- [ ] T4: Telecom Restoration Brigade + COW/COLT actions + restoration policy
- [ ] T5: coverage scoring function
- [ ] T6: telemetry emitter + work-order ingestion (plain REST; TMF-shaped at v1.1)
- [ ] T7: BTS layer on real maps (test → kobe → berlin)

## License

BSD-3-Clause (inherited from rcrs-server, LICENSE file at root; covers
the whole tree including `modules/telecom`).
