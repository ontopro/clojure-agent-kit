# tools/toolchain/ - the tools the KIT runs on, at exact versions and checksums

The KIT's own toolchain, held by the KIT: Babashka, the JDK, clj-kondo and the official Clojure
CLI, each at one exact version whose archive has one sha256, written once in
[`download-matrix.edn`](download-matrix.edn). Plan 7.0, item 8, builds it in steps: the matrix,
the tasks that read it, and the install are here; running cljfmt, clj-kondo and clojure-mcp-light
through the pack comes next.

```
bb --config <kit>/tools/toolchain/bb.edn <task> [args]
```

> [!IMPORTANT]
> The harness reaches this pack only as a subprocess, never by requiring its code: what it reads
> is the pack's output - `status --edn`'s map - and a test holds that format.

## The download matrix

A map keyed by tool. For each, the exact version, the archive's URL with `{version}` and
`{asset}` to fill, and per platform - `macos-aarch64`, `macos-amd64`, `linux-aarch64`,
`linux-amd64` - the asset's spelling and its sha256. A fifth platform is refused by name.

| Tool | Where from | The publisher's checksum |
|---|---|---|
| `bb` | Babashka's GitHub release | `.sha256` beside each archive |
| `java` | Temurin 25, Adoptium's GitHub release | `.sha256.txt` beside each archive |
| `clj-kondo` | clj-kondo's GitHub release | `.sha256` beside each archive |
| `clojure` | the official CLI's tools tarball, one for every platform | none of its own; the sha256 is the one the official `posix-install.sh` embeds |
| `git` | the source tarball, for the KIT's toolchain image only | `sha256sums.asc` at kernel.org |
| `geckodriver` | Mozilla's GitHub release, Linux, for the KIT's toolchain image only | none: computed by `toolchain-sum` |

`git` and `geckodriver` are `:image-only`: installed only where named, which the KIT's toolchain
image does - never on a machine, where git and a browser are the person's own. git's source
tarball is one archive for every platform, and its `:os :linux` keeps it to Linux's two.

The floors a machine must meet stay in `harness/resources/toolchain.edn`, which `bb doctor` reads;
a test holds the matrix's `bb` and `java` at or above them. cljfmt and clojure-mcp-light will be
this pack's `bb.edn` dependencies, held by Maven's and git's own integrity, not by the matrix.

## The tasks

| Task | What |
|---|---|
| `status [--edn]` | A row per tool this machine takes: its version, where the pack puts it, whether it is there. `--edn` prints `{:cache :platform :rows [{:tool :version :path :present? :fix}]}`, `:fix` the install command for a row not present - the map `bb doctor` will read |
| `toolchain-install [--only <tool>,…] [--link <dir>] [--dry-run]` | Each tool this machine takes, into the cache: its archive fetched into `<cache>/downloads/` (or the copy there reused while its sha256 is the matrix's), the sha256 compared with the matrix's before anything is unpacked - a mismatch is deleted and refused by name, nothing installed - then unpacked into `<cache>/<tool>/<version>/`. The Clojure CLI is laid out as the official `posix-install.sh` does (its tarball's own `install.sh` is Homebrew's). A tool already in the cache is kept; bb is skipped when the bb running the install is at or above the matrix's; the JDK, 200 MB, only when named. `--only` names the tools (an `:image-only` one only so); `--link <dir>` links each one's executables into `<dir>` (the KIT's toolchain image); `--dry-run` prints the plan. A failure is one line and exit 1 |
| `toolchain-sum <tool> [<version>]` | Each platform's archive of the tool fetched once into a temporary folder, its sha256 taken and set beside the publisher's checksum and the matrix's, the lines to paste printed. Exit 1 when one differs from its publisher's |

A version moves with its sha256s, by the person, as a pin move does: `toolchain-sum <tool>
<new-version>`, read, then pasted into the matrix with the version.

## The cache

`~/.kit/toolchain/`, or the folder `KIT_TOOLCHAIN_DIR` names: per tool, then per version -
`bb/1.13.229/bb`, `java/25.0.5+8/…`, `clojure/1.12.6.1673/bin/clojure`. Nothing of it is in the
repository.
