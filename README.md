# rescue-sim — Telecom Disaster Extension for RoboCup Rescue Simulation

Fork of [rcrs-server](https://github.com/roborescue/rcrs-server) (BSD-3-Clause,
`upstream` remote tracks origin repo). Goal: a realistic **telecom disaster
scenario** for the RoboCup Rescue Simulation league, to encourage telecom
industry support for the league.

**Status**: T1-T2 implemented (telecom module: BTS entity + radial coverage,
unit-tested). T3+ not yet implemented — see
[ROADMAP](#roadmap).

## What this adds (planned)

1. **BTS entities** (cell sites) on the world model: position, coverage
   radius, state (`operational | damaged | destroyed`), power dependency
   (grid | generator), backhaul dependency (fiber route | microwave | satellite).
2. **Telecom disaster model**: hurricane/earthquake damage curves calibrated
   to real cases (Hurricane Maria 2017: 95% of network down day 1, 49% of
   towers operating at day 30 — see papers/paper3/paper4 docs for sources).
3. **New agent type — Telecom Restoration Brigade**: repairs BTS, deploys
   COW (cell-on-wheels, ~1 day) / COLT (~3 h) units, refuels generators.
4. **Coverage-aware civilians**: victims in uncovered areas are harder to
   locate (search radius / rescue time penalty), modeling real emergency
   dispatch dependence on network coverage.
5. **Coverage scoring**: population-covered-% over time alongside classic
   RCRS scores (pluggable `ScoreFunction`).
6. **Event stream for external OSS**: sim emits telecom telemetry
   (site outages, alarms, coverage) over HTTP/events to an external
   AI-native OSS (see telecom-oss repo) — the OSS dispatches restoration
   work orders back. Contract is public TMF Open APIs only
   (TMF639 resource inventory, TMF642 alarm management, TMF697 work orders;
   events per the TMF630 notification pattern),
   no code coupling.

## Design constraints

- **Upstream-mergeable**: telecom code lives in its own gradle module
  (`modules/telecom`), never modifies standard/ in place; config-gated so
  classic scenarios behave identically with telecom off.
- **No coupling to external repos**: all interaction via documented APIs.

## Roadmap

- [x] T1: `modules/telecom` module + BTS entity URNs (config-gated).
  BTS extends `AbstractEntity` with its own URN space
  (`urn:rescuecore2.telecom:entity:bts`, id prefix `0x2100`) rather than
  `StandardEntity` — adding to `StandardEntityURN` would require editing
  `modules/standard`, violating the upstream-mergeable constraint. Telecom
  entities live module-side, not in the kernel `StandardWorldModel`;
  registration of `TelecomEntityFactory`/`TelecomPropertyFactory` happens
  automatically via jar deep-inspection (`jars/telecom.jar`), so classic
  scenarios without telecom behave identically (nothing registers).
- [x] T2: Coverage computation (simple radial → path-loss later).
  `telecom.CoverageModel`: binary radial coverage, BTS serves iff
  operational AND powered AND backhaul ≠ NONE; population-covered-%
  (FCC DIR style) over civilians. Unit-tested (`gradlew test`).
- [ ] T3: Disaster damage model (Maria-calibrated curves)
- [ ] T4: Telecom Restoration Brigade agent + COW/COLT actions
- [ ] T5: Coverage scoring function
- [ ] T6: Telemetry emitter + work-order ingestion (TMF API contract)
- [ ] T7: Map extension (BTS layer on existing RCRS maps: test → kobe → berlin)

## License

BSD-3-Clause (inherited from rcrs-server, LICENSE file at root).

## Related

- `telecom-oss` repo: AI-native OSS consuming this sim's telemetry
- `papers/paper3-rescue` + `paper4-telecom-oss`: research outputs
- jade platform (private fork): agent team infrastructure for OSS agents
