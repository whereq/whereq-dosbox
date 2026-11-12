#!/bin/bash
# run-bench.sh - DOSBox CPU-core throughput probe (whereq-dosbox).
#
#   ./run-bench.sh [rounds] [core ...]
#
# Metric notes (learned the hard way, see BENCH-NOTES.md):
#  * "core" and "cycles" belong to the [cpu] section in DOSBox 0.74, NOT [dosbox].
#  * The BIOS tick counter is NOT a valid throughput metric: DOSBox advances its
#    virtual clock once per CPU_CycleMax instructions, so "instructions per BIOS
#    tick" just returns CPU_CycleMax*1000 (i.e. the cycle budget, not the core).
#  * The valid metric is instructions per WALL second, measured with the emulator
#    running flat out (cycles=fixed <big>) so it is CPU-bound, not throttled.
#  * The real-time-DOS threshold is 3,000,000 instructions/s (CPU_CycleMax = 3000,
#    src/cpu/cpu.cpp:53). Anything above that can run DOS software at 1x speed.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOSBOX="$ROOT/build/native/src/dosbox"
BENCH="$ROOT/bench/bench.com"
RUNDIR="$ROOT/bench/run"

. "$ROOT/toolchain/env.sh"

ROUNDS="${1:-3}"; shift || true
CORES=("$@")
[ ${#CORES[@]} -eq 0 ] && CORES=(normal simple)

OUTER=20000
INNER=2000
# 6 instructions in the inner loop + 3 per outer iteration (plus int 1a overhead)
INSNS=$(( OUTER * (INNER * 6 + 3) ))
TICK_HZ=18.2
REALTIME_IPS=3000000
CYCLE_BUDGET=2000000

printf "%-8s %6s %10s %10s %12s %12s %10s\n" \
       CORE "ROUND" "WALL_SEC" "CPU_SEC" "INSTRUCTIONS" "IPS" "vs_REALTIME"

for CORE in "${CORES[@]}"; do
  BEST_WALL=""; BEST_IPS=0; BEST_CPU=""
  for R in $(seq 1 "$ROUNDS"); do
    rm -rf "$RUNDIR"; mkdir -p "$RUNDIR"
    cp "$BENCH" "$RUNDIR/bench.com"

    cat > "$RUNDIR/dosbox.conf" <<EOF
[sdl]
output=surface
fullscreen=false
autolock=false
waitonerror=false

[dosbox]
memsize=16
nosound=true

[cpu]
core=$CORE
cycles=fixed $CYCLE_BUDGET

[autoexec]
mount c $RUNDIR
c:\\bench.com
exit
EOF

    /usr/bin/time -f "%e %U" -o "$RUNDIR/time.txt" \
      env SDL_VIDEODRIVER=dummy timeout 600 "$DOSBOX" -conf "$RUNDIR/dosbox.conf" \
      > "$RUNDIR/stdout.txt" 2>"$RUNDIR/stderr.txt"
    RC=$?

    if [ -f "$RUNDIR/BENCH.TXT" ]; then
      WALL=$(awk '{print $1}' "$RUNDIR/time.txt")
      CPU=$(awk '{print $2}' "$RUNDIR/time.txt")
      IPS=$(echo "scale=0; $INSNS / $WALL" | bc)
      printf "%-8s %6s %10s %10s %12s %12s %9s%%\n" \
             "$CORE" "$R" "$WALL" "$CPU" "$INSNS" "$IPS" \
             "$(echo "scale=2; $IPS * 100 / $REALTIME_IPS" | bc)"
      if [ "$(echo "$IPS > $BEST_IPS" | bc)" = "1" ]; then
        BEST_IPS=$IPS; BEST_WALL=$WALL; BEST_CPU=$CPU
      fi
    else
      printf "%-8s %6s %10s %10s %12s %12s %10s  (rc=%s, no BENCH.TXT)\n" \
             "$CORE" "$R" "-" "-" "-" "-" "-" "$RC"
    fi
  done
  echo "  -> BEST $CORE: ${BEST_IPS} IPS  (wall ${BEST_WALL}s, cpu ${BEST_CPU}s)" | sed 's/^/ /'
done
