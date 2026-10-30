# tools/toolchain/ - the tools the KIT runs on, at exact versions and checksums

The KIT's own toolchain, held by the KIT: Babashka, the JDK, clj-kondo, cljfmt and the official
Clojure CLI, each at one exact version whose archive has one sha256, written once in
[`download-matrix.edn`](download-matrix.edn). Plan 7.0, item 8: the matrix, the tasks that read
it, and the install are here. The pack installs the tools; it does not run them - the KIT calls
each by name, the project's toolchain folder first on the PATH (item 8, reshaped 2026-10-30).

```
bb --config <kit>/tools/toolchain/bb.edn <task> [args]
```

> [!IMPORTANT]
> The harness reaches this pack only as a subprocess, never by requiring its code: what it reads
> is the pack's output - `status --edn`'s map - and a test holds that format.

## The download matrix

A map keyed by tool. For each, the exact version, the archive's URL with `{version}` and
`{asset}` to fill, and per platform - `macos-aarch64`, `linux-aarch64`, `linux-amd64` - the
asset's spelling and its sha256 - the three the matrix publishes for, of which the health records
certify `macos-aarch64` and `linux-aarch64`. Any other platform is refused by name; an Intel Mac
among them.

| Tool | Where from | The publisher's checksum |
|---|---|---|
| `bb` | Babashka's GitHub release | `.sha256` beside each archive |
| `java` | Temurin 25, Adoptium's GitHub release | `.sha256.txt` beside each archive |
| `clj-kondo` | clj-kondo's GitHub release | `.sha256` beside each archive |
| `cljfmt` | cljfmt's GitHub release (darwin-aarch64, linux-aarch64, linux-amd64-static) | `.sha256` beside each archive |
| `clojure` | the official CLI's tools tarball, one for every platform | none of its own; the sha256 is the one the official `posix-install.sh` embeds |
| `geckodriver` | Mozilla's GitHub release, Linux, for the KIT's toolchain image only | none: computed by `toolchain-sum` |
| `bbin` | the one script at its release tag, `raw.githubusercontent.com` | none: computed by `toolchain-sum` |
| `clojure-mcp-light` | its repository at a commit, installed by the `bbin` above: `clj-nrepl-eval`, `clj-paren-repair`, `clj-paren-repair-claude-hook` | none needed: git's own integrity, the commit named in full |

`geckodriver` is `:image-only`: installed only where named, which the KIT's toolchain image does -
never on a machine, where a browser is the person's own. git is no row (plan 7.0, item 8.2e): a
prerequisite, as bb is, the person's own on a machine and Ubuntu's package in the image.

The floors a machine must meet stay in `harness/resources/toolchain.edn`, which `bb doctor` reads;
a test holds the matrix's `bb` and `java` at or above them.

clojure-mcp-light is a commit, not a release (plan 7.0, item 8.2d): `main` on 2026-06-19, the one
known-good records, nine commits after the `v0.2.2` tag. `toolchain-install` runs the folder's `bbin`
by its path, once per command, `bbin install <repository> --git/sha <commit>` with
`BABASHKA_BBIN_BIN_DIR` set to the folder's `bin/` on those calls alone, then reads each wrapper's
header (`; :bbin/start` .. `; :bbin/end`) and fails by name when it is absent or names another commit.

> [!WARNING]
> bbin 0.2.5 drops `--tag` without a word and refuses `--sha`: it adds `:latest-sha` unless its options
> carry `:git/tag` or `:git/sha`, and those two flags give `:tag` and `:sha`. `--tag v0.2.2` installed
> the default branch's tip. `--git/sha` is the spelling it honours. And an old bbin layout,
> `~/.babashka/bbin/bin`, wins over `BABASHKA_BBIN_BIN_DIR` - the header check says so, with `bbin
> migrate`.

> [!NOTE]
> cljfmt is the matrix's binary, not a library bb loads: the library cost 75 ms a call over the
> binary's 12-28, on every write gate 0 repairs (the person, 2026-10-30). One binary serves the
> KIT's fmt gate, gate 0 and a project's gate 1, at one version, called directly - no task of this
> pack's in between.


## The tasks

