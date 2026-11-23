import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Do virtual threads doing file I/O on a slow filesystem (NFS, FUSE) starve other virtual threads?
 *
 *   java -Djdk.virtualThreadScheduler.parallelism=2 Repro.java DIR PATH
 *
 * DIR holds files small0..small63 (4 KiB) and big0..big63 (large), e.g. a slowfs mount where every
 * file operation waits in the kernel for a fixed delay. PATH is one of the operations below.
 *
 * 2 x parallelism "slow" virtual threads run PATH in a loop on DIR for a 3 s window. Meanwhile a
 * platform thread starts a trivial virtual thread every 10 ms and records how late it starts.
 * If the slow I/O holds every carrier without compensation, the trivial threads cannot start until
 * the window ends. The program prints one RESULT line: ticker lateness, slow-op count, the threads
 * that carried the slow virtual threads, and the peak number of live threads per name pattern
 * (compensation shows up as extra ForkJoinPool workers).
 */
public class Repro {
    static final long WINDOW_MS = Long.getLong("repro.windowMs", 3000);
    static final long TICK_MS = 10;
    static final long STARVED_MS = 100;   // a ticker start later than this counts as starved
    /** Distance between consecutive read offsets; > 0 defeats the NFS page cache and read-ahead (see README). */
    static final long STRIDE = Long.getLong("repro.stride", 0);
    static final long BIG_SIZE = 1L << 30;

    static long nextPos(long[] n) { return (n[0]++ * STRIDE) % (BIG_SIZE - 4096); }

    interface Op { void run() throws IOException; }

    static Op makeOp(String path, Path dir, int i) throws IOException {
        Path small = dir.resolve("small" + i), big = dir.resolve("big" + i);
        byte[] buf = new byte[4096];
        ByteBuffer bb = ByteBuffer.allocate(4096);
        switch (path) {
            case "sleep": {                       // control: Thread.sleep unmounts the virtual thread
                return () -> { try { Thread.sleep(100); } catch (InterruptedException e) { throw new IOException(e); } };
            }
            case "fis-read": {
                FileInputStream in = new FileInputStream(big.toFile());
                long[] n = {0};
                return () -> { if (STRIDE > 0) in.getChannel().position(nextPos(n)); in.read(buf); };
            }
            case "fos-write": {
                FileOutputStream out = new FileOutputStream(big.toFile(), true);
                return () -> out.write(buf);
            }
            case "raf-read": {
                RandomAccessFile raf = new RandomAccessFile(big.toFile(), "r");
                long[] n = {0};
                return () -> { if (STRIDE > 0) raf.seek(nextPos(n)); raf.read(buf); };
            }
            case "fc-read": {
                FileChannel fc = FileChannel.open(big, StandardOpenOption.READ);
                long[] n = {0};
                return () -> { bb.clear(); if (STRIDE > 0) fc.read(bb, nextPos(n)); else fc.read(bb); };
            }
            case "open-close":
                return () -> new FileInputStream(small.toFile()).close();
            case "files-readAllBytes":
                return () -> Files.readAllBytes(small);
            case "files-size":
                return () -> Files.size(small);
            case "fc-force": {                    // fsync: compensated on FJP (Blocker section)
                FileChannel fc = FileChannel.open(big, StandardOpenOption.WRITE);
                return () -> fc.force(false);
            }
            case "fc-write-force": {              // write 4 KiB, then fsync: on NFS the fsync has dirty data to flush
                FileChannel fc = FileChannel.open(big, StandardOpenOption.WRITE);
                long[] n = {0};
                return () -> { bb.clear(); bb.put(buf).flip(); fc.write(bb, nextPos(n)); fc.force(false); };
            }
            case "raf-rws-write": {               // O_SYNC write: compensated on FJP (Blocker section)
                RandomAccessFile raf = new RandomAccessFile(big.toFile(), "rws");
                return () -> raf.write(buf);
            }
            default:
                throw new IllegalArgumentException("unknown path " + path);
        }
    }

    static String pattern(String name) { return name.replaceAll("\\d+", "#"); }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        String path = args[1];
        int parallelism = Integer.getInteger("jdk.virtualThreadScheduler.parallelism",
                Runtime.getRuntime().availableProcessors());
        int nSlow = 2 * parallelism;

