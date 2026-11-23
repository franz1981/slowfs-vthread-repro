#!/bin/bash
# Runs inside the client container: add latency in toxiproxy, mount the export through it, run Repro.java per path.
set -u
delay=${DELAY_MS:-100}; par=${PARALLELISM:-2}; tmo=${TIMEOUT:-60}
paths=${PATHS:-"sleep fis-read fos-write raf-read fc-read open-close files-readAllBytes files-size fc-force fc-write-force raf-rws-write"}
java=java; [ -x /jdk/bin/java ] && java=/jdk/bin/java

for _ in $(seq 60); do curl -sf http://slowfs-toxiproxy:8474/version >/dev/null && break; sleep 1; done
curl -sf -X POST http://slowfs-toxiproxy:8474/proxies/nfs/toxics -d \
  "{\"name\":\"lat\",\"type\":\"latency\",\"stream\":\"downstream\",\"attributes\":{\"latency\":$delay,\"jitter\":0}}" >/dev/null \
  || { echo "could not add latency toxic" >&2; exit 1; }
# Two mounts, one at a time. SYNC: noac makes writes synchronous, so every write() is a WRITE RPC (needed for fos-write).
# ASYNC: write() only dirties the page cache, so fsync has data to flush; needed for fc-write-force.
opts_sync=${MOUNT_OPTS:-vers=4.2,proto=tcp,port=2049,hard,noac,lookupcache=none,rsize=65536,wsize=65536}
opts_async=vers=4.2,proto=tcp,port=2049,hard,actimeo=0,lookupcache=none,rsize=65536,wsize=65536
mkdir -p /mnt/nfs
cur=
mount_as() {
  [ "$cur" = "$1" ] && return 0
  [ -n "$cur" ] && { umount /mnt/nfs || { echo "umount failed" >&2; exit 1; }; }
  local o=$opts_sync; [ "$1" = async ] && o=$opts_async
  for _ in $(seq 30); do
    mount -t nfs4 -o "$o" slowfs-toxiproxy:/export /mnt/nfs 2>/tmp/mount.err && { cur=$1; echo "mount ($1): $(grep ' /mnt/nfs ' /proc/mounts)"; return 0; }
    sleep 2
  done
  echo "mount failed:" >&2; cat /tmp/mount.err >&2; exit 1
}
echo "toxic: $(curl -s http://slowfs-toxiproxy:8474/proxies/nfs/toxics)"

res=$(mktemp)
for p in $paths; do
  case $p in fc-write-force) mount_as async;; *) mount_as sync;; esac
  timeout "$tmo" "$java" -Djdk.virtualThreadScheduler.parallelism="$par" -Drepro.stride=1048576 ${JVM_ARGS:-} \
      /repro/Repro.java /mnt/nfs "$p" | grep '^RESULT ' >> "$res" \
    || echo "RESULT path=$p FAILED" >> "$res"
done
echo "jdk: $("$java" -version 2>&1 | head -1)   nfs latency=${delay}ms (toxiproxy, downstream) parallelism=$par"
/repro/table.sh "$res"
echo "--- evidence that each path reaches the server: fs_ops = client RPCs in the 3 s window (/proc/self/mountstats),"
echo "--- fs_avg_rtt_ms = mean round trip per RPC type. A path with fewer RPCs than slow ops never paid the latency."
sed -E 's/^RESULT path=([^ ]+) .*slow_ops=([0-9]+) .*(fs_ops=[^ ]+) (fs_avg_rtt_ms=[^ ]+).*/\1 slow_ops=\2 \3 \4/' "$res"
awk '{ for (i = 1; i <= NF; i++) { if ($i ~ /^path=/) p = substr($i, 6); if ($i ~ /^slow_ops=/) ops = substr($i, 10) }
       if ($0 ~ /path=/ && $0 ~ /slow_ops=/) { n = 0; split($0, a, "fs_ops="); split(a[2], z, " "); m = split(z[1], b, ","); for (k = 1; k <= m; k++) { c = split(b[k], kv, ":"); if (c == 2) n += kv[2] }
       if (p != "sleep" && n < ops + 0) print "NOT REPRODUCED ON NFS: " p " (" ops " ops but only " n " RPCs; the calls were served locally)" } }' "$res"
umount /mnt/nfs
