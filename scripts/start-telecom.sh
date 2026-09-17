#!/bin/bash
# Telecom scenario launcher (T7).
#
# Why this script exists: scripts/start.sh + functions.sh:startKernel hardcode
# `-c $CONFIGDIR/kernel.cfg`, and kernel.cfg contains no
# `kernel.simulators.auto` / `kernel.viewers.auto` keys — it is the classic
# TCP launch (simulators and viewers connect as separate JVMs via
# LaunchComponents). The telecom stack is only reachable through
# kernel-telecom.cfg -> kernel-inline.cfg, which auto-starts simulators and
# viewers IN-PROCESS (kernel.InlineComponentLauncher). That in-process launch
# is a hard requirement, not a convenience: BTSLayer reads the in-process
# TelecomRegistry singleton, so the viewer must share the kernel's JVM.
#
# This file is additive — no upstream script (start.sh, functions.sh) is
# modified, keeping the fork mergeable (DECISIONS.md constraint 1).
#
# Usage (like start.sh — pass repo-relative paths from the scripts dir):
#   cd scripts && bash start-telecom.sh -m ../maps/kobe/map -c ../maps/kobe/config
#   cd scripts && bash start-telecom.sh -g              # headless, test map
#   TELECOM_OPTS="--telecom.http.port=8081" bash start-telecom.sh
#
# TELECOM_OPTS is passed to StartKernel as `--key=value` config overrides.
trap "echo 'killing...'; ./kill.sh; exit" INT

# Always operate from this script's directory (upstream functions.sh resolves
# ../logs and ./kill.sh relative to cwd).
cd "$(dirname "$0")" || exit 1

. functions.sh

processArgs $*

# kill.sh works by `ps -ef | grep <repo path>` (scripts/kill.sh), so an
# ABSOLUTE path in this process's own command line would make it kill itself.
# Rewrite repo-internal paths to be relative to this directory before any
# kill.sh call; the launched java process still gets a valid path because it
# runs with this same cwd.
relativise() {
  if command -v realpath >/dev/null 2>&1; then
    local rel
    rel=$(realpath --relative-to=. "$1" 2>/dev/null)
    if [ -n "$rel" ]; then
      echo "$rel"
      return
    fi
  fi
  echo "$1"
}

MAP=$(relativise "$MAP")
CONFIGDIR=$(relativise "$CONFIGDIR")
LOGDIR=$(relativise "$LOGDIR")

# Kill stale simulator processes without the self-kill hazard of
# scripts/kill.sh: that script does `ps -ef | grep <repo path>` with no
# exclusions, so invoking this launcher with an absolute repo path makes it
# match — and kill — itself. This variant snapshots ps once, excludes this
# process, and only targets java processes carrying a classpath.
# NOTE: it also kills any OTHER kernel from this repo — one live scenario
# at a time; launching map B stops a running map A.
safeKillStale() {
  local tmp
  tmp=$(mktemp)
  ps -eo pid=,args= >"$tmp" 2>/dev/null
  awk -v pat="$BASEDIR" -v self="$$" '
    $0 ~ pat && $0 ~ /java/ && $0 ~ /-cp/ { if ($1 != self) print $1 }
  ' "$tmp" | xargs -r kill -9 2>/dev/null
  rm -f "$tmp"
}

# Delete old logs
rm -f $LOGDIR/*.log
safeKillStale

KERNEL_CONFIG="$CONFIGDIR/kernel-telecom.cfg"
if [ ! -f "$KERNEL_CONFIG" ]; then
  echo "Telecom scenario config not found: $KERNEL_CONFIG"
  echo "Expected kernel-telecom.cfg in the scenario config dir ($CONFIGDIR)."
  exit 1
fi

GUI_OPTION=""
if [[ $NOGUI == "yes" ]]; then
  GUI_OPTION="--nogui"
fi

makeClasspath $BASEDIR/jars $BASEDIR/lib

echo "Telecom scenario: $KERNEL_CONFIG (in-process simulators/viewers)"
execute kernel "java -Xmx2048m -cp $CP -Dlog4j.log.dir=$LOGDIR kernel.StartKernel -c $KERNEL_CONFIG --gis.map.dir=$MAP --kernel.logname=$LOGDIR/rescue.log.7z $GUI_OPTION $TELECOM_OPTS"
waitFor $LOGDIR/kernel.log "Listening for connections"

echo "Telecom scenario running in-process. BTS windows: Telecom viewer."
echo "Press Ctrl-C to stop."
waitFor $LOGDIR/kernel.log "Kernel has shut down" 30

kill $PIDS
./kill.sh