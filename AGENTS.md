# AGENTS.md — rescue-sim

Telecom disaster extension for the RoboCup Rescue Simulation server.
Public repo; drives papers 3/4 in the sibling (private) papers portfolio.

## Read first

**DECISIONS.md at repo root is the authoritative decision log.** All
architecture/scope/config decisions are locked there; adhere to them and
never contradict silently. Update it only with a new user decision.

## Hard rules (from DECISIONS.md)

1. Telecom code lives ONLY in `modules/telecom`. Never edit
   `modules/standard`, `modules/kernel`, `modules/rescuecore2`,
   `modules/gis2` in place. Upstream-mergeable is the design constraint.
2. Classic scenarios must behave identically — all telecom activation is
   config-gated via `telecom.*` keys; classic cfg files stay untouched.
3. Kernel/perception/comms integration goes through existing plugin
   seams only (`kernel.simulators.auto +`, `kernel.communication`,
   `score.function`).
4. Single BSD-3 LICENSE for the whole tree. No new licenses.
5. Public repo: never commit secrets, keys, or references to private
   repos' content (papers quotes must not leak private material).

## Build / test / run

```bash
./gradlew test               # JUnit 5 (telecom module tests)
./gradlew completeBuild      # all jars -> jars/, libs -> lib/
./gradlew telecomJar         # just the telecom jar
```

Java 21 required. First wrapper run downloads Gradle (~130MB).

Headless telecom run recipe (from repo root — flags matter):

```bash
CP="$(printf '%s:' jars/*.jar)$(printf '%s:' lib/*.jar)"
java -Xmx512m -Dlog4j.log.dir=/tmp/telecom -cp "$CP" kernel.StartKernel \
  -c maps/test/config/kernel-telecom.cfg \
  --gis.map.dir=maps/test/map \
  --kernel.logname=/tmp/telecom/rescue.log.7z \
  --loadabletypes.inspect.dir=jars \
  --nogui --nomenu --autorun
```

(The `--loadabletypes.inspect.dir=jars` flag is required when cwd is the
repo root; upstream scripts assume cwd `scripts/`.)

## Conventions

- Java: follow the existing RCRS style (2-space indent in old files,
  javadoc on public classes/methods).
- Tests: JUnit 5, Given_When_Then naming, Arrange-Act-Assert body
  comments, in `modules/telecom/src/test/java/` (wired as the project
  test sourceSet in build.gradle).
- Calibration numbers (damage curves, COW/COLT times, fuel) come from
  the papers-portfolio grounding-facts file — sources must travel with
  the numbers when cited in docs.
- Commits: `feat(telecom):`, `fix(telecom):`, `docs:` prefixes; keep
  build green (`./gradlew test`) before pushing.
- Upstream sync: `git fetch upstream && git merge upstream/master`
  (repo is a real fork of roborescue/rcrs-server; default branch `main`,
  upstream uses `master`).

## Roadmap / status

Tracked in README.md + DECISIONS.md (milestones M1-M6, T1-T7). Next up
per DECISIONS.md: M4 (brigade + restoration policy), M5 (scoring),
M6 (REST telemetry, v1.0 tag).
