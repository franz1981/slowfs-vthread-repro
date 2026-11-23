#!/bin/bash
# Print the result table from a file of RESULT lines: ./table.sh RESULT_FILE
awk 'BEGIN { printf "%-20s %-8s %14s %8s %10s\n", "path", "starved", "ticker_max_ms", "workers", "ops" }
{
  delete kv
  for (i = 2; i <= NF; i++) { n = index($i, "="); if (n) kv[substr($i, 1, n-1)] = substr($i, n+1) }
  if ($0 ~ /FAILED/) { printf "%-20s FAILED\n", kv["path"]; next }
  peak = "?"; m = split(kv["threads_grew"], g, ",")
  for (j = 1; j <= m; j++) if (g[j] ~ /worker-#:/) { sub(/.*->/, "", g[j]); peak = g[j] }
  printf "%-20s %-8s %14s %8s %10s\n", kv["path"], kv["starved"], kv["ticker_max_ms"], peak, kv["slow_ops"]
}' "$1"
