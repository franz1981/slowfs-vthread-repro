#!/bin/bash
# Build slowfs, mount it on a temp dir, run Repro.java once per path (fresh JVM each), unmount, print a table.
#   ./run.sh [/path/to/jdk] [extra JVM flags...]
# Env: DELAY_MS (default 100), PARALLELISM (default 2), PATHS (default: all), TIMEOUT (per JVM, default 60 s).
set -u
here=$(cd "$(dirname "$0")" && pwd)
jdk=${1:-${JAVA_HOME:-}}; [ $# -ge 1 ] && shift
[ -n "$jdk" ] || { echo "usage: $0 [/path/to/jdk] [jvm flags...]  (default: \$JAVA_HOME)" >&2; exit 2; }
delay=${DELAY_MS:-100}
par=${PARALLELISM:-2}
tmo=${TIMEOUT:-60}
paths=${PATHS:-"sleep fis-read fos-write raf-read fc-read open-close files-readAllBytes files-size fc-force raf-rws-write"}
command -v fusermount3 >/dev/null || { echo "fusermount3 not found (install fuse3)" >&2; exit 1; }
command -v cc >/dev/null || { echo "cc not found (install a C compiler)" >&2; exit 1; }
[ -w /dev/fuse ] || { echo "/dev/fuse is not usable by this user" >&2; exit 1; }
[ -x "$jdk/bin/java" ] || { echo "no $jdk/bin/java" >&2; exit 1; }

mkdir -p "$here/build"
cc -O2 -Wall -pthread -o "$here/build/slowfs" "$here/slowfs.c" || exit 1
mnt=$(mktemp -d "${TMPDIR:-/tmp}/slowfs.XXXXXX")
res=$(mktemp "${TMPDIR:-/tmp}/slowfs-res.XXXXXX")
fspid=
cleanup() {
  fusermount3 -u "$mnt" 2>/dev/null
  [ -n "$fspid" ] && { wait "$fspid" 2>/dev/null || true; }
  rmdir "$mnt" 2>/dev/null
  rm -f "$res"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

"$here/build/slowfs" "$mnt" "$delay" 32 >/dev/null &
fspid=$!
for _ in $(seq 50); do [ -e "$mnt/stats" ] && break; sleep 0.1; done
[ -e "$mnt/stats" ] || { echo "slowfs did not mount" >&2; exit 1; }

for p in $paths; do
  timeout "$tmo" "$jdk/bin/java" -Djdk.virtualThreadScheduler.parallelism="$par" "$@" \
      "$here/Repro.java" "$mnt" "$p" | grep '^RESULT ' >> "$res" \
    || echo "RESULT path=$p FAILED" >> "$res"
done

echo "jdk: $("$jdk/bin/java" -version 2>&1 | head -1)   delay=${delay}ms parallelism=$par"
"$here/table.sh" "$res"
