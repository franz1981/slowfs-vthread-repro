// slowfs: a tiny user-level FUSE filesystem in which every file operation waits DELAY_MS
// in the daemon before replying, like an NFS client waiting on a slow server RPC.
//
// No libfuse needed: it speaks the kernel FUSE protocol (<linux/fuse.h>, from kernel-headers)
// and mounts through the setuid fusermount3 helper, so no root is required.
//
//   gcc -O2 -pthread -o slowfs slowfs.c
//   ./slowfs MOUNTPOINT DELAY_MS [THREADS]      # runs in the foreground until unmounted
//   fusermount3 -u MOUNTPOINT
//
// Contents (flat root directory):
//   small0..small63  4 KiB files
//   big0..big63      1 GiB files (data is generated, writes are accepted and discarded)
//   stats            per-opcode request counters, served without delay and not counted
//
// Every LOOKUP, GETATTR, SETATTR, OPEN, READ, WRITE, FLUSH, FSYNC and ACCESS on a data file sleeps
// DELAY_MS before replying. Files are opened with FOPEN_DIRECT_IO and all cache timeouts are 0, so
// each read/write/stat syscall reaches the daemon (no page cache or attribute cache hits).
// THREADS (default 16) requests are served concurrently.
#define _GNU_SOURCE
#include <linux/fuse.h>
#include <errno.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define NFILES 64
#define SMALL_SIZE 4096ULL
#define BIG_SIZE (1ULL << 30)
#define ROOT_ID 1ULL
#define STATS_ID 2ULL
#define SMALL_ID(i) (16ULL + (i))
#define BIG_ID(i) (16ULL + NFILES + (i))
#define BUFSZ (1 << 20)

static int fuse_fd;
static long delay_ms;
static atomic_ulong counts[64];

static const char *opname(unsigned op) {
    switch (op) {
    case FUSE_LOOKUP: return "LOOKUP";   case FUSE_GETATTR: return "GETATTR";
    case FUSE_SETATTR: return "SETATTR"; case FUSE_OPEN: return "OPEN";
    case FUSE_READ: return "READ";       case FUSE_WRITE: return "WRITE";
    case FUSE_FLUSH: return "FLUSH";     case FUSE_RELEASE: return "RELEASE";
    case FUSE_FSYNC: return "FSYNC";     case FUSE_ACCESS: return "ACCESS";
    case FUSE_STATFS: return "STATFS";   case FUSE_FORGET: return "FORGET";
    case FUSE_BATCH_FORGET: return "BATCH_FORGET";
    case FUSE_GETXATTR: return "GETXATTR"; case FUSE_LISTXATTR: return "LISTXATTR";
    case FUSE_OPENDIR: return "OPENDIR"; case FUSE_READDIR: return "READDIR";
    case FUSE_RELEASEDIR: return "RELEASEDIR";
    case FUSE_INTERRUPT: return "INTERRUPT"; case FUSE_LSEEK: return "LSEEK";
    default: return NULL;
    }
}

static void reply(uint64_t unique, int err, const void *data, size_t len) {
    struct fuse_out_header h = { .len = sizeof h + (err ? 0 : len), .error = -err, .unique = unique };
    char buf[sizeof h + 4096];
    if (err == 0 && len > 4096) {          // large READ payload: header + data via a heap buffer
        char *big = malloc(sizeof h + len);
        memcpy(big, &h, sizeof h);
        memcpy(big + sizeof h, data, len);
        if (write(fuse_fd, big, sizeof h + len) < 0 && errno != ENOENT) perror("write reply");
        free(big);
        return;
    }
    memcpy(buf, &h, sizeof h);
    if (err == 0 && len) memcpy(buf + sizeof h, data, len);
    // ENOENT: the request was interrupted and is gone; nothing to do.
    if (write(fuse_fd, buf, h.len) < 0 && errno != ENOENT) perror("write reply");
}

