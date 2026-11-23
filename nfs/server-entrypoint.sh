#!/bin/bash
# Create the files Repro.java expects (small0..63: 4 KiB, big0..63: 1 GiB sparse), then run ganesha in the foreground.
set -e
mkdir -p /export /var/run/ganesha
for i in $(seq 0 63); do
  [ -e /export/small$i ] || head -c 4096 /dev/urandom > /export/small$i
  [ -e /export/big$i ] || truncate -s 1G /export/big$i
done
chmod -R a+rwX /export
exec ganesha.nfsd -F -L /dev/stdout -f /etc/ganesha/ganesha.conf -N NIV_EVENT
