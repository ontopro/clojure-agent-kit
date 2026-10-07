# THE FRESH MACHINE. The KIT's toolchain and nothing of the KIT: a Linux image a clone is
# made in, so `bb gates` and `bb health` are run on a machine that has only what the harness
# README's prerequisites name. Built and run by `bb docker-gates` and `bb docker-health` at
# the root of the clone; the GitHub workflow runs on the same image.
#
# EVERY VERSION IS A BUILD ARG, and the two tasks read each value from
# `harness/resources/known-good.edn` - the set a recorded health run actually ran together -
# so the container is the known-good set and not a second list someone types. The defaults
# below are that file's values on the day this was written; the tasks override them, so a
# recorded health run moves the container with it. Three things known-good does not carry
# and this file pins alone, said here: Temurin's build number behind the JDK version,
# geckodriver's version, and Firefox ESR, which Mozilla's repository serves as its current
# ESR and which nothing here pins.
#
# NEVER A MOUNT OF THE WORKING TREE: the tasks mount the repository read-only and `git clone`
# HEAD inside, so what is checked is what is committed, and nothing the container writes
# reaches the tree.

ARG JAVA_VERSION=21.0.12.1
ARG JAVA_BUILD=1
ARG JAVA_IMAGE_TAG=${JAVA_VERSION}_${JAVA_BUILD}-jdk-noble

# ---------------------------------------------------------------------------------------
# git at the known-good version, built from source: Ubuntu 24.04's package is older than
# the version the record names, and a floor the image falls under is a doctor note on every
# run. Built in its own stage so the toolchain image carries no compiler; without its Rust
# parts, optional until git 3.0, so no cargo.
FROM eclipse-temurin:${JAVA_IMAGE_TAG} AS git-build
ARG GIT_VERSION=2.56.0
RUN apt-get update && apt-get install -y --no-install-recommends \
      build-essential ca-certificates curl gettext libcurl4-openssl-dev libexpat1-dev \
      libssl-dev zlib1g-dev \
 && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL "https://mirrors.edge.kernel.org/pub/software/scm/git/git-${GIT_VERSION}.tar.xz" \
      | tar -xJ -C /tmp \
 && cd "/tmp/git-${GIT_VERSION}" \
 && make -j"$(nproc)" prefix=/opt/git NO_RUST=1 NO_TCLTK=1 NO_GETTEXT=1 NO_PERL=1 NO_PYTHON=1 install \
 && rm -rf "/tmp/git-${GIT_VERSION}"

# ---------------------------------------------------------------------------------------
# The toolchain image: Ubuntu 24.04 (noble) with Temurin 21, from Adoptium's own image.
FROM eclipse-temurin:${JAVA_IMAGE_TAG}

ARG BB_VERSION=1.13.225
ARG CLOJURE_VERSION=1.12.6.1673
ARG CLJ_KONDO_VERSION=2026.08.04
ARG CLJFMT_VERSION=0.16.6
ARG BBIN_VERSION=0.2.5
# clojure-mcp-light: the tag the doctor's install command names, and the commit known-good
# records for its tools; the build fails when the tag no longer resolves to that commit.
ARG MCP_LIGHT_TAG=v0.2.2
ARG MCP_LIGHT_SHA=d341c23
ARG GECKODRIVER_VERSION=0.37.1
ARG TARGETARCH

ENV DEBIAN_FRONTEND=noninteractive \
    PATH=/opt/git/bin:/root/.local/bin:/root/.babashka/bbin/bin:${PATH}