static int fill_attr(uint64_t id, struct fuse_attr *a) {
    memset(a, 0, sizeof *a);
    a->ino = id;
    a->uid = getuid();
    a->gid = getgid();
    a->blksize = 4096;
    a->atime = a->mtime = a->ctime = 1700000000;
    if (id == ROOT_ID) { a->mode = S_IFDIR | 0755; a->nlink = 2; return 0; }
    a->nlink = 1;
    a->mode = S_IFREG | 0644;
    if (id == STATS_ID) a->size = 0;
    else if (id >= SMALL_ID(0) && id < SMALL_ID(NFILES)) a->size = SMALL_SIZE;
    else if (id >= BIG_ID(0) && id < BIG_ID(NFILES)) a->size = BIG_SIZE;
    else return ENOENT;
    a->blocks = a->size / 512;
    return 0;
}

static uint64_t lookup_name(const char *name) {
    int i;
    if (strcmp(name, "stats") == 0) return STATS_ID;
    if (sscanf(name, "small%d", &i) == 1 && i >= 0 && i < NFILES) return SMALL_ID(i);
    if (sscanf(name, "big%d", &i) == 1 && i >= 0 && i < NFILES) return BIG_ID(i);
    return 0;
}

static size_t stats_text(char *out, size_t cap) {
    size_t n = snprintf(out, cap, "delay_ms %ld\n", delay_ms);
    for (unsigned op = 0; op < 64; op++) {
        unsigned long c = atomic_load(&counts[op]);
        const char *nm = opname(op);
        if (c) n += snprintf(out + n, cap - n, "%s %lu\n", nm ? nm : "OP", c);
    }
    return n;
}

static void handle(char *req) {
    struct fuse_in_header *in = (struct fuse_in_header *) req;
    void *arg = req + sizeof *in;
    uint64_t id = in->nodeid;
    unsigned op = in->opcode;

    uint64_t target = id;
    if (op == FUSE_LOOKUP) target = lookup_name((char *) arg);
    int data_node = target != ROOT_ID && target != STATS_ID && target != 0;
    if (data_node && op < 64) atomic_fetch_add(&counts[op], 1);

    if (data_node) {
        switch (op) {
        case FUSE_LOOKUP: case FUSE_GETATTR: case FUSE_SETATTR: case FUSE_OPEN: case FUSE_READ:
        case FUSE_WRITE: case FUSE_FLUSH: case FUSE_FSYNC: case FUSE_ACCESS: {
            struct timespec ts = { delay_ms / 1000, (delay_ms % 1000) * 1000000L };
            while (nanosleep(&ts, &ts) == -1 && errno == EINTR) { }
        }
        }
    }

    switch (op) {
    case FUSE_INIT: {
        struct fuse_init_in *ii = arg;
        struct fuse_init_out o = { 0 };
        o.major = FUSE_KERNEL_VERSION;
        o.minor = ii->minor < FUSE_KERNEL_MINOR_VERSION ? ii->minor : FUSE_KERNEL_MINOR_VERSION;
        o.max_readahead = ii->max_readahead;
        o.max_background = 16;
        o.congestion_threshold = 12;
        o.max_write = 128 * 1024;
        o.time_gran = 1;
        reply(in->unique, 0, &o, sizeof o);
        return;
    }
    case FUSE_LOOKUP: {
        struct fuse_entry_out e = { 0 };
        if (target == 0 || id != ROOT_ID) { reply(in->unique, ENOENT, NULL, 0); return; }
        e.nodeid = target;
        e.generation = 1;
        fill_attr(target, &e.attr);
        reply(in->unique, 0, &e, sizeof e);
        return;
    }
    case FUSE_GETATTR: case FUSE_SETATTR: {
        struct fuse_attr_out a = { 0 };
        int err = fill_attr(id, &a.attr);
        reply(in->unique, err, &a, sizeof a);
        return;
    }
    case FUSE_OPEN: case FUSE_OPENDIR: {
        struct fuse_open_out o = { .fh = id, .open_flags = op == FUSE_OPEN ? FOPEN_DIRECT_IO : 0 };
        reply(in->unique, 0, &o, sizeof o);
        return;
    }
    case FUSE_READ: {
        struct fuse_read_in *r = arg;
        if (id == STATS_ID) {
            static __thread char txt[4096];
            size_t n = stats_text(txt, sizeof txt);
            size_t off = r->offset < n ? r->offset : n;
            size_t len = n - off < r->size ? n - off : r->size;
            reply(in->unique, 0, txt + off, len);
            return;
        }
        struct fuse_attr a;
        fill_attr(id, &a);
        uint64_t len = r->offset >= a.size ? 0 : a.size - r->offset;
        if (len > r->size) len = r->size;
        char *data = malloc(len ? len : 1);
        memset(data, 'x', len);
        reply(in->unique, 0, data, len);
        free(data);
        return;
    }
    case FUSE_WRITE: {
        struct fuse_write_in *w = arg;
        struct fuse_write_out o = { .size = w->size };
        reply(in->unique, 0, &o, sizeof o);
        return;
    }
    case FUSE_STATFS: {
        struct fuse_statfs_out s = { 0 };
        s.st.bsize = s.st.frsize = 4096;
        s.st.namelen = 255;
        s.st.blocks = 1 << 20;
        reply(in->unique, 0, &s, sizeof s);
        return;
    }
    case FUSE_FLUSH: case FUSE_RELEASE: case FUSE_FSYNC: case FUSE_ACCESS: case FUSE_RELEASEDIR:
        reply(in->unique, 0, NULL, 0);
        return;
    case FUSE_FORGET: case FUSE_BATCH_FORGET: case FUSE_INTERRUPT:
        return;                                   // no reply for these
    default:
        reply(in->unique, ENOSYS, NULL, 0);       // xattrs, readdir, create, ...: not supported
        return;
    }
}

