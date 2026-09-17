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

**v1.0 released (tag `v1.0`).** Telecom disaster module implemented
(`modules/telecom`, unit tests green, headless closed-loop verified):
BTS entities, radial coverage v0, Maria/Sandy-calibrated damage model,
BTS-gated communication model, restoration brigade (COW/repair/refuel)
with the rule-based classical policy, population-coverage scoring, and a
plain-JSON REST telemetry/work-order endpoint. T7 (post-v1.0): BTS/COW
coverage discs on the map viewer (`telecom.view`, config-gated). v1.1
reshapes the REST contract to TMF Open APIs. **Design decisions are
locked in [DECISIONS.md](DECISIONS.md) — read it before contributing.**

## Relationship to upstream

This is a fork of [roborescue/rcrs-server](https://github.com/roborescue/rcrs-server)
(BSD-3-Clause) with the telecom disaster extension: a
`modules/telecom` module and small additive wiring in `build.gradle`
(telecom source dir + jar tasks, mirroring how every other module is
wired). Upstream sync is normal forking workflow
(`git fetch upstream && git merge upstream/master`).

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

### Seeing BTS on the map (T7)

`scripts/start.sh` cannot show BTS, for two independent reasons:

1. `scripts/functions.sh` (`startKernel`) hardcodes `-c $CONFIGDIR/kernel.cfg`,
   and `kernel.cfg` has no `kernel.simulators.auto` / `kernel.viewers.auto`
   keys at all — so no telecom component is ever started.
2. It launches viewers as separate JVMs over TCP. `BTSLayer` reads the
   **in-process** `TelecomRegistry` singleton, so only an in-process viewer
   can draw it.

Use the telecom launcher (additive file; no upstream script modified):

```bash
cd scripts && bash start-telecom.sh        # GUI on the test map
cd scripts && bash start-telecom.sh -g     # headless
# key=value overrides go through TELECOM_OPTS, e.g. expose telemetry:
cd scripts && TELECOM_OPTS="--telecom.http.port=8081" bash start-telecom.sh
```

It runs `kernel.StartKernel -c maps/test/config/kernel-telecom.cfg`, which
(via `kernel-inline.cfg`) auto-starts simulators and viewers **in-process**
through `kernel.InlineComponentLauncher` — the same JVM, so the viewer can
read the registry.

Two map windows appear, and this is deliberate: the classic `Viewer N`
(which has no BTS layer by design) and **`Telecom viewer N`** — the one
with translucent coverage discs, tower markers and the `Coverage: %`
readout. Hide the discs with `viewer.standard.BTSLayer.visible: false`.
Telecom entities stay out of the standard world model, so `sample` agents
and the classic viewer are unaffected.

Note on `telecom.bts.grid` units: positions are **model millimetres, 0-based**.
`GMLWorldModelCreator` converts raw GML coordinates with
`ScaleConversion(map.minX, map.minY, 1000, 1000)` and
`convertX = (x - xOrigin) * xScale` (`modules/maps/src/maps/ScaleConversion.java:27`),
so the raw minimum becomes 0 — the model box is `0..(rawMax - rawMin) x 1000`
per axis. Grids must be derived from that box (`dx = span/cols`, `x0 = dx/2`),
not from raw GML minima; taking raw coordinates shifts the whole grid off the
map's left/bottom edge (there is a regression test:
`BtsPlacementConfigTest` walks every `maps/*/config/kernel-telecom.cfg`,
parses the sibling `map/map.gml`, recomputes the model box and asserts every
declared site is inside it and the grid is centred).

### BTS placement on real maps: the planner (T7 review)

Hand-tuned grids do not transfer between maps: the first kobe/berlin rollout
shipped the same 4x3 grid shape on maps whose areas differ by 21x (kobe
~0.17 km2 vs berlin ~3.6 km2), with arbitrary radii, and free coordinate-space
grid points landed on roads as often as on blocks. When a telecom config sets
**neither** `telecom.bts.list` nor `telecom.bts.grid`, placement is derived
from the world model at kernel connect time by `telecom.BtsGridPlanner`:

- site count `N = round(density x area)`, clamped to `[min-sites, max-sites]`
  — counts now scale with city size;
- grid shape from the map aspect ratio, cell-centred (`x0 = dx/2`) so margins
  are symmetric by construction;
- coverage radius `= 0.75 x cell-diagonal/2`, clamped to `[50 m, 500 m]`;
- each grid point snaps to the nearest unused **building centroid** within
  `0.35 x min(dx, dy)` — sites sit inside blocks, not on roads.

Tuning keys: `telecom.bts.sites-per-km2-milli` (default 8000 = 8/km2),
`telecom.bts.min-sites` / `telecom.bts.max-sites` (6 / 48),
`telecom.bts.snap-max-milli` (350). The chosen geometry is logged at connect
(`planned BTS placement: ...`). Guarded by `BtsGridPlannerTest` (8 unit tests)
plus the `BtsPlacementConfigTest` map-bounds checks for explicit placements.

### Real maps: kobe and berlin (T7)

The same scenario runs on shipped upstream maps with the telecom launcher:

```bash
cd scripts
bash start-telecom.sh -m ../maps/kobe/map -c ../maps/kobe/config     # kobe, GUI
bash start-telecom.sh -m ../maps/berlin/map -c ../maps/berlin/config -g   # berlin, headless
```