        // Open the files on platform threads, in parallel, so that setup is not part of the window.
        ExecutorService setup = Executors.newFixedThreadPool(nSlow, r -> new Thread(r, "repro-setup"));
        List<Future<Op>> futures = new ArrayList<>();
        for (int i = 0; i < nSlow; i++) { int idx = i; futures.add(setup.submit(() -> makeOp(path, dir, idx))); }
        Op[] ops = new Op[nSlow];
        for (int i = 0; i < nSlow; i++) ops[i] = futures.get(i).get();
        setup.shutdown();

        Thread.ofVirtual().start(() -> { }).join();   // start the scheduler before the baseline
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        Map<String, Integer> base = threadCounts(mx);
        Map<String, Long> fsBefore = fsStats(dir);
        Map<String, Long> rttBefore = nfsRttMs(dir);

        long t0 = System.nanoTime(), end = t0 + WINDOW_MS * 1_000_000;
        AtomicLong slowOps = new AtomicLong();
        Thread[] slow = new Thread[nSlow];
        for (int i = 0; i < nSlow; i++) {
            Op op = ops[i];
            slow[i] = Thread.ofVirtual().name("slow-" + i).start(() -> {
                try {
                    while (System.nanoTime() < end) { op.run(); slowOps.incrementAndGet(); }
                } catch (IOException e) { throw new UncheckedIOException(e); }
            });
        }

        // Ticker: every 10 ms start a trivial virtual thread; record how late it starts.
        int nTicks = (int) (WINDOW_MS / TICK_MS);
        long[] lateness = new long[nTicks];
        Arrays.fill(lateness, -1);
        Thread[] tickVts = new Thread[nTicks];
        Thread ticker = Thread.ofPlatform().name("repro-ticker").start(() -> {
            for (int k = 0; k < nTicks; k++) {
                long due = t0 + k * TICK_MS * 1_000_000;
                while (System.nanoTime() < due) LockSupport.parkNanos(due - System.nanoTime());
                int kk = k;
                long submitted = System.nanoTime();
                tickVts[k] = Thread.ofVirtual().start(() -> lateness[kk] = System.nanoTime() - submitted);
            }
        });

        // Sampler: peak live threads per name pattern, and which thread carries each slow virtual thread.
        Map<String, Integer> peak = new TreeMap<>(base);
        Map<String, Integer> carriedBy = new TreeMap<>();
        Thread sampler = Thread.ofPlatform().name("repro-sampler").start(() -> {
            while (System.nanoTime() < end) {
                threadCounts(mx).forEach((k, v) -> peak.merge(k, v, Math::max));
                for (Thread vt : slow) {
                    String s = vt.toString();
                    int at = s.indexOf('@');
                    if (at >= 0) carriedBy.merge(pattern(s.substring(at + 1)), 1, Integer::sum);
                }
                LockSupport.parkNanos(20_000_000);
            }
        });

        ticker.join();
        sampler.join();
        for (Thread t : slow) t.join(java.time.Duration.ofMillis(10_000));
        for (Thread t : tickVts) t.join(java.time.Duration.ofMillis(10_000));
        Map<String, Long> fsAfter = fsStats(dir);
        Map<String, Long> rttAfter = nfsRttMs(dir);

        long[] ran = Arrays.stream(lateness).filter(x -> x >= 0).sorted().toArray();
        double p50 = ran.length == 0 ? -1 : ran[ran.length / 2] / 1e6;
        double max = ran.length == 0 ? -1 : ran[ran.length - 1] / 1e6;
        long late = Arrays.stream(ran).filter(x -> x > STARVED_MS * 1_000_000).count();
        StringBuilder grew = new StringBuilder();
        peak.forEach((k, v) -> {
            int b = base.getOrDefault(k, 0);
            if (v > b && !k.startsWith("repro-")) grew.append(grew.isEmpty() ? "" : ",").append(k).append(':').append(b).append("->").append(v);
        });
        StringBuilder carriers = new StringBuilder();
        base.forEach((k, v) -> { if (k.contains("worker") || k.contains("carrier")) carriers.append(carriers.isEmpty() ? "" : ",").append(k).append(':').append(v); });
        StringBuilder fs = new StringBuilder();
        fsAfter.forEach((k, v) -> { long d = v - fsBefore.getOrDefault(k, 0L); if (d > 0) fs.append(fs.isEmpty() ? "" : ",").append(k).append(':').append(d); });

