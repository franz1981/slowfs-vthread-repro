ARG JDK_IMAGE=docker.io/library/eclipse-temurin:27-jdk
FROM ${JDK_IMAGE}
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      nfs-common netbase curl gawk && rm -rf /var/lib/apt/lists/*