The `-m/-c` flags select the map/config dirs (default is still the test map);
the launcher passes `--gis.map.dir=$MAP` so a stale absolute `gis.map.dir`
inside a map's `gis.cfg` cannot break the load, and it kills stale kernels
with `safeKillStale` (which never matches the launcher itself — upstream
`kill.sh`'s `ps -ef` grep does, and self-killed the launcher with exit 137
when invoked by absolute path). **`safeKillStale` kills every kernel from
this repo, so only one scenario can run at a time** — launching berlin stops
a running kobe. Run them sequentially, or comment out the `safeKillStale`
call for a temporary second instance (log dir is shared either way).

Expect larger loads on real maps (kobe ≈ 2.6k entities, berlin ≈ 5k) and a
map-load pause before the first window (berlin ~20-30s). Verify placement
without a GUI via the headless offscreen render harness (see
`scripts/diag-telecom.sh` history or `telecom/view` docs) and, once up, the
telemetry endpoints `GET /telecom/sites` / `GET /telecom/coverage`:

```bash
cd scripts && TELECOM_OPTS="--telecom.http.port=8082" \
  bash start-telecom.sh -m ../maps/kobe/map -c ../maps/kobe/config
curl -s localhost:8082/telecom/sites | head -3
curl -s localhost:8082/telecom/coverage
```

Shipped placement (verified by live telemetry): test map keeps its explicit
3x3 grid at `55000x47000mm` spacing from `27500,23500` (deterministic doc
example); kobe and berlin run the **planner** — kobe: 3x2, radius ~90 m,
6/6 sites snapped to buildings; berlin: 6x5, radius ~184 m, 28/30 snapped.
To pin a fixed layout on a real map, set `telecom.bts.grid` (model mm,
0-based — see the units note above; the old hand-tuned values are kept as
comments in each `kernel-telecom.cfg`).

## Telecom config keys (opt-in)

| Key | Meaning |
|---|---|
| `kernel.simulators.auto +: telecom.TelecomSimulator` | activates the telecom sim (BTS load + damage) |
| `kernel.communication: telecom.comms.TelecomCommunicationModel` | BTS-gated comms (delegates channel model) |
| `score.function: telecom.score.TelecomScoreFunction` | RSL21 + population-coverage% composite scoring |
| `telecom.bts.list: x,y,radius;...` | explicit BTS placement (takes precedence) |
| `telecom.bts.grid: cols,rows,dx,dy,x0,y0,radius` | seeded grid placement |
| `telecom.bts.sites-per-km2-milli` | planner density (default 8000 = 8 sites/km2) |
| `telecom.bts.min-sites` / `telecom.bts.max-sites` | planner count clamp (default 6 / 48) |
| `telecom.bts.snap-max-milli` | planner building-snap budget (default 350 = 0.35 of min(dx,dy)) |
| `telecom.damage.scenario: maria\|sandy\|none` | day-1 damage curve |
| `telecom.damage.steps-per-day: N` | kernel steps per simulated day (1440 = 1-min steps) |
| `telecom.damage.generator-hours: H` | generator fuel tank (hours) |
| `telecom.comms.bts-required: true\|false` | hearing requires BTS coverage (false = passthrough) |
| `telecom.policy.enabled: true\|false` | rule-based restoration policy (default off; external orders otherwise) |
| `telecom.policy.max-orders-per-tick: N` | planning bound per tick |
| `telecom.brigade.cow-stock: N` | COW inventory |
| `telecom.brigade.cow-setup-steps: N` | COW deployment time (default 1440 ≈ 1 day) |
| `telecom.brigade.repair-steps: N` | repair time (default 4320 ≈ 3 days) |
| `telecom.brigade.refuel-steps: N` | refuel time (default 720 ≈ 12 h) |
| `telecom.brigade.refuel-hours: H` | tank refill amount (default 72 h) |
| `telecom.http.port: P` | REST telemetry port (0 = off, default); `GET /telecom/sites\|coverage\|alarms`, `POST /telecom/workorders` |
| `kernel.viewers.auto +: telecom.view.TelecomViewerComponent` | map viewer with BTS/COW/coverage-disc layers (telecom state via TelecomRegistry, not the kernel model) |
| `viewer.standard.BTSLayer.visible: false` | hide a telecom layer by default (layers also toggle in the viewer right-click menu) |

See `maps/test/config/kernel-telecom.cfg` for a working example.

## Roadmap

- [x] T1: telecom module + BTS entities (config-gated, upstream-mergeable)
- [x] T2: radial coverage model (v0; path-loss later)
- [x] T3: disaster damage model (Maria/Sandy-calibrated)
- [x] Comms-through-BTS integration (kernel pluggable communication model)
- [x] T4: Telecom Restoration Brigade + COW actions + restoration policy
- [x] T5: coverage scoring function
- [x] T6 (v1.0): telemetry emitter + work-order ingestion (plain REST)
- [ ] T6 (v1.1): TMF-shaped contract (TMF639/642/697 + TMF630 events) — with telecom-oss
- [x] T7: BTS layer on real maps (test, kobe, berlin — grids in model space, guarded by `BtsPlacementConfigTest`)

## License

BSD-3-Clause (inherited from rcrs-server, LICENSE file at root; covers
the whole tree including `modules/telecom`).
