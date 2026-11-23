FROM docker.io/library/ubuntu:24.04
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      nfs-ganesha nfs-ganesha-vfs ca-certificates && rm -rf /var/lib/apt/lists/*
COPY ganesha.conf /etc/ganesha/ganesha.conf
COPY server-entrypoint.sh /server-entrypoint.sh
ENTRYPOINT ["/server-entrypoint.sh"]