        // NFS only: average round trip per RPC type over the window (client mountstats), e.g. READ:100.3
        StringBuilder rtt = new StringBuilder();
        fsAfter.forEach((k, v) -> {
            long d = v - fsBefore.getOrDefault(k, 0L), r = rttAfter.getOrDefault(k, 0L) - rttBefore.getOrDefault(k, 0L);
            if (d > 0 && rttAfter.containsKey(k)) rtt.append(rtt.isEmpty() ? "" : ",").append(k).append(':').append(String.format(Locale.ROOT, "%.1f", (double) r / d));
        });

        System.out.printf(Locale.ROOT,
                "RESULT path=%s jdk=%s parallelism=%d slow_vts=%d window_ms=%d slow_ops=%d ticks=%d ticks_ran=%d "
                        + "ticker_p50_ms=%.1f ticker_max_ms=%.1f ticks_late_gt%dms=%d starved=%s "
                        + "carriers_at_start=%s threads_grew=%s slow_vt_carried_by=%s fs_ops=%s fs_avg_rtt_ms=%s%n",
                path, System.getProperty("java.runtime.version"), parallelism, nSlow, WINDOW_MS, slowOps.get(),
                nTicks, ran.length, p50, max, STARVED_MS, late, late > 0 ? "yes" : "no",
                carriers.isEmpty() ? "-" : carriers, grew.isEmpty() ? "-" : grew,
                carriedBy.isEmpty() ? "-" : carriedBy.toString().replace(" ", ""), fs.isEmpty() ? "-" : fs, rtt.isEmpty() ? "-" : rtt);
        System.exit(0);
    }


    static Map<String, Integer> threadCounts(ThreadMXBean mx) {
        Map<String, Integer> m = new TreeMap<>();
        for (ThreadInfo ti : mx.getThreadInfo(mx.getAllThreadIds())) {
            if (ti != null) m.merge(pattern(ti.getThreadName()), 1, Integer::sum);
        }
        return m;
    }

    /** Per-operation request counters of a slowfs mount (DIR/stats), empty if DIR is not slowfs. */
    static Map<String, Long> fsStats(Path dir) {
        Map<String, Long> m = new TreeMap<>();
        if (!Files.exists(dir.resolve("stats"))) return nfsMountStats(dir, 0);
        try {
            for (String line : Files.readAllLines(dir.resolve("stats"))) {
                String[] kv = line.split(" ");
                if (kv.length == 2 && !kv[0].equals("delay_ms")) m.put(kv[0], Long.parseLong(kv[1]));
            }
        } catch (IOException | NumberFormatException ignored) { }
        return m;
    }

    static Map<String, Long> nfsRttMs(Path dir) { return nfsMountStats(dir, 1); }

    /**
     * Linux NFS client per-RPC statistics of the mount that holds DIR, from /proc/self/mountstats:
     * what = 0 gives the request count per RPC type, what = 1 the cumulative round-trip time in ms.
     * Empty if DIR is not on NFS.
     */
    static Map<String, Long> nfsMountStats(Path dir, int what) {
        Map<String, Long> m = new TreeMap<>();
        try {
            String d = dir.toRealPath().toString();
            List<String> lines = Files.readAllLines(Path.of("/proc/self/mountstats"));
            int best = -1, bestLen = -1;
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                int on = l.indexOf(" mounted on ");
                if (!l.startsWith("device ") || on < 0 || !l.contains("fstype nfs")) continue;
                String mp = l.substring(on + 12, l.indexOf(" with fstype"));
                if ((d.equals(mp) || d.startsWith(mp + "/")) && mp.length() >= bestLen) { best = i; bestLen = mp.length(); }
            }
            if (best < 0) return m;
            boolean perOp = false;
            for (int i = best + 1; i < lines.size() && !lines.get(i).startsWith("device "); i++) {
                String l = lines.get(i).trim();
                if (l.startsWith("per-op statistics")) { perOp = true; continue; }
                if (!perOp) continue;
                String[] f = l.split("\\s+");
                if (f.length >= 9 && f[0].endsWith(":")) {   // name: ops trans timeouts sent recv queue rtt execute
                    long ops = Long.parseLong(f[1]);
                    if (ops > 0) m.put(f[0].substring(0, f[0].length() - 1), Long.parseLong(f[what == 0 ? 1 : 7]));
                }
            }
        } catch (IOException | NumberFormatException ignored) { }
        return m;
    }
}
