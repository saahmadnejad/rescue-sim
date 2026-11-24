#!/bin/bash
# Multi-Scenario Telecom Disaster Launcher
# Launches all telecom scenarios (Kobe, Berlin, Test) concurrently with GUI viewers and full multi-agent teams.
#
# Usage:
#   cd scripts && bash start-all-telecom.sh       # Launches all scenarios with GUI viewers on $DISPLAY
#   cd scripts && bash start-all-telecom.sh -g    # Headless mode (no GUI)

cd "$(dirname "$0")" || exit 1

. functions.sh

NOGUI="no"
for arg in "$@"; do
  if [ "$arg" == "-g" ] || [ "$arg" == "--nogui" ]; then
    NOGUI="yes"
  fi
done

GUI_OPTION=""
if [ "$NOGUI" == "yes" ]; then
  GUI_OPTION="--nogui --nomenu --autorun"
else
  GUI_OPTION="--nomenu --autorun"
fi

# Clean up any stale runs first
safeKillStale() {
  local tmp
  tmp=$(mktemp)
  ps -eo pid=,args= >"$tmp" 2>/dev/null
  awk -v pat="$BASEDIR" -v self="$$" '
    $0 ~ pat && $0 ~ /java/ && $0 ~ /-cp/ { if ($1 != self) print $1 }
  ' "$tmp" | xargs -r kill -9 2>/dev/null
  rm -f "$tmp"
}

safeKillStale

makeClasspath "$BASEDIR/jars" "$BASEDIR/lib"

# Setup distinct log directories
mkdir -p "$BASEDIR/logs/kobe"
mkdir -p "$BASEDIR/logs/berlin"
mkdir -p "$BASEDIR/logs/test"

PIDS=()

cleanup() {
  echo ""
  echo "Terminating all scenario kernels..."
  for pid in "${PIDS[@]}"; do
    kill -9 "$pid" 2>/dev/null
  done
  safeKillStale
  exit 0
}

trap cleanup INT TERM

export TELECOM_NO_KILL=1

echo "======================================================================"
echo " Starting All Telecom Scenarios Concurrently"
echo " Modes: Autonomous Agents (Police, Ambulance, Fire, Centre, Restoration)"
echo " Comms: Radio Channel 1 (BTS-gated) & Voice Channel 0"
echo " Blockade routing: RoadRouter with emergency police dispatch"
echo "======================================================================"

# 1. Scenario Kobe (Port 27931)
echo "[1/3] Launching Kobe scenario on port 27931..."
java -Xmx1536m -cp "$CP" -Dlog4j.log.dir="$BASEDIR/logs/kobe" kernel.StartKernel \
  -c ../maps/kobe/config/kernel-telecom.cfg \
  --gis.map.dir=../maps/kobe/map \
  --kernel.port=27931 \
  --gis.port=27932 \
  --kernel.logname="$BASEDIR/logs/kobe/rescue.log.7z" \
  --loadabletypes.inspect.dir=../jars \
  $GUI_OPTION > "$BASEDIR/logs/kobe/kernel-stdout.log" 2>&1 &
PIDS+=($!)

# 2. Scenario Berlin (Port 27941)
echo "[2/3] Launching Berlin scenario on port 27941..."
java -Xmx2048m -cp "$CP" -Dlog4j.log.dir="$BASEDIR/logs/berlin" kernel.StartKernel \
  -c ../maps/berlin/config/kernel-telecom.cfg \
  --gis.map.dir=../maps/berlin/map \
  --kernel.port=27941 \
  --gis.port=27942 \
  --kernel.logname="$BASEDIR/logs/berlin/rescue.log.7z" \
  --loadabletypes.inspect.dir=../jars \
  $GUI_OPTION > "$BASEDIR/logs/berlin/kernel-stdout.log" 2>&1 &
PIDS+=($!)

# 3. Scenario Test (Port 27951)
echo "[3/3] Launching Test scenario on port 27951..."
java -Xmx1024m -cp "$CP" -Dlog4j.log.dir="$BASEDIR/logs/test" kernel.StartKernel \
  -c ../maps/test/config/kernel-telecom.cfg \
  --gis.map.dir=../maps/test/map \
  --kernel.port=27951 \
  --gis.port=27952 \
  --kernel.logname="$BASEDIR/logs/test/rescue.log.7z" \
  --loadabletypes.inspect.dir=../jars \
  $GUI_OPTION > "$BASEDIR/logs/test/kernel-stdout.log" 2>&1 &
PIDS+=($!)

echo "All 3 scenarios running in background (PIDs: ${PIDS[*]})."
echo "Monitoring simulation progress... Press Ctrl+C to stop all."

# Wait for children
wait