| Task | What |
|---|---|
| `status [--edn]` | A row per tool this machine takes: its version, where the pack puts it, whether it is there. the first line names the project folder, the toolchain folder and the `.env`. `--edn` prints `{:folder :env :cache :platform :rows [{:tool :version :path :present? :running :fix}]}`, with `:note` or `:refusal` (below) - bb present when the bb running it is at or above the matrix's (`:running`, as the install skips it then), `:fix` the install command for a row not present, runnable as printed - the map `bb doctor` will read |
| `toolchain-install [--only <tool>,…] [--link <dir>] [--keep-downloads] [--dry-run]` | Each tool this machine takes, into the toolchain folder: its archive fetched into `<folder>/downloads/`, the sha256 compared with the matrix's before anything is unpacked - a mismatch is deleted and refused by name, nothing installed - then unpacked into `<folder>/<tool>/<version>/`, and the archive deleted: downloads are not kept (`--keep-downloads` keeps them - the KIT's toolchain image's build caches them - and a copy there is reused while its sha256 is the matrix's). Each tool's executables are linked into `<folder>/bin/`, the one folder a PATH names. The Clojure CLI is laid out as the official `posix-install.sh` does (its tarball's own `install.sh` is Homebrew's). A tool already in the folder is kept; bb is skipped when the bb running the install is at or above the matrix's; the JDK, 200 MB, only when named. `--only` names the tools (an `:image-only` one only so); `--link <dir>` links them into `<dir>` instead, replacing a file of the same name there - the KIT's toolchain image's, into its empty `/usr/local/bin`; `--dry-run` prints the plan. A failure is one line and exit 1 |
| `toolchain-env` | The shell lines for `eval "$(bb toolchain-env)"`, run in the shell that starts a session: the toolchain folder's `bin` first on the `PATH` (every tool the true-up installs, the JDK's executables among them) and its JDK as `JAVA_HOME` - no JDK looked for on the machine and no pin read (plan 7.0, item 8.3c); what the folder lacks is said with the true-up, and the shell's own left as it is - and the line that has the shell load the project's `.env`, in the folder above the clone. **No value is ever printed.** Any name is taken; a `.env` a repository tracks or does not ignore is refused; a line the shell would run or expand, a carriage return or a name a program the KIT runs reads its hooks from is flagged by its number, never its text, and a flagged file is not loaded until `env-accept` accepts it as it is. A change to the file is added to the `.env` record (`.env-record.edn` beside it: names and sha256s, never a value). Exit 1, nothing on stdout, for a refused or flagged file. The front door offers it as `bb toolchain-env` |
| `env-accept` | The person's acceptance of the `.env` as it is now, flagged lines and all: the flags by number, then their name and the word `accept` typed at a terminal; the file's sha256 added to the `.env` record, and every load after says who accepted it and when. Refused with no terminal - a command an agent runs has none; not a lock, since a program can fake one - for a file a repository holds, and for a file that changed while the answers were given. The front door offers it as `bb env-accept` |
| `toolchain-sum <tool> [<version>]` | Each platform's archive of the tool fetched once into a temporary folder, its sha256 taken and set beside the publisher's checksum and the matrix's, the lines to paste printed. Exit 1 when one differs from its publisher's |

A version moves with its sha256s, by the person, as a pin move does: `toolchain-sum <tool>
<new-version>`, read, then pasted into the matrix with the version.

## The toolchain folder

The project's, in the folder above the KIT's clone - `<project>/.kit/toolchain/`, beside the project's
`.env` (plan 7.0, item 8.2b): `KIT/.kit/toolchain/` for the KIT's own development, the workspace's for a
project, whose README clones the KIT into it. `KIT_TOOLCHAIN_DIR` names another. Inside, per tool, then
per version - `bb/1.13.229/bb`, `java/25.0.5+8/…`, `clojure/1.12.6.1673/bin/clojure` - and `bin/`, a link
to each tool's executables at the matrix's version. About 500 MB, 330 of it the JDK; deleting the
project deletes it. Nothing of it is in the repository.

> [!WARNING]
> A clone straight in your home folder, or at `/`, is refused by every task: the `.env` there would be
> `~/.env`, a file many people already have. Clone the KIT into a project folder. A project folder
> inside a git repository is said - its `.kit/` and `.env` are untracked files there.
