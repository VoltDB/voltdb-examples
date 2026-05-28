# ============================================================================
# VoltDB ddos-detection Example - Docker Image
# ============================================================================
#
# Build from repo root:
#   docker build -t voltdb-example-ddos-detection -f ddos-detection/Dockerfile .
#
# Override the VoltDB base image at build time (e.g. for the gated enterprise
# build, or a different version):
#   docker build --build-arg VOLTDB_IMAGE=voltdb/voltdb-enterprise:15.2.0 ...
# ============================================================================

ARG VOLTDB_IMAGE=voltactivedata/volt-developer-edition:15.2.0_voltdb

# ----------------------------------------------------------------------------
# Stage 1: Build with Maven
# ----------------------------------------------------------------------------
FROM registry.access.redhat.com/ubi10-minimal:latest AS builder

# Install Java 21 and Maven dependencies
RUN microdnf upgrade --refresh --assumeyes --best && \
    microdnf install --assumeyes --best --nodocs \
      java-21-openjdk-devel \
      tar \
      gzip \
      which && \
    microdnf clean all

# Install Maven manually (not in UBI repos)
ARG MAVEN_VERSION=3.9.6
RUN curl -fsSL https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz | \
    tar -xzf - -C /opt && \
    ln -s /opt/apache-maven-${MAVEN_VERSION} /opt/maven

ENV MAVEN_HOME=/opt/maven
ENV PATH="${MAVEN_HOME}/bin:${PATH}"
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk

WORKDIR /build

# Copy entire project (needed for parent POM module resolution)
COPY . .

# Build and copy dependencies
RUN mvn package -pl ddos-detection -am -DskipTests -q && \
    mkdir -p /build/ddos-detection/target/dependency && \
    mvn dependency:copy-dependencies -f ddos-detection/pom.xml \
        -DoutputDirectory=/build/ddos-detection/target/dependency \
        -DincludeScope=runtime -q

# ----------------------------------------------------------------------------
# Stage 2: VoltDB source (for sqlcmd and tools)
# ----------------------------------------------------------------------------
FROM ${VOLTDB_IMAGE} AS voltdb-source

# ----------------------------------------------------------------------------
# Stage 3: Runtime Image
# ----------------------------------------------------------------------------
FROM registry.access.redhat.com/ubi10-minimal:latest

# Install runtime packages
RUN microdnf upgrade --refresh --assumeyes --best && \
    microdnf install --assumeyes --best --nodocs \
      hostname \
      iputils \
      java-21-openjdk-headless \
      jq \
      less \
      nc \
      net-tools \
      openssl \
      pip \
      procps \
      python3 \
      shadow-utils \
      tar \
      vim-minimal \
      which && \
    microdnf clean all

ENV JAVA_HOME=/usr/lib/jvm/jre-21-openjdk

# Copy VoltDB installation for sqlcmd and tools
COPY --from=voltdb-source /opt/voltdb /opt/voltdb

ENV VOLTDB_HOME=/opt/voltdb
ENV PATH="${VOLTDB_HOME}/bin:${JAVA_HOME}/bin:${PATH}"

WORKDIR /app

# Create non-root user
RUN groupadd -g 10000 voltapp && useradd -u 10000 -g voltapp -m voltapp

# Copy artifacts from builder
COPY --from=builder /build/ddos-detection/target/ddos-detection-1.0-SNAPSHOT.jar /app/procs.jar
COPY --from=builder /build/ddos-detection/target/ddos-detection-1.0-SNAPSHOT.jar /app/client.jar
COPY --from=builder /build/ddos-detection/target/dependency/ /app/lib/
COPY --from=builder /build/ddos-detection/src/main/resources/ddl.sql /app/ddl.sql

COPY docker/scripts/entrypoint.sh /app/entrypoint.sh

RUN chmod +x /app/entrypoint.sh && chown -R voltapp:voltapp /app

ENV APP_NAME=ddos-detection
ENV MAIN_CLASS=com.example.voltdb.DdosDetectionApp
ENV DDL_FILE=/app/ddl.sql
ENV PROCS_JAR=/app/procs.jar
ENV CLIENT_JAR=/app/client.jar
ENV VOLTDB_SERVERS=localhost:21212
ENV DURATION=120
ENV WARMUP=5
ENV DISPLAY_INTERVAL=5
ENV MODE=init-run
# This app uses positional arguments: host port
ENV ARG_STYLE=positional

USER voltapp
ENTRYPOINT ["/app/entrypoint.sh"]