static void *worker(void *unused) {
    char *buf = malloc(BUFSZ);
    for (;;) {
        ssize_t n = read(fuse_fd, buf, BUFSZ);
        if (n < 0) {
            if (errno == EINTR || errno == EAGAIN || errno == ENOENT) continue;
            if (errno == ENODEV) exit(0);         // unmounted
            perror("read /dev/fuse");
            exit(1);
        }
        handle(buf);
    }
    return NULL;
}

// Mount through fusermount3: it opens /dev/fuse, mounts, and passes the fd back over a socket.
static int mount_fusermount(const char *mnt) {
    int sv[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) < 0) { perror("socketpair"); exit(1); }
    pid_t pid = fork();
    if (pid == 0) {
        char fdstr[16];
        snprintf(fdstr, sizeof fdstr, "%d", sv[1]);
        setenv("_FUSE_COMMFD", fdstr, 1);
        close(sv[0]);
        execlp("fusermount3", "fusermount3", "-o", "nosuid,nodev,fsname=slowfs,subtype=slowfs",
               "--", mnt, (char *) NULL);
        perror("exec fusermount3");
        _exit(1);
    }
    close(sv[1]);
    char dummy;
    char ctl[CMSG_SPACE(sizeof(int))];
    struct iovec iov = { &dummy, 1 };
    struct msghdr msg = { .msg_iov = &iov, .msg_iovlen = 1, .msg_control = ctl, .msg_controllen = sizeof ctl };
    if (recvmsg(sv[0], &msg, 0) <= 0) { fprintf(stderr, "fusermount3 failed\n"); exit(1); }
    struct cmsghdr *c = CMSG_FIRSTHDR(&msg);
    if (!c || c->cmsg_type != SCM_RIGHTS) { fprintf(stderr, "no fd from fusermount3\n"); exit(1); }
    int fd;
    memcpy(&fd, CMSG_DATA(c), sizeof fd);
    waitpid(pid, NULL, 0);
    close(sv[0]);
    return fd;
}

int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "usage: %s MOUNTPOINT DELAY_MS [THREADS]\n", argv[0]); return 2; }
    delay_ms = atol(argv[2]);
    int nthreads = argc > 3 ? atoi(argv[3]) : 16;
    signal(SIGPIPE, SIG_IGN);
    fuse_fd = mount_fusermount(argv[1]);
    fprintf(stderr, "slowfs: mounted %s, delay %ld ms, %d threads\n", argv[1], delay_ms, nthreads);
    for (int i = 1; i < nthreads; i++) {
        pthread_t t;
        pthread_create(&t, NULL, worker, NULL);
    }
    worker(NULL);
    return 0;
}
