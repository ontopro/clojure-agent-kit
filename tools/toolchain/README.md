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
`{asset}` to fill, and per platform - `macos-aarch64`, `linux-aarch64`, `linux-amd64` - the
asset's spelling and its sha256. Any other platform is refused by name; an Intel Mac among them.

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
a test holds the matrix's `bb` and `java` at or above them. cljfmt is this pack's `bb.edn` dependency
(8.2a) and clojure-mcp-light will be (8.2d), held by Maven's and git's own integrity, not by the matrix:
bb fetches each into `~/.m2` at first use, which needs the network once.

> [!NOTE]
> The KIT's own gates and gate 0 run this pack's cljfmt. A project's gate 1 still runs the `cljfmt`
> binary - the application template's `bb fmt-check` calls it until the template's next release (8.4) -
> so the binary stays on the machine and in the toolchain image, at the version the pack pins.


## The tasks

| Task | What |
|---|---|
| `status [--edn]` | A row per tool this machine takes: its version, where the pack puts it, whether it is there. `--edn` prints `{:cache :platform :rows [{:tool :version :path :present? :running :fix}]}` - bb present when the bb running it is at or above the matrix's (`:running`, as the install skips it then), `:fix` the install command for a row not present, runnable as printed - the map `bb doctor` will read |
| `toolchain-install [--only <tool>,…] [--link <dir>] [--dry-run]` | Each tool this machine takes, into the cache: its archive fetched into `<cache>/downloads/` (or the copy there reused while its sha256 is the matrix's), the sha256 compared with the matrix's before anything is unpacked - a mismatch is deleted and refused by name, nothing installed - then unpacked into `<cache>/<tool>/<version>/`. The Clojure CLI is laid out as the official `posix-install.sh` does (its tarball's own `install.sh` is Homebrew's). A tool already in the cache is kept; bb is skipped when the bb running the install is at or above the matrix's; the JDK, 200 MB, only when named. `--only` names the tools (an `:image-only` one only so); `--link <dir>` links each one's executables into `<dir>`, replacing a file of the same name there - the KIT's toolchain image's, into its empty `/usr/local/bin`, and no root task offers it on a machine; `--dry-run` prints the plan. A failure is one line and exit 1 |
| `toolchain-env [<dir>]` | The shell lines for `eval "$(bb toolchain-env)"`, run in the shell that starts a session: `JAVA_HOME` and `PATH` for the JDK the project pins (`.mise.local.toml` over `.mise.toml`, the application's in a workspace; the download matrix's when none) - the first of that major in the cache, macOS's `java_home -F`, jenv's, mise's, Linux's `/usr/lib/jvm`, each checked by its own `release` file - and the line that has the shell load the `.env`: the workspace's in a workspace, the clone's outside one. **No value is ever printed.** Any name is taken; a `.env` a repository tracks or does not ignore is refused; a line the shell would run or expand, a carriage return or a name a program the KIT runs reads its hooks from is flagged by its number, never its text, and a flagged file is not loaded until `env-accept` accepts it as it is. A change to the file is added to the `.env` record (`.env-record.edn` beside it: names and sha256s, never a value). Exit 1, nothing on stdout, for a refused or flagged file; exit 1 with the keys line alone when no JDK is found. JDK-only until 8.3c: the cache's bb, clj-kondo and clojure are not on its PATH line. The front door offers it as `bb toolchain-env` |
| `fmt [--dir <d>] <paths…>` | cljfmt's `fix` over the paths, from `--dir` (default the folder it runs in): each file held to the first cljfmt config found from its own folder up, else the one from `--dir` up, else cljfmt's defaults - so gate 0 and gate 1 agree on a file in a folder with no config of its own. `Reformatted <path>` for each file it changed; exit 2 for a file it could not read. The harness's `bb fmt` and gate 0 (`bb repair`, the loop's write tool) call it |
| `fmt-check [--dir <d>] <paths…>` | cljfmt's `check`, each file's config as `fmt` finds it, in cljfmt's words: `<path> has incorrect formatting` and the diff, then the count. Exit 1 for a file formatted incorrectly, 2 for one it could not read. The KIT's fmt gate and the selfcheck project's call it |
| `env-accept [<dir>]` | The person's acceptance of the `.env` as it is now, flagged lines and all: the flags by number, then their name and the word `accept` typed at a terminal; the file's sha256 added to the `.env` record, and every load after says who accepted it and when. Refused with no terminal - a command an agent runs has none; not a lock, since a program can fake one - for a file a repository holds, and for a file that changed while the answers were given. The front door offers it as `bb env-accept` |
| `toolchain-sum <tool> [<version>]` | Each platform's archive of the tool fetched once into a temporary folder, its sha256 taken and set beside the publisher's checksum and the matrix's, the lines to paste printed. Exit 1 when one differs from its publisher's |

A version moves with its sha256s, by the person, as a pin move does: `toolchain-sum <tool>
<new-version>`, read, then pasted into the matrix with the version.

## The cache

`~/.kit/toolchain/`, or the folder `KIT_TOOLCHAIN_DIR` names: per tool, then per version -
`bb/1.13.229/bb`, `java/25.0.5+8/…`, `clojure/1.12.6.1673/bin/clojure`. Nothing of it is in the
repository.
