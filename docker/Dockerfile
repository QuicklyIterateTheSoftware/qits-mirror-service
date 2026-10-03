# qits-platform-mirror as a GraalVM-native container IMAGE — the same shape as qits-artifacts',
# qits-platform-dns' and qits-gateway's docker/Dockerfile: a Mandrel builder stage compiles the
# binary, a minimal runtime stage carries only it.
#
# This file is not how the binary is built; it is how the binary is SHIPPED. `.sdkmanrc` names a
# GraalVM (25.0.2-graalce), so `sdk env && ./mvnw verify -Dnative` produces
# `target/qits-platform-mirror` locally with no container involved. What this adds is the image
# around it — and a way for a machine with no GraalVM (a CI runner, a laptop on a plain OpenJDK) to
# get a binary at all, since the builder stage brings its own native-image.
#
# Build from the repo root:
#
#     git submodule update --init src/main/webui
#     (cd src/main/webui && npm ci && npm run build)
#     docker build \
#       --build-arg QITS_DOMAIN=wohlben.eu \
#       --secret id=qits-client-id,env=QITS_MAVEN_AUTH_USR \
#       --secret id=qits-client-secret,env=QITS_MAVEN_AUTH_PSW \
#       -t qits/platform-mirror:latest -f docker/Dockerfile .
#
# THE FIRST TWO LINES ARE NOT OPTIONAL and are not a convenience: this image carries the explorer
# client (src/main/webui = the qits-platform-spa-mirror submodule), and the builder stage below
# packages a bundle that was ALREADY BUILT rather than building one. See the Quinoa flags on the
# mvnw line for why the build cannot happen inside that stage.
#
# A CREDENTIAL IS REQUIRED and neither host has a workable anonymous default. This repository is ONE
# application over two library repositories: qits-blobstore and
# qits-registries-{common,npm,maven,oci} are jars published to the platform's own Maven repository
# (registry.qits.$QITS_DOMAIN) and available from nowhere else — not Maven Central, and not the
# builder container's empty ~/.m2. The registry is a public host, reachable from a RUN step like any
# other address, but it answers 401 without the commissioned client.
#
# A MIRROR MUST NOT BUILD THROUGH ITSELF. Every registry address in this file is qits-artifacts' —
# the Maven repository above, and the two FROM lines below — so this service's first deploy, and
# every later one, is independent of whether this service is up. Maven Central is dialled directly
# (QITS_MAVEN_CENTRAL_URL stays empty, below) for the same reason: this service IS the Central cache,
# so a deployment being replaced must not be asked to proxy its own replacement's build. Do not
# repoint either address at this deployment's own published port when the cutover moves the
# third-party namespaces here.
#
# --- this image does NOT run with an empty environment, and that is not a packaging bug ------------
# `docker run --rm qits/platform-mirror:latest` dies before the HTTP listener, at Flyway, because
# this service ships its datasource as the platform's generic resource contract and nothing resolves
# it:
#
#   quarkus.datasource.mirror.jdbc.url=${QITS_RESOURCE_DB_URL}
#   quarkus.datasource.mirror.username=${QITS_RESOURCE_DB_USERNAME}
#   quarkus.datasource.mirror.password=${QITS_RESOURCE_DB_PASSWORD}
#
# THAT IS THE DESIGN AND A FALLBACK IS NOT A FIX. A cache that invented an empty store would answer
# every lookup as a miss and refetch the internet on every build, healthily, forever. The refusal is
# what turns that into a visible failure.
#
# ON THE PLATFORM NOBODY SETS THESE BY HAND. This repository's .config/qits/deployments.yml carries
# `resources: postgresql:db`; qits-deployments creates the role and the database `qits_platform_mirror`
# on the tier's postgres before the successor container starts and injects all three variables.
#
# THAT DATABASE IS THE WHOLE OF THIS CONTAINER'S STATE. Cached tarballs, jars and layers are chunk
# rows beside the identity rows that name them (V2__blob_tables.sql, and
# qits.artifacts.blobs-datasource=mirror), so there is NO blobs directory and NO volume to mount. A
# restart loses only whatever fetch was in flight; the cache re-warms itself on the next miss,
# because re-fetching from upstream is what a pull-through cache does.
#
#   docker run -p 127.0.0.1:8082:8080 \
#     -e QITS_RESOURCE_DB_URL=jdbc:postgresql://some-postgres:5432/qits_platform_mirror \
#     -e QITS_RESOURCE_DB_USERNAME=... -e QITS_RESOURCE_DB_PASSWORD=... \
#     qits/platform-mirror:latest
#
# THE PUBLISHED HOST PORT IS NOT OPTIONAL FOR THIS SERVICE, unlike its siblings. Its clients are not
# on qits-net: dockerd resolves no qits-net name, so a `registry-mirrors` entry has to be a host
# address, and `/v2` behind the gateway is already qits-artifacts' entry. The loopback bind is
# deliberate — this is an unauthenticated cache and nothing outside the host should reach it.
#
# --- stage `build`: native-compile ----------------------------------------------------------------
# The builder JDK MUST be 25: native-image cannot read bytecode newer than its own JDK, and this
# project targets release 25.
#
# `mirror.dev.localhost:8080/quay/…` is the platform's pull-through mirror of quay.io: the first
# build fetches the image from upstream and caches it, every later build is served from local disk.
# The tag is upstream's, unchanged — the mirror revalidates it, so `jdk-25` still moves when quay
# moves it.
FROM mirror.dev.localhost:8080/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
USER root
WORKDIR /src