# What the tools and the health check lean on: a shell, curl and unzip for the downloads,
# rlwrap for the Clojure CLI's `clj`, the libraries git links, and Firefox ESR with its
# fonts from Mozilla's apt repository (ESR, the one the KIT's browser pack is tried on).
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates curl gnupg unzip xz-utils rlwrap \
      libcurl4 libexpat1 zlib1g \
 && install -d -m 0755 /etc/apt/keyrings \
 && curl -fsSL https://packages.mozilla.org/apt/repo-signing-key.gpg \
      -o /etc/apt/keyrings/packages.mozilla.org.asc \
 && echo "deb [signed-by=/etc/apt/keyrings/packages.mozilla.org.asc] https://packages.mozilla.org/apt mozilla main" \
      > /etc/apt/sources.list.d/mozilla.list \
 && printf 'Package: *\nPin: origin packages.mozilla.org\nPin-Priority: 1000\n' \
      > /etc/apt/preferences.d/mozilla \
 && apt-get update && apt-get install -y --no-install-recommends firefox-esr fonts-dejavu-core \
 && ln -s /usr/bin/firefox-esr /usr/local/bin/firefox \
 && rm -rf /var/lib/apt/lists/*

COPY --from=git-build /opt/git /opt/git

# The release binaries, each at its ARG's version, named by the architecture Docker builds
# for (arm64 on Apple silicon, amd64 on a GitHub runner): Babashka, clj-kondo, cljfmt,
# geckodriver, and the Clojure CLI's own Linux installer.
RUN set -eu; \
    case "${TARGETARCH}" in \
      arm64) BB_ARCH=aarch64; KONDO_ARCH=aarch64; CLJFMT_ARCH=aarch64; GECKO_ARCH=linux-aarch64 ;; \
      amd64) BB_ARCH=amd64;   KONDO_ARCH=static-amd64; CLJFMT_ARCH=amd64; GECKO_ARCH=linux64 ;; \
      *) echo "no release binaries for ${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    curl -fsSL "https://github.com/babashka/babashka/releases/download/v${BB_VERSION}/babashka-${BB_VERSION}-linux-${BB_ARCH}-static.tar.gz" \
      | tar -xz -C /usr/local/bin bb; \
    curl -fsSL -o /tmp/kondo.zip "https://github.com/clj-kondo/clj-kondo/releases/download/v${CLJ_KONDO_VERSION}/clj-kondo-${CLJ_KONDO_VERSION}-linux-${KONDO_ARCH}.zip" \
      && unzip -q /tmp/kondo.zip -d /usr/local/bin && rm /tmp/kondo.zip; \
    curl -fsSL "https://github.com/weavejester/cljfmt/releases/download/${CLJFMT_VERSION}/cljfmt-${CLJFMT_VERSION}-linux-${CLJFMT_ARCH}.tar.gz" \
      | tar -xz -C /usr/local/bin cljfmt; \
    curl -fsSL "https://github.com/mozilla/geckodriver/releases/download/v${GECKODRIVER_VERSION}/geckodriver-v${GECKODRIVER_VERSION}-${GECKO_ARCH}.tar.gz" \
      | tar -xz -C /usr/local/bin geckodriver; \
    curl -fsSL -o /tmp/clojure-install.sh "https://github.com/clojure/brew-install/releases/download/${CLOJURE_VERSION}/linux-install.sh" \
      && bash /tmp/clojure-install.sh && rm /tmp/clojure-install.sh

# bbin, then the three clojure-mcp-light tools by the doctor's own install command, held to
# the commit known-good records.
RUN set -eu; \
    install -d /root/.local/bin; \
    curl -fsSL -o /root/.local/bin/bbin "https://raw.githubusercontent.com/babashka/bbin/v${BBIN_VERSION}/bbin" \
      && chmod +x /root/.local/bin/bbin; \
    bbin install https://github.com/bhauman/clojure-mcp-light.git --tag "${MCP_LIGHT_TAG}" \
      --as clj-nrepl-eval --main-opts '["-m" "clojure-mcp-light.nrepl-eval"]'; \
    bbin install https://github.com/bhauman/clojure-mcp-light.git --tag "${MCP_LIGHT_TAG}" \
      --as clj-paren-repair --main-opts '["-m" "clojure-mcp-light.paren-repair"]'; \
    bbin install https://github.com/bhauman/clojure-mcp-light.git --tag "${MCP_LIGHT_TAG}"; \
    bbin ls | grep -E "^clj-nrepl-eval +${MCP_LIGHT_SHA}" >/dev/null \
      || { echo "clojure-mcp-light ${MCP_LIGHT_TAG} is not the recorded commit ${MCP_LIGHT_SHA}:" >&2; bbin ls >&2; exit 1; }

# The clone is made by root from a mount owned by another user; git refuses that by default.
RUN git config --global --add safe.directory '*' \
 && git config --global init.defaultBranch main \
 && git config --global user.email kit@container \
 && git config --global user.name "KIT container"

WORKDIR /work
CMD ["bash"]
