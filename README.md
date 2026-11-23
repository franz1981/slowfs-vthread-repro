# Virtual threads and file I/O on a slow filesystem

- On JDK 27 and 28-ea, with the default scheduler, buffered file I/O (read, write, open, close, `Files.size`, ...)
  on a slow filesystem (NFS, FUSE) blocks the carrier thread and no extra carrier is added (JDK-8329593).
- Once every carrier is blocked this way, no other virtual thread in the JVM runs, including ones that do no
  file I/O. In the run below they wait 3 s.
- `FileChannel.force` and `RandomAccessFile` "rws" writes do add carriers, and nothing is starved.

`Repro.java` runs 4 virtual threads looping on one operation for 3 s with
`-Djdk.virtualThreadScheduler.parallelism=2`, while a trivial virtual thread is started every 10 ms. "starved" means
a tick started more than 100 ms late. Every call to the filesystem takes 100 ms.

## Run

Linux, a JDK 27 or later. Both scripts take an optional JDK path (FUSE: default `$JAVA_HOME`; NFS: default is the
JDK of the `eclipse-temurin:27-jdk` image).

FUSE (about 45 s). Needs `fusermount3` (package `fuse3`), `/dev/fuse` usable by your user, a C compiler and kernel
headers. `slowfs.c` is a small user-level FUSE filesystem (no libfuse, no root) that answers every request after
100 ms, with direct I/O and zero cache timeouts so every call reaches it.

```
./run.sh /path/to/jdk
```

NFS (about 60 s after the first image build). Needs `sudo` with rootful podman (the client container is privileged,
to call mount(2)), `podman-compose` and network access to pull images. 100 ms is added by toxiproxy between an
nfs-ganesha server and the kernel NFS client; client caching is off (`noac,lookupcache=none`, reads at a new 1 MiB
offset each time) so every call is an RPC. Containers `slowfs-*` are removed on exit.

```
./run-nfs.sh [/path/to/jdk]
```

Env for both: `DELAY_MS` (100), `PARALLELISM` (2), `TIMEOUT` (60 s per JVM), `PATHS`, `JVM_ARGS` (NFS only; FUSE
takes extra arguments after the JDK path). NFS also: `MOUNT_OPTS`, `CLIENT_CPUSET` (e.g. `0-3`, optional).

Columns: `ticker_max_ms` = latest start of a trivial virtual thread; `workers` = peak ForkJoinPool workers
(2 = parallelism, 5 = compensated); `ops` = slow operations completed (120 if all 4 slow threads ran, 60 if only 2).
Paths are named after the API they call; `sleep` is the control, `fc-write-force` (NFS only) is a 4 KiB write
then `FileChannel.force`.

## Expected output

FUSE, JDK 27 (build 2026-09-15). Timings vary by a few ms between runs.

```
jdk: openjdk version "27" 2026-09-15   delay=100ms parallelism=2
path                 starved   ticker_max_ms  workers        ops
sleep                no                  0.9        2        120
fis-read             yes              3001.5        2         60
fos-write            yes              3001.7        2         60
raf-read             yes              3001.1        2         60
fc-read              yes              3001.9        2         60
open-close           yes              3202.0        2         16
files-readAllBytes   yes              3002.6        2         10
files-size           yes              3002.0        2         30
fc-force             no                  1.1        5        120
raf-rws-write        no                  0.8        5        120
```

NFS, Temurin 27.0.1.1. After the table the script prints the RPCs sent per path (omitted here). A path with fewer
RPCs than operations was served locally and is reported as not reproduced: `fc-force` with nothing to flush, hence
`fc-write-force`.

```
jdk: openjdk version "27.0.1.1" 2026-11-17   nfs latency=100ms (toxiproxy, downstream) parallelism=2
path                 starved   ticker_max_ms  workers        ops
sleep                no                  0.7        2        120
fis-read             yes              3020.2        2         30
fos-write            yes              3020.0        2         30
raf-read             yes              3121.3        2         46
fc-read              yes              3120.7        2         54
open-close           yes              3423.1        2         12
files-readAllBytes   yes              3221.9        2          8
files-size           yes              3120.4        2         16
fc-force             no                  0.9        5   15481690
fc-write-force       no                  0.7        5        120
raf-rws-write        no                  0.7        5        120
NOT REPRODUCED ON NFS: fc-force (15481690 ops but only 3 RPCs; the calls were served locally)
```

Tested on JDK 27 and 28-ea+25.

## Related

- JDK-8329593: Drop adjustments to target parallelism when virtual threads do I/O on files opened for buffered I/O
