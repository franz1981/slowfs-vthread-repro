#!/bin/bash
# NFS mode: nfs-ganesha server + toxiproxy (latency) + client container that mounts the export through the proxy
# and runs Repro.java once per path. Needs rootful podman (sudo) and podman-compose; nothing is installed on the host.
#   ./run-nfs.sh [/path/to/jdk]          # default: JDK from the eclipse-temurin:27-jdk image
# Env: DELAY_MS (100), PARALLELISM (2), TIMEOUT (60), PATHS, MOUNT_OPTS, JVM_ARGS, CLIENT_CPUSET (e.g. 0-3, optional), JDK_IMAGE.
set -u
here=$(cd "$(dirname "$0")" && pwd)
if [ $# -ge 1 ]; then
  [ -x "$1/bin/java" ] || { echo "no $1/bin/java" >&2; exit 1; }
  export JDK_DIR=$(cd "$1" && pwd)
fi
command -v podman >/dev/null || { echo "podman not found" >&2; exit 1; }
command -v podman-compose >/dev/null || { echo "podman-compose not found" >&2; exit 1; }
sudo -n true 2>/dev/null || { echo "sudo needs a password; it will prompt (rootful podman)" >&2; sudo true || { echo "sudo is required (rootful podman)" >&2; exit 1; }; }
export DELAY_MS PARALLELISM TIMEOUT PATHS MOUNT_OPTS JVM_ARGS CLIENT_CPUSET JDK_IMAGE
c="sudo -E podman-compose -f $here/nfs/compose.yaml"
[ -n "${CLIENT_CPUSET:-}" ] && c="$c -f $here/nfs/compose-cpuset.yaml"
cleanup() { timeout 120 $c down -t 2 >/dev/null 2>&1; }
trap cleanup EXIT
trap 'exit 130' INT TERM
timeout 900 $c build >/dev/null || { echo "image build failed" >&2; exit 1; }
timeout 900 $c up -d >/dev/null || { echo "compose up failed" >&2; exit 1; }
rc=$(timeout 900 sudo podman wait slowfs-nfs-client)
sudo podman logs slowfs-nfs-client 2>&1
exit "${rc:-1}"