# Fetch the Maven distribution first, keyed only on the wrapper files, so a source-only change does
# not re-download it.
COPY mvnw .
COPY .mvn/ .mvn/
RUN ./mvnw --version

# One module, so this is the module. `.dockerignore` is what keeps the copy cheap and stops a stale
# target/ shadowing what this stage compiles.
COPY . .

# -Dnative flips quarkus.native.enabled through the `native` profile in pom.xml. Cap native-image RAM
# so the build is deterministic on small machines.
#
# Tests are skipped because `./mvnw verify` on a clone is their gate (README.md) and re-running them
# here proves the same thing more slowly — they spawn a real postgres child process, which this stage
# is not the right place to arrange.
#
# `-s .qits-maven-settings.xml` is what lets the `qits-maven` repository be resolved at all: an
# exact repository-id mirror is how the derived URL below reaches the pom's `qits-maven` repository.
#
# --- the Quinoa flags, and why this stage does not build the client -------------------------------
# package-manager-install: the Mandrel builder image ships no node at all, so this stage installs its
# own from nodejs.org, which a docker build can reach. It lives here rather than in
# application.properties on purpose — a developer machine already has node and should keep using it,
# and no build should silently download a toolchain. 22.22.0 is the PLATFORM pin, the node every
# other qits builder stage installs. It is deliberately NOT the step container's node (node:24-alpine
# on node-docker-base, where the bundle is actually built); the two need not agree, because the three
# command overrides below neuter install, ci and build to `--version` and the only thing node does
# here is exist and prove its package manager runs.
#
# THE OVERRIDES ARE THE LOAD-BEARING PART, and the reason this file deviates from the platform's
# Quinoa recipe (docs/project-setup-quinoa-angular.md), which has the builder run the real install:
#
#   src/main/webui is qits-platform-spa-mirror, and it depends on @qits/ui-components. That package
#   exists ONLY on the platform's own npm registry — registry.qits.$QITS_DOMAIN, which is
#   qits-artifacts, reachable from a RUN step like any other public address, but a mirror must not
#   build through itself: the npm PROXY half of that install is THIS service, so this stage is not
#   handed a credential at all and cannot dial either half. An install that "worked" here would have
#   resolved the @qits scope against npmjs.org, where that name does not exist and never will.
#
# The bundle is therefore built BEFORE this image, by the step in .config/qits/ci-post-receive.yml,
# which runs on qits-net where the registry answers, and arrives in the build context as
# src/main/webui/dist/ (see .dockerignore, where dist/ is the one client path NOT excluded).
#
# THE `test -f` GUARD IS WHAT KEEPS THIS FROM FAILING QUIETLY. With no package.json Quinoa disables
# itself with a warning and this stage stays GREEN while shipping no client — a service that boots,
# answers /mirror/ with a 404 and looks deployed. A missing bundle, from a forgotten submodule init
# or a forgotten `npm run build`, is a red build instead, stopped before the multi-minute native
# compile rather than discovered in production.
#
# --- and why the bundle is copied onto itself first -----------------------------------------------
# Quinoa does not copy build-dir into target/quinoa/build, it MOVES it, and overlayfs cannot rename a
# directory that still lives in a LOWER image layer. It answers EXDEV, the JDK falls back to its
# cross-file-store path, and that path refuses a non-empty directory — the build dies seconds in with
# `DirectoryNotEmptyException`, which names the source and explains nothing. A builder stage that
# *creates* dist itself is immune by accident; ours arrives by COPY. One `cp` re-materialises it in
# the layer that is about to move it, and it has to be in THIS `RUN` rather than a tidier one of its
# own — a layer boundary is exactly what it is undoing.
# The platform's hosted Maven, https://registry.qits.$QITS_DOMAIN/artifacts/maven/maven, derived
# below from one ARG — the public domain. `.qits-maven-settings.xml` mirrors it onto the pom's
# `qits-maven` repository id.
ARG QITS_DOMAIN=wohlben.eu
#
# --- and where Maven Central is mirrored, when it is ----------------------------------------------
# A MIRROR MUST NOT BUILD THROUGH ITSELF (see the header): this service IS the Central cache, so
# unlike every other repository's Dockerfile this one does NOT derive QITS_MAVEN_CENTRAL_URL from
# QITS_DOMAIN. It stays its own ARG, EMPTY by default, which means direct Maven Central.
# `.qits-maven-settings.xml` activates its central-proxy profile only on a non-empty value, so a
# bootstrap or hand build that passes nothing resolves exactly as it always did, and the release
# recipe passes a literal empty string for the same reason.
ARG QITS_MAVEN_CENTRAL_URL=
ENV QITS_MAVEN_CENTRAL_URL=$QITS_MAVEN_CENTRAL_URL
#
# --- and how Maven authenticates ------------------------------------------------------------------
# The hosted registry answers 401 anonymously, so every build that resolves through it needs a
# credential. The two secret mounts put the CI run's commissioned client into THIS `RUN`'s
# environment and nowhere else — not a layer, not the image history. That is why they are not build
# args: this image is pullable, and a build arg is recorded where anyone who pulls it can read it.
# `.qits-maven-settings.xml` reads the two variables as the `qits-maven-network` server.
#
# Absent secrets leave both variables empty — so a hand build without them still starts, and fails
# at the first 401 with Maven naming the host. Pass your own client to build by hand:
# `--secret id=qits-client-id,env=… --secret id=qits-client-secret,env=…`.
#
# FILE FORM (`/run/secrets/<id>`, read into the command's own environment below), never the `env=`
# mount form these two lines used to carry. `env=` needs a dockerfile frontend the BUILDER chooses,
# and since the builds moved to the platform's buildkitd the frontend is the builder's to pick, not
# this file's; the bare file mount is served identically by every frontend version.
RUN --mount=type=secret,id=qits-client-id \
    --mount=type=secret,id=qits-client-secret \
    export QITS_MAVEN_AUTH_USR="$(cat /run/secrets/qits-client-id 2>/dev/null || true)"; \
    export QITS_MAVEN_AUTH_PSW="$(cat /run/secrets/qits-client-secret 2>/dev/null || true)"; \
    export QITS_MAVEN_REPOSITORY_URL="https://registry.qits.$QITS_DOMAIN/artifacts/maven/maven"; \
    d=src/main/webui/dist/qits-platform-spa-mirror/browser; \
    test -f "$d/index.html" || { \
      echo "No built client at $d." >&2; \
      echo "The build context must carry one: run 'npm ci && npm run build'" >&2; \
      echo "in src/main/webui first — this stage cannot, see the Quinoa flags above." >&2; \
      exit 1; }; \
    cp -a "$d" "$d.layer" && rm -rf "$d" && mv "$d.layer" "$d" && \
    ./mvnw -B -ntp -s .qits-maven-settings.xml \
      org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom \
      -DoutputFormat=json -DoutputName=sbom -DschemaVersion=1.6 \
      -Dcyclonedx.skipNotDeployed=false \
      package -Dnative -DskipTests \
      -Dqits.maven.repository.url="$QITS_MAVEN_REPOSITORY_URL" \
      -Dquarkus.native.native-image-xmx=4g \
      -Dquarkus.quinoa.package-manager-install=true \
      -Dquarkus.quinoa.package-manager-install.node-version=22.22.0 \
      -Dquarkus.quinoa.package-manager-command.install=--version \
      -Dquarkus.quinoa.package-manager-command.ci=--version \
      -Dquarkus.quinoa.package-manager-command.build=--version
