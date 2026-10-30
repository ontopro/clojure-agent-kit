# THE KIT'S TOOLCHAIN IMAGE - a fresh Linux machine in Docker with only the KIT's toolchain,
# and nothing of the KIT: a clone is made in it, so `bb gates` and `bb health` are run on a
# machine that has only what the harness README's prerequisites name. Built and run by
# `bb docker-gates` and `bb docker-health` at the root of the clone; tagged by known-good's date and
# a digest of this file and the toolchain pack (`security-review-sandbox/image-tag`), the name the
# security review's sandbox and the review REPL run.
#
# THE VERSIONS ARE THE DOWNLOAD MATRIX'S (`tools/toolchain/download-matrix.edn`), and the image
# installs its tools BY RUNNING THE TOOLCHAIN PACK (plan 7.0, item 8.1c, D-9): one implementation
# of every layout and sha256 check, and `toolchain-install` proved on Linux at every build. Only
# what the shell fetches before bb exists comes in as build arguments - bb's version, URL and
# sha256, which `docker-image!` reads from the matrix for the platform built (`bb --config
# tools/toolchain/bb.edn image-args <platform>`) - with no defaults here, since a default is a
# second owner of the version; and KIT_AS_OF, the date below. bbin and clojure-mcp-light are the
# matrix's too (plan 7.0, item 8.2d). git is Ubuntu's package: a prerequisite the KIT pins nowhere
# (8.2e), which the image had built from source to match a version no machine was held to.
# Firefox ESR is Mozilla's repository's current ESR, which nothing here pins.
#
# NEVER A MOUNT OF THE WORKING TREE: the tasks mount the repository read-only and `git clone`
# HEAD inside, so what is checked is what is committed, and nothing the container writes
# reaches the tree.

# ---------------------------------------------------------------------------------------
# The KIT's toolchain image: Ubuntu 24.04 (noble); the JDK is the matrix's Temurin, installed by
# the pack (Q1: no eclipse-temurin base, a second owner of the JDK's version).
FROM ubuntu:noble

ARG BB_VERSION
ARG BB_URL
ARG BB_SHA256
ARG TARGETARCH

ENV DEBIAN_FRONTEND=noninteractive \
    KIT_TOOLCHAIN_DIR=/opt/kit-toolchain

# THE SET'S DATE, known-good's `:as-of`, used before the first layer that installs packages: a
# new health record rebuilds the operating-system layer and every layer after it, where the
# cache had kept them as old as the image's first build (register row 131). The tasks build
# with `--pull` too, so the base is the newest published under its tag.
ARG KIT_AS_OF
RUN echo "${KIT_AS_OF}" > /etc/kit-toolchain-as-of

# What the tools and the health check lean on: a shell, git (Ubuntu's), curl, unzip and xz for the
# downloads, rlwrap for the Clojure CLI's `clj`, and Firefox ESR with its fonts from Mozilla's apt
# repository (ESR, the one the KIT's browser pack is tried on).
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates curl git gnupg unzip xz-utils rlwrap \
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

# bb, the one tool the pack cannot install, since the pack runs on it: the matrix's archive for
# this platform, its sha256 checked before it is opened.
RUN curl -fsSL -o /tmp/bb.tar.gz "${BB_URL}" \
 && echo "${BB_SHA256}  /tmp/bb.tar.gz" | sha256sum -c - \
 && tar -xz -C /usr/local/bin -f /tmp/bb.tar.gz bb \
 && rm /tmp/bb.tar.gz \
 && bb --version | grep -q "v${BB_VERSION}\$"

# The rest by the pack: the JDK, clj-kondo, the Clojure CLI, cljfmt, geckodriver and bbin into the
# toolchain folder, each archive checked against the matrix's sha256, linked into /usr/local/bin; then
# clojure-mcp-light's three commands, installed by that bbin into /usr/local/bin at the matrix's commit,
# each wrapper's header checked to name it (plan 7.0, item 8.2d). The
# fetched archives stay in a BuildKit cache across builds (`--keep-downloads`: a project's folder
# keeps none), so a change to the pack reruns the unpacking and the checks but not the JDK's 200 MB
# download.
COPY tools/toolchain /opt/kit/tools/toolchain
RUN --mount=type=cache,target=/opt/kit-toolchain/downloads \
    bb --config /opt/kit/tools/toolchain/bb.edn toolchain-install \
      --only java,clj-kondo,clojure,cljfmt,geckodriver,bbin,clojure-mcp-light --link /usr/local/bin --keep-downloads

# The clone is made by root from a mount owned by another user; git refuses that by default.
RUN git config --global --add safe.directory '*' \
 && git config --global init.defaultBranch main \
 && git config --global user.email kit@container \
 && git config --global user.name "KIT container"

WORKDIR /work
CMD ["bash"]