# makeBom goes FIRST, BEFORE `package`, and the order is LOAD-BEARING (qits-441). Quinoa's node
# install (package-manager-install=true, inside quarkus:build) runs the embedded frontend-plugin
# `VertxFileDownloader`, whose first line is `System.setProperty("https.protocols", "TLSv1.2")` —
# JVM-wide, for the rest of this Maven process. Every HTTPS connection Maven opens after it offers
# TLS 1.2 only. The public edge (mirror.qits.wohlben.eu, which a remote runner's build resolves
# Central through) speaks TLS 1.3 only, so each of those handshakes dies at once with `Received
# fatal alert: protocol_version`, and makeBom's plugin dependencies — resolved lazily, when the
# goal runs — fail as five "Failed to read artifact descriptor" lines with no HTTP status. The
# in-network mirror is plain http:// and never notices, which is why only EDGE-plane runs failed.
# Ahead of `package`, makeBom resolves before Quinoa has touched the property; the SBOM is the
# same, since it reads the dependency graph and not the build's output.
#
# cyclonedx.skipNotDeployed=false is LOAD-BEARING, measured on qits-artifacts-service's first SBOM
# release (2026.902.45658): a module that skips maven-deploy — this one does, it ships as this image
# and not as a jar — is SKIPPED by the plugin by default, with a green build and one INFO line. The
# sbom stage's COPY then fails on a file nothing wrote. The property spelling matters too: the bare
# -DskipNotDeployed=false is silently ignored; only the cyclonedx.-prefixed one reaches the plugin.

# --- sbom -----------------------------------------------------------------------------------------
# This repository's CycloneDX document, exported so the release step can PUT it to the platform's
# SBOM store. One module, so it is at target/ and not service/target/, the same asymmetry the
# binary's COPY below carries. A stage rather than a layer of the runtime image, and BEFORE the
# runtime stage on purpose: the last stage is what a plain `docker build` builds, and that must stay
# the image. The release step reaches this one with `--target sbom --output type=local` using the
# SAME args and secrets as the image build, so every layer above is a cache hit and the export costs
# seconds. `makeBom` rides on the mvnw invocation above rather than a second one, for the same cache
# reason — one RUN, one layer, one truth.
FROM scratch AS sbom
COPY --from=build /src/target/sbom.json /sbom.json

# --- runtime --------------------------------------------------------------------------------------
# `mirror.dev.localhost:8080/redhat/…` is the same mirror, pointed at registry.access.redhat.com.
FROM mirror.dev.localhost:8080/redhat/ubi9/ubi-minimal:9.6
WORKDIR /work

# Nothing is installed here. This service fetches over HTTP, hashes bytes and writes rows; it shells
# out to nothing at all. A package added here is a package on a host serving anonymous reads.
#
# `quarkus.package.output-name` names the binary `qits-platform-mirror` (no -runner suffix), so the
# process is identifiable in `ps` / `docker top`. One module, so it is at target/ and not
# service/target/.
COPY --from=build --chown=1001:root /src/target/qits-platform-mirror /work/qits-platform-mirror
RUN chmod +x /work/qits-platform-mirror

# NO /data AND NO VOLUME. This image used to own a mount point for the blob files; blob bytes are
# PostgreSQL rows now, so the container writes nothing it needs to keep and there is nothing to
# mount. A deployment that still mounts the old volume is carrying dead bytes.
#
# Nothing is named here either: no QUARKUS_DATASOURCE_*. The database belongs to the deployment, and
# this image refuses to start until a deployment says where it is.

# One port for all of it — /artifacts/npm/**, /artifacts/maven/**, /v2/**, /mirror/api/**,
# /mirror/q/** and the SPA at /mirror/ are six route stacks in one process, not six listeners.
EXPOSE 8080
USER 1001

# Quarkus binds 127.0.0.1 outside dev mode, which inside a container means nothing can reach it —
# not the published host port, not a peer on qits-net.
ENTRYPOINT ["/work/qits-platform-mirror", "-Dquarkus.http.host=0.0.0.0"]
