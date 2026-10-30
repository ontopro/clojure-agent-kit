# Devlog

What changed, when, and why — newest first. A running record, appended to as
work happens.

The documents around it each keep one job, and this one does not repeat
them: [`NOTES.md`](NOTES.md) is what is still open *now*, and
[`portability.md`](portability.md) is the standing design analysis. What lives
only here is the **sequence** — the order things were tried in, and every place
a later change corrected an earlier one. That is the part no other document
keeps.

**This log starts on 2026-09-16, when the loop was rebuilt.** The entries before
that date - the extraction, the first runs by hand, the first bake-offs - are in
this file's history on `main`, and nothing here cites a run id or a register row
from them: a reference a reader cannot resolve is worse than none.

**How it names things.** The KIT was tried by building projects with it. They are
named here by their order - the first project built with the KIT, the second, and
so on - and their records are theirs, not this repository's; so are the records
of the KIT's own early runs (`a1`-`a4`). Figures measured on them are not quoted,
because nothing here can re-derive them: what was learned is said in words. The
development branch reached `main` as one commit, so its commits are not cited
either; an entry's date is its reference.

**How it is versioned.** The KIT carries a tag at each plan's boundary, and a heading for the
tag stands above the day's entries here, with one line: what the plan added, and anything a
workspace made before it needs - what `bb doctor` run there will list - in a sentence. The numbers mean this and not what a
library's would: the minor is a plan family - `0.6.0` closed plan v6, the guided lean workflow -
and the patch is a follow-on plan that completes the family without changing the workflow's
shape, so `0.6.1` closes plan 6.1, `0.6.2` the plan after it, and a patch here is not a fix.
What `1.0` will mean is not decided here. `bb init` records the version beside the commit in `workspace.edn`,
the doctor prints both, and the root README names the current tag. Not `kit-vN`: those are
the template fork's tags and version the template, not the KIT.

**Before a tag**, in this order, because the containers clone HEAD and see only what is committed:

1. The plan's work committed and the tree clean.
2. The health records on that commit: `bb health --record` in `harness/`, then `bb docker-health`
   at the root, then `bb health-sync`; the records and the README block committed. A record
   names the commit it ran on, and a tag on code its records never ran is a tag on an untried
   claim. They make known-good the pins: `bb tag-check`'s `versions` line holds the download
   matrix, the pack's pins and the Dockerfile's to what the runs recorded, the JDK apart - the KIT
   an adopter gets has its pins equal to known-good.
3. The tag's documents: its heading here, with its line - what the plan added, and anything a
   workspace made before it needs - and the root README's sentence naming the current tag.
4. On the commit the tag will name: `bb repair && bb gates` green, then `bb docker-gates` run by
   hand and green - the gates on a clone of that commit in the KIT's toolchain image, a fresh
   Linux machine in Docker with only the KIT's toolchain: the check on a fresh machine until a
   workflow runs it on push.
5. The merge, the tag and the push, by the person.

`bb tag-check <tag>` at the root is this list's mechanism: a line per check and no tag unless
every one holds. `bb tag-check <tag> --run` does steps 2 and 4 where they are stale, stopping for
the commit between them; `--landed` checks origin after step 5. bear-claw's secrets scan stays a
step by hand, named there, and so does Docker Scout's scan of the KIT's toolchain image (a Docker
login): `docker scout cves local://clojure-agent-kit-toolchain:<as-of>-<digest> --only-severity critical,high`, as
`bb tag-check` prints it.

**The toolchain's pins move at a plan's start, not before its tag.** `bb toolchain-newer` lists
each pin beside its newest release, and `bb kit-status` and `bb tag-check` print its newer lines.
When a plan's branch is cut the person reads it and decides what moves; a move is the plan's first
item, with its health runs, so the plan's whole length runs on it. Before a tag only an advisory
moves a pin: a HIGH or CRITICAL that Scout finds against a pinned tool. The doctor's "newer than
tested" stays information for an adopter, never a block.

---

## 2026-10-29 (plan 7.0)

### The workspace `.env` in the documents: setup's last two steps, the orientation, the profile's rule, and its limit in the register (plan 7.0, item 8.6c)

Written once, with 8.6d's final shape (the person: "go, 8.6d before 8.6c"). `workflow.md`: SETUP's
boxes end with the keys into `.env`, `eval "$(bb toolchain-env)"`, `bb profile` and `claude` from that
shell, and step 0's row says so and lists the `.env` among what setup leaves. The root README's *How to
use KIT* gains steps 5 and 6 - fill the `.env` (one key per project, a limit at the provider; mode 600,
no repository, the session denied it; a `DATABASE_URL` or a `PORT` beside the keys), load it and check
with `bb profile`, what a flag means and `bb env-accept`, the `.env` record - and an IMPORTANT callout:
the keys reach a session only through the shell that starts it, so the `eval` is run in that terminal
and again, with `claude` restarted, after a change. `kit-start`'s part 1 names the file and tells the
session never to read it. `portability.md`: where a `:key-env`'s value lives, and that the harness never
reads the file. `harness/AGENTS.md`: the init row names the `.env` and `settings.json`; rows for `bb
toolchain-env` and `bb env-accept`. And Fable 5.1's wording nit from the read of `cef1494`: `/path` in a
deny rule is relative to the session's primary working directory, the folder a project settings file
is read from - `init/env-deny-rules`' docstring and step 3's entry say so. `NOTES.md` row 236, on watch
by design: the keys reach the session's Coder (its nREPL runs on the host as the same user), and every
guard on the file is said with what it does not cover - a rail, not a lock; containment stays the
sandbox's. `harness/README.md`'s Prerequisites wait for 8.3's rewrite, as the design note placed them.

### `bb init` writes the `.env` at 600 and the session's deny rules; `bb upgrade` adds them; a readable `.env` is said (plan 7.0, item 8.6d, step 3)

The design note's layer 2, as Fable 5.1's read corrected it. `bb init` writes the `.env` template with
mode `600` (owner read and write) and the workspace's `.claude/settings.json` with three deny rules,
`init/env-deny-rules`: `Read(/.env)`, `Edit(/.env)`, `Edit(/.env-record.edn)`. No `Write` rule - Claude
Code matches path rules for `Read` and `Edit` only, accepts a `Write` rule and never matches it, and a
`Read` deny blocks Edit and Write too (its permissions page). `/path` is relative to the session's
primary working directory, which for a project settings file is the folder it was read from - the
workspace, for a session opened there, the one kit-start opens (worded so at Fable 5.1's read of `cef1494`). A
guard rail, not a lock: Claude's file tools, the shell commands Claude Code recognizes and redirections,
never a program that opens the file itself - the docstring says so. The keys still reach the build: the
person's shell sources the file before `claude` starts, and the harness reads keys by `System/getenv`
(the person's question, 2026-10-30). `bb upgrade`'s orientation step writes the settings file where
there is none; one that exists is the project's, never merged into - the rules it lacks are said
(`settings-missing`, which takes the project's own rules beside them and a file that is not JSON).
`toolchain-env` says when the group or others can read the `.env`, with `chmod 600` - a leak to other
users, not the agent's write, so a line, not a flag. Tests: init's file list, the `.env`'s mode and the
settings' three rules in `init_test`; the upgrade's file holding them and `settings-missing`'s cases in
`upgrade_run_test`; `others-read?` in `toolchain_env_test`.

### The program hooks gain `KIT_` and `DOCKER_`; an acceptance names the file's names; the first sight says so (From Fable 5.1's reads of `bd26fdb` and `956ea06`)

The person: "go, take them as one commit". From the read of `bd26fdb`, two lows: the hooks list lacked
the KIT's own - a grep of `System/getenv` gives `KIT_TEMPLATE_LOCAL` (the template `bb init` runs),
`KIT_TOOLCHAIN_DIR` (the cache 8.3c puts on the PATH) and `KIT_WORKSPACE` - and docker's, run in four
places (`DOCKER_HOST` sends every build and container elsewhere, `DOCKER_CONFIG` names the plugins it
runs); both prefixes added. And a nit: a comment says why a flag carries the name `select-keys` strips.
From the read of `956ea06`, a low-medium: an acceptance given before any load saw that state left its
names unrecorded, and the next change was measured from an older entry, so names that arrived two
states ago read as new. The acceptance entry now carries `:names`, and every entry says what the file
had, so the next change is measured from the last entry, whatever its kind. A low: the first sight was
silent, so a deleted record hid a change; it now says "first sight of <file>: N names, recorded in
<record>", and a "first sight" months in shows the record went. A doc nit: `:loaded?` is whether the
lines were handed to the shell - seen, not sourced. `toolchain_env_test` holds each: the two new
prefixes, the first-sight line, an acceptance before any load then `X=1` added giving `:added ["X"]`.

### A flagged `.env` loads once the person accepts it; every change seen is added to the `.env` record (plan 7.0, item 8.6d, step 2)

The person (2026-10-30): a flag is "kicked out for human review and documented - a simple statement that
.env was reviewed and accepted by <name of human> at <timestamp>"; and every change logged, not only a
flagged one ("surely the file's hash would have changed"). `.env-record.edn` beside the `.env` - a
record in the KIT's sense, added to and never rewritten, the name Fable 5.1's (its read of the design
note) - one EDN map a line. Each load whose sha256 is not the last entry's adds `{:at :sha256 :names
:added :removed :flags :loaded?}` and says on stderr what moved (`+DATABASE_URL, -OLD_KEY`, or "the same
names, values or other lines changed", and how many lines are flagged). `bb env-accept [<dir>]` (pack,
harness, front door, root) shows the flags by number, takes the person's name and the word `accept` at a
terminal (`System/console`, so a command an agent runs is refused), reads the file again and refuses if
it changed meanwhile, and adds `{:at :sha256 :accepted-by :flags}`. A flagged file loads only when an
acceptance names its sha256, and every load then says who accepted it and when - the answer to a faked
terminal, which is not prevented: the documents say it is a rail, not a lock. Never a value in the
record or on stderr, a flagged line's text included; a record line that is not an EDN map is skipped and
counted. The KIT ignores `.env-record.edn` as it does `.env`. The pack's README rows for both commands,
the `toolchain-env` row brought up to `3ec4c9e` and step 1. Tried here: the first load adds every name;
an unchanged load adds nothing; a bare word added is flagged, not loaded, the change said; `env-accept`
with no terminal refused, nothing written; no secret and no flagged text in the record.
`toolchain_env_test` holds each, `env-accept` with no terminal run as a process.

### The `.env` flagged, not fenced: any name taken, no repository holds it (plan 7.0, item 8.6d, step 1)

Fable 5.1's read of `3ec4c9e` found the deny list still short - `BABASHKA_PRELOADS` runs its code in
every bb command, `GIT_CONFIG_*` sets `core.fsmonitor`, the `clojure` launcher reads `JAVA_CMD` and
`CLJ_CONFIG` - and said a deny list is never finished. A key-shaped allow list was set aside by the
person: a `.env` holds database URLs, ports and flags as well as keys (the application template reads
`PORT` and `DATABASE_URL`). The person's shape instead (2026-10-30: "start with the KISS principle and
go with only those that are needed. The only absolute check may need to be that it is not checked into
GIT. Warnings / flags for things that look like executable code"), designed in
`.local/plans/v7/kit-plan-v7.0-item8.6-env-review-design.md` and read by Fable 5.1 (go). This step:
`check-env` takes any name and `export NAME=value`, and FLAGS by line number, never the line's text -
a bare word (`rm -rf ~`) IS its text - a line that is not `NAME=value`, a value the shell would expand
or run, a carriage return, and a name in `program-hooks`, now with the reader's `BABASHKA_`, `GIT_`,
`CLJ_` prefixes and `JAVA_CMD`, `JAVA_OPTS`: a hint, never finished, said so. A flagged file is not
loaded, nothing on stdout. The absolute check, `repo-check`: refused when the repository the `.env`'s
folder is in tracks it (`git ls-files --error-unmatch`, asked first) or does not ignore it (`git
check-ignore -q` - one `git add -A` from committed); no repository, or no git, passes - a workspace's
root is none. Next in 8.6d: the person's acceptance of a flagged file and the `.env` record (step 2),
then init's mode 600 and the session's deny rules (step 3). Tried here: a `.env` with
`DATABASE_URL`, `PORT` and a key loads; `rm -rf ~` and `BABASHKA_PRELOADS` flagged by number, text not
shown, exit 1; the same workspace made a repository without ignoring it refused. `toolchain_env_test`
holds each, `repo-check` against git in a scratch repository.

### `toolchain-env`'s `.env` refuses the names the shell and the JVM read; the workspace's `.env` alone; what holds is printed (From Fable 5.1's read of `82b99ed`)

The medium: the keys line is sourced after the JDK's lines, and a Coder can write the workspace's
`.env`, so a `PATH=` or `BASH_ENV=` line in it was code run at the next command. `check-env` now refuses,
in any case (zsh ties `path` to `PATH`), the names the shell finds and runs commands by, reads at start or
splits words by, and the ones the JVM loads from - `PATH`, `JAVA_HOME`, `HOME`, `IFS`, `ENV`, `BASH_ENV`,
`ZDOTDIR`, `SHELL`, `CDPATH`, `FPATH`, `PROMPT_COMMAND`, `PS4`, `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`,
`_JAVA_OPTIONS`, `CLASSPATH`, every `LD_` and `DYLD_` - and any line with a carriage return (the shell
keeps it in the value), each by its line number, no value echoed. A list of names refused, not of names
taken: a key file's names are the profile's, but the profile is not read here. The lows: in a workspace
the workspace's `.env` alone, never the clone's (the design's rule; the clone's only outside one); the
preferred JDK is the download matrix's `java` major, not a constant `25`; and the command says it is
JDK-only until 8.3c - the cache's bb, clj-kondo and clojure join the PATH line with 8.3c's cache lines
(the person: "JDK-only until 8.3c"). And: `java_home -v N -F`, so an absent major fails rather than
printing the default JDK with exit 0 (the reader's probe), the `release` check kept; with no JDK of the
pin, the keys line is still printed and the exit is 1 - a refused `.env` still prints nothing. Tried
here: this clone gives Temurin 25 by `java_home`; a scratch workspace pinned to 11 gives the keys line
alone, exit 1; a `path=/x` line refused by its number, nothing on stdout. `toolchain_env_test` holds each.

### `bb toolchain-env`: the JDK the project pins and the workspace's keys, loaded by the shell, no value printed (plan 7.0, items 8.3c pulled forward and 8.6a)

D-6 (the person, 2026-10-28: "setting the Java version to use seems like a tooling concern or belongs in a
bb script - not in the harness") and 8.6a ("this is environment setup. so it does not belong in the
harness code"; "1. Yes 2. Yes" - no value printed). The toolchain pack's `toolchain-env [<dir>]` prints the
lines for `eval "$(bb toolchain-env)"`: `JAVA_HOME` and `PATH` for the JDK the project pins - the `java`
line of `.mise.local.toml` over `.mise.toml`, the application's in a workspace, read by `java-pin` as the
doctor's `mise-pins` reads it (a test holds them to one answer on three shapes), 25 when none - the first
JDK of that major in the toolchain cache, macOS's `java_home`, jenv's, mise's or Linux's `/usr/lib/jvm`,
each a home whose own `release` says that major; then `set -a; . '<.env>'; set +a`, the workspace's
`.env` (else the clone's own), after `check-env` - a sourced file runs `$(...)`, so a line the shell would
expand or run, an `export`, a bare word is refused by its number, no value echoed. Stdout carries only
the lines, and nothing when a step refuses. The harness task (offered by the front door, for
`KIT_WORKSPACE`) and the root task run the pack as a subprocess. Tried in a scratch workspace: a local
pin of 21 over the project's 25 gave Homebrew's 21 by `java_home`, `java -version` 21 after the eval, two
dummy keys set and none printed; a `$(touch …)` line and an `export` refused, exit 1, nothing on stdout,
nothing touched. With it, Fable 5.1's read of `71ccf65`: the `.env` header says a limit is set at the
provider's console and that `bb balance` shows it for an OpenRouter key alone; the header is one
`str/join` of lines; `.gitignore`'s comment says "anywhere in the clone". The person: "go".

### `bb init` writes the workspace `.env` template, and the KIT ignores `.env`, `.env.local`, `.mise.local.toml` (plan 7.0, items 8.6b and 8.6c's ignore lines)

The person, 2026-10-28: "one of the steps needs to be to initialize a '.env' file with just the KEYS so
that the Adopter can put in the relevant keys - basically a '.env' template"; and 2026-10-29, "go on 8.6c
and 8.6b first". `init/env-template` (pure): every distinct `:key-env` the seat's profile names, its roles
and its security panel, one `NAME=` line each, EMPTY, sorted by name, under a comment naming what uses it
and the host it is sent to; a role with no `:key-env` (a local model) adds no line, a profile with none
writes no `.env`. The header: this project's keys, one key per project with a limit at the provider
(`bb balance` shows it), never committed, loaded before the session starts. `layout` writes it at the
workspace root beside `workspace.edn` - never again: `bb upgrade` does not render the layout - and lists
it in `--dry-run`; the workspace README names it; `init`'s closing lines say fill it first. `bb
profile`'s unset line names where the key goes and the command that loads it, reading nothing. The
claude example gives `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, `OPENROUTER_API_KEY`. The KIT's own
`.gitignore` gains `.env`, `.env.local` and `.mise.local.toml` before any is placed in a clone (the gates
check the whole tree). The loader the header and the lines name, `eval "$(bb toolchain-env)"`, is 8.6a,
the next commit in item 8's build order.

### `tag-check`'s `versions` line reads the one list of tools a record must run at the pins (From Fable 5.1's read of `f9b5af8`)

`versions-check` named bb, clj-kondo, the Clojure CLI and cljfmt by hand, and `kit-pins/recorded-tools`
- which `bb health --record`'s guard and `set-stales?` read - named them again: one decision, "matrix =
known-good", written twice and agreeing by hand. The four pairs are made from `recorded-tools` now;
clojure-mcp-light's commit and the image's git stay as the two pairs of their own. The person: "yes,
take 1 to 3 now".

### A health run that moves the pins is not stale against the commit that holds it (From Fable 5.1's read of `4eab9df`, register row 127)

The reader ran `tag-check`'s `read-records` on `4eab9df`: both records stale, "known-good.edn changed
since". Row 127 had kept the set's date out of the comparison; this was its other half, never seen
before because the set had not moved since - a run that MOVES the set compared the set at the record's
commit (the old pins) with HEAD's (the ones it wrote), so every pin move cost a second pair of health
runs before a tag. `set-stales?` (pure): the set's move stales a record only when the record did not
run what HEAD's set says, over `kit-pins/recorded-tools` - the JDK, git (a Mac's is brew's, the
image's the matrix's) and the rest apart. On HEAD now: both records fresh, "both platforms recorded
on code HEAD still has", with no rerun. The person: "yes, take 1 to 3 now".

### The health records on the moved pins: known-good is the matrix's, both platforms 12 of 12 (plan 7.0, item 8.1c)

`bb health --record` on this Mac (macOS 27.0.1 arm64, at `f9b5af8`): 12 of 12 checks in 186 s; `bb
docker-health` in the KIT's toolchain image, rebuilt under its new name since known-good's date moved
(Linux Ubuntu 24.04.5 arm64): 101 s, 12 of 12 as `bb health` counts them - two of them skipped, the app
image and XTDB over pgwire, for no Docker inside the container, so 10 run, as the README block says
(From Fable 5.1's read of `4eab9df`); `bb health-sync` rendered the README block. Both passed
`--record`'s new guard - no drift. `known-good.edn` now says bb 1.13.229, clj-kondo 2026.10.26 and Java
25.0.5 (it said 1.13.226, 2026.08.04 and 21.0.12.1), with XTDB 2.2.0 and the project set beside them;
each run kept under its own time-named file (`health/records/<platform>/2026-10-29-f9b5af8.edn`, item
11). `bb tag-check`'s `versions` line, red since `cea3a41`: "the pins are what the health runs
recorded, the JDK apart (bb 1.13.229, clj-kondo 2026.10.26, clojure 1.12.6.1673, cljfmt 0.16.6,
clojure-mcp-light d341c23, git (the image's) 2.56.0)".

### `bb health --record` refuses before the run when this machine has drifted from the KIT's pins (plan 7.0, item 8.1d)

The person, 2026-10-29: "As part of the 'bb health --record' run, it runs the report and if there are
differences, it stops and asks what to do." It stops - one refusal, exit 1, before any minute is spent -
and the person is asked by whoever runs it: `bb health --record` is run by an agent or by `bb tag-check
--run`, where a prompt would hang (the writer's reading, "agree"). The doctor's probe the run already
takes is read through `kit-pins` (`read-pins`, `installed`, `rows` with no releases, offline), and
`record-drift` keeps the rows a record must not certify: the tools `tag-check`'s `versions` line holds -
bb, clj-kondo, the Clojure CLI, cljfmt - and git only where the record is the image's (Linux); the JDK
apart, any 21 and newer. `drift-refusal` names each row, installed against pinned and where, and the two
ways out: move the pin (`bb kit-pins`, the person's go, `toolchain-sum`) or put the machine back. A
newer release never stops a record. No flag records anyway: `tag-check` would refuse what it recorded.
On this machine today: no drift. The kit-session skill's step 5 says it.

### `bb toolchain-newer` is `bb kit-pins`, and the kit-session skill says when to run it on demand (plan 7.0, item 8.1d)

The person, 2026-10-29: "I will be running brew updates regularly, so this is going to come up. The
best thing to do is to reduce friction ... making it easy to run the version report easily on demand.
Maybe both directly and via a KIT skill also?" - and on its name: "It is a version report of what? for
what purpose? needs a better name." It is the KIT's pins, each beside what this machine runs and its
newest release, for deciding which pins move: `bb kit-pins` ("kit-" as `bb kit-status`, a command of
the KIT's own development; "pins" the KIT's word for these versions, qualified because the doctor's
"pin" is a project's `.mise.toml` line). The person: "yes, bb kit-pins". The namespace is
`harness.gates.kit-pins`; `kit-status`, `tag-check`, the root `bb.edn`, the harness README and the
matrix's header follow. The kit-session skill gains step 5: after a `brew upgrade`, or on the
person's word, `bb kit-pins` - a row "installed differs" is a go or no-go now, a row "newer released"
waits for a plan's start or a tag unless the person moves it.

### The toolchain image's name is made in one namespace, `tag-check`'s Docker Scout command uses it, and the digest covers hidden files (From Fable 5.1's read of `1de97ad`)

The low: `bb tag-check` printed the Docker Scout command a person runs before a tag with
`clojure-agent-kit-toolchain:<known-good's date>`, the tag's old shape, which Docker no longer has
once the image is named for what it holds. `tag-check` (a gate) cannot require the security review's
sandbox (a model), so `image-context`, `context-digest` and `image-tag` move to a leaf of their own,
`harness.gates.toolchain-image`, which the sandbox, the review REPL, `tag-check` and the root
`bb.edn`'s `docker-image!` all read - one maker; `tag-check`'s `known-good-as-of` goes. The DEVLOG's
checklist line says `<as-of>-<digest>`, as `bb tag-check` prints it. The nit: the digest listed the
pack with `fs/glob "**"`, which skips hidden files, while `.dockerignore`'s `!tools/toolchain` sends
them - so a `.DS_Store` would have changed the image and not its name; it globs hidden files now, and
a test puts one in a pack and sees the digest move. The person: "yes, bb kit-pins - go in that order".

### `bb tag-check` holds the pins to what the health runs recorded, the JDK apart (plan 7.0, item 8.1c)

The person, 2026-10-29: "The KIT that an Adopter gets should have matrix = known-good versions (with
the Java exception noted)." `tag-check` gains a `versions` line (`versions-check`, pure): bb,
clj-kondo, the Clojure CLI and cljfmt as pinned - the matrix, the pack's deps or the Dockerfile's
until the fold - against known-good, the Mac host's recorded run; git against the Linux record's,
the image's git being the matrix's and the Mac's brew's (Fable 5.1's read of the versions
decision); clojure-mcp-light's commit, the Dockerfile's `MCP_LIGHT_SHA` until 8.2d, against
known-good's `clj-nrepl-eval`. The JDK is not compared: known-good records the recording machine's,
and any 21 or newer is allowed. Red today, "bb pinned 1.13.229, recorded 1.13.226; clj-kondo pinned
2026.10.26, recorded 2026.08.04", until the health runs after `1bfe459` - and `--run` reruns them, since
`tools/` is a health path, so the line's advice is the command that closes it. The checklist above
says it.

### The KIT's toolchain image is named for what it holds; `image-args` refuses a platform it has no image for (From Fable 5.1's read of `720f2af`)

The low: the security review's sandbox and the review REPL run the image by its tag without building
it, and the tag was known-good's date alone - right while the image's versions came from the same
file, wrong once they are the matrix's: after a matrix move, until a health record moves the date,
both would have run whatever Docker last built under that date, the old toolchain reviewing code.
The tag is now the date and the first 7 hex of a sha256 over what the image is built from - the
Dockerfile and every file of the toolchain pack, the context `.dockerignore` lets in, so the matrix and
the pack's code together (`clojure-agent-kit-toolchain:2026-10-22-a797b79` today). One place makes it,
`security-review-sandbox/image-tag`; the root `bb.edn`'s `docker-image!` names its build by it, so a
change not yet built is "Unable to find image", a named refusal. The nits: the Dockerfile's header
said a GitHub workflow runs on the image - this repository has none; and `image-args` with no or a
Mac platform printed a map of nils - it refuses by name now, one line and exit 1. The person: "yes, go
on both follow-ups".

### `status` and the install say one thing of bb; the fix is runnable; a tool in place is moved aside, not deleted first (From Fable 5.1's read of `cf554a7`)

The person: "yes, go on both follow-ups". The lows: after an install on a machine whose bb is at or
above the matrix's - which the plan skips, the person's rule - `status` said bb "not installed" and
offered a fix that skipped it again, the loop the doctor would read at 8.2e; bb is present now when
the bb running `status` is at or above the matrix's, its line "the bb running this, 1.13.229" and its
row `:running`. And `:fix` carried a literal `<kit>`: it names the pack's own `bb.edn` now, runnable as
printed, and the lines a person reads show it under a row not present. On the reader's answers: a
tool in place is moved aside to `<dir>.old` and put back if the move fails (`swap-in!`), since
`--link`'s links and `toolchain-env`'s PATH point at it; the Clojure CLI is laid out beside its place
with its place's paths, then swapped in; tar's own error is in the one line ("tar could not unpack
bad.tar.gz: tar: Error opening archive: …"); the pack's README says `--link` replaces names and is the
image's. The nits: the status lines print before the links; the plan test says why its keys keep their
order. Tried into the scratch cache: both tools reinstalled and linked, a tool whose executable was
gone reinstalled over its folder with no `.old` left.

### The KIT's toolchain image is built on `ubuntu:noble` and installs its tools by running the pack (plan 7.0, item 8.1c)

D-9 (the person, 2026-10-28: "yes to both"), Q1 ("go with option (a)"). The image was `eclipse-temurin`'s
Temurin 21 with every version a build argument from `known-good.edn`, each tool fetched by its own
`curl` line and none checked. It is now `ubuntu:noble`:
- git built from source in its own stage and bb fetched, each by shell before bb exists, from
  `GIT_URL`/`GIT_SHA256` and `BB_URL`/`BB_SHA256` - `sha256sum -c` before either is opened;
- then the pack, `COPY tools/toolchain` (the build context is the pack alone, `.dockerignore`), and
  `toolchain-install --only java,clj-kondo,clojure,geckodriver --link /usr/local/bin` under a
  BuildKit cache mount on `<cache>/downloads/` - the JDK is the matrix's Temurin `25.0.5+8`, no second
  owner of its version; the same code, layouts and sha256 checks as on a machine, proved on Linux at
  every build;
- cljfmt, bbin and clojure-mcp-light still by their own lines, pinned in the Dockerfile alone until
  8.2a and 8.2d, each said where it is.
`docker-image!` asks Docker its architecture (`docker info`), and the pack's new `image-args
<platform>` prints the six build arguments from the matrix (`asset`, one implementation); `KIT_AS_OF`
stays known-good's date. The ARGs carry no defaults for these. A test holds the Dockerfile's ARG set
to `image-args`' keys, `KIT_AS_OF`, `TARGETARCH` and Q3's four leaving with their sub-tasks. The
environment table gains Docker's row, optional, `:needed-for` naming each use. Built here
(linux-aarch64): `/tmp/git.tar.xz: OK`, `/tmp/bb.tar.gz: OK`, the four tools installed and linked;
in the image, `bb` v1.13.229, `java` 25.0.5, `clj-kondo` v2026.10.26, `clojure` 1.12.6.1673,
`geckodriver` 0.37.1, `git` 2.56.0, `cljfmt` 0.16.6, and `clojure -M -e` evaluated. The doctor's
`docker info` probe ("daemon not running") is left to 8.2e, the doctor's commit.

### The toolchain pack installs: `toolchain-install`, its plan pure, each archive checked before it is unpacked (plan 7.0, item 8.1c)

The install's core, which the KIT's toolchain image will run (D-9). `toolchain-download-matrix/plan`
is pure - the matrix, the cache, the platform, `--only`, the running bb and the cache's state in;
a step per tool out: `:kept` (in the cache), `:skip` (bb when the running one is at or above the
matrix's - the person, 2026-10-29; the JDK unless named, 200 MB, the `--java` flag and the lookup
of a JDK already on the machine being 8.3a's), `:install` with its archive, folder and download.
`toolchain-install` walks it: the archive fetched into `<cache>/downloads/` - or the copy there
reused only while its sha256 is the matrix's - `verify!` before anything is unpacked (a mismatch
deleted and refused by name), unpacked beside its place and moved in; the Clojure CLI laid out by
`cli-layout`, the nine files as the official `posix-install.sh` does it (`PREFIX` and `BINDIR`
filled, 755 and 644), its tarball's own `install.sh` being Homebrew's; `--link <dir>` links each
tool's executables for the image, whose PATH can name no version. A failure is one line and exit
1, never a stack trace. `status`'s rows gain `:fix`, the install command for a row not present
(8.1b's read). Tried into a scratch cache (`KIT_TOOLCHAIN_DIR`): clj-kondo and the CLI installed and
linked, `clj-kondo --version` v2026.10.26, `clojure --version` 1.12.6.1673, `clojure -M -e` ran; a
second run kept both; a corrupted download in `downloads/` was fetched again, not reused.

### The harness README's example `.mise.toml` is what `bb init` writes (From Fable 5.1's read of `1bfe459`)

The block under "Versions: floors, one pin, and a known-good set" showed the template's file as it
shipped before plan 7.0 wrote the KIT's set into a project: java `temurin-21`, babashka 1.13.223,
clj-kondo 2026.08.04, cljfmt 0.16.5, no tailwindcss, and a bbin comment that goes with bbin in 8.2d -
a reader comparing it with a generated file found nothing the same. It is now an excerpt of what `bb
init` and `bb upgrade` write: the KIT's note above `[tools]` (`project_set/tools-note`) and the
project set's six lines. The person: "yes". Still by hand; 8.2e, which rewrites the README's
Versions section, decides whether a check holds it to `project-set.edn`.

### The version report says a git dep and XTDB as fetched, and what an unpinned row's machine runs (plan 7.0, item 8.1d)

The person, at the report's first run: "why is 0.8.0 not showing up in the intalled here column?"
deps-new is a git dep the Clojure CLI fetches when `bb init` generates the application, into
`~/.gitlibs/libs/io.github.seancorfield/deps-new/<full sha>` - `2f96530…` was there - and the report
looked only in `~/.m2`, for the pack's libraries. `fetched` now looks in `~/.gitlibs` for the
template and deps-new, by the pin's short sha as a prefix of the folder's name, and in `~/.m2` for
XTDB as for the pack's deps: "0.17.0, fetched", "kit-v1.3, fetched", "2.2.0, fetched" on this
machine. And Fable 5.1's nit on `2267ee7`: an unpinned row keeps what this machine runs in its
installed column. The run after `1bfe459`: every row the same three ways, "0 newer released, 0
installed differ".

### The pins move on the person's go: bb 1.13.229, clj-kondo 2026.10.26, deps-new 0.17.0 (plan 7.0, item 8.1d)

The version report's first run (`bb toolchain-newer`, `2267ee7`) showed three rows behind their newest
release, bb and clj-kondo installed newer than pinned. The person, row by row: bb "yes", clj-kondo
"yes", deps-new "yes. check with Fable - it did a run and verified it."
- **bb 1.13.226 → 1.13.229, clj-kondo 2026.08.04 → 2026.10.26**: the download matrix's versions and
  each platform's sha256, taken by `bb --config tools/toolchain/bb.edn toolchain-sum <tool> <version>`
  - all eight match the publisher's `.sha256` files; the project set (`project-set.edn`, what `bb
  init` and `bb upgrade` write into a project) with them. This machine's gates ran on both already.
- **deps-new v0.8.0 → v0.17.0** (`template-pins.edn`, `657fca6`): verified by a scratch `bb init` on the
  pinned template with 0.17.0 beside one with 0.8.0 (Fable 5.1, 2026-10-29, in its session's scratch
  folder): the generated applications identical by `diff -r` (42 files each), the application's `bb
  fmt-check`, `bb lint`, `bb test` green (10 tests, 53 assertions); deps-new's CHANGELOG v0.9 to v0.17
  and `new.clj`/`impl.clj` read for what `create-command` and the template rely on - nothing changed.
  Not exercised: a user- or directory-level `.cljconf/org.corfield/deps-new.edn` (deps-new 0.15 on),
  which would override the template's `template.edn` keys; none exists on this machine.
`bb health` was not run before the move: its record after 8.1c (`bb health --record`, `bb
docker-health`, `bb health-sync`) is the evidence, and moves `known-good.edn` and the Dockerfile's
defaults with it.

### The version report reads the doctor whatever its exit, keeps a lost pin's row, says the JDK's major apart, and has XTDB (From Fable 5.1's read of `51ce107`)

The low: the report discarded `bb doctor --edn`'s map whenever the doctor exited 1 - and the doctor
prints its map and exits 1 when a required tool is unusable, so on the machine the report is most
for every installed cell read `-`. It reads the map whatever the exit now (`read-doctor`), `{}` only
when the output is no map. The nit: a source no file carries dropped out of the table silently; it is
a row now, `-` across, "no file carries its pin". And, on the reader's answers: a JDK of another major
than the pin's is no difference under "21 and newer, 25 preferred" - its own mark, worded by the
doctor's note on the java row (the report never restates the environment table's rule), while
another patch of the pin's major stays "installed differs"; XTDB joins, its pin read from
`harness/health/xtdb/deps.edn`, the one place the KIT writes it (2.2.0, the newest). The images wait
for 8.5b, which adds a registry's tags as a source; clojure-mcp-light's commit is compared at 8.2d,
when its pin is one. The person: "yes to both, take them as one commit".

### `bb toolchain-newer` is the version report: each pin as installed here, as pinned, and its newest release (plan 7.0, item 8.1d)

The person, 2026-10-29: "in KIT's development, known-good is a record of everything that has been
tested and known to work. The process starts with the versions in the matrix, then a check for
latest versions available is made. This should result in a report showing a table of what is
currently installed, what is in the matrix, and latest version available for each of the
tools/libraries in the spec. I will be reviewing the report and give the go/no-go to update them - to
the latest version or if necessary to the matrix version." `bb toolchain-newer` compared known-good
with the newest tags; it now reads each pin where it is owned - the download matrix for the tools the
pack fetches, the pack's `bb.edn` deps for its libraries (the Dockerfile's `ARG`s until 8.2a and 8.2d
move cljfmt and clojure-mcp-light there), `project-set.edn` for tailwindcss and beside each tool a
project shares, `template-pins.edn` for the template and deps-new - and prints a table: tool,
installed (`bb doctor --edn`'s version, in a subprocess, the doctor's table being the harness's
resource; for a library the pack loads, its pinned version and whether `~/.m2` has it - the person:
"cljfmt may be a library, but it is still tooling. So why not show its version?"), pinned (where),
latest, and the marks: newer released, latest unknown, installed differs, project set differs.
Temurin's `+8` build is not part of the version compared. Run on this machine:

    bb                 1.13.229     1.13.226 (matrix; project set 1.13.226)        1.13.229     newer released; installed differs
    clj-kondo          2026.10.26   2026.08.04 (matrix; project set 2026.08.04)    2026.10.26   newer released; installed differs
    deps-new           -            0.8.0 (template-pins)                          0.17.0       newer released

and every other row the same three ways. `bb kit-status` and `bb tag-check` print its newer lines as
before. The kit-session skill says it: the report at a plan's start and again before a tag, a go or
no-go per row, a version moving only on that go with its health runs.

### Every Argon role goes direct to Google's API: one model, one route (plan 7.0, item 1.8)

The person, 2026-10-29, asked whether the security panel's Argon stays on OpenRouter as in the
`claude` example: "Every Argon reference goes to Google API - basically 1 model 1 route." Both
examples' panel member moves from OpenRouter (pinned to `google-vertex`) to Google's endpoint with
`GEMINI_API_KEY` and the Argon `:pricing` (Google's page, 2026-10-28) - a direct reply carries no cost.
The member reads nothing while Argon is the Coder, so no review changes today; `bb security-review`'s
balance pre-flight applies to an OpenRouter endpoint only (`security_review.clj` 665), so a direct
member takes no OpenRouter margin. `bb roster-sync` wrote roster.md's and portability.md's cells;
portability.md's adapters sentence, the harness README's and `build-docs/03`'s say every Argon role
goes direct. The first project's own profile is the project's: the change there is its session's.

### `bb roster-sync` says a missing file in one line too; the `agy-ide` header says what differs (From Fable 5.1's read of `85419dc` and `5b3eb7d`)

`roster-sync-main` caught only the KIT's own refusals, so a `roster.md` or `portability.md` gone from
the clone still left a stack trace - under the person's "tooling that crashes ... is bad" it catches
every exception now, the same one line and exit 1, nothing written (probed: `../portability.md (No such
file or directory) - nothing written`). And `agy-ide.edn`'s header said its roles are the `claude`
example's "all but the orchestrator"; the security panel differs too (two members to three). The
person: "yes".

### The `agy-ide` example's Argon roles go direct to Google's API, as the `claude` example's Coder does (plan 7.0, item 1.8)

The person, 2026-10-29: "the agy-ide profile: update the 'gemini-4-argon' references to use the
Google API (same as the Claude profile). Update the relevent documents too". `agy-ide.edn`'s Coder and
orchestrator move from OpenRouter (pinned to `google-vertex`) to Google's OpenAI-compatible endpoint,
`GEMINI_API_KEY`, with the `claude` example's Argon `:pricing` (Google's page, 2026-10-28); its header
gains the line on Google's endpoint the `claude` header has. The security panel's Argon stays on
OpenRouter, as in the `claude` example - it is left out while Argon is the Coder. `bb roster-sync`
wrote portability.md's `agy-ide` column; its profile excerpt, its sentence on the two examples'
differences and on the adapters each uses, and the harness README's sentence on `agy-ide` follow.

### portability.md's note under the seat table says the Anthropic roles' route as it is (From Fable 5.1's read of `b8dda37`)

The note under the seat table said "The `claude` seat's two Anthropic roles go through OpenRouter
since 2026-09-25 in the shipped file", false since 2026-10-16, and the paragraph after it said the
opposite on the same page. The person: "correct it". It says now that they went direct to Anthropic
on 2026-10-16, over OpenRouter from 2026-09-25 before, and why that route had been chosen.

### `bb roster-sync` refuses in one line and writes nothing (From Fable 5.1's read of `b8dda37`)

Two nits: a portability.md row without its closing ` |` was refused as "0 cells, 3 expected - a ' |
' inside a cell?", the wrong cause - it says "no closing ` |`" now; and an example gone from
`resources/profiles/` reached the renderer as a NullPointerException - it is refused by name, "no
shipped example `agy-ide` in resources/profiles/". The person, taking them: "KIT should be resilient -
so tooling that crashes and takes down the application is bad." So `bb roster-sync` renders both files
before it writes either, and a refusal is one line on stderr and exit 1, "roster-sync: <the reason> -
nothing written" - never a stack trace, never one file of the two (probed: portability.md without its
tables, roster.md untouched).

### portability.md's tables of models say the current model alone, written from the profiles (From Fable 5.1's read of `60842de`)

The reader found the check `60842de` added read the cells' history: a name or a route word in a
"before" parenthetical satisfied it, so a Coder moved to OpenRouter, or back to a model its cell once
held, passed (probed in a scratch copy). The person, 2026-10-29, removed the history instead:
"simplify the 2 tables with seats/models - remove the history and keep only the current model &
effort (origin in parenthesis)", and chose the cells written from the profiles by `bb roster-sync`
and held by a test, as roster.md's column is, the `agy-ide` column from `agy-ide.edn`. Each cell is
now `` `model`, effort (origin) `` - Google API, Anthropic API or OpenRouter - the panel's members in
rotation, the one of the Coder's family "left out while it is the Coder". `render-portability`
writes the two tables (refusing a table not found, a role with no row, a row of other than its
cells, by name), so a drift is caught both ways - a member or an effort removed from the profile
too, the reader's second nit - and the check, with its nits on a missing effort, a cascading "names
no" and a member named twice, is gone. The README's sentence stays a check, `readme-gaps`: the
Coder's model and its route. The role table's header drops its date. The history the cells carried
is in this log's earlier entries and the profiles' comments.

### portability.md's two tables and the README's profile sentence are checked against the profile (plan 7.0, item 1.8)

The roster's check left three places that say the `claude` example's models by hand: portability.md's
seat table and its role table, and the harness README's sentence on the profile. The person,
2026-10-29: "add them to the check to keep them updated". Each cell there sits beside its dated
history ("from 2026-10-16; `gemini-3.8-flash` before"), which the profile does not hold, so they are
checked, not written: `profile/table-gaps` reads each table's rows by role and says, by line, a model
the cell does not name (the panel's in rotation order), an `effort <e>` it does not give after the
model, a member left out it does not say is, a route it does not name (`direct`; `OpenRouter` in the
seat table, the role table naming only direct routes), a role with no row, and a plan reviewer said to
be the spec reviewer's selection when it is not. `profile/readme-gaps` holds the README's
`(its Coder is ...)` to the Coder's model and route. A test runs both on the files. The check found
the seat table had no blueprint-reviewer row: it has one now. The README names the Coder as
`gemini-4-argon` rather than Argon, so the check can read it.

---

## 2026-10-28 (plan 7.0)

### The roster check reads a row whole or refuses it (From Fable 5.1's read of `9478b22`)

`render-roster` cut every line starting `| ` as if it ended ` |`: a hand-edited row without the
closing bar lost its last two characters, rewritten without a word (the reader ran it: `… | notes`
came back `… | not |`), and a line `| ` alone threw an index error. A row is now a line that starts
`| ` and ends ` |`; anything else is left byte for byte. And a row that names a profile role but has
not six cells - a ` | ` typed inside a cell - was skipped silently, the way the column fell behind;
it is refused now with its line number, so the test fails saying which row. The person: "yes to
both".

### The roster's Model column is written from the profile, and a test holds it (plan 7.0, item 1.8, register row 235)

The person, 2026-10-28, asked whether something should hold `roster.md` to the profile so it cannot
fall behind again: "Yes". The Model column had said the `claude` example's models by hand and was
twelve days behind it. `profile/render-roster` writes the cell of every table row whose Profile role
names a role of the profile - the model, its effort, its route (direct to Anthropic, direct to
Google, OpenRouter pinned to its host), a Tester's rounds, the security panel in rotation with the
member of the Coder's family said to be left out - and keeps every other byte; `bb roster-sync`
writes it, and `profile_test`'s `the-roster-says-the-claude-examples-models` fails the gates when
the file differs. A test rather than a new gate: the check is one comparison, and the test suite is
already in `bb gates`. Two cells lost hand-written words to it: the plan review's "the spec
reviewer's selection by default" moved to its Notes, and security routing's "as Triage above" is
now the orchestrator's model itself. Row 235 closed.

### The build docs' profile sentence and the panel's third member (From Fable 5.1's read of `07270b2`)

`build-docs/03-method-and-tooling.md`'s instruction above the role table still sent the shipped
`claude` example's Coder through OpenRouter - the one route document 1.8 missed, and the one an
adopter reads while filling the table; it says direct to Google's API with `:pricing` now. And the
profile's panel comment said why Astra and Argon are members (6 of 6 on the KIT's fixture) and
nothing of Sol: it joined from the first KIT project's panel (2026-10-23), with no fixture figure of
its own.

### The shipped `claude` profile is the one the first project builds with; the roster follows it (plan 7.0, item 1.8, register row 235)

The person, 2026-10-28: the first KIT project's profile "is the tried and tested one we want to use"
- its configuration, not its comments. Two differences from the shipped `claude.edn`: the Coder,
Gemini 4 Argon, on Google's own API (`GEMINI_API_KEY`) rather than through OpenRouter, priced from
Google's page because that endpoint reports no cost (`report.clj` counts a direct step's cost from
`:pricing`); and a third security panel member, GPT-6.1 Sol at `xhigh`, which the project tried on
OpenRouter first. The person: "xhigh is what it is. Item 10.2 does its thing" - the routes table's
levels stay 10.2's. `harness/roster.md` was older still (dated 2026-10-08: Sonnet coding, Flash
testing, every role over OpenRouter), and `portability.md`'s seat tables and the harness README's
seat paragraph said the old routes; all now say the profile. Two of the profile's own comments that
the change made false were corrected (the `:openai` shape's header line, "both" in the panel's).
`bb profile` holds the file. Nothing yet holds the roster to the profile (row 235 stays in part).

### The download matrix's reader refuses what it cannot take (From Fable 5.1's read of `158508e`)

git's source tarball is one archive for every platform, so a Mac asked for it (`--only git`) would
have taken it; its entry now says `:os :linux`, and `asset` gives an archive with an `:os` only to
that OS's platforms - one rule with geckodriver's, which has Linux platforms alone. A tool `--only`
names that the matrix has not (`jaava`) was dropped without a word; `machine-tools` refuses it by
name, so 8.1c's install inherits the refusal. `toolchain-sum` exited on a mismatch inside the `try`
whose `finally` deletes its temporary folder, and `System/exit` runs no `finally`: the folder is
deleted before any exit now. Two nits: the matrix's header says what reads it today and what will,
and `status`'s version column is 16 wide.

### The toolchain pack's first step: the download matrix, `status`, `toolchain-sum` (plan 7.0, item 8.1b)

The KIT's tools move from "installed by the person, versions recorded" to "held by the KIT at exact
versions and checksums", and the first step is the one place those versions are written:
`tools/toolchain/download-matrix.edn`, a fourth tool pack, because the toolchain is tooling, never
the harness (the person, 2026-10-28: "cljfmt, clojure, MCP light are all tooling."). Per tool its
exact version and, for each of four platforms, the archive and its sha256: Babashka 1.13.226,
Temurin 25.0.5+8, clj-kondo 2026.08.04, the official Clojure CLI's tools tarball 1.12.6.1673, and,
for the KIT's toolchain image only (`:image-only`), git 2.56.0's source and geckodriver 0.37.1 for
Linux. Every sha256 was pasted from `bb --config tools/toolchain/bb.edn toolchain-sum <tool>`,
which fetched all 16 archives and took each one's sha256 from the archive itself: 13 match the
publisher's checksum file (Babashka's and clj-kondo's `.sha256`, Temurin's `.sha256.txt`,
kernel.org's `sha256sums.asc`); geckodriver publishes none, so its two are computed and said so;
the Clojure tarball's matches the one the official `posix-install.sh` embeds (Fable 5.1's design
note, verified there). `status [--edn]` lists the tools this machine takes, where the pack puts them
(`~/.kit/toolchain/<tool>/<version>/`, or `KIT_TOOLCHAIN_DIR`) and whether they are there - none
yet: the install is 8.1c. The harness requires none of it; `bb doctor` will read `status --edn`
as a subprocess in 8.2e. Tests: `toolchain-download-matrix-test` (the four platforms and a fifth
refused, every URL filled with a sha256, the image-only rule, the matrix at or above the
environment table's floors, the cache paths, a publisher's checksum read out of a list, the status
rows, `toolchain-sum`'s verdicts).

### The name in three live places 8.1a left (From Fable 5.1's read of `25d5092`)

Live text, not history, still said "fresh machine" where an adopter or a session reads it: `NOTES.md`'s
"No CI" paragraph, the help text of `bb security-review --kit` - the one place an adopter meets the
name with no README near - and this file's head checklist, its step 4 and the Docker Scout line
under it. Each now says "the KIT's toolchain image", with its explanation where it is the first
mention. The dated entries below, and `NOTES.md`'s rows, keep their words.

### One name for the image the KIT is checked in: the KIT's toolchain image (plan 7.0, item 8.1a)

The image the root `Dockerfile` builds had two names: "the toolchain image" (`review_repl`,
`security_review`, `security_review_sandbox`, the skill) and "the fresh-machine image" or
"container" (`tag_check`, `harness/README.md`, `bb.edn`, the skill). One thing, one name - the
person, 2026-10-28: "yes to the KIT's toolchain image". A name says what the thing is, and the image
has two jobs, not one: `bb docker-gates` and `bb docker-health` check the KIT in it, and a project's
code reviewer REPL and security review run in it as their sandbox - so "fresh machine" is its
explanation, not its name. The first mention in each file now reads "the KIT's toolchain image - a
fresh Linux machine in Docker with only the KIT's toolchain", the name alone after it. Linux, because
Docker runs only Linux: the macOS health record is this Mac's own, and nothing tries a fresh Mac.
Older entries here and `NOTES.md` keep the old words.

### The reading session starts in a tab of its own (the kit-session skill; `76d0b7b`, `7c6af2b`, `2425690`)

Said here for the commits that changed only `.claude/` (Fable 5.1's read of `7c6af2b`: the commit
hook does not watch `.claude/`, and `76d0b7b` and `7c6af2b` were the first skill commits with no entry).
`76d0b7b`: an item's parts are sub-tasks N.M, defects apart from enhancements. `7c6af2b`: the reader
starts from `.claude/agents/kit-reader.md` with `claude --agent kit-reader -n kit-reader --model
fable`; spawned first as an agent-teams teammate, it worked, and was set aside - the writer wrote its
brief, and each of its turns sent the writer an idle notice carrying its reply, its words to the
person included. `2425690`: the definition names no model, so `--model` is the one place.

---

## 2026-10-26 to 2026-10-28 (plan 7.0, items 1-7 and 11)

Written after the fact (From Fable 5.1's read of `25d5092`): these items closed with a register row
each and no entry here, because the commit hook takes a `NOTES.md` change alone. The rows carry the
detail and the tests; an entry says what each item changed and why, and names its rows. Every
commit named was read by Fable 5.1 and holds, some after a fix the read named (listed beside it).

### The first project's defects (plan 7.0, item 1, register rows 213-218, 220)

Seven defects the first KIT project met in its stages 5 and 6. The security pack threw a
NullPointerException when no request was answered, so a deploy's record could not be written and
the cause was hidden; it now refuses by name with the first request's error (row 213, `96a7ddd`).
It probed an https deploy with no port written on port 80, because a load-filed namespace shadowed
its `port` (row 214, `9c9115a`, `c155cd8`). The browser pack's narrow widths were 500 px - headless
Firefox keeps no window under 500 - so "no sideways scroll at 390" was never measured at 390; a
narrow width is measured in a frame (row 215, `b50bae1`, `7dd56f4`, `1d5ffb0`). Firefox updated
itself under a check, and no profile pref stops it; `bb health`'s browser check warns when the
machine has no policy turning the updater off or an update is staged, and names how to set one - the
lasting fix, a pinned Firefox in a container, is item 8.5 (row 218, `864b545`, `2c6fc20`). `run-loop amend` can take a dependent into `:files/test`, where a new run was the only
way on (row 216, `7b55e35`, `2bf73b0`); a red gate on a dependent says so by name (row 217,
`cf1c175`). And `bb security-route` keeps each run's record, which the stage report sums: it had
counted a stage's last routing run alone, $0.57 of $1.38, so the cap read short (row 220, `1a1e648`,
`719e6a7`).

### Enhancements: the money by role, and what changed since the last report (plan 7.0, item 2, rows 219, 221)

The money-by-role table names each model once with its stages after it,
`claude-sonnet-5.5 (S0); gemini-4-argon (S1->S4)`, where it listed every stage (row 219, `e5c3083`).
"Since you last looked" counted from a look nobody recorded and read as the state now - the person:
"mighty confusing"; it is now what happened since the report was last generated, from the reports
log (row 221, `cc67406`, `2b5dab3`).

### A stage folder's files by fixed names (plan 7.0, item 3, rows 222, 223)

The stage plan was its folder's first Markdown file by name, so the browser pack's
`accessibility.md`, and later an amendment's brief, were taken for it. The plan is `stage-N-plan.md`
for every kind, found by that name (`f5b3229`); working documents go in `stage-N/notes/` (`143dd08`);
one table, `stages/folder-names`, gives a stage folder's names, rendered in `build-docs/README.md`
and held to it by a test (`a057277`, `b0038da`), and
`bb next` names a file it does not give (`f65b5db`); `bb upgrade` renames a stage-0 plan and its
blueprint's header (`af06907`, `bfb40c6`).

### The security panel's table by reading (plan 7.0, item 4, row 224)

A member who read a stage twice got the same marks in both columns, so the table said an earlier
reading found what only a later one did. A row's marks name the reading, a column is looked up by its
record, and a member who read twice is numbered (`a20676a`).

### A security decision deferred states its blast radius (plan 7.0, item 5, row 225)

The first project left its content-security policy to its pre-release stage, and three stages of
Alpine expressions made the strict policy a rewrite. `02-architecture.md` §8's deferred row carries
`deferred to stage N: what grows against it · the cost to switch`, `bb plan-check` holds it
(`1bbf089`), `kit-stage-end` re-reads it (`5045bd5`), §4 and the plan review's checklist ask the
cost (`44b3323`, `8997f84`), and a rule keeps a request value out of `x-*` and `hx-*` attributes
(`9cd7af6`). Fable 5.1 read the design first.

### The first project's kept lessons (plan 7.0, item 6, rows 226-231)

Six lessons the project had kept as its own, taken into the KIT. No role logs an exception whole -
its data can carry the request (row 226, `5b393c2`, `d4caa5c`). The blueprint review asks two
questions it let through (row 227, `0a3c37c`). A stage's cap plans the security review's growing
cost and the fix after it (row 228, `e1c3a3b`). A belief about a KIT command is read in its source
before a reading is skipped on it (row 229, `c15e42f`). `bb packet-tests` lists the tests that read
what a packet changes, before it is cut (row 230, `af8b27f`, `727c594`). A disputed library fact is
settled on the JVM, the form and its result in the answer, and the code reviewer has a REPL over a
copy nobody merges (row 231, `44857bc`, `014e30a`, `5ec5aae`; Fable 5.1 designed 6.6).

### The JDK to 25, XTDB pinned, one owner for each version (plan 7.0, item 7, rows 233, 234)

The KIT held JDK 21 exactly for a reason that had gone: XTDB 2.2.0 starts on 25. Proved first -
an embedded node on Temurin 25.0.5 and `bb health` on 25 - then the pin is 21 and newer, 25
preferred (`95484b7`), and XTDB pinned at 2.2.0 and tried by `bb health`, in-process and over pgwire
with the image's own JVM named (`e19067b`, `38bbc95`). The versions the KIT writes into a project
are one hand-written file, `project-set.edn` - the whole toolchain, `.mise.toml`'s tools and the
Dockerfile's images (`bebeb2d`, `0ee5cdc`, `76ee47e`) - which `bb init` and `bb upgrade` write as a
commit of their own (`9dc3d5f`). The doctor reads a project's java pin as preferred, not a floor,
and names the fix in the shell's tool manager, never the harness (`6280f16`, `53818da`). `bb health`
builds the generated application's image on the set and serves its first page from it (`db749f6`),
and that first build opened row 234: the template's image builds for amd64 alone and has no folder
for SQLite's file. No doctor test sees this machine's JAVA_HOME - a fixture holds it nil until 8.2e
makes it an input of `probe` (`af54380`). Row 233 stays fixed in part: `known-good.edn` and the
Dockerfile's JDK move at the tag, and JAVA_HOME becomes `probe`'s input in 8.2e.

### Records are add-only (plan 7.0, item 11, row 232)

The person's rule (2026-10-26): "record keeping should follow immutability rule i.e never
overwriting". Fable 5.1's design note sorted every file the KIT writes into record, view or working
state, and the person chose. Health records are kept per run (`f826185`); the security pack keeps
each run beside the first (`8acd506`); the threat model is signed once, at the close, after the last
amendment, and a signing is never written over (`8cf33a8`, as amended `3319663`); a correction is
a superseding entry, a repriced cost computed and never stored over (`b813581`, `6c8e539`); a
document cites a record by commit or `:at` (`af58b0d`, `fca8fe6`); and `method.md` §09 says it
(`f110f6b`, `cdf4398`).

---

## 0.6.11 — plan 6.11's boundary: a stage amended after its packets ran, and the KIT's own security review

Plan 6.11 added:
- **The stage amendment.** A stage can be amended after its packets ran: `bb stage-amend Q-n` opens
  the pass, and `bb next` walks it - the plan read again and approved again with its cap, the
  blueprint read again and signed off again.
- **Times.** Every gate is a time with its offset, in the project's zone.
- **Money.** A report says how much of its money it covers, and nothing throws on a figure a
  provider left out.
- **The build** ignores `.local/`, and a §0 prep row's parts are sub-bullets.
- **The seats**, the KIT's own two-session way of working, and `bb security-review --kit`, a
  security review of the KIT itself by flow.
- **That review's fixes**, rows 172-208: paths and links held to their folders, keys a provider
  echoes redacted, a packet's files held to a namespace's path, a tool a role was not offered
  refused, a provider's safeguard recorded as a refusal.
- **From the sandbox proof on the first project:** a change review reads a long diff whole with
  `read_diff`, the where map is plain EDN again, and a cap is dated by the approval that set it.

A workspace at `0.6.10` takes it with `bb upgrade` from the workspace folder. It then
re-renders any published run table whose report gained the coverage line, which
`bb report-check` names.

## 2026-10-22 (plan 6.11)

### A change reviewer reads the whole change (plan 6.11, item 11, register row 211)

The proof's first run gave stage 5 one GPT-6 Astra reading, `--stance diff`. The change was
205,711 characters, and the opening cut it at 60,000 with "read_file has the rest". No file held
the diff, so eight of the reading's eleven failed calls hunted for one. The reviewer still read 32
of the changed files, but nothing showed it the rest of the change.

- **`read_diff`:** a change reviewer's tool. It returns a page of the diff the review was opened
  with, from an offset, and each page names the next. The diff is held in memory, never written
  into the clone, so a test cannot replace it and the record does not collect it.
- **The page:** `tools/max-output` less 1,000 characters, so the tool-output limit never cuts the
  note naming the next offset. The first page size tried, 40,000, was cut by it.
- **The cut note:** it says the rest is not a file in the repository, and gives the offset.
- **Only for a diff review:** `registry` offers `read_diff` to the diff stance; a tracer has no
  diff to read.

The tests need the fix to load.

### A cap is dated by the approval that set it (plan 6.11, item 11, register row 210)

Also from the proof's first run. Since row 170 the stage report's cap is the latest re-approval's,
but the day beside it was still the first approval's, so a moved cap read as if it stood on that
day. `stages/effective-approval` returns the gate the cap stands on, with its time. `effective-cap`
reads it, and the report dates the cap by it. The test fails without the fix.

### The where map is plain EDN again (plan 6.11, item 11, register row 209)

The sandbox proof's first run found it: `bb next --where --edn` on the first KIT project printed
six `#object[java.time.Instant ...]`, and the map no longer read back. Row 170 gave `bb next` a
stage's reading times as instants to compare. `where-data` now turns every `Instant` into ISO-8601
text at its edge, so the map is plain EDN whatever a reader later adds, and `bb next` still compares
instants inside. The read-back test passed before because its fixture had no reading. The fixture
now carries a plan reading and a blueprint reading, and the test fails without the fix.

### Two nits from Fable's reads of row 208 and the three nits (plan 6.11, item 12)

- **`score!` given a bare file name.** It took the record's folder as `(fs/parent tests-dir)`, which
  is nil for a one-part relative path such as `review.edn`. `inside` then threw. Every command
  passes a full path, so only a direct call from a REPL reached it. The folder is now taken from
  the absolute path. There is no test: one would need a record in the test run's own working
  directory.
- **The `follow-links` comment** now names the trap as well. With `NOFOLLOW_LINKS`, `toRealPath`
  resolves no link at all, and a picture reached through a linked folder would be judged where it is
  written. The folder-link test holds that either way.

### Three nits from Fable's reads: a picture path, a link option's name, a key's mask (plan 6.11, item 12)

- **An absolute picture path is refused.** `picture-file`'s docstring said absolute meant nil, but
  an absolute path landing inside the icons file's folder was taken. Its test fails without the fix.
- **`no-links` is `follow-links`.** An empty option array makes `isRegularFile` and `toRealPath`
  FOLLOW links, and the old name said the opposite. A test of a folder that is a link to outside
  now holds the behaviour; it passed before too, since only the name was wrong.
- **A fetched record's mask names the variable.** `fetch!` masked the key as `<key>`, unlike every
  other redaction. It takes `:key-env` in its options, which the dispatch's and `bb reprice`'s
  calls now pass, so the mask reads `<OPENROUTER_API_KEY>`. The reprice test, updated, fails
  without the fix.

### A review's tests are judged from the record's folder (plan 6.11, item 12, register row 208)

Argon direct's routing record, verified on HEAD. `route!` and `score!` read a record's tests
inside its `<id>-tests/` folder, taking that folder as the root. When the folder was itself a link
to outside, the root moved with it, and a host file a finding's `:test` named reached the triage
model's provider or the scorer's run. This is row 203's class, and the build repository can carry
the link. `security-review-tools/record-test-file` keeps the path inside the tests folder and
judges `<id>-tests/<path>` from the record's own folder. Both readers use it. Each reader's test
fails without the fix.

### A test of the review's own is a file, not a link (plan 6.11, item 12, register row 207)

Argon direct's security-review record, verified on HEAD. `write_test` lets the review rewrite a
test it wrote itself, and judged the path, not what it landed on. A test running in the sandbox,
which mounts the clone writable, could replace that file with a link to an application's test, and
the next `write_test` changed the application's test through it. `test-path-problem` now refuses a
path that is not `contained-path/as-written?`, as row 188 does for the file tools. The record's
test, adapted, fails without the fix.

Not taken, said here: collecting only the files `write_test` wrote. A file a test planted in the
clone still reaches the record; the read-only clone of plan 7.0's draft is the fix.

## 2026-10-21 (plan 6.11)

### Hygiene: every read of a worktree or `final/` inside it (plan 6.11, item 12, step 11, no rows)

Argon direct's tool-calls hypotheses, and the record step's and the resumed upgrade's reads. Each
needs a role that can plant a link where only the KIT writes, so none is a row. Each is now one
check:
- **Through `read-inside`:** triage's file section (what reaches the triage model), the runner's
  worktree snapshot, `final-files` and `files-as-written`.
- **Through `inside` and `as-written?`:** `sync-from-gate!` reads the gate's copy and writes the
  role's own, and `record!` writes `final/<path>` and `final/as-written/<path>`.
- **The resumed upgrade:** a template-upgrade run whose state names a worktree outside
  `<work>/worktrees` is refused before anything is checked out there.
- **The clones:** the three review clones are made with `--no-hardlinks`, so a clone shares no
  object file with the repository it came from.
- **A note's text:** it must be a string. A number had thrown in `tools/note` and again in
  `runner/outcome`, which now skips an errored or non-string note, and the second throw took the
  dispatch down.

Tests cover the snapshot, the file section, `final/`, and the note; the first three fail without the
fix. The sync, the resume and the clone flag have none beyond the suites they pass.

Not taken, said here: the boundary gate reads the `ns` form and `require` calls (row 196), not
`in-ns`, `requiring-resolve` or `load`, so a namespace can reach another without the gate seeing it.
Holding a layer's code to its declared surface is the rule source's business (plan 7.0), not a
check this gate can complete.

### Names and destinations: a target is a file, a record's name a name (plan 6.11, item 12, register rows 188 and 206)

Step 10's lows, in one commit.

- **Row 188.** A target linked to a context file inside the worktree let `write_file` and `edit_file`
  change the context file. `contained-path/as-written?` holds a path inside with no link between the
  root and the file, judged from the root's own real path so that macOS's `/tmp` is not a link in
  it. Both tools refuse otherwise.
- **Row 206.**
  - The scorer writes a review's tests through `inside` its clone, so a `test` link in the fixture's
    branch leads nowhere.
  - `write-record!` refuses an id that is not a name. The id carries a panel member's `:name`.
  - `bb profile` names such a member.
  - The rule is `[A-Za-z0-9._:-]+`: Fable's set plus `:`, because a candidate's id is a slug's last
    segment and a slug may carry a variant (`:free`). A `:` cannot make a path.

The bake-off case id, the fourth low, landed with row 193. Each test fails without its fix. The record
id's test, run without its fix, wrote a record into the temp folder's parent: the escape was real, and
that stray file was removed.

### A money answer echoing its key is redacted (plan 6.11, item 12, register row 205)

The money-and-records flow's two echo findings (GPT-6 Astra). Row 183 redacted the key from a
completion's error, but two other answers still reached the records as sent:
- the balance returned a provider's own `:data` `:unavailable` as its reason;
- a generation record's `provider_name` became the step's provider.

`redact` now lives in `provenance`, below every caller that sends a key, and `agent/redact` points to
it. `fetch!` redacts the key it sent from the record it returns, which covers `bb reprice` and a
dispatch's own fetch. The balance drops a provider's own `:unavailable`, and redacts the key from
each answer and each exception's message. Astra's two tests, adapted, fail without the fix.

### The SVG pack's trace stays in its folders (plan 6.11, item 12, register row 204)

The tool-packs flow's finding (GPT-6 Astra): `trace` took an icon's name, a picture's path and the
output folder from the project's icons file and followed each wherever it led. Where the command
reads and writes is now `svg-trace-files`, plain `java.nio` so the pack still needs no library and
its tests run in bb (the command imports ImageIO, which bb lacks). A name is `[A-Za-z0-9_-]+`, every
one checked before the first write. A picture is a file of the icons file's folder, links followed.
Each write is opened with `NOFOLLOW_LINKS`, so a link is refused even if it is planted between the
check and the write, and a default `traced/` that is a link is refused. The contact sheet escapes its
text, and `svg-trace` refuses an added arc's flags unless each is 0 or 1. Astra's four tests, plus the
arc test, are adapted in `svg-trace-files-test`. The command, run on the JVM against the four
attacks, refused each one and traced a plain icon.

### Two nits from Fable's reads: a route that does not parse, the refusal's line (plan 6.11, item 12)

`balance/openrouter-route` parsed each route's endpoint with `java.net.URI.` under `some->`, which
catches nothing: one entry with a space, or one that is not a string, threw and stopped `bb balance`.
Such an entry is now skipped. And the review's printed refusal line now names the flagged 403
(row 199) beside `content_filter` and `refusal`.

### A refused skill is never "in sync" (plan 6.11, item 12, register row 202)

From Fable 5.1's read of `3db3932`: the sync refused a linked skill, but `bb skills-sync` and its
`--check` printed it "in sync" and exited 0, so the workspace gates passed over the link. The command
now prints `skills refused:` with the reason and exits 1 in both modes. And `stale` no longer reads a
linked folder's `SKILL.md` to decide whether it is a rendering: a link is never one this wrote.

### The readings read only inside the plan (plan 6.11, item 12, register row 203)

The contract and setup flows' shared finding: the plan review slurped `docs/<name>` unbounded, so a
linked `source.md` sent a host file to the reviewer's provider. `contained-path/read-inside` - inside,
a regular file, else nil - now reads the plan's documents, the stage plan and the blueprint review's
requirements.

### The skills are never synced or pruned through a link (plan 6.11, item 12, register row 202)

The setup flow's skills finding: a link planted under `.claude/skills/` made the sync overwrite, and
the prune delete, outside the workspace. A linked or outside path is refused and said; the prune
removes regular files inside only.

### The panel keeps links and its records' names (plan 6.11, item 12, register rows 200 and 201)

Two panel findings, both flows' (Astra's and Argon's): the member's copy followed a link in the
project into a regular file holding host text, and a panel row's `:record "../x"` moved the folder
whose tests `security-route` trusted. The copy keeps links as links; only a record's file name is
taken.

### A key echoed in an answer is redacted too (plan 6.11, item 12, register row 187)

The tool-calls flow's provider-echo finding: rows 183 and 186 redacted errors and tool results, not an
answer's own text or a tool call's arguments. `converse!` now reads every answer for the
environment's secrets before the history, the notes, the transcript or the record take it. With it,
`inside`'s docstring names the two-readings refusal of row 198, where a caller reads it.

### A flagged 403 is a refusal (plan 6.11, item 12, register row 199)

OpenAI refused two flows with a 403 that says the content was flagged; the records called them failed
calls. `agent/refused?` now reads such a 403 as the provider's refusal, and the review's table shows
the two old records as refused.

### A `..` after a link is taken where the OS takes it (plan 6.11, item 12, register row 198)

The seams pass on Argon at Google's own API (20 completions, $1.90; the two flows OpenAI refused ran
on Argon too, $1.22 and $0.63, no refusal) found a hole in row 190: `inside` took `..` from the string
before following links, while the file tools opened the string, where the OS follows the link first.
The walk now takes the path as written, `..` from where it has got to; a path is inside only when its
written and normalized forms are one file, so either string is safe to open.

### The sandbox stops before its tests are collected (plan 6.11, item 12, register row 197)

The security-review flow's lifecycle finding: the tests were collected while the container ran, so a
process a test left could swap a checked file for a link before the read. Both reviews stop the
sandbox they started first.

### The boundary gate reads every top-level form (plan 6.11, item 10, register row 196)

The contract flow's sixth finding: the gate read a file's first form, so a second `ns` form or a
`(require …)` call loaded a forbidden namespace past it. It reads every top-level form now, by the
same reader as the form table. A test file that does not parse also covers no target since row 195 -
said here, as the behaviour it changed; gate 0 runs before either.

### A target is covered only by a test that runs (plan 6.11, item 10, register row 195)

The contract flow's fifth finding: the targets gate read lines, so a marked `deftest` inside
`(comment …)` counted as coverage. It now reads the file's top-level forms and counts only a test form
that starts one; an alias's `t/deftest` counts too.

### A file is one role's (plan 6.11, item 10, register row 194)

The contract flow's fourth finding: a path named under both `:files/impl` and `:files/test` let the
Tester's version replace the Coder's at assembly. `check-files` refuses a path named in more than one
list.

### A bake-off case is a file of the plan, its id a name (plan 6.11, item 10, register row 193)

The money flow's third finding, with its second folded in: a spec's case `:path` and `:stage` went
unbounded into every candidate's and the judge's prompt, and its id into its records' file names.
`bake-off/case-refusal` holds both paths inside the plan (the run folders for the spec reviewer) and
the id to a name, before any call is paid.

### `write_test` holds a path inside the clone too (plan 6.11, item 10, register row 192)

The security-review flow's first finding: `test-path-problem` judged a path against `test/` as its
root, so a test that replaced `test/` with a link moved where the host's next `write_test` wrote. The
path must now be inside the clone as well.

### A spec's file has a source file's shape (plan 6.11, item 10, register row 191)

The contract flow's second finding: the `:files/impl` path became the stub's namespace, held only to
relative with no `..`, so a file name carrying forms loaded as code. `stub/source-path` now holds the
impl and test paths to `src/`, `test/` or `dev/` and a namespace's characters, at every place
`check-files` runs.

### A dangling link is judged where it points (plan 6.11, item 10, register row 190)

The contract flow's first finding, with the setup flow's front door folded in: `inside` asked
`fs/exists?` of a path, which follows a link, so a dangling link read as a file not yet written and a
write through it created the target outside. `inside` now walks the path part by part, following each
link to where it points, dangling or not; the front door's sync never writes through a link.

### The flows' findings, and `bb balance` asks only OpenRouter (plan 6.11, item 10, register row 189)

The six flows by Astra with the last-round notice: tool-calls and security-review refused by OpenAI's
safeguard (a 403, "flagged for possible cybersecurity risk", at completions 19 and 15, $3.49 and
$2.17, their tests kept), contract six reproduced ($3.78), money-and-records five ($3.17),
setup-and-upgrade three ($3.69), tool-packs four ($2.11) - $18.41, every flow the provider let finish
answering. Each pass's tests were run again in a fresh clone and sandbox, and each Fable 5.1 called
real failed again. Not findings, said here: clj-kondo run on the host over a project's
`.clj-kondo/config.edn` - the planted hook ran under kondo's interpreter and wrote nothing; `bb tasks`
evaluating a project's `:init` - on bb 1.13.226 it does not; the clone's diff drivers - `.git/config`
is the person's own clone's, read-only in the container since row 180.

The first fixed, HIGH: `bb balance` took a run record with a `:roles` key for its profile and any
endpoint containing "openrouter" for OpenRouter's, and sent the variable the record named to it.
OpenRouter is asked only at the endpoint `routes.edn` names, with the routes' key; a record is never
a profile.

### A review is told its budget, and its last completion (plan 6.11, item 10)

The first two flows, Astra at twenty completions each, both stopped at the limit with no answer -
$3.78 and $3.19, mostly searches and reads - because neither was told the next completion was its
last. The person stopped the batch. `converse!` takes `:final-notice`, a text it sends beside the tool
results before the last completion allowed (a text block in the Anthropic shape's turn, a user
message after the OpenAI shape's tool messages); a capped run still says `:capped?`. A security
review sends one - "This is your last completion: answer now with the JSON block…" - and its
opening says the budget: "You have N completions, tool calls included; the last one must be your
answer." Generic: a Coder's would say to finish its target file.

### The balance check reads the lower of the key and the account (plan 6.11, item 10)

From Fable 5.1's read of `680f598`, before any flow ran: the pre-flight took the OpenRouter key's
remaining and fell to the account's only when the key had none. The key's limit was raised to $500
that afternoon, so it showed $341.08 left while the account held $51.08 - and OpenRouter refuses at
whichever runs out first; seven flows would have started against $51. The lower of the two binds now,
and the line names it, the other beside it. Three files had been in no flow but the seams -
`run_lock.clj` and `agent-rules.edn` join tool-calls, `bake_off.clj`, `routes.edn` and `profiles/`
money-and-records (since row 181 the routes decide where a key may go), `contained_path.clj` the
contract - and a test now holds every source file to a flow.

### The KIT's own review in flows (plan 6.11, item 10)

The person's decision, after Astra's pass: the KIT is reviewed a flow at a time. The cost of a whole
pass is its growing context - Astra's sent 6.5 million tokens over 38 completions, the last request
near 300 thousand, and $13.66 bought no answer when the credit ran out. A flow is a short pass over
one path through the KIT, so every request stays small, a refusal or a cut-off loses one flow and
not the pass, and the passes are read side by side. `resources/security-review/kit-flows.edn` names
six - a model's tool calls, the contract path, money and records, the security review itself, setup
and upgrade, the tool packs - and `seams`, which reads everything and asks only where they meet.
`bb security-review --kit --flow <name>` points the opening at the flow's files and question, the rest
context, findings only inside it; `--after` may be given once a pass, merged; `--table` writes
`kit-review.md` and `.edn` from the records, a row a pass and every finding under its flow, with the
tests a pass wrote even when it gave no answer. Before a pass the OpenRouter key's remaining is read:
under $20 for a flow, $100 for the whole, nothing starts unless `--force`, and the line says the last
pass's cost beside the margin. The kit-session skill runs the six, then the seams.

### A tool's output is read for secrets (plan 6.11, item 9, register row 186)

From Fable 5.1's read of `71652b6`: the Coder's `nrepl_eval` runs with the person's whole environment,
so one `(System/getenv)` put every key into a tool result - to the provider, the transcript, the
record. `converse!` now reads the environment's secrets once a conversation - each variable named
for a key, token, secret or password, and the role's own key-env - and replaces each value by its
name in every tool result before the model or the transcript sees it.

### A dispatch says how many tools its role was not offered (plan 6.11, item 9, register row 182)

From Fable 5.1's read of `eec5da8`: the runner counts a dispatch's refused calls, `:unoffered`, and the
dispatch event carries the count when it is not zero - "this role tried a tool it was not given",
read from the run log without opening a transcript.

### Only a test file is collected from the sandbox (plan 6.11, item 9, register row 180)

From Fable 5.1's read of `baccaeb`: a test running as root can write any file under `test/` - an editor's
swap file, a `.DS_Store`, ten gigabytes the host would load. `security-review/written-files` collects
only a path `test-path?` holds, the shape `write_test` held the writer to, and none over
`max-test-bytes`, naming each such file once.

### The assembly copies only inside both folders (plan 6.11, item 9, register row 185)

Astra's last finding, LOW-MEDIUM: `assemble!` joined loop.edn's `:architecture :files` paths, as written,
to the source folder and the gate worktree, so `../x` wrote beside the gate. A path is now copied only
when `contained-path/inside` holds it in both; otherwise the assembly is refused by name.

### The boundary gate reads a prefix list (plan 6.11, item 9, register row 184)

Astra's fifth finding: `boundary/requires-of` dropped a list, so `(:require (app [secret :as s]))` named
nothing and a forbidden dependency gated green by its spelling; `:use` was not read. A prefix list is
now read as each namespace under its prefix, nested, and `:use` as `:require`.

### A key a provider echoes never leaves in the error (plan 6.11, item 9, register row 183)

Astra's fourth finding: a provider's error that echoed the Authorization value went, as text, into the
dispatch event - the run's state, its log, the committed record. `agent/redact` replaces the key's
value by its variable's name, `<OPENROUTER_API_KEY>`, before the error leaves `converse!`.

### Only a tool the role was offered runs (plan 6.11, item 9, register row 182)

Astra's third finding: `converse!`'s `:tools` set reached only the declarations, so a provider's call
for a tool never offered ran - a reading with no tools read a project's file. The offered set is now
what runs; another call is answered as not offered and kept on the call as `:unoffered?`, in the
transcript and the run log.

### A key never comes from a record (plan 6.11, item 9, register row 181)

Astra's second finding, HIGH: `bb reprice` took a step's endpoint and its key's variable from the run
record first - a project's file, which the build commits - so a record could send any key in the
environment to any host. `reprice/key-env-for` now gives the variable from the profile or `routes.edn`
for the endpoint, and an endpoint neither names is not fetched from, said by name; the same for a
reading's record. The stub-provider tests name their loopback stub as a trusted route in a fixture.

### Astra's pass, and the sandbox that could run a command on the host (plan 6.11, item 9, register row 180)

The second pass, the command's first real run: `bb security-review --kit --model "openai/gpt-6-astra
high" --after <Argon's record>`. It did not finish: OpenRouter refused completion 39 for want of credit
(402), so the record says nothing - 38 completions, 12.6 minutes, $13.66 as OpenRouter reported it,
about nine times Argon's pass. It had written six tests; each failed again in a fresh clone and
sandbox, and Fable 5.1 read each against the code and found it failing for the property it states, so
the six stand as reproduced, rows 180-185, each fix with Astra's test.

The first, HIGH, for every review and not only the KIT's: the clone, `.git` included, was mounted
writable in the container where a model's tests ran as root, and the host collected those tests with
`git ls-files` in it - a `core.fsmonitor` written into `.git/config` ran on the host, and a link left in
`test/` put a host file into the record. The clone's `.git` is now mounted read-only in both run
containers (tried: the write refused, the tests run), and the tests are collected with no git after
the sandbox: the tracked files listed before it starts, the folder walked after, links passed over.

### `bb security-review --kit`: the KIT's own security review, a command (plan 6.11, item 9)

The person's decision, after item 8: the local driver that ran the KIT's own review becomes the
command, so a `DEVLOG.md` entry cites a command, not a gitignored script. `--kit` clones this
checkout's HEAD (no tags, no remote; `.local/` is never in it), starts the same sandbox with the KIT
as its target - warmed once with the network, then `--network none`, `run-argv`'s limits, both caches
read-only, the container named under `kit-review-` so a killed one is cleared by the next (row 178) -
and runs the review with the KIT's tools: `read_file` and `search` over the clone, `write_test` and
`run_tests` on `harness/`, no request tool. The prompt carries the KIT's threat model: what a model
returns, what a project's files say, what a provider answers. `--after <record.edn>` names an earlier
pass's findings as found and fixed, so the next pass looks elsewhere. The clone is removed whatever
happens; the record goes to `.local/security-review-kit/out/`. The kit-session skill says when it runs
and where a result lands. Tried for real without a model: a clone of HEAD, the sandbox up, one test
namespace run in it offline, the container and the clone gone after.

### Two loose ends of the review's fixes (plan 6.11, item 8, register rows 172 and 175)

From Fable 5.1's read of `34fe4ad` and `f402057`, two nits, one commit. `bb security-route` lists in its
trigger and its record only the files a finding names that are inside the clone - an outside path had
travelled as a name with no text, noise for whoever read it. `bb sigs` given a spec that is not EDN says
so in one line and exits 2 (`sigs/read-spec`), where the reader's stack trace had ended it.

### Every file of a spec is a path inside the worktree (plan 6.11, item 8, register row 179)

From Fable 5.1's read, checking every reader of a spec's paths - a site Argon's review did not reach:
row 173 held `:files/impl` only, and `record` copied each `:files/test` path from the worktree into
`final/<path>`, so a blueprint's `../../../some/file` wrote outside the run. `stub/check-files` now holds
every path of `:files/impl`, `:files/test` and `:files/context` to relative with no `..` part, by key,
at extraction, at `start` and at `amend`; `record` copies through `contained-path/inside` as the second
line. The extraction's refusal names the key, and says to split the packet only for a second file.

### A killed review's container is cleared by the next (plan 6.11, item 8, register row 178)

From the review's own run: two `kit-review-*` containers, exited two days earlier, held the dependency
cache's volume - a review removes its container in a `finally`, and a killed run reaches none.
`security-review-sandbox/start!` now removes the exited ones first and names them; a running review
beside it, a panel member's, is left alone.

### A security reviewer's test path carries only a namespace's characters (plan 6.11, item 8, register row 177)

The review's last finding, LOW: `write_test` took a path with `"$(id)"` in it, and its namespace went
into the `bash -c` of `sandbox/tests-argv`, inside the no-network sandbox; `score!` read a record's
`:tests` with no bound. `security-review-tools/test-path?` holds a test file's path, as it resolves, to
`test/` and parts of letters, digits and `_` - `test/app/../app/x_test.clj` is still the file it
names - and `score!` scores only the paths that pass it, read inside the test folder.

### Two readings read only inside the project (plan 6.11, item 8, register row 176)

The review's fifth finding, the reads half of the stub's: the spec review read each `:files/context`
path, and the blueprint review the stage document its `**Input:**` header names, with no bound - a
file from outside the project went into the reviewer's prompt at its provider. Both read through
`contained-path/inside` now; outside reads as missing.

### `bb sigs` reads its spec as EDN (plan 6.11, item 8, register row 175)

The review's fourth finding: `sigs/-main` read the spec on stdin with `read-string`, so `#=` in it ran
on the host. LOW-MEDIUM by Fable 5.1's reading - a blueprint's fences are read with `edn/read`, which
refuses `#=`, so only a spec written by hand reaches it - and one word to fix: `edn/read-string`, the
only `read-string` that was left in `harness/src`.

### A model's file tools check a path by its parts (plan 6.11, item 8, register row 174)

The review's third finding: `tools/within?` compared strings, so a sibling of the worktree whose name
starts with the worktree's passed, and a symlink was followed out; the security reviewer's `search` and
`write_test` did the same. The code reviewer has `read_file` alone, so for it this was the whole
boundary - a link in a project's repository put any file the person can read into its prompt.
`read_file`, `write_file` and `edit_file` now go through `contained-path/inside`, an absolute path
said as one; `search` refuses a path outside and leaves out a file linked outside; `write_test` does
not write through a linked folder under `test/`. `nrepl_eval` is still a shell, as `tools.clj` says.

### The stub is written only inside the worktree (plan 6.11, item 8, register row 173)

The review's second finding, MEDIUM-HIGH by Fable 5.1's reading: `driver/start!` wrote the Tester's
stub to the spec's `:files/impl` path, on the host, before any sandbox, and nothing read the path - a
blueprint, which a model writes, could name `../../../.githooks/pre-commit`. `stub/check-files`, which
`driver/start!` and `blueprint/spec-for` run before anything is paid for, now refuses a blank, absolute
or `..` path by the entry, beside its refusal of a second file; `write!` writes through
`contained-path/inside`, so a folder of the worktree linked elsewhere is not written through either.

### The KIT's own security review, and a finding's paths read only inside (plan 6.11, item 8, register row 172)

The person asked for a security review of the KIT itself, by Gemini 4 Argon at Google's own API.
`bb security-review` is built for an application from the template - `bb serve`, `clojure -X:test`, a
request tool - and the KIT has none of those, so a local driver reused its parts: `agent/converse!`,
the review's `read_file`, `search`, `write_test` and `run_tests`, its findings format, over a clone of
the KIT at `ef9a93c`, the tests run by `bb` in the toolchain image with no network. One tracer pass,
high effort, 92 files in scope (`harness/src`, `tools/`, `.githooks/`, the `bb.edn` files), the rest
readable: 38 completions, 102 tool calls, 12.2 minutes, about $1.49 - computed from the usage (337,863
tokens in, 7,282,837 read from the cache, 8,416 out) at Argon's list price, since Google's API reports no
cost and the driver's role carried none (row 169's "cannot be said", doing its job). Five findings, all
reproduced; each test failed again in a fresh clone and sandbox. The record stays local, as the KIT's
run records do; the tests come into `harness/test/` under the KIT's names, one commit per finding,
and they are the evidence. Fable 5.1 read the driver (its no-network container had mounted the shared
dependency cache writable; read-only now, and the cache volume removed after the run) and the record.

Before it, the security pack's two scans on the KIT: `secrets --all` finds nothing in 77,042 added
lines; `deps` finds no advisory for the two `deps.edn`, and fails on `jackson-core` that `bb.edn`'s
etaoin brings - waived, row 133: a jar's Java classes are never loaded under `bb`.

The first finding, HIGH: `bb security-route` read the files a finding's `:test` and `:where` name with
no bound, so a security reviewer's answer could send any file the person can read - a workspace's
`.env` - to triage's provider and into `security-routing.edn`, which a build commits. A new leaf,
`harness.contained-path/inside`, is the check every such reader will make: the path compared by its
parts (`fs/starts-with?`, never a string prefix), followed through a symlink to its real path - or its
nearest existing ancestor's, for a file not yet written - and an absolute path refused. `route!` reads
through it; outside reads as nil, as a missing file always has.

### A commit with no read mark is listed (plan 6.11, item 5)

`bb kit-status` prints one more list: the commits since the last tag, merges aside, that no line of the
plan's Task Status marks as read - a backticked sha followed, before another backtick, by `(read:`.
Every commit sits on a line, the tag's own and each fix with its own mark, so the check keeps no list of
exceptions (Opus 5.5's read of the design). A sha named only inside another's mark, or only in the
Sessions log, is not marked. Information, never a refusal, and not in `bb tag-check`: a tag is the
person's, and a read is a reading, not a gate.

### The seats: how the KIT itself is developed, written down (plan 6.11, item 5)

Since plan 6.5 the KIT has been developed by two sessions in this folder and the person: one writes,
one reads, the person decides - the method's roles done by hand, outside the loop. It was never written
down, and the record shows the cost: the DEVLOG names Fable 5.1's reads, and no read of a Fable 5.1
diff, though plan 6.5's rule asked for one; nothing can say whether those reads happened. The person
asked for the workflow to be formalized. The kit-session skill has a section, "The seats": a seat is
a role, not a model; the KIT session alone edits tracked files and the plan, gates and commits, and
relays each read to the person, whose go a read's fix waits for; the reading session reads a design
before code and a diff after, may draft a design under `.local/plans/`, and names no tag or plan
number nor takes a relayed message for the person's words. Every commit of a plan's branch sits on its
item's Task Status line with its read mark - `(read: holds)`, `(read: 1 low → fix)`, `(read: declined -
Sessions)` - written by the writer; a finding taken names its read in the DEVLOG, one declined is
written with its reason. Two tabs, never one switching `/model`: the independence is by context, and a
switched tab would read its own reasoning. For the KIT's own development only, Claude Code, never
shipped. Designed by Fable 5.1, read by Opus 5.5 - six points taken: the person's go on every fix, the
reader's drafts allowed and the plan the writer's, the marks the writer's, a scope's commits
unblocked, every commit on a line so the check has no exceptions, and the reader's two standing rules.
Plan 6.11's lines carry the marks from this commit on.

### The amendment's two loose ends (plan 6.11, item 4, register row 170)

Fable 5.1's read of `799b5c3`. `bb next`'s packet arm kept its merge, escalated and running cases
after the run under way had moved ahead of the amendment's gates: unreachable, and three copies of
messages that would drift; the arm is one start now, its reason chosen by whether the packet has a
run. `bb stage-amend` added the amendments key by splicing the text before its last brace, which a
trailing comment carrying a brace would have broken; it is placed on the record's map by rewrite-clj
now, as the other two paths already were, and an entry is written without the commas `pr-str` puts
between a map's entries - a gates record is typed by a person. A `:workspace/time-zone` that is no zone
name still falls to the machine's without a word; `bb doctor` could say it, on the plan's Maybe list.

### A stage amended after its packets ran (plan 6.11, item 4, register row 170)

The first project's stage 5 had every packet merged when the person, walking the site, asked for
arrows into a journey page as well: a Q row, a new requirement, four packets, inside the cap. The KIT
had no step for it - `bb next` asked for the next packet - and the project's session nearly skipped
the blueprint review. The person: iterative development allows such a change, and it goes through the
process again, recorded as a second pass. Method §04 now says when a change is amended into the stage
and when it is deferred, beside the rule that a gate can loop back; an amendment re-runs the four
gates whatever it touched, which costs a plan review on a change that only split a packet, and keeps
one rule. A pass is one entry of `:stage/amendments`, not two lists paired by position (Fable 5.1:
a project that re-approved twice and signed once would be unreadable); `bb stage-amend` writes its
opening, because an opening written by hand after the readings would leave `bb next` asking for them
forever. `bb next` puts a run under way first - a person who walks the site mid-stage is not left with
a merge nobody asks for - then the pass's gates, then a new packet. Each reading is compared with its
gate as an instant (row 171). The cap is one function, `stages/effective-cap`, read by the stage
report and the roadmap, never a sum of passes. The security review read before an amendment is said
as that in the stage's progress, and the stage-end skill reads it again - otherwise an amendment would
be the one way a stage closes with code no security reviewer read. The readings kept only their cost
per reading; the findings now too, a size choice reversed, the money unchanged. New §0 rows of a pass
follow the open-prep rule as any. Nothing to migrate: the key is optional, and a project records an
amendment made before it by `bb stage-amend` and its sign-offs, or leaves it in its status lines.

### Every gate a time (plan 6.11, item 4, register row 171)

The design for amending a stage (row 170) needed to say that a reading came after the amendment that
asked for it, and the gates record had only days. The person decided: every recorded event a
timestamp, shown as a date or a date and time as fits. A gate now carries `:at`, ISO-8601 with its
offset; a record from before keeps its `:date`, read as that day's midnight in the project's zone, or
the machine's. Every reader goes through `stages/gate-instant` and `gate-day`, so the two forms are
read one way and an old record renders the same day; a time is compared as an instant, never as text
(Fable 5.1: `09:30-05:00` sorts before `14:00Z` though it is later). `bb next`'s command text gives a
ready `:at` in the project's zone, and an `:at` it cannot read - a bare date, a typo - is named at its
gate's step rather than thrown. Row 140's edge closes for any stage approved with an `:at`: a run on
the approval day is placed by the instant; a stage with only a `:date` is compared by day as before, so
a build with both forms places runs as it did. The security pack's `:security/at` was a local time with
no zone, the one ambiguous value; it carries its offset. The upgrade's record is a time. Left as days,
being text or ids: the documents' "Last updated" lines, `source.md`'s rows, the lessons' "Closed:",
bake-off ids, and the health record's `:as-of` (row 127).

### A dispatch's completions are not its rounds (plan 6.11, item 3, register row 169)

Fable 5.1's read of 3b found its reprice rule counting a dispatch step's generation ids against
`:step/rounds`, the loop's iterations. A round whose request the provider failed has no completion,
so a dispatch that failed after k completions had k+1 rounds and k ids, and `bb reprice` left it
unpriced for good, saying a completion had kept no id - the kind of step that most needs a late cost.
The step now keeps the runner's own count, `:step/completions` (optional, so an older record renders
unchanged), and the rule reads it; an older record's rounds stand in only for a step that finished.
Tested on a failed dispatch, the same from before the count was kept, a capped one, and a finished
one whose completion kept no id.

## 2026-10-20 (plan 6.11)

### A sum over what was priced says so (plan 6.11, items 3b and 3c, register row 169)

The audit behind 3a found sums presented as whole over parts. A reading - a plan, blueprint, spec or
security review, a bake-off's - wrote the sum of its priced completions as its cost, and `bb
reprice`, which fills a nil, never filled it; now a reading's cost is nil unless every completion was
priced, as a dispatch's already was, and the reading keeps its completion count, so a reprice leaves
a call that kept fewer ids than completions unpriced rather than summing part of it. A run with
nothing priced recorded `:run/cost` 0, which reads as free; it is nil. Where a sum is over some parts
the line says how many were not, and only then, so a whole record renders as before: the run
report's spec readings and direct providers, the stage report (a new "Unpriced:" line), the stage
progress, the security panel's history, the roadmap's money, the stop's reviews bought, `bb balance`,
the bake-off table, which also drops its per-dollar figure over part of a cost. The token figures say
their coverage the same way (3c). A randomized test drops figures - always, or at random - from a
run's steps, balances, usage and readings and from a bake-off's records, three hundred times, and
asserts every renderer renders. Against the first project's build: its stage reports and progress
sections unchanged; one published run table gains "Tokens cover 6 of 7 dispatches", a step priced
with no token count.

### A money figure a provider leaves out never throws (plan 6.11, item 3a, register row 169)

The first project's key had its limit unset between a run's start and its record, so the balance at
record carried `:limit nil :remaining nil`, and `bb report` threw subtracting one from the other:
report-check could not render the run's table and the workspace gates went red. The person asked for
the KIT to take any money figure missing, from any provider, always, sometimes or at random. 3a is
the throws. The run report's balance line (`report/balance-line`) keeps its wording when both ends
have a remaining, so every published table re-renders byte for byte; otherwise it takes the key's
spend from the `:usage` difference, which a key has with a limit or without, and says the end with no
limit; with no usage either it says the spend cannot be said. An end unavailable or not taken no
longer hides the other end. A provider's answer that is not JSON, or has no data, was a throw at
three places - the balance (`bb balance`, a run's start and `run`) and a generation record (a
dispatch already paid for); now it is unavailable, with the reason, and a call that failed says why
rather than "HTTP 0". `bb balance` says a key with no limit instead of "remaining of its — limit".
The first project's record renders: "OpenRouter key $7.70 at start → with no limit at record: $0.15
spent by the key, from its usage".

### A prep row's parts as sub-bullets (plan 6.11, item 2, register row 168)

The first project's stage 5 blueprint had a §0 prep row of nine parts - the rules changes, the
data conventions, the sync and its check, two icon files, the gates, the commit, a note on
`layers.edn` - joined by semicolons, and the roadmap page's "Waits on you" printed it whole in
bold: the person read one run of words. Now only the row's id is bold; its first part follows, and
each further part, split at "; " (`where/prep-parts`), is a sub-bullet under the line - on the page
and in `bb next --where --full`, each wait rendered by one pure function (`wait-li`,
`wait-line`). The blueprint template's §0 says a prep cell is one sentence or short parts joined by
"; ", and a longer list goes under the table.

### The build ignores what the application does (plan 6.11, item 1, register row 167)

The first project's build tracked three `.DS_Store` files: `bb init` made the build repository
with no `.gitignore`, where the application's, from the template, and the KIT's own both ignore it.
`bb init` now writes the build's `.gitignore` - `.DS_Store`, and `.local/` for working material -
and `bb upgrade` has a step, `build-ignore`, that adds the lines a build's file lacks (the file
written when there is none) and commits it in the build. A `.DS_Store` already tracked is named and
left tracked: untracking a file is the person's. A `.gitignore` never committed stops the step as
"not committed", one with edits as "uncommitted edits" (the code review's nit).

## 0.6.10 — plan 6.10's boundary: the security reviewer panel, and a stage's progress from its records

Plan 6.10 added what the first project's third and fourth stages asked for: the security reviewer
as a panel - `:security-panel` in the profile, `bb security-panel` running the next member, two or
all of them, the Coder's family left out and said, a skipped review the person's and recorded, and
all of them at pre-release; one run-loop command at a time on a run, and `record
--rebuild-from-log` for a state that lost what a second one wrote; a rule to every role that what a
visitor submits is the plan's to keep, never the application's log; a stage plan's progress
section written from its records, `bb stage-progress`; the money by provider and by role, in
`bb next --where --full` and on the roadmap page, now titled "<name> - Roadmap Report" and dated,
with the project's display name and time zone from two optional `workspace.edn` keys; `trace` in
the SVG pack, an icon traced from its picture; a stage report counting each cost once; a security
routing's verdict naming its model, provider and channel; `bb sigs` reading a spec's context from
the workspace's application; a run-loop command in a copied or moved workspace reading its own
paths, never the original's; and a record taken again that is the same record. A workspace at
`0.6.9` takes it with `bb upgrade` from the workspace folder, then re-renders each stage report
with `bb stage-report <stage-plan.md>`; a profile's `:security-reviewer` still works, as a panel of
one, until the person moves it into `:security-panel`.

## 2026-10-20 (plan 6.10)

### A record taken again is the same record (plan 6.10, item 9a, register row 166)

The fourth sandbox run of the upgrade, on the first project's latest build, found what the first
three had shown and nobody read: the rebuilt run's table said "Time: 2314m22s total" for a run of
11 minutes, and its commits were the heads of the day it was re-recorded. Only the total had been
checked, and the total was right. A second `record` took a second balance at record, timestamped
then, and the time runs to the last event; and it read the three heads again. `record!`'s
docstring said a record replayed writes the same record. Now it does: the balance at record is
taken once, and the commits come from the run directory's earlier `run.edn` (`record-commits`),
the heads read only for a first record or one from before records named them.

### Never the original's build; `bb sigs` from the workspace; the routing's channel; the roadmap page's date and name (plan 6.10, item 9, register rows 161, 163-165)

The code review of row 161's fix found its failure mode silent: a copy whose build folder was
renamed is no copy `rebased-state` recognises, and read as it stands it acted on the original
again. `driver/foreign-build` closes it - a state naming a build other than the workspace's own
is refused by name, so a command is rewritten or refused, never the original's.

The first project's stage 4 end sent three more. `bb sigs` read a spec's context files from the
KIT's `harness/`, where the front door runs it, so the README's pre-flight reported every file
missing; with no `--dir` it now reads the workspace's application (`workspace/sigs-args`). A
security routing's verdict records the provider that answered and the triage role's family and
endpoint beside its model, so the money by provider places the call. And the roadmap page says
under its title when it was generated - the local time, with the project's own zone beside it when
`workspace.edn` names one (`:workspace/time-zone`) - and takes `:workspace/display-name` for its
title. Both keys are the person's and optional; `init/workspace-edn` writes them when present, so
an upgrade's rewrite of the file keeps them.

The page's title is now "<name> - Roadmap Report" (the person): it shows where the project stands
on its roadmap at the time it was generated - a report, as a stage report is, rendered from the
records and dated. The drawing alone keeps "<name> - the roadmap", and the command and its files
keep their names. `bb sigs` with a `--workspace` that names no workspace says so and exits 1, as
every other task does (the code review's nit).

### A copied workspace's run reads its own paths; a role's unrecorded model said (plan 6.10, item 9, register rows 161-162)

The first sandbox run of the upgrade to this release, on a copy of the first project's workspace,
rebuilt a run's record from its log - and the record was copied into the ORIGINAL workspace's
build. `start` keeps the run's config in `state.edn` with absolute paths, and every later command
reads that config, so in a copy `record` wrote to the original and `teardown` would have removed
its worktrees. One uncommitted file; the person undid it.

`driver/rebased-state` reads the state in the workspace the run now sits in: when the build its
config names is another folder of the same name, every path under that workspace's folder - a
gate's command too - is rewritten under the current one's, and the command says so. A workspace
that moved is read the same way. Tried on the copy: the record landed in the copy's build, the
original untouched. A test holds the rewrite, a sibling folder sharing the prefix left alone.

The same run's fuller account printed `S4 ` with nothing after it for the stage 4 security
routing, a record written before routings kept their model. `where/models-by-stage` says "not
recorded" there, on the roadmap page too, as the provider table already does. Test.

## 2026-10-19 (plan 6.10)

### An icon traced from its picture, the SVG pack's `trace` (plan 6.10, item 8, register row 160)

The first project drew a set of icons from its pictures freehand three times, and each round failed
on proportion and stroke; then it wrote a tracer that follows the picture's own strokes, and its
owner approved the sixteen icons it traced. The script was that project's: each icon's settings and
some hand-measured geometry in its code, absolute paths, BMP input made by a macOS tool. It is now
the SVG pack's `trace`, a rewrite that keeps the fitting. `svg-trace` is pure: a picture's ink (colour
on white, or darkness for black line art) upsampled, blurred once and thresholded; Zhang-Suen
thinning to the centre line; the skeleton cut at its junctions into chains; each chain fitted as the
simplest shape that holds - a line, a circle, a Kåsa arc, else a smoothed, simplified spline; the
whole fitted to the 24-unit grid with only `path` and `circle`, no fill, `currentColor`, round caps
and joins. It runs under bb, so its tests are in the KIT's gates, on pictures drawn in the test - a
ring a circle, a bar a line, a pen's arc an arc, black line art read by its darkness and not as
colour, a region left out and an element added. `svg-trace-command` is the JVM's edge: PNGs read
with `javax.imageio` from an EDN file of icons, each with its settings, `:exclude` regions and `:add`
elements in the picture's own pixels, and written as each icon, its trace over its picture, and a
contact sheet of them all to judge before any is used. Checked end to end on a PNG drawn on the JVM -
a circle, an arc and a line found as such - and the result held by the pack's own `check`. The
first project's own check, its sixteen icons byte for byte, is that project's to run.

The person asked why no fill, and the first project's icons answer it: 18 of its 99 carry
`fill="currentColor"` on an element - dots and discs - with `fill="none"` on the `svg` alone. So an
element may be filled: a short stroke standing alone is a dot (`:dots?`), drawn as a circle filled at
the stroke's width, a small blob whose skeleton is one point included; and a shape the picture
fills, which tracing thins to a line, is added by hand with `:fill true`. Fable 5.1's read found three
small things, fixed with it: the overlay names its picture with `xlink:href`, which the pack's own
`check` (Batik) reads as browsers do - it had said the overlay did not render; an added arc's end is
inside the fitted box; and lengths are in units of the picture drawn 600 wide, not the region.

### The money by provider and by role (plan 6.10, item 7, register row 159)

The person asked where a project's money went - to which makers' models, through which channel,
by which role, with the model each ran on - on the roadmap page and in the fuller account. The first
project built its own table from the records and it disagreed with the stage reports three times:
the stage report counted a spec reading two records carried twice (fixed, row 152), the project's
own table counted a continued run's triage call twice, and a run had lost a step from its record
(row 147, now recoverable). So the breakdown is the stage report's own items: `cost-rows` lists
every priced item of a stage - each run's priced steps, its spec readings once each, the stage plan
and blueprint reviews, stage 0's project plan review, each security reviewer's record, the
security routing's triage calls - as role, model, maker and channel, from the same records its
money adds, and a test holds that the rows sum to the stage's money. On the project's records,
read without writing, every stage's rows sum to its stage report to the cent. The maker is read
from the model's name, the channel from the step's provider or the reading's endpoint (a provider's
own API is "direct"), and a model has one name whichever route reached it (`claude-sonnet-5-5` and
`anthropic/claude-sonnet-5.5` are both `claude-sonnet-5.5`). `bb next --where --edn` carries each
stage's `:by-maker`, `:channels` and `:by-role`; the fuller account and the roadmap page show the two
tables after the road and after "Money and time", each stage's total its stage report's. The
security routing now records the model that routed each finding; an older routing says "not
recorded". Two ways of reading a security review folder became one, `security-review-records`, so
the reading, the money by role, the panel and the progress section all count the same files.

Fable 5.1's read, checked against the person's mock-up cell by cell, found two small things, fixed
after: a total over stages is rounded once, from the unrounded figures, as a stage's own total is (a
sum of rounded cells said $5.28 as $5.29); and a run's maker and channel come from its record's own
word first - each role's family and endpoint, `:run/roles` - the model's name the fallback, and a
call with neither provider nor endpoint recorded says "not recorded", never OpenRouter by default.
The tables keep the model's ID, not the name people say it by: one model, one name, checkable with
`bb models`.

### A stage plan's progress, written from its records (plan 6.10, item 6, register row 158)

The first project ended every stage plan with a checklist - a line per step of the task list and the
exit criteria, ticked with its date and the commit or record that proves it - and the person wanted
it as the project's standard. Ticked by hand, most of those lines repeat what the gates record and
the run records already hold, and a hand tick can disagree with them. So every stage plan template
now ends with the section, "Progress — checked off as the stage runs", in two parts. `bb
stage-progress <stage-plan.md>` writes the upper one from the records, between two marker comments:
the approval with its cap, the blueprint reviewed and signed, each §0 prep row before the first
packet it blocks (`every task` rows first, rows that block the stage's end at its end), each packet
in the blueprint's order with its merged run, the run's whole money, its rounds and the application's
commit, the security pack and its two scans, the security review's members or its recorded skip, the
threat model at pre-release, the stage closed. Below the end marker are the person's own lines - the
interaction checks, the browser pack, the owner's walk, the written records, the tag - ticked by hand
and kept across every render. A rendered line is ticked only by a record, so the section is never
wrong, only behind: `--check` names the plans behind their records, and the workspace gates run it
as information, never as a failure, since a gate would go red after every merge. A plan whose
section was written by hand, with no markers, gets the rendered part placed above its own lines,
which are left for the person to prune. On the first project's stage 4, read without writing, the
rendered packet lines carry the same money as its hand-written ones; its rounds read higher, since
the KIT counts one plus each triage event, as `bb report` does.

Fable 5.1's read found three small things, fixed after: a per-run §0 row is done again before every
run and never closes, so it is printed with no box; a security review that was declined or left no
answer ticks nothing and is said as that, as the stage-end skill holds; and the section says what a
round is, so the reader of a stage plan does not file the KIT's count as a defect.

### What a visitor submits: what a feature that takes it keeps is decided before it is built (plan 6.10, item 5, register row 157)

The first project's stage that added search settled with the person what search keeps - the words
counted by day, never who; never in the application's log; kept as typed; a command for the report -
and its plan review caught five things the plan had not amended. Then the person asked about a box
the law needs kept, "I am 18 or older", and that is the opposite case: who confirmed, and when, kept
on purpose. One principle covers both, and it is the method's new section beside security: what is
kept of what a visitor submits, and in what shape, is the plan's decision, and the application's
log is never the store - a confirmation "kept" in a line of it is rotated away, and a search query
in it sits beside an address and a time. The section asks the questions in writing, gives the two
worked examples with their opposite defaults, and lists what the plan review otherwise finds. The
stage-plan skill asks it of any stage taking what a visitor submits, and the plan and stage-plan
skills read it.

And a rule, because the slip is the code-writing roles' and only the rule source reaches every
family: what a visitor submits - words typed, a box checked, an answer given - reaches no line of the
application's log, and what the project must keep of it is a record its plan decides. The rule names
the record so a Coder does not read it as "never record this". The architecture template says the
pin's exception line never carries the query string - checked on the template at `kit-v1.3`, which
logs no request at all and whose exception line carries the method and the path - and its *what it
holds* row names what a visitor submits. "The application's log" is said in full: a bare "log" is the
decision log's word, now in CLAUDE.md's vocabulary. Fable 5.1 drafted the section and the rule; the
person widened the rule from a visitor's words to all a visitor submits, and added the attestation.

### The stage's end offers the panel; the method says it (plan 6.10, item 4, register rows 154-156)

The last part of the security reviewer panel is what a person meets. The kit-stage-end skill reads
`bb security-panel --history` and what the stage changed (`git diff --stat` since the stage before),
then makes one offer in the person's words - the next member in rotation, another, two, all of them,
or skip the audit - naming the option it recommends and why; a pre-release stage offers no skip,
all of them its default, and the session says whether fewer would do. After the run it reads the
combined table and the routing with the person and puts one decision on each row to them - fix and
retest, the Architect, or their own review - triage's route being its proposal. The pre-release
template's exit criteria read with the whole panel; the method's security paragraph and roster, the
harness roster, the workflow table and the security pack's README say the panel. Row 154 is fixed;
two things the item found and did not fix are rows of their own - a bake-off candidate takes only its
family's route (155), and a finding naming two tests in one field is routed with none (156).
Fable 5.1's read of the documents found four small things, fixed after: `bb profile` on a profile with
its one `:security-reviewer` says a `:security-panel` adds members - the only hint an adopter has
before the upgrade hand-off; `--history` marks a computed cost ~; `bb next` refuses a pre-release
stage's skip before it would ask the skip for a name; and the skill's per-reading costs are the ones
this log carries. The offer keeps the person's own words, "skip the audit", for the security review.

### A skipped security review is the person's, and written (plan 6.10, item 4, register row 154)

At a stage before pre-release the person may skip the security reviewer panel, the session
recommending from what the stage changed. A skip is a decision, so it is written where the stage's
other decisions are: the gates record's `:security/skipped`, `{:date :by :reason}`, a key the
gates template now carries with a comment and no mark - a workspace takes it as a build-docs hunk at
its next upgrade. `bb next` holds it to a name as it holds a sign-off, and refuses it at a
pre-release stage, which reads with every member: remove the key and run `bb security-panel --all`.
The stage report says it where the review's money would have been: "Security review: skipped (who,
date): why".

### The security reviewer panel's run and its combined table (plan 6.10, item 4, register row 154)

`bb security-panel <clone>` runs one, some or all of the panel's members, each reviewing its own copy
of the merged application as `bb security-review` does, two at a time, each record written into the
stage's security review folder under the member's name and carrying it (`:member`). With neither
`--members` nor `--all` it reads with the next member in rotation, which it says before anything
runs: the member after the one that read the latest stage, read from the stage folders' records - no
file of its own to drift from them, and a skipped stage moves nothing. A member of the Coder's family
is said and never run; naming one is refused with the reason. Then the combined table,
`security-panel.edn` and `.md`, from every record in the folder: each reproduced finding keyed by the
place it names - the files its `where` names and the functions after each file's colon - so two
members' findings of one place are one row, saying who reproduced it and who ran and did not; the
hypotheses apart; a member that refused or gave no answer counts for nothing. Agreement decides
nothing - one member's failing test is a finding - and the key's limit is said in the table (one
flaw named at two places is two rows). `bb security-route` takes the table as it takes one record:
one triage call per row, its test read from the record that carried it. The stage report leaves the
table out of the security review reading, as it does the routing file. Two things from Fable 5.1's
reads ride with it: a review record says how its cost was got (`:cost-source`), a computed cost
marked ~ in the table; and `bb balance` reads every role block a profile configures, a panel's
members among them, where it read the eight roles only.

Fable 5.1 read the commit against the design and re-rendered the table from real records - the
KIT's bake-off readings and the first project's security review folders - and found the key doing
what the design forbids: a finding naming files and no function keyed by its files alone, so an
insecure direct object reference and an SQL injection at one file pair were one row, and the table
printed only the first title. A row now merges only on its files AND a function they name; any other
finding is its own row, since splitting never hides a finding. A merged row prints every title, a
member's two at one place included; a member that refused says so in its column; a re-rendered table
keeps its stance and the clone's commit; the exit says whether this run's members answered, not the
folder's; `--history` gives each stage's members, reproduced findings and cost. On the bake-off
readings: 18 reproduced findings, 17 rows, every title shown.

### The security reviewer panel, in the profile (plan 6.10, item 4, register row 154)

The person's decision: in place of one security reviewer at every stage's end, a panel of them, run
one, some or all at a time, rotated by default. Its first part is the profile. `:security-panel`, a
list of security reviewers in rotation order, stands in place of `:roles :security-reviewer` - one
or the other, never both - so a member is added by configuration and never in code; a profile with
its one `:security-reviewer` reads as a panel of one, so every profile written before reads
unchanged. A member is known by its `:name`, its model slug's last segment unless given, so a member
whose route moves keeps its history. A member of the Coder's family is left out of every choice and
said, not refused - the panel outlives the Coder; a panel with no member outside the Coder's family
is refused, as a lone reviewer of that family always was. Every reader of the eight roles sees the
first eligible member as the security reviewer (`profile/as-roles`), `bb doctor`'s missing-role line
counts a panel as the role, and `bb security-review --profile` takes the first eligible member. The
two shipped examples carry GPT-6 Astra and Gemini 4 Argon, Argon left out since it is their Coder -
the rule shown, said in their comment. Fable 5.1 is not a member: it declined the review on both
routes (below).

### Fable 5.1 declines the security review at Anthropic's own API too; a refusal there is said as one (plan 6.10, item 4, register row 153)

The security reviewer panel's design put Fable 5.1 beside GPT-6 Astra and Gemini 4 Argon, and it had
declined the review three times through OpenRouter (row 121). So its bake-off ran first, on the
planted-fault fixture, at Anthropic's own API: it declined both readings, the response's stop reason
`refusal`, category cyber, after 4 and 14 completions - $1.12. No prompt was reworded. It is not a
panel member. Two KIT faults came out of it. `bb security-bake-off` builds a candidate from the
family's route only, so the direct route was run from a script over the bake-off's own reading. And
the refusal was recorded as no answer: at Anthropic's own API a refusal is the response's
`stop_reason`, which the adapter makes the call's error, with no completion step whose finish reason
`refused?` reads. `agent/refused?` now reads the call's error too; the security review passes it, and
triage asks it before calling a failed call failed - a triage refusal there had been said as a failed
call. The same bake-off filled bake-off-3's empty money from the generation records - the night it ran,
OpenRouter's generation endpoint answered nothing (row 129) - with `bb reprice` and the report
re-rendered: Argon $0.58, Astra $2.54 for the two readings - by reading, Argon $0.11 on the careful branch and $0.47 on the faulted, Astra $1.35 and $1.19, the figures the stage-end skill quotes.

### A spec reading two records carry is counted once (plan 6.10, item 3, register row 152)

The first project, building its own cost tables from the records, found its stage 2 report counting
16 spec readings where 14 were taken: a task's later run starts from the spec the earlier one
reviewed, and its record carries that review's history again, so the stage report - summing each
run's readings - counted the shared ones per record. A stage's readings are now taken once, by their
completions' ids, or by their time and model where a reading has no ids; one with neither is its own,
as it was. On the project's stage 2 records: 14 readings, $1.04, where the report said 16, $1.20.

The same report said a `:continued` event's cost - an orchestrator's call - was in no stage total.
It is: that cost is the loop's triage call, which is the run's `:triage` step, priced and counted
with the orchestrator's money; the event carries a copy of the verdict. A table that added the event
too counted the call twice. A test now holds a continued run's triage call to one count, so a later
"fix" cannot add it again.

### Four small ones from the first project's stage 3 (plan 6.10, item 2, register rows 148-151)

`bb self-check` in a workspace's clone ended with `next bb init`, a fresh clone's advice; it now
names the workspace's next step when a `workspace.edn` sits above the clone. kit-where-am-i offered
the fuller account first and listed the roadmap page last of eight lines to open, never saying it
was HTML, and the person asked whether the skill made a page at all: one offer now follows the
block, three choices in the person's words, the roadmap page the third. A §0 row whose Blocks named
a packet by its short id - `t-01`, the ID the blueprint template defines - was read as blocking
nothing against `t-01-crop-map`, and `bb next` offered the packet's run with the row open;
`blocks?` matches the `t-NN` too, and a row naming a `t-NN` that is no packet is said beside the
step. The first design said every row naming no packet, and the project's next blueprint showed
why not: three rows block "the stage's end", a row of another kind, so only a `t-NN` that matches
nothing is said. And the targets gate, which since row 145 counts an earlier packet's marker for
nothing, now says the marker is there - the test it stands over - instead of "named by no test",
which had sent the Architect and the triage to the comment's formatting. Fable 5.1's read of the
diff found two small things, fixed after: an earlier declaration's detail says to declare it anew
(there is no test to mark anew), and row 150 says the mistyped-id line is `bb next`'s alone - it
reads a blueprint's §0 and its packets on every look, and `bb plan-check` reads neither.

### One run-loop command at a time on a run (plan 6.10, item 1, register row 147)

The first project's stage 3 lost a Tester dispatch from its record. A `retry` was typed while a
backgrounded `run` was going: the retry appended its triage and its dispatch to `events.log` and
wrote `state.edn`, then the loop, which had read the state once and kept it in memory, wrote its
own over it. The log kept both events; the state lost them, and with them the dispatch's step,
which was in the state alone. `record` refused the drift, as it should, and no command could
repair the run: a hand repair restored the events, and the step's cost stayed lost.

Two changes. Every `bb run-loop` command now holds a lock on the run directory,
`run-loop.lock`, from `orchestrate/-main` - at the command, not at each write, since the loss
was between two whole commands. A second command is refused with what to do (a `run` stops by
itself for a decision, and a hand `retry` is for after it stops); a lock whose process is gone,
or whose pid a later process reuses, is taken over and said. And the log can now rebuild what
the state loses: a dispatch's log line carries its report step, and `record` on drift names the
events the state lacks and offers `--rebuild-from-log`, which rebuilds the events, every
missing dispatch step - placed before the next logged dispatch's step, so the gates the loop
ran before it stay before it - and the attempts, keeping the old state as
`state.before-rebuild.edn`. A log from before this has no step: a direct provider's dispatch is
priced from its logged usage at its logged list prices, anything else is left unpriced and
said. On the first project's run that prices the lost Tester round at $0.0736.

Fable 5.1 read the scope first: the placement, building the step through `report/dispatch-step`
rather than a second constructor, the refusal saying what to do, and the lock's docstring saying
where it is and is not. Its read of the diff found two small things, fixed after: a holder that
finished between the failed take and the read no longer reads as a lock left behind, and the
`run-loop` task's line, which a workspace's `bb tasks` shows, names the lock and the rebuild.

---

## 0.6.9 — plan 6.9's boundary: a long build's second stage

Plan 6.9 fixed what the first project's second stage found: a retry checks its triage file before
it counts the round; a role can run on Google's own endpoint, `:google-direct`, with the thought
signature Gemini needs returned on its next call, an array error read, the prices it has, and its
spend said apart from OpenRouter's; a target names data and never carries it - method §06 rule 15,
§0 widened to the files a task reads that no role may write, and `bb spec-from-blueprint` saying a
spec's size; the targets gate counts only the markers this Tester added, where an earlier packet's
markers in the same test file had failed it and, worse, covered it; and a stage's security routing
is its own reading, not a second review. A workspace at `0.6.8` takes it with `bb upgrade` from the
workspace folder, then re-renders each stage report with `bb stage-report <stage-plan.md>`, since a
stage with a routing file now reads one security review and its routing apart; a role moves to
Google's endpoint only when a person copies the `-direct` route into the profile.

## 2026-10-17 (plan 6.9)

### A stage's security routing is its own reading, not a second review (plan 6.9, item 4b, register row 146)

The first project's stage 2 report read "security review 2" where one review ran: the stage
report's folder reading counted every `.edn` in the stage's security review folder, and
`bb security-route` writes `security-routing.edn` there. The total was right only because that
routing had triaged no finding - and the triage calls a routing does make, each verdict carrying
its cost, were in no reading at all. Now the folder reading leaves the routing file out, and the
routing file is its own reading, "security routing", its cost the verdicts' and its count the
findings triaged. The fixture's routing file makes the old report read three reviews. On the first
project's stage 2: "security review 1", "security routing 0". Also a sentence in the targets
gate's docstring (item 4's read, Fable 5.1): after a merge, `check` on that run would read every
marker as earlier, and nothing in the loop checks after a merge. Built by Opus 5.5.

### An earlier packet's test markers are its own (plan 6.9, item 4, register row 145)

A fix packet of 5 targets named, as its `:files/test`, the file an earlier packet wrote with 13.
The targets gate read every marker in the file as this packet's: "a marker names target N and the
spec has 5" came eight times, and the project's workaround was a target telling the Tester to
renumber. That was the loud half. The quiet half: the earlier `;; target 1` to `;; target 5` counted
as this packet's coverage, so a Tester that wrote no test would have passed. Now `targets/coverage`
reads each test file as the gate worktree's HEAD has it - the base, until the merge commits - and
leaves out a marker that already stood there over the same test (or with the same reason): neither
coverage nor an unknown target, reported as `:earlier` in the targets event. Only what this Tester
added counts, and the markers are counted rather than matched, so an old test marked anew with the
earlier packet's own number counts once for this packet (Fable 5.1's catch, before the commit); a
file the run created reads as before. The Tester's instruction says the markers
already in a file are an earlier packet's, to leave them and mark its own. Not taken: renumbering
(it erases the earlier packet's accounting) and task ids in the markers (every marker a project has
is bare, so it fixes nothing already written, and it rests on every model writing the id). Four
tests on a real repository; three fail on the old gate. Designed and built by Opus 5.5.

### Data a target needs is a file placed before dispatch (plan 6.9, item 3, register row 144)

A packet carried thirty icons' SVG geometry in its targets: a 38 KB spec, where every other spec the
first project dispatched was 10.2 KB or less. One Coder hit its provider's timeout twice; a faster
one spent its rounds retyping the geometry. As files placed before dispatch and read at load, the
spec was 9 KB and the packet ran. The method had no word on where bulk data goes, and no dispatched
role may write outside its target. Now method §06 rule 15: a target names data, it never carries it -
geometry, long fixtures, copy, tables, images are files a §0 row places, and the target names the
path and what the code does with it. §0 is widened to say so (the files a task reads that no role
may write, placed by the person or the Architect's session), in §06 and in the blueprint template;
the blueprint review asks it with no code change, since its checklist sends §06's rules whole.
`bb spec-from-blueprint` prints a spec's size and, past 16 KB, says such a spec usually carries data
a §0 row should place - information for the Architect, never a refusal; §07 says so. Not taken: a
rule for the Coder (the fault was the packet's), a hard limit (a large spec can be right), a token
count (bytes are what `wc -c` checks). Drafted by Fable 5.1, reviewed and built by Opus 5.5.

### Gemini at Google's own endpoint: the signature returned, a route, the prices it has (plan 6.9, item 2, register rows 142 and 143)

The first KIT project moved its Coder to Gemini at Google's OpenAI-compatible endpoint, and every
dispatch failed at its second completion with HTTP 400: Gemini returns each tool call with its
thought signature under `extra_content` and refuses a history that comes back without it, and the
adapter kept a call's id, name and arguments only. `adapter/parse :openai` now keeps a call's
`extra_content` and `assistant-message :openai` returns it on the same call - the way the
`:anthropic` shape returns its raw blocks - while the transcript and the run log leave it out. A
provider that sends none is unchanged. Not taken: Google's validator-bypass token, which passes
the check by dropping the model's reasoning between calls, and a native Gemini request shape, which
the compatible endpoint does not need. Google's endpoint also answers an error as a JSON array, and
the adapter had logged such a 400 as "no message"; an array is read as its first error. With it,
`:google-direct` in `resources/routes.edn` (Google's endpoint, `GEMINI_API_KEY`, no provider to
pin), the role schema's cache-write prices optional - Gemini charges none, and the project had
written its input price there to satisfy the schema - a direct dispatch to Google recorded as
"Google AI API", and `bb balance` naming every direct provider's spend, not Anthropic's alone.
Proved on a real dispatch at the person's word: a Coder on `:google-direct` (Gemini 4 Argon) ran a
project packet's dispatch through 5 completions and 4 tool calls with no 400, recorded as "Google
AI API" at list price (~$0.05), no signature in the transcript or the run log.

The code reviewer's reading of it found four more. A run record's money lines summed every step
not on Anthropic's API as OpenRouter's, so a direct Gemini step went into "summed from this run's
OpenRouter steps" and set off the two-cent drift note; the line beneath named Anthropic's spend
alone - the defect `bb balance` had just lost, one file over. One test now says which providers are
direct (`provenance/direct-provider?`), and the record prints a spend line for each. The new route
was said to let `bb models` build the role; nothing reads a `-direct` route - the family comes from
the slug's prefix - so the route is what a person copies into a profile, and row 143 and the routes
file now say so. The signature's round trip had a test of the adapter's two halves only, where the
defect was in the loop between them; a stub conversation now holds it, and fails on the old
adapter. And a cache-write rate written as nil, which `:or` does not cover, is no charge too.

### A retry checks its triage file before it counts the round (plan 6.9, item 1, register row 141)

`bb run-loop retry <run-dir> <role> <triage.edn>` read the file and recorded the `:triage` event
and the round before anything used the feedback, so a file with a bare string where a feedback item
belongs crashed the dispatch after the round was spent: one of the first KIT project's Coder rounds
went on nothing. The stop's printed hint had shown the file as `{:decision "..." :feedback [...]}`,
never an item's keys. A triage file is now a shape, `shapes/TriageDecision` - a decision, and
feedback items that are `Feedback` maps - checked first in `retry-with!`, which a person's retry and
triage's routed one both go through: a file that is not one, or not EDN that reads, is refused with
a sentence that shows the shape, nothing written and no round counted. The hint prints the shape
whole.

## 0.6.8 — plan 6.8's boundary: orienting the person

Plan 6.8 added what a person needs to find their place in a long build: `bb next --where --edn`,
where the project stands as one map read from the build's files; the fuller account from it,
`bb next --where --full`, and the roadmap page, `bb roadmap-page`, the road drawn with every stage
by its state, its spend against its cap and its open decisions and risks; `bb last-look`, from
which "since you last looked" counts; `kit-where-am-i` offering the account and the page, at a
stage's end, a stage's pull and a cold open. With them, a run is counted in its own stage - a run
record names its stage, an older one is placed by date - where a task id a later stage reused had
made `bb next` read unrun packets as merged; a stage's total counts its security review; the
block's read more names where a phase, a step and the project plan are defined; the gates
template's comments carry no mark; and the vocabulary rule's other half, a name says what the
thing is. A workspace at `0.6.7` takes it with `bb upgrade` from the workspace folder, then
re-renders each stage report with `bb stage-report <stage-plan.md>`, since every total now counts
the security review and only the stage's own runs.

## 2026-10-17

### A run belongs to its stage, not to every stage with its task id (plan 6.8, item 2c, register row 140)

A task id is its blueprint's, and the first KIT project's stage 2 reused five of stage 0's and
stage 1's. A run was placed by its task id alone: `bb next` read four stage 2 packets as merged
from the older runs, though they had not been dispatched, and would have pointed past them to the
next; and `bb stage-report` counted runs in a stage that did not make them - stage 0's report took
stage 2's run of the same id, and stage 2's took stage 0's. A copy of the workspace, rendered while
the tag's checklist ran, printed stage 2's figures from five old runs and no one noticed until the
project said so. Now each run records its stage: `bb spec-from-blueprint` writes the stage of the
blueprint's folder into the spec as `:task/stage`, the run record carries it, and `bb next`, the
stage report and where-we-are read a stage's own runs. A record from before is placed by date - of
the stages whose blueprint names its task, the latest approved on or before the day the run started,
on this machine's calendar, since an evening run west of UTC is the next day in UTC; one stage
naming it is that one's; two that no date tells apart, neither. `bb plan-check` lists a task id more
than one stage's blueprint carries, failing nothing. The tests take the project's shape, two stages
sharing ids, and fail on the old code.

### The fuller account offered, and the person's last look (plan 6.8, item 5)

`kit-where-am-i` shows the block, then offers the fuller account and prints `bb next --where
--full` as it comes on a yes; among the lines to read into it adds the roadmap page, which it
draws with `bb roadmap-page`. When the person says they know where the project stands, it runs
`bb last-look --by <name>`, which records the moment in `work/where/last-look.edn`; "since you last
looked" then counts from it. The skill types no record and writes nothing by hand. `method.md`
gains the rule: the fuller account is offered, not given unasked, at a stage's end, a stage's
pull and a session opened cold - `kit-stage-end` and `kit-stage-plan` offer it after the block,
and `kit-start` hands a project under way to `/kit-where-am-i` first. The reports still open with
the short block.

### The roadmap page: `bb roadmap-page` (plan 6.8, item 6)

The project's roadmap, drawn: five lanes, a phase each, the current one tinted and marked "you
are here"; each stage a box in the lane of its phase, coloured by where it stands - closed, in
progress, next and not pulled, ahead - with its spend against its cap as a bar beneath and its
open decisions and risks as badges on the stage that owns them; a line joining the stages in
order. Under the drawing the page says what waits on the person, money and time as a table, what
changed since, what is open one line each, and what is next. Both are renderings of `bb next
--where --edn`'s map, so the page says what the fuller account says. It is written to
`work/where/` and never committed: `roadmap.svg` on its own, for the SVG pack to render with no
browser, and `roadmap.html`, which carries the same SVG inline and fetches nothing, so it opens
from a file. Every link is relative from its folder to the workspace. Drawn on a copy of the first
KIT project's workspace, it renders in the SVG pack with no error, and every link opens a file.
The fuller account's read-more lines name it.
Fable's review of it, three touches to the drawing: the next stage's caption says its cap, the box
and the legend already saying next; the badges sit above a box, clear of its title; and the
drawing's title names the project, as the page does.

### The fuller account: `bb next --where --full` (plan 6.8, item 5)

What a person back after a while reads, rendered by code from item 4's map so its figures are the
files' and the skill that shows it restates nothing: where - the block's first line, then each
phase with its stages and "you are here"; the road, a table of every stage with its kind, state,
cap, spend, days and runs, and the spend against the caps approved; what waits on the person, each
with its command or its file and section; what changed since the date and why that date; what is
open, a line a decision and a line a risk under a heading with the count and the file - the
person's correction at the roadmap page's mock-up, never the block's packed line; next; and where
to read more. A fact the map lacks is said: nothing waiting, no date to count from, a stage's
readings only before its blueprint, the runs' money since a date as runs only. The block that opens
the Architect's reports is unchanged.

### Where the project stands, as one map: `bb next --where --edn` (plan 6.8, item 4)

The block `bb next --where` prints is the Architect's, terse and at a step's boundary. A person
back after a while needs more, and the fuller account and the roadmap page (items 5 and 6) need it
from one place. `harness.setup.where` reads it into one map of plain data: the five phases with
where the person is; every stage of the stage map with its state (closed, running, planned, next,
ahead), its cap read from the gates record or the map's cell, its money and runs from the stage
report's figures - a stage with no blueprint yet has its readings so far - and its days from
approval to close; what waits on the person; what is open, each decision with its subject and
owner, each risk with where it runs and its spike; and what changed since a date. The date is
`--since`, else the person's last look once one is recorded, else the approval of the last closed
stage, since its close date would show nothing of a stage approved and closed on one day. A
decision settled counts by the date its RESOLVED cell carries; most cells carry none, and the map
says how many. The next step is said once: it names the first gate not passed, so no gate is
repeated in the person's list. The map is read through the readers `bb next` and the stage report
already have, never the documents a second time; the clock and the build's commit are passed in,
so a test prints the map and reads it back equal. A stage map cell is prose around IDs, so the IDs
are read out of it, and an owner runs to the sentence's end, past a title's full stop. `bb next`
stays read-only in every form; a KIT kept outside its workspace is now said by its absolute path.

Fable's review of it, four fixes: `--since` is parsed as a date of the calendar, and one that is
not (`2026-13-99`, whose shape passed) is refused with exit 1, as is `--since` without `--edn`,
which the block had dropped without a word; money is rounded to the cent once, in the map, so the
views cannot round it three ways; and the runs' money since a date says it is the runs' alone, a
reading carrying no date to count it by.

### The gates template's comments carry no mark (plan 6.8, item 2b, register row 139)

The gates record's template says, in a comment, what each gate holds once passed; the stage plan's
approval was written `{:date "YYYY-MM-DD" :cap "<$>" ...}`. A copy keeps its comments when its
gates are filled, and `bb plan-check` reads every angle-bracket mark outside a code fence, comments
included, so a stage approved with its cap was reported one mark short. The comment now writes the
cap `"$n"`, as its date and signer were already written, and a test holds the template's comments
free of marks. The same comments sent the reader to "the plan's §11" for the cap and the exit
criteria, which is the section's number only for an increment; they name the section by its
heading now. A stage copy already made keeps its own text.

### The block says where a phase, a step and the project plan are defined (plan 6.8, item 2, register row 138)

`bb next --where` names a phase ("phase C BUILD") and a step ("step 7") and pointed only at
where the project's IDs are defined: a person reading it asked where the phases are described,
where the steps are listed, and where the project plan is. The rule a report keeps for an ID - cite
it with the file and section that define it - holds for the KIT's own words too. `read more:` now
opens with the phase map in the KIT's `method.md` and the steps in its `workflow.md`, under the
workspace's KIT folder as `workspace.edn` names it, and the project plan's start, the overview,
whose section 3 lists the plan's documents. A second table beside `id-kinds`, `kit-terms`, holds
the two KIT headings, and a test holds each to its file; `kit-where-am-i` reads into them.

### A name says what the thing is (the vocabulary rule's other half)

`CLAUDE.md`'s Vocabulary gains the half of the rule that chooses a new name, beside the half that
keeps an existing word's meaning: before naming something new, say in one sentence what it is,
and the name is that sentence's noun phrase, in the words the KIT already uses for its category.
A shortening, an image or a metaphor that only hints at the thing is not a name. It came from a
correction while plan 6.8 was written: "the road page" for what is the project's roadmap, now the
roadmap page.

### A stage's total counts its security review (plan 6.8, item 1, register row 137)

`bb stage-report` summed a stage's runs and its readings - the project plan review, the stage plan
review, the blueprint review, the spec reviews - and said the total against the stage's cap. The
security review at the stage's end was left out, though its record carries its cost: the first KIT
project's figures were short by it at both stages, and its Architect added it back by hand in the
plan's prose. The readings now have a fifth row, "security review", summed over every record in the
stage folder's `stage-N-security-review/` (one per review; the `-tests` folder beside them is not
read), so the total counts it and the readings line names it. A stage plan rendered before this
shows the old total until `bb stage-report` renders it again; the workspace gates hold it to the
new sum until then.

## 0.6.7 — plan 6.7's boundary: a folder per stage, and where the project stands

Plan 6.7 added a folder per stage - `docs/stages/stage-N/` holds a stage's documents and its
readings - with the upgrade moving a workspace's into theirs; `bb next --where` and the
`kit-where-am-i` skill, saying where the project stands, with every ID cited where it is defined;
the vocabulary rule; `bb toolchain-newer`, the toolchain held current; a rules overlay refusing a
key a rule does not have; every spec reading counted in a stage report; and the upgrade's dry run
asking origin. A workspace at `0.6.6` takes it with `bb upgrade` from the workspace folder, then
re-renders each stage report with `bb stage-report <stage-plan.md>`, and adds or decides against
each build-docs hunk the upgrade saved under `work/upgrades/<id>/conflicts/`, most of them a
template's new `**IDs.**` sentence; the one-word defined terms `bb plan-check` lists are the
project's to rename or keep.

## 0.6.6 — plan 6.6's boundary: a template namespace a project removes

Plan 6.6 added one fix: `bb plan-check` passes a template namespace removed with its file - its
`layers.edn` entry and its file under `src/` both gone - which it had refused while the boundary gate
refused keeping the entry, so `start` stopped a stage's first packet. A workspace at `0.6.5` takes it
with `bb upgrade` from the workspace folder; one made at `0.6.4` upgrades by hand the first time, as
`0.6.5`'s line says, checking out `0.6.6`.

## 0.6.5 — plan 6.5's boundary: the upgrade, and the profile measured

Plan 6.5 added `bb upgrade` - each newer release's requirements checked against the machine, the
clone moved, the workspace brought up by the new version's own code - with `bb template-upgrade`
taking a template release through a `template` branch, and the toolchain as data
(`resources/toolchain.edn`); `bb next` and `bb plan-check` reading the plan, and a workspace's own
`bb gates`; a sign-off recorded with who signed; the `kit-` prefix on every skill; the retry cap
spent per role; guidance sent a defined term's definition; the KIT's checks on a port they pick; the
template at `kit-v1.3`; Anthropic's models on Anthropic's own API; and a `claude` profile that codes
with Gemini 4 Argon and tests with Sonnet 5.5. A workspace made at `0.6.4` upgrades by hand the first
time: `git -C KIT fetch --tags && git -C KIT checkout 0.6.5`, then
`bb --config KIT/harness/bb.edn upgrade --after-pull` from the workspace folder. Its `profile.edn` is
the project's and the upgrade leaves it as it is: compare it with
`KIT/harness/resources/profiles/claude.edn` to take the new roles.

## 2026-10-16

### The vocabulary rule: one meaning per bare word, a defined term two words or more (plan 6.7, item 6)

Plans 6.4 and 6.5 tripped over words the KIT gives two meanings - plan, gates, reviewer, name,
run, stage, template - and over a project's one-word defined term. `CLAUDE.md`'s Vocabulary now
holds the rule: a bare word keeps the meaning the KIT already gives it, a second meaning carries
its qualifier every time (the project plan and a stage plan, the gates and a gates record, the
code reviewer and the security reviewer, the application template and the build-docs
templates), and a new identifier reusing a word carries the qualifier. For a project, the
defined terms of its data conventions are what retry guidance is matched against, word for word
in any case, and a matched term's definition is sent with the guidance. A one-word term - WORD,
THE TREE - is matched by every sentence that uses the word. The `:data-conventions`
placeholder says to name each term as a phrase of two words or more, an article aside, and
`kit-plan` says so where the overlay is filled. `bb plan-check` lists a project's one-word
terms after its verdict, failing nothing, beside the inferred requirements and the uncited
observations. The matching is not changed, and a project's terms are its own to rename. No
sweep of older prose.

### `bb next --where` after a stage's end, and what is open (plan 6.7, item 5, Fable's review)

The block was a stage behind at the moment a person most asks where the project is. Between a
stage's end and the next plan, it named the closed stage as the current one - its goal, its
status DONE - while its own `next:` line said to pull the next stage. A closed stage is now behind
the block: it names the closed stage with its date and then the next one, not pulled yet, with
that stage's phase, name, status and goal from the stage map. Its decisions count both kinds not
yet settled: PROVISIONAL and OPEN. The method enters everything not yet decided as OPEN with an
owner, and a list without those would mislead. A long `also:` line, the paths an upgrade left, is
cut in the block (`bb next` prints it whole). Lines are cut at a space. The stage's name is read
from a column whose header starts `Name`. `kit-stage-end` now finds the exit criteria by the
heading's text, as `kit-where-am-i` does: the section is §11 only in an increment's plan.

### `kit-where-am-i`: where the project stands, at any time (plan 6.7, item 5)

The sixth skill, and like `kit-start` not a step: it runs `bb next --where`, shows the block as
printed, then asks which line to open - the stage's goal with its exit criteria (found by the
heading's text, whose number differs by kind), what is done, what is next, what is open, an ID,
the stage map - and answers from the file that line names, every ID with the file and section
that define it. It writes nothing. Listed where the skills are: `skills/README.md`, the README,
`workflow.md`'s head, the harness's task table; the workspace's `CLAUDE.md` names it, and
`kit-start` hands over to it once a brief is filed. A workspace gets it by the upgrade's skills
step and its orientation step.

### A report opens with where we are, and cites an ID where it is defined (plan 6.7, item 5)

The rule is written once, in `method.md` §04: a report of the Architect's session to the person -
a stage plan for approval, a blueprint for sign-off, a stage's end - opens with the block `bb next
--where` prints, as printed, and every ID it cites carries the file and section that define it,
never one that only cites it. `workflow.md`'s steps 7, 8 and 14 say so, and `kit-stage-plan` and
`kit-stage-end` begin with it. Each template says what it defines where it defines it, in a
sentence opening `**IDs.**` - the requirements' index and NFRs, the source's observations and
questions, the decision log's five sections, the stage map, the risks, the findings, the threat
model, the blueprint's human prep and packets - in the form the file writes its IDs (`D1`,
`P0-n`). The test of `id-kinds` now asks for that sentence in each section it names. Plain prose,
not a blockquote: the plan check reads a template's blockquote as an instruction to delete, and
these stay. A project's documents are filled, so the upgrade's build-docs step cannot place most
of these sentences by context; it saves them as conflicts, one line each to add by hand.

### `bb next --where`: where the project stands, in one block (plan 6.7, item 5)

The first project's person, at stage 1's plan step, read a report citing FR-8 and S-3.2 with no
file, and S-3.2 is cited in many files and defined in one. They asked for a bird's-eye view of where
the project is in the process, and a guided way into the details, at any time. `bb next --where`
prints it, from the files `bb next` reads and three more: the phase (`method.md` §01, by the stage's
kind) and the stage with its name from the stage map; its status there; its goal, the map's
Delivers cell, and its plan's §1 to read; the blueprint, reviewed, signed, its packets merged; what
is done, every stage's close and the current stage's gates; the next step, as `bb next` says it; the
decisions still PROVISIONAL and the risks OPEN, six of each named; and where each kind of ID is
defined, file and section (`id-kinds`). One renderer, so the Architect's reports, a skill and the
person asking cannot say it three ways. A fact it cannot read is said as such. A test holds
`id-kinds` to the shipped templates: each file there, each section a heading in it.

### A stage report re-renders wherever it sits in its plan (plan 6.7, item 4, register row 136)

Item 4's run on a copy of the first project's workspace re-rendered its two stage reports after the
upgrade, as row 135 asks. Stage 0's changed; stage 1's command said it had written the block and
left the plan as it was, and `stage-report --check` stayed red. `splice` replaced only the first
fenced block of a plan, and only if that block was the report; stage 1's plan has fenced blocks
before its report. A first render, into a plan with no block, appends and had always worked, so
the fault showed only at a re-render. `splice` now asks every fenced block and replaces the report
wherever it is; the test puts two blocks before it and fails on the old code.

### The upgrade moves a workspace's stage documents into their folders (plan 6.7, item 4)

`bb upgrade` phase 2 has a step before the build documents are patched, `stage-folders`
(`harness.setup.stage-move`): every flat `stage-N-` entry of the build's `docs/stages/` that is not
a template, and every `reviews/stage-N/`, moved into `docs/stages/stage-N/`. It moves every such
entry rather than a known list, since a project puts more there than the KIT writes - the first
project's browser checks left `stage-N-interactions.edn` beside its plans. A tracked entry moves by
`git mv`, so its history follows; an untracked one moves on disk and stays untracked. One commit in
the build, by pathspec, the release named. With nothing flat it says so, which is also how a re-run
and a workspace made with the folders pass it.

The paths go with the files, in the same commit, so the history reads as a rename with a small edit.
Fable's review of the design set the rule for the moved documents: one more `../` where each run of
them begins - not `../` doubled, which is wrong for a path three folders up (`../../../extracted/…`
becomes four, to the same place) - and never inside a fenced block, where a blueprint's packet
carries the application's paths for the Coder. A survey of the first project's build found two more
kinds the move had to carry: its other documents cite stage plans as `stages/stage-0-plan.md`, the
decision log most of all, and readings as `reviews/stage-N/…`, and a stage document names
another stage's files bare (`stage-0-blueprint.md`), which were neighbours and are now a folder
apart. Each is rewritten: `stages/stage-0/stage-0-plan.md`, `stages/stage-N/reviews/…`,
`../stage-0/stage-0-blueprint.md`; a moved document's own readings become its `reviews/`. The
templates are not touched here - the build documents step brings their change - and nor is a
security review's folder, whose files quote the application. An uncommitted edit to a tracked file
the step would touch stops it by name before anything moves. A relative path is deepened only when it led somewhere from the old place: the first run, on a copy of
the first project's workspace, deepened a link its stage plan quoted from the site's content, which
named nothing from the plan's folder and is a quotation; the new texts are now read before anything
moves, against the old layout. Fable's review of the diff found two cases the rewrite missed, neither
in the first project's documents (the old and new rewrites give the same text on all of them): a
moved document citing a path written from `docs/` (`stages/stage-0-gates.edn`), now put through
the folder as the other documents' are; and a document deeper under `docs/` reaching a reading by
more than one `../`, now any run - one `../` shorter, to the stage folder under `docs/`. The tests run the move on a git
repository in the old layout: the renames git detects, the paths, the fence untouched, the untracked
files still untracked, and the second run moving nothing.

### A folder per stage (plan 6.7, item 4)

A build's `docs/stages/` held every stage's documents flat, beside the six templates: by the first
KIT project's first stage the person looked in it and saw stage plans, blueprints, gates records,
the security pack's records and a security review's folder for two stages, mixed with the
templates. Each stage is now a folder, `docs/stages/stage-N/`, holding its plan, its blueprint, its
gates record, the security pack's records, the security review's folder, and `reviews/` - the stage
plan review's and the blueprint review's readings, which were under the build's
`reviews/stage-N/`. The templates stay at `docs/stages/`, beside the folders.

`harness.setup.stages` is where that is known: the folders, a stage's documents, its readings, and
the stage's id read from the folder name, once. Four namespaces had each read the id from a file
name, in four ways - `bb next`, `bb stage-report`, and the plan and blueprint reviews, whose
readings went to a folder named by whatever their file names began with. The files keep their
names (`stage-1-<slug>.md`, `stage-1-gates.edn`, `stage-1-security-review/`), so a grep for a stage
still finds them, and what keys on a suffix - the plan check's exclusion of a security review's
folder, the stage report's three security records - reads as before.

The project plan review stays at the build's `reviews/plan-review.edn`: it reads the whole plan once,
before stage 0 is approved, and is no stage's own. Stage 0 also has its stage plan reviewed, like
every stage, and `bb stage-report` had counted the project plan review as stage 0's plan review and
never read stage 0's own. Stage 0's report now counts both, said apart as "project plan review"
and "stage plan review" (the person's words); later stages count their stage plan review. A
published stage-0 block re-renders with the new figures.

The templates' paths were written from where a copy lives, one folder below `docs/`; a copy now
lives two below, so each run of `../` gained one, and each template says so in a line under its
heading ("**Paths:** from `docs/stages/stage-N/`"). A workspace takes the templates' change through
the upgrade's build documents step as ordinary hunks. `bb next` prints the folder's `mkdir -p` with
the gates template's copy when a stage is pulled. The skills, the build documents' tree, the
method, the workflow and the harness's own documents name the new paths. Moving an existing
workspace's documents is the upgrade's next step, its own commit.

### The upgrade's dry run names origin's newer releases (plan 6.7, item 3)

The first KIT project's first `bb upgrade` began with a dry run, right after a release was pushed,
and it said "newer releases known locally (no fetch): none". That was true and no help: the clone's
tags are only those its last fetch saw. Fetching in a dry run would write refs into `KIT/`, so the
dry run asks origin instead, with `git ls-remote --tags`, which writes nothing: "newer releases on
origin: 0.6.7". Offline, it says origin was not reached, gives the clone's own list, and says that
`bb upgrade` fetches the tags itself. The sentence is `dry-run-summary`, a pure function, tested for
the three cases; `origin-tags` was run against the KIT's origin and against a remote that does not
exist.

Fable's review of it: `git ls-remote` has no timeout of its own, and on a network that resolves and
then drops packets the dry run would wait out git's connect timeout before its offline line. So
`origin-newer` asks origin's host first, in the five seconds phase 1 already allows it, and
does not ask origin at all when the host does not answer; a local path has no host and is asked
directly. A test holds both, with a fake KIT as origin and a host that does not answer.

### The stage report counts every spec reading (plan 6.7, item 2, register row 135)

The first KIT project's stage 1 report said one spec review per run, and the stage's total was
short by about half the readings' money. A run's record holds one `:spec-review` event, with every
reading under `:reviews` and the last one's cost as the event's own `:cost`. The run report reads
`:reviews`; `stage-report`'s `run-summary` counted the events and summed `:cost`. Its fixture still
had one event per reading, the shape before the readings' history, so its test passed against a
record no run writes. `run-summary` now counts and sums the readings, an event without `:reviews`
being one reading, and the fixture is in the records' shape. A block rendered before this keeps the
old figures until `bb stage-report` renders it again.

### The rules overlay refuses a key a rule does not have (plan 6.7, item 2, register row 134)

In the first KIT project's stage 1, two quote pairs inside the overlay's `:data-conventions` text
went in unescaped. The file still read as EDN: the `:text` string ended at the first quote and the
rest of the sentence became keys of the map. Nothing noticed. `bb rules-sync` rendered the cut
text, `bb rules-check` compared the mirror with the same broken source and stayed green, the plan
check passed, and every packet of the stage went out with the project's data conventions ending
mid-sentence. The packets' own targets carried most of what was lost, and every run merged; the
project's session found it by reading the rendered `AGENTS.md`.

`harness.rules/overlay` checked an entry's `:group`, `:audience`, `:title` and `:text`, and never
its set of keys. It now refuses an entry with any key outside `rule-keys` (`:id :group :audience
:title :text :from`, `:from` being the source's provenance key), naming the keys and the likely
cause. Every reading of the rules goes through `overlay`, so the refusal reaches `rules-sync`,
`rules-check`, the plan check, `start` and each role's prompt alike. A warning on a rule's text
that ends without punctuation was suggested and not taken: an unescaped pair always leaves stray
keys or EDN that does not read, and the key check is exact where punctuation is a guess.

### The toolchain held current (plan 6.7, item 1, register rows 131 and 133)

The fresh-machine image was as old as its first build: `docker build` with no `--pull`, and no
build argument that changed with the known-good set's date, so while the tools' versions stood
every layer came from the cache and a new health record only added a tag. `docker-image!` now
builds with `--pull` and passes the set's `:as-of` as `KIT_AS_OF`, which the Dockerfile uses just
before the layer that installs the operating system's packages. Three builds proved it: the date
as known-good has it, the same date again with every layer cached, and a later date that rebuilt
from that layer on while the base and the git build stayed cached.

`bb toolchain-newer` (root, the KIT's own development) prints each pin beside its newest release:
the known-good set's tools, the three versions only the Dockerfile pins, the template's tag and
deps-new's. Releases come from `git ls-remote --tags`, with no API and no token, every repository
asked at once; a tag of a pre-release's shape is never newest, and a repository that does not
answer is said as unknown. Information, exit 0. `bb kit-status` and `bb tag-check` print its
newer lines, and `tag-check` names Docker Scout's scan of the image as a step by hand beside
bear-claw's, with its command. The plan named two more sources: the image's `apt list
--upgradable`, and an advisory scan over the Clojure CLI's jar. Neither was built. Scout already
reports the image's fixable packages and its libraries' advisories, and the jar was a first guess
that the fresh scan disproved.

The rule is in this file's tag checklist and the `kit-session` skill. A pin moves at a plan's start,
when its branch is cut; the person reads the list and decides, and a move is the plan's first item
with its health runs. Before a tag, only an advisory moves a pin. The first reading found `bb`
1.13.226 released and `clj-kondo` already at its newest, so the `jackson-core` HIGHs inside both
binaries are row 133, on watch until their releases carry the fix.

The person moved `bb` to 1.13.226 at this plan's start, so the plan's first health runs took it:
`bb health --record` on macOS and `bb docker-health`, 9 of 9 on both, the known-good set and the
Dockerfile's default with them. Scout on the image they built still flags `jackson-core` inside
both binaries (row 133), and nothing in the operating system's packages at HIGH.

### A template namespace removed with its file passes the plan check (plan 6.6, register row 132)

The first KIT project's second stage removed the template's unused database by a recorded decision:
the namespaces' files deleted and their `layers.edn` entries with them. The boundary gate was green
- it refuses an entry with no file, as dead config - and `bb plan-check` refused the opposite: "the
template's ... is no longer declared". `start` re-runs the plan check whenever `layers.edn` changes,
so the stage's first packet stopped before anything was dispatched. Two checks a project could not
both satisfy, and the only way past was to switch the plan check off.

`layer-problems` takes the namespaces the application's `src/` defines (`boundary/defined`, the
boundary gate's own reading): a template namespace with neither an entry nor a file is removed and
passes, since nothing can require what is not there. An entry gone while its file stays is still a
loosening, said so ("its file is still under src/"). Checked on that project's workspace, read only:
both sentences gone, nothing else said.

### The no-absolute-path test holds wherever the clone is (plan 6.5, item 17e)

`bb docker-gates` on the tag's commit went red where `bb gates` here was green: five failures, all
in `init-writes-no-absolute-path-into-the-workspace` (item 10), which had not run in the container
before. It took the KIT from where it runs - `/work/kit` there - and expected the clone's folder to be
named `clojure-agent-kit`, and it looked for the workspace's path as a bare substring, so `/work`
was found in `../work/runs/`, `../workspace.edn` and `:repl/worktree`. Nothing `bb init` writes names
an absolute path; the test now expects the clone's own folder name and matches the workspace's path
only as a whole path.

### `agy-ide.edn` takes `claude.edn`'s roles, with Argon for triage (plan 6.5, item 17)

The person's decision: the two shipped profiles had drifted apart role by role - `agy-ide`'s Tester
a model behind `claude`'s line, its reviewers Anthropic where `claude`'s are OpenAI - and only
`claude`'s roles have been measured. `agy-ide.edn` now has `claude.edn`'s roles, block for block,
but for the orchestrator: Gemini 4 Argon, medium, over OpenRouter pinned to Vertex, the seat's
family (triage is not a verifier). The two examples now differ in the seat and in triage only, and
each exercises both shapes. `portability.md`'s table and example, the harness README and the
profile namespace's docstring say so.

### `agy-ide`'s Coder is Gemini 4 Argon too (plan 6.5, item 17)

The measurement that moved the `claude` example's Coder found Gemini 3.8 Flash unable to code in
the loop: at medium effort and at high it worked in the REPL until its rounds ran out and never
wrote a file. `agy-ide.edn` coded with it, on no build. The person's decision: its Coder is Argon,
at medium over OpenRouter pinned to Vertex, as `claude.edn`'s. The family stays Google, the
seat's; its Tester (OpenAI) and its Anthropic reviewers are unchanged, and its orchestrator stays
Flash - triage is one call with no file to write.

### The shipped profiles say what configures them, and no more (plan 6.5, item 17)

`claude.edn` and `agy-ide.edn` had grown to 640 lines between them, most of it the history of
each selection - which bake-off, which build, which model before. A project copies one of them
into its build and edits it; what it needs there is how the file works. Each now has a short
header - the key variable, the verifiers' rule `bb profile` checks, what each shape means for
caching, cost and credit, the rounds limit, where the selections are dated - and one line per role
saying what the role does. 235 lines; the data is unchanged (read back equal to the commit
before). The history stays where history is kept: this log and `NOTES.md`'s register.

### The `claude` example codes with Gemini 4 Argon and tests with Sonnet 5.5 (plan 6.5, item 17d, register row 130)

The first KIT project's Tester was most often the first red gate of a packet, and another model had
proposed swapping the Coder and the Tester. Measured now, on that project's first stage in the
sandbox: its hardest packets, each re-run from its own base and spec by the loop alone, every
stop answered by one mechanical rule, against a control - the shipped profile - on the same KIT.
Flash coding failed every packet, at medium effort and at high: it worked in the REPL until its
rounds ran out and never wrote a file. Gemini 4 Argon coding with Sonnet 5.5 testing passed every
packet first time, for less money and less time than the shipped pair, which passed them too but
retried on the hardest, mostly on its Tester's own mistakes; Sonnet tested in a fraction of
Flash's rounds. The person's decision: the shipped `claude.edn` codes with Argon (medium, over
OpenRouter pinned to Vertex) and tests with Sonnet (medium, on Anthropic's API, its cache asked
for). The Coder's family no longer follows the seat, a convention set aside on a measurement; the
verifiers still differ from it. Triage (Opus) now shares the Tester's family instead of the
Coder's, and row 2's watch turns round with it.

The security reviewer stays GPT-6 Astra. Gemini 4 Argon, tried against it on the KIT's
planted-fault fixture the same day, found every planted fault in half the time for a fraction of
the cost, but read the careful branch in a minute and missed the real flaw Astra found there; and
it is now the Coder's family. One run per packet, one bake-off: both are measurements to repeat
when a model in either seat changes.

### An OpenRouter cost is the reply's own, the generation records gone missing (plan 6.5, item 17, register row 129)

Plan 6.5's security bake-off of `gemini-4-argon` against Astra finished with every cost "not
reported", and `bb reprice` found nothing to fill them with. OpenRouter's generation endpoint, the
one place the KIT read an OpenRouter completion's cost from, answered 404 "not found" for every id:
for the bake-off's readings half an hour old, and for a fresh call asked after 5, 15 and 30
seconds. Every OpenRouter role's cost in a run record, a reading and a bake-off was going to be
blank - and each completion waited the fetch's whole retry budget, about 20 seconds, for it. The
reply carries the cost itself (`usage.cost`) and names the provider. `parse :openai` keeps the
cost; the record takes the generation record's word when one arrives and the reply's otherwise,
marked reported; a reply that carried its cost is not waited on. A real call through `converse!`
now records its cost and provider with no wait. What the generation record alone gave - native
token counts, the generation time, the service tier - is absent while it stays missing.
Bake-off-3's own readings keep no cost; the plan has their estimate from usage and list prices.

### Every shipped Anthropic role goes direct to Anthropic's API (plan 6.5, item 17a, register row 128)

The person's decision: Anthropic models on Anthropic's own API. Since 2026-09-25 the `claude`
example had sent its Anthropic roles through OpenRouter pinned to `anthropic` (row 68), for four
things the money tooling does on that side - one balance, a reported cost, one credit stop, the
generation record. They are given up knowingly, and `claude.edn`'s header now says so in their
place.

A paid probe came first, through the harness's own adapter and `list-price`: Sonnet 5.5 with a
small prompt, Sonnet 5.5 with the rule block (a cache write of 9,423 tokens), again (the same
9,423 read), and Opus 5.5 at effort medium - all answered, and each computed cost recomputed by
hand from usage and the list prices `bb models` gives (Sonnet $2/$10, Opus $4/$20 per million;
cache writes 1.25 times input, reads 0.1 times). The Console's figures arrive up to half an hour
late, so the person took the computed costs rather than wait.

The change: `claude.edn`'s Coder (`claude-sonnet-5-5`, medium), Blueprint reviewer and
orchestrator (`claude-opus-5-5`, high and medium) and `agy-ide.edn`'s Blueprint reviewer on the
`:anthropic` shape, `ANTHROPIC_API_KEY`, effort as `:output_config`, `:pricing` dated 2026-10-16.
The Coder keeps the cache the adapter asks for; the roles that read a prompt once - triage, the
Blueprint review, and `agy-ide`'s three direct reviewers, which had been paying the write premium
- say `:cache_control nil` (the entry below). `profile_test` holds every Anthropic role in both
examples to the direct route, priced, and to the cache rule. The harness README, the method and
tooling template and `portability.md` say the new route.

### A direct Anthropic request can leave the cache out (plan 6.5, item 17a, register row 25 (c))

The `:anthropic` shape asks for automatic prompt caching on every request, which is right for a
tool loop - the Coder re-sends its conversation each completion - and wrong for a role that reads
its prompt once: triage and the Blueprint review would pay the cache-write premium, 1.25 times
the input rate, on a prompt nobody reads again. The shipped `claude` profile kept those roles on
OpenRouter, where caching is asked for or not; `agy-ide`'s three reviewers are direct and paid it;
item 17a moves `claude`'s to Anthropic's own API. `:cache_control nil` in a role's `:params` was already accepted, and sent
the field as a JSON `null`; a paid probe showed the API takes the null and writes nothing (9,411
input tokens uncached). The adapter now leaves the field out instead of relying on that, and the
test says so.

## 2026-10-14

### `tag-check` no longer stales the records it just wrote (plan 6.5, item 15, register row 127)

`bb health --record` re-dates `resources/known-good.edn` beside the record, and `tag-check`
counted everything under `harness/resources/` as code the records ran on - so committing the
records with the re-dated set made both records stale against the commit that held them, and
the next `--run` ran both health checks again instead of the gates: one extra health run on each
platform at plan 6.3's tag. The set now counts when it says something new - a version, a tool,
the platform - and never when only its `:as-of` moved: the diff leaves the set out, and its text
at the record's commit and at HEAD is compared with the date dropped. A `--run` goes from the
records to the gates in one commit, which item 17's tag will show.

### The KIT's checks start the application on a port they pick, and a busy port is said with its holder (plan 6.5, item 15)

Port 8000 was fixed in the health check's serve step and in the two packs' defaults, so a
`bb serve` left running - this project's, or another's from an archived site, as the first
project met - failed a check for a reason that was not the KIT's. The template's `kit-v1.3`
takes `PORT` (its layer 1, item 12), and this is the KIT's layer 2: the health check's serve
step picks a free port and starts `bb serve --port <n>`; the browser pack and the security pack,
with no `--base` and a server to start, pick one in `check.bb`, hand it to the server through
`PORT` (`server.clj`) and to their scripts through `APP_BASE`, so the application comes up where
the check will look and never on the person's port. `--base` names a port instead, `--running`
keeps the template's default for the server already serving. Layer 4: wherever a port is still
checked - the health step's own pick taken in the moment between, the packs' start finding
something already answering - the busy port is said with what holds it, pid, command and
folder, from `lsof`, or that `lsof` is absent; a busy port said as a number is a fact, said with
its holder it is a thing to stop. `bb init`'s closing line no longer promises 8000.

A review caught the application that ignores `PORT`: every one generated before `kit-v1.3` -
the fifth project's, from the moment it takes `0.6.5` until its upgrade branch is merged - and
any custom `--serve`. It comes up on 8000 whatever it was told, and the pack waiting on the
picked port would say "did not come up", pointing away from the cause. The packs' server start
now watches 8000 too, when it was quiet before the start, and stops with the cause and the two
ways out: merge the template upgrade, or pass `--base http://localhost:8000` - and kills the JVM
a tree kill misses before it throws, since the first try of this left one holding 8000. Run
against the sandbox's pre-`kit-v1.3` application: the message, and 8000 free after it. Then
tighter, on a second reading: "quiet before the start" is no listener on 8000 at all, not merely
no `/health` answering, so a program of someone else's on that port is never the thing killed;
and both packs' scripts catch the start's refusal and print its words alone, exit 1, instead of
babashka's error box around them.

### Round 1 of the sandbox: three things the first real phase 2 showed (plan 6.5, item 16)

The first-time path on a fresh copy of the proof build's workspace, its clone on a throwaway tag
at the branch tip: every step ran, the front door, the skills, the orientation (`CLAUDE.md`
unmarked, the new text beside it), the build documents (fifteen hunks applied, the filled
retry-cap row the one conflict, saved), `loop.edn`, the application's branch - which stopped on
the `server.clj` conflict the plan predicted, the project's no-cache wrapper against the
template's asset handler - and the record. Three things in the doctor's notice were wrong. It
said the application's branch was merged: a branch cut off `main` and stopped on its first
conflict still points at the commit it was cut from, which is an ancestor of HEAD, so ancestry
alone read as a merge; merged by ancestry now needs the tip to have moved. It said "upgraded to
0.6.4", the release the workspace already had: the record names what was taken, a release or a
commit past one, while the workspace's version stays the nearest release tag. And the workspace
folder printed as `<dir>/.` after a `.` given as the folder; it is normalized once where it is
found.

Then the conflict resolved for the template's side - the project's no-cache wrapper was the
template's fix a release early - and `bb template-upgrade` again: the mirror re-rendered on the
branch, format and lint green, and the project's own tests red. One of them stubs `run-jetty`
with a plain object to see the server init call the system context first, and the template's
new init read the bound port off whatever `run-jetty` returned. A template a project cannot
unit-test with a stubbed server is the template's fault: the fork's candidate gains a commit -
the port read off a Jetty server only, the port asked for printed otherwise, with the test - and
the pin moves to it, `f9089ad`, as the plan foresaw a round would make it; item 15's health check
against the candidate runs again. The round ends there: a fixed template is a new tree, so the
next round starts fresh.

Round 2, from a fresh copy at the branch tip: phase 2 clean, its notices right, the conflict
resolved the same way, and the gates red again on the format gate - the test the fork's fix
added laid its options map on one line, which `cljfmt` breaks over three, and `fmt-check` is a
gate in every generated project; the health check against the candidate failed the same way on
the generated application's gates. The fork gains the layout `cljfmt` wants, `f12268d`, the pin
moves to it, and round 3 starts fresh. Two lessons the fork's own checks did not teach: the
fork's `bb test` runs the template's config tests, not the generated project's gates, so a
change to a generated test file is proved only by generating - the KIT's health check does it,
and from now on it runs before the pin moves, not after.

Round 3, the first-time path end to end: phase 2, the conflict resolved for the template, the
template upgrade resumed with the mirror re-rendered and all four of the project's gates green,
`bb run-loop merge` with its decision, the branch gone and `template` kept; then the workspace's
`bb gates` green with the nine published records held, `bb next` at stage 1's plan with the
leftovers beside it, `bb plan-check` clean. The Architect's two leftovers by hand: the saved hunk
is the retry-cap row's new label over the project's filled row, a one-line edit in the build; the
new `CLAUDE.md` taken whole. One more thing the doctor then showed: "the application's branch is
merged" stays for ever once said. It is said only while something else of the upgrade is still
left; an upgrade wholly resolved goes quiet.

Round 4, the one command, was set up with the two throwaway releases re-cut onto the tip - and
the re-cut failed quietly, so `0.6.7` still sat on an older line. Phase 1 fetched, found it
newer, moved `KIT/` to it, and phase 2 refused: the recorded commit was not in that line's
history, with the clone left stranded where it was moved. A target that does not descend from
the clone's commit - a tag cut on another line, a hotfix branch's - is not an upgrade path, and
phase 1 now refuses it by name before anything moves, with the two `git log` commands that show
why. And a pin "moved" from `kit-v1.3` to `kit-v1.3` read as nothing: the message names the
commits beside the tags.

Round 4 again, on the throwaways re-cut by hand: a copy of round 3's upgraded workspace,
`bb upgrade` once - the tags fetched (after the sandbox's own force-moved tags were deleted
locally; a fetch that git refuses now fails with git's words, not an empty line, since `--quiet`
hid them), two releases newer than the clone's, both met, the latest taken, `KIT/` moved to
`0.6.7`, phase 2 in a new process: the renamed key migrated first, the front door and skills
current, `CLAUDE.md` rewritten under its marker from round 3, and the build documents refused
by name on an uncommitted edit, nothing written. The edit committed, `bb upgrade` again: the
clone already past the recorded commit, straight to phase 2's first step not done, the
documents applied (the throwaway's one line a conflict, saved - it and the test's own notes
both appended to the same file's end), the application needing nothing (the pin as it was, the
mirror current, the branch dropped), the commit moved, the doctor clean but for the saved hunk.
The workspace's `bb gates` green, `bb next` at stage 1's plan. Two versions in one jump, a stop
and a resume, the re-run after the move: the three things the plan asked the sandbox to prove.

The review of the rounds read `merged?` once more and found the round 1 fix half a fix: "the tip
has moved" was read against HEAD, so a branch still at its base read as not merged only while
`main` stood still. One commit on `main` - stage 1 going on while the conflict waits, the likely
order in the real workspace - and the base was an ancestor of HEAD that differed from it: merged,
and since round 3 the notice gone with it. Reproduced in a test. "Moved" is now read against the
base the run's `state.edn` records; HEAD is the measure only when the run directory is gone. The
reverse case holds too: a branch resolved past its base and taken by a fast-forward, tip equal to
HEAD, is merged.

The same review read the refusal's words: of the two `git log` commands it named, "`<head>..<tag>`
is empty" holds only for a tag that is an ancestor of the clone, which a tag on a side line is
not - its own commits are exactly what that log lists. The refusal names the one log that is
never empty, what the clone has that the tag lacks, and the test holds it to those words.

One thing the rounds did not hit, the review noted: the plan's stop was "the network cut after
the skills", and round 4 stopped on an uncommitted note instead, so the one step that needs the
network, the generation, was never seen to fail and resume. A test does it now: the generation
throws, the app step records the words and nothing is written to `workspace.edn`, the six steps
before it stand done; the next run picks up at the app step, generates, and ends awaiting its
merge in the same folder.

### The pin moves to `kit-v1.3`, and a record from a local clone is refused (plan 6.5, item 15)

`template-pins.edn` names the fork's candidate - `kit-v1.3`, commit `1c1838a`, the tag not yet
pushed - and every document and fix text that named `kit-v1.2` names it; `PROVENANCE.md` gains
the template's section: the fork, what each `kit-vN` tag changed, and that the fork stays an
ordinary Clojure Stack Lite application. The build docs' static-files row says what the release
does: `no-cache` outside production, a year under `:prod`, a missing asset a 404 in every
profile. The health check's serve step asks for a missing asset and expects the 404, since that
is what the release fixed and what a pin whose template regressed would show first. And a health
run from a local clone - `KIT_TEMPLATE_LOCAL`, how a candidate is tried before its tag is pushed -
said so on the terminal only, while its record named the pin's tag and commit as if fetched: the
record now carries the local root, the README's row says "from a LOCAL CLONE, not the pin", and
`bb tag-check` counts such a record stale with the words, so `--run` records it again - a tag's
records come from the pushed pin, never with the variable set. The health check itself against
the candidate runs after the port work below, so that it exercises it.

### `bb upgrade`: the check and the move, then the hand-over (plan 6.5, item 14d)

The other half of the one command. Phase 1 is the clone's code as it is, and it touches nothing
outside `KIT/`: the network first - the KIT's remote, Clojars and Maven Central for the
application's dependencies - reached before anything moves, or "cannot reach <host>: nothing
changed"; `KIT/` clean, a convention nothing enforced until now, the KIT's own record files named
as such with the command that discards them and any other edit refused with the files named; the
tags fetched, which moves nothing; the clone's own version read as the nearest release tag at or
before HEAD, so a clone on a throwaway tag or a candidate's commit reads as the release before;
then every release tag newer than it, release-shaped only, each with its requirements read from
the tag - `git show <tag>:harness/resources/toolchain.edn`, the table item 14b made data, never by
running the tag's code inside this one - and checked against this machine, with the significant
changes said: a JDK major, a new required tool, the template pin moved. A row the newer table
carries that this version's probe does not know - a `:via` it has no case for, a key it does not
read - is said as uncheckable here, never passed or failed silently. The latest is the default and
`--to <tag>` names another; a tag whose check fails is refused with what is missing and how to fix
it, and the newest release this machine can take is named. Then the clone moved to the tag,
detached and never a branch tip, and nothing else in this process: the hand-over to phase 2 is
a new process, `bb --config KIT/harness/bb.edn upgrade --after-pull` from the workspace folder,
because a namespace loaded after the move would be the new version's code inside the old one.
A re-run after the move - a run stopped there, or a move by hand - goes straight to phase 2's
first step not done, by this code, which is then the new version's. Refused by name: no
workspace, the clone at the latest or at `--to` (nothing changed, exit 0), `--to` older than the
clone (a downgrade: phase 2 would run on code that does not know the newer workspace), a tag the
remote has not got. After the move, `bb self-check` is offered: the version check is not the
machine proved. Every document that told an adopter to `git pull` in `KIT/` now says `bb upgrade`,
and the README's note gives the first-time path, since `0.6.4` has no `bb upgrade`: the fetch and
checkout by hand, then phase 2 alone. Tested: the pure parts (the clone's version, the newer
releases, the target and its refusals, the local changes sorted, the uncheckable row, the
requirement check, the significant changes) and the whole phase over a clone of a small KIT
repository: at the latest before the release exists; the network, a local change, a record file
and an unmet requirement each refused by name with nothing moved; then the tags fetched, the
release's table read from its tag, the clone detached at it, the hand-over's command and folder;
and a run after the move going through phase 2.

A review the same day: the two phases read the version differently - phase 2 by `git describe`,
which names any tag, so a sandbox round would have recorded a throwaway `0.6.5-r0` as the
version and named its branch after it - and phase 1's "already moved" asked whether a strictly
newer release was recorded, so a `git pull` out of habit, or a later commit of the same release,
read as "at the latest" while phase 2 never ran. Both phases now read the version as the highest
release tag reachable from HEAD, and the move is decided by commit: HEAD past the recorded commit
is phase 2's, HEAD not descended from it is refused by name. Smaller: a new platform's record is
untracked and `checkout --` cannot discard it, so the discard command says `clean -f --` for
those; and the machine is probed once over every row the newer tables carry, each release then
checked against that result, instead of once per release. And the by-commit decision ran before
anything else, so a `workspace.edn` with no recorded commit reached it with nil and the refusal's
own words threw: phase 1 now refuses that first, in phase 2's words.

Seen setting up the sandbox rounds: a clone past its release on a throwaway tag reads as that
release, the one the workspace already records, and phase 2 would have named its branch and its
folder `upgrade-0.6.4` after a release the workspace had. The release an upgrade is named by is
the clone's version when it is newer than the recorded one, else the commit; the version the
record carries stays the nearest release tag, which is the truth about the clone.

### The upgrade's run is the one `bb template-upgrade` resumes, and the notices follow it (plan 6.5, item 14c, after review)

A review of the entry below against the plan's text and the sandbox baseline's filled build
found four holes and four smaller things. The worst: after a conflict, the upgrade's run was
named `upgrade-<version>` and its notice said "`bb template-upgrade` after", but that command
looked for a run of its own name, found none, saw `template` ahead of `main`, and started a
second run - the same merge, the same conflict, in another worktree - orphaning the Architect's
resolution and never re-rendering the mirror; the first project's `server.clj` conflict would
have met exactly this. `bb template-upgrade` now resumes an open run of its kind whatever its
id, and the run records the step it wants before its gates by name (`:hook`), so the resume
re-renders the mirror too. The notices read the run's `state.edn` rather than the state recorded
at upgrade time, so a conflict resolved and gated reads as awaiting its merge, a merge recorded
reads as merged, and a branch gone with no merge recorded reads as abandoned, not merged. The
workspace is read again for every step, so a renamed key migrated by the first step is seen by
the rest in the same run - the point of migrating first. Every target document is checked for
uncommitted edits before any is written, so a stop leaves nothing half done and the re-run is not
refused by the upgrade's own edits. Smaller: the build's commits name their paths, so staged work
of the person's is not swept up; `loop.edn` gets the same check; a clone with no reachable tag
names the release by its commit; blank lines count for nothing in the hunk rule. Each has a test.

The re-review's residue, the same day: a run whose branch was deleted by hand but whose
directory was left blocked `bb template-upgrade` with git's words at every call - it is refused
once, with the way out, the run directory to remove; a run open for an earlier release is
finished first when the pin has moved since, and the command now says so and that the newer
release waits; and `--dry-run` names the run the real call would resume, not a fresh one.

### `bb upgrade --after-pull`: the workspace brought up by the new KIT's own code (plan 6.5, item 14c)

A KIT upgrade was a README's list of hand steps, each run when someone remembered: round 0 of
the fifth project's upgrade, done that way on a copy, left the application and the build dirty,
a `.rej` in the build that the workspace's gates passed over, and nothing saying to commit. The
upgrade is one command in two phases, split by whose code runs: phase 1, the check and the move
of `KIT/`, is the old version's and comes with item 14d; phase 2 is this, the new version's, in a
new process after the move, because the new version alone knows what its release changed. Nine
steps in the plan's order, each recorded in `work/upgrades/<id>/done.edn` as it finishes, so a run
after a failure picks up at the first step not done, and every root file copied to `before/`
there before it is rewritten - the workspace root is no repository, and that folder is the way
back. `workspace.edn`'s renamed keys go first, since every later step reads the file through this
version's code and a renamed key reads as nil until then.

Three things needed a judgement. The orientation: the workspace `CLAUDE.md` and the role file
were written once by `bb init` and re-synced by nothing, and the text is a string in `init.clj`,
so "as the old version wrote it" would mean running the old version. `bb init` now writes a first
line carrying a hash of the text below it; an upgrade rewrites a file whose hash still matches,
and leaves one edited or unmarked - the fifth project's - alone, the new text beside it as
`CLAUDE.md.new`, marked too, so taking it whole re-adopts the file. The role file is compared
with the old commit's copy, which git holds. The build documents: the diff from the recorded KIT
commit to HEAD over `build-docs/`, applied to the build's `docs/` hunk by hunk; a hunk that does
not apply is dropped as "instruction already removed" only when every line it removes is a
template instruction now gone from the document AND every line it adds is one too - the added
lines count, or a heading or a row the release adds beside a reworded instruction is lost without
a word; anything else, round 0's filled table row among them, is a conflict saved under
`conflicts/` and named, never a `.rej` in the build; a document with uncommitted edits stops the
step by name; the documents touched are committed in the build with the release named. And the
application: `bb template-upgrade` with the rule mirror re-rendered on the upgrade branch before
its gates, so the tip the gates saw is the tip that merges - and the branch is made even when
the release leaves the template pin alone, since the mirror still changes; a branch with nothing
on it is no run. One source of truth for "which KIT is this workspace at": phase 2 moves
`:workspace/kit-commit`, which the doctor's report and the next upgrade both read, and records
the upgrade under `:workspace/upgraded-to` with what it left for the Architect - the branch, the
saved hunks, the `.new` files - which the doctor and `bb next` say in the same words until each
is resolved, so a green `bb gates` does not pass over a saved conflict unread. Tested over a small
KIT repository with two releases and a workspace made from the first: every step, the three
hunk outcomes, the new document, the rewritten path, the mirror's branch gated and awaiting its
merge, the record; and a run stopped by an uncommitted document, resumed after the commit, with
an edited `CLAUDE.md` given its `.new`.

### The toolchain is data: `resources/toolchain.edn` (plan 6.5, item 14b)

The doctor's requirement table - every tool, its tier, whether it is required, the one exact
major and the one floor, what installs a miss - was a vector in `doctor.clj`, plain data
already, with one function call in it for three install lines. It is now
`harness/resources/toolchain.edn`, which the doctor reads at load and reports exactly as before;
the three install lines are written out with their tag. The reason is the upgrade: phase 1
(item 14d) checks a release's requirements against this machine before the clone moves to it,
and it must read them from the fetched tag - `git show <tag>:harness/resources/toolchain.edn`,
plain EDN, no reader macro - never by running the release's code inside the old one.
`read-toolchain` is that reader, the same one the doctor uses on its own copy; a test holds the
doctor's table to the file and the file to the shape the doctor reads.

### The health records are the KIT's own: a workspace's clone does not write them (plan 6.5, item 14a)

`bb self-check` passed `--record` on to `bb health`, which writes `health/records/<platform>.edn`
and rewrites the known-good set - tracked files, the KIT's evidence of a machine it certified.
In a workspace's clone of the KIT they are local changes with no home: noise that blocks a
clean upgrade, and a record nobody would commit. The first project's clone had none only because
the documented path never passed the flag. Now `bb health --record` is refused, before the
minutes are spent, where a `workspace.edn` is found above the clone: "records are the KIT's
own; a workspace's clone does not write them" - the run itself goes on without the flag. And
`bb self-check` no longer offers it: given `--record` it says whose the records are and exits.
The method's line that named `bb self-check --record` names `bb health --record` in the KIT's
own clone. The upgrade's own check for local changes in `KIT/`, which names record files as such
with how to discard them, is item 14d's.

Two limits, known. A KIT kept outside its workspace - `workspace.edn` naming the clone by an
absolute path, nothing above the clone - is not caught: the refusal reads the clone's place, and
such a clone can still record; 14d's local-changes check sees the files it would write. And any
clone under a workspace is refused, not only that workspace's own KIT: a second clone inside a
workspace folder is an adopter's machine too, and the records are not its to write. Both
refusals exit 1; the self-check's has a test that runs the task and sees nothing else run.

### The template upgrade's merge takes the gated tip, and "nothing to do" asks the base (plan 6.5, item 13, after review)

A review of the entry below found two holes and a plan line left over. The merge took the
branch's tip as it stood, checked clean and not mid-merge, and nothing else: a commit made after
the run reached `:awaiting-merge` would have merged ungated. The gates record the commit they ran
on, so the merge now refuses a tip that is not that commit, and one that does not hold the
template's commit, each with "`bb template-upgrade` again". And "nothing to upgrade to" was read
off `template`'s tip - is it the pin? - when the question is whether the tip is merged into the
base branch: a run that failed between the template's commit and its state, or a run directory
deleted to start over, left `template` ahead of `main` and every later run refusing with exit 0.
Now a tip not yet merged is the release still to take, taken from the tip as it stands without
generating again, and a worktree lost mid-conflict and checked out again from a branch the merge
never reached is merged again, never gated as it is. Smaller: a merge with every conflict staged
and not committed is said as that; `--dry-run` names the run id a real run would get; the
generated tree is copied with its file modes. `bb next`'s notice while the branch awaits its
merge is item 14c's, with the upgrade's done record, and the plan said so beside it; the entry
below had not. Each hole has a test over real git.

A second reading found the same hole one step on: a merge that failed because `main` moved
during a long resolution is answered by a commit on the branch, and the retried merge reused
the `:merge-commit` it had recorded and merged the branch by name - the new tip, ungated. An
upgrade's merge now reads its tip again on every attempt, under the same checks, and records the
commit it merges; and a resumed run merges the template commit its state names, not the branch
by name, so a newer release committed onto `template` since cannot slip under an older state.
Tested: `main` moved, the first merge aborted, the branch brought up and gated again, that tip
merged; and a candidate whose tree is `template`'s unmerged tip taken from the tip - the path
the sandbox's candidate rounds take on a re-run.

### `bb template-upgrade`: a template release taken through a `template` branch (plan 6.5, item 13)

A moved template pin was a report and a compare link, and nothing applied it: the template's
files are written under a placeholder name and the application's carry the project's, so a raw
diff between two tags does not apply, and the first project built on the KIT could only read the
diff and write it as work. Now both trees are generated with the project's name and git does the
merge. The old side is a branch, because it has to be: `bb init`'s first commit is the generated
tree untouched, and a three-way merge against it is right for the first upgrade only - after it,
the tree the previous release generated is held by no commit on `main` by itself. So the
application has a `template` branch rooted at that first commit, which `bb init` makes from now
on and an older application gets from its first commit (refused with the words when that commit
is not the untouched tree). Each upgrade generates the new tree at the pin - `KIT_TEMPLATE_LOCAL`
for a candidate - commits it onto `template` as the whole tree with the tag in the subject, and
merges `template` into a branch off `main` in a worktree under `work/`: git's own merge with the
right base every time, and a later release needs nothing more. Which pin is old is what
`template`'s tip holds; no key in `workspace.edn` says it, and a tip that is the pin already is
"nothing to upgrade to".

A merge that stops on a conflict cannot be committed, so its states are written down as the
loop's: the run has `work/runs/template-upgrade-<tag>/` with `state.edn` and `events.log`, a
conflict names the files and stops for the Architect, who resolves and commits in the worktree
and runs the command again; it picks up where it stopped, runs the project's gates on the branch
(the build's `loop.edn` gates, `{{kit}}` resolved), and stops `:awaiting-merge` - or red, for the
Architect, with a further commit gating again. `bb run-loop merge` takes that run through the
same door as a task's: the same refusals and decision, the branch's tip as it stands in place of
a gate commit, the `--no-ff` merge, a record, the worktree and the branch gone, `template` kept.
The loop's other commands refuse it by name. Tested over real git with generation stood in for:
the clean path through to the merge, the conflict path through resolution and a red gate, the
refusal, and an older application given the branch.

### A new workspace's application opens on the KIT's first page (plan 6.5, item 12a)

The proof build served its new application and saw "Welcome to Clojure Stack Lite": the
template's own page, pointing at a file the template does not generate, with nothing to say this
was a KIT workspace or what came next - while `method.md` had told the newcomer to expect "Welcome
to KIT". The page could have gone into the template fork, at the price of the fork no longer
being an ordinary Clojure Stack Lite application; it is written by `bb init` instead, so a richer
page later is a KIT change and not a fork release. The second commit, which already adds
`AGENTS.md`, `CLAUDE.md` and `layers.edn`, now replaces the one top-level `(def home-page ...)` in
`src/<ns>/views.clj`, found through the form table, every other line as it was and no new
namespace: "Welcome to Clojure Agent Kit (aka "KIT")", the project's name, the next step (`cd ..`,
a Claude Code session there, `/kit-start`), that stage 0 replaces it, and the KIT version - no
folder name and no link out, so a moved workspace and an offline machine read it the same. A
template with no such form, or two, stops `init` by name before anything of the KIT's is
written. The health check's serve step now expects the page, so a pin whose template moved its
home page fails there first. `method.md`'s stage 0 deliverable, "a page that says 'Welcome to
KIT' is enough", now says the project's own first page in place of that welcome.

### From a workspace, `rules-sync` says which mirrors are the KIT's own (plan 6.5, item 11)

Round 0's `bb rules-sync`, run from the workspace, printed "agent-rules already current:
AGENTS.md" and "health/selfcheck/AGENTS.md" above the application's absolute path - the KIT's own
mirrors, listed relative to its `harness/`, read there as the workspace's own file. From a
workspace they now say "(the KIT's own)", in every line `rules-sync` and `rules-check` print about
them; in the KIT itself, or for a mirror named on the command line, the path stands as before.

### The doctor's missing-skills line names no date (plan 6.5, item 11)

Round 0 - a copy of the proof build's workspace taken from 0.6.4 to this branch by the hand steps -
showed the doctor saying the workspace "lacks the KIT's kit-plan, kit-scoping, kit-stage-end,
kit-stage-plan skills (bb init writes them since 2026-10-07)". The `kit-` names are a week younger
than that date, which was the skills' first rendering. The line now says the skills are ones
`bb init` writes, and no date; the line beside it that removes the old names already gives the
rename's.

### A workspace can be copied or moved: `{{kit}}` is resolved when a run starts (plan 6.5, item 10)

`bb init` wrote the KIT clone's absolute path into the build's `loop.edn`: the boundary gate is
the KIT's own, run in a worktree under `work/`, where no relative path to the KIT holds, so the
pin's `{{kit}}` was replaced at init. A copy of the workspace then ran the ORIGINAL's KIT at that
gate, and a workspace moved or restored elsewhere pointed at nothing; an upgrade tried on a copy
did not see it, because it ran no gate. `init` now writes `{{kit}}` as it stands, and
`driver/resolve-config` makes it the KIT of the workspace the run is in (`workspace.edn`'s
`:workspace/kit`, absolute once found) - or, outside a workspace, the clone the harness runs from.
A `loop.edn` written with the literal path is read as before; the upgrade rewrites it. A test reads
every file `init` writes, rendered or copied, for the workspace's own folder, and finds none: in
the default layout the KIT is named relatively. A KIT kept outside the workspace is still named
absolutely in `workspace.edn`, the one path that has to be. On the way, the upgrade test that decides whether a pin
bump is being gated read any uncommitted edit to `template-pins.edn` as one; it compares the pins
now, so a comment edited there is not a bump.

### `bb report` takes a path (plan 6.5, item 9)

`bb report` read its record from stdin and nothing else. Given a run directory, the way a person
reaching for a run's report would call it, it waited on stdin and threw "EOF while reading" with a
stack trace. It now takes a path: a `run.edn`, a run directory (its `run.edn`, or a line saying
none is written yet and that `bb run-loop record` writes it), or a published record under the
build's `runs/`. With no path it reads a piped record; with nothing piped either - stdin a
terminal - it prints its usage instead of waiting. A missing path, two paths, an empty input, a
file that is not EDN or not a map each get a line and the usage, exit 2, never a trace; tagged
literals read as `report-check` reads them. The text `bb init` writes into a workspace showed
`bb report < runs/<id>.edn` "from the workspace folder", where that path does not exist; it now
names the build folder, `bb report <name>-build/runs/<id>.edn`, and so does `report-check`'s
line for a drifted report.

### The security pack's path check starts a path, and a judged finding can be accepted (plan 6.5, item 8)

The proof build's first stage ended with the pack failing its page not found for "a path on a
machine": `/home/home-logo-new-with-ribbon.jpg`, the tail of the application's own logo address,
`/assets/content/extracted/pictures/home/...` - a folder named `home`. The pattern had no anchor.
It now matches a path only where one starts: at the text's start or after a space, a quote, a
`>` or `file://`, never after a path segment; and `/home/`, the one prefix a site route
plausibly has, never as an `href`, `src` or `action` value. Every other prefix matches in an
attribute too: a template that renders a file into an image source leaks it there (the narrowing
is a review's). A test holds the real logo address, a URL with `/home/`, and a root-relative link,
against eight paths a server could print, two of them in attributes. The path a row quotes ends at
a quote or an angle bracket as well as at whitespace: it had carried the `"` or `</pre>` after it,
and a row's text is now what an acceptance is keyed by. And a drive letter takes one backslash,
as a page prints a Windows path: the pattern had wanted two, and matched only a path escaped in a
string - never, before or after the anchor, the shape an error page shows. And the fail could not be accepted: it stood in the
stage plan with its reason and failed every run after. A fail or a warn the project has judged now
goes in the routes file under `:accepted-findings`, keyed by the row's exact text, with a reason:
the row says `accepted` and why, never `ok`, and fails nothing. An acceptance with no reason, or
whose row now says anything else, accepts nothing - a new page or a new leak is a new row. The
pack's summary and the stage report count accepted rows where there are any.

### The blueprint review reads the requirements, and two rules for targets (plan 6.5, item 7)

The proof build's first stage paid for two things its blueprint review passed. A composition root
took "a context" no target said the source of, so every map the type admits was in its domain,
and its code review rejected twice on a sorted map and a record nothing could send. And a
placeholder page's target pinned the page's whole Hiccup with the title alone: the requirement's
sentence, the words a person was to read, was in no target, and a person's ask to restyle the
page needed the contract changed. `method.md` §06 gains rules 13 and 14 for both, and the review's
checklist carries them, being read from that section. The second could not have been caught by a
checklist line: the review was sent the stage plan and the blueprint, and the requirement's words
were only in the requirements, which the stage plan summarised as "a placeholder". The review now
reads `docs/01-requirements.md` too, and its prompt names the two rules, with three finding kinds.

What a model is told is a change to the run, so it was measured on the proof build's stage 0
documents as signed. The review as it was found none of the three; with the rules and the
requirements, worded first as "the requirements this stage's plan takes on, never a later
stage's", it found the whole Hiccup but left the sentence out - the requirements assign that
requirement to the next stage, though this stage built the page. Worded as what a packet builds,
whichever stage the requirement is assigned to, it found all three, and named the menu that could
never open, a fault the first stage's code reviews had only noted. One sample each.

### Guidance that names a defined term is sent the term's definition (plan 6.5, item 6)

The proof build lost a round to a paraphrase. The Architect's guidance for a Coder retry named
THE WORDS OF A TREE, the project's own defined term, and restated it - an image adds nothing to
a head cell's words - where the definition makes an image a word boundary. The Coder held the
definition among its rules, followed the guidance, and the code reviewer rejected on the
contract: one round and a ten-minute stop. Nothing in the KIT said guidance quotes.

Now the driver does it mechanically, the way a packet inlines its slice's shapes. `retry-with!`,
which a routed retry and a typed one both pass through, reads the guidance - the `:triage` and
`:architect` feedback, never gate output or the code reviewer's findings - for the terms the
project's filled `:data-conventions` defines, and sends each one named as a new feedback source,
`:conventions`, word for word, right after the guidance. A term is an ALL-CAPS heading that starts
a sentence, its definition running to the next heading, or a run of caps words inside one,
defined by the heading it sits under; matched as whole words in any case (`harness.rules`,
`defined-terms`). The definitions are added after the Tester's leak check - rule text every role
holds already, whose namespaces are the contract's - and the round's event records
`:quoted-terms`. On the proof build's conventions the parser finds its twenty terms, and the
guidance that went wrong names two, THE WORDS OF A TREE and WORD, so both definitions would have
gone with it. A rule for every role and the person, `:guidance-quotes-the-contract`: guidance
quotes a defined term and never restates it, and where it differs from the contract the contract
holds. Triage's prompt asks the same of the model.

### An approval with notes says so at the stop (plan 6.5, item 5)

The proof build merged a layout packet whose code reviews each approved with notes naming real
faults - a menu that could never open, a row that closed as it opened - judged outside the
packet's targets and so not a rejection. The stop read "the code reviewer approved; the merge is a
person's" either way, and the notes were found by whoever read the findings above it. Now an
approval whose verdict carries reasons says how many: "the code reviewer approved with 2 notes -
read them before the merge, which is a person's". The interim answer only: what would hold such a
fault in the loop is an interaction target the browser pack checks, which is not built.

### What the code prints and what models are told say "code reviewer" (plan 6.5, item 5)

The documents said "code reviewer" from the day the security reviewer became a role; the code
did not - about a hundred and forty lines of stop messages, usage text, docstrings, the triage,
spec-review and runner prompts, the shipped profiles' comments, one rule's text, and the tests
pinning them. One function did the sweep (a bare "Reviewer", not one already named by its kind,
not an identifier: `ReviewerPacket` and the `:reviewer` key are what records and profiles carry,
and stay). The blueprint review's prompt said "the six roles" of a method that has more; it says
"the roles". What a model is told is a change to the run, so it was measured first: the proof
build's saved prompts whose text the rename changes - two triage calls on a rejection, three spec
reviews - asked once in the old words and once in the new. Triage routed the same both times; two
spec reviews found nothing either time, and the third found the same seven things either time
with one or two of its own on each side, a sample's spread, none about the code reviewer's rules.

### The retry cap is spent per role, and the stop names whose rounds are spent (plan 6.5, item 5)

The cap was per task, spent by whichever role a round went to, on the argument that a Coder's
retry and a Tester's are often two attempts at one failure and that a cap per role lets a task be
dispatched twice as often before anything escalates. The proof build's view packets answered it:
their rounds went to the Coder, to the Tester's own fixtures and to the Architect's guidance, the
cap was raised a round at a time - each raise a stop and a decision - and the count said a task
was hard when it should have said who was struggling. Now each role's rounds are counted
against the cap (`driver/role-rounds`): its first dispatch and every triage decision that sent it
back. An existing `:retry-cap 3` reads as three each; nothing is migrated. Before triage the loop
stops only when neither role has a round left, since whose round it is is triage's answer; when
triage names a role whose rounds are spent, the stop says so - "triage sent the round to the
Coder, whose 3 rounds are spent (the cap is per role; the Tester has 2 left)" - one triage call
paid for the name. A round that followed guidance contradicting the contract is nobody's, and the
harness cannot tell: `bb run-loop amend ... --not-counted` is the person's mark on the last round,
with the amendment's reason, refused when there is no round or it is already marked. The record
grows `:run/rounds` and `:run/retry-cap`, and `bb report` says each role's rounds against the cap
and any not counted - only for a record that has them, so a published table re-renders unchanged.
The task's total stays what the reports count.

### A workspace has its own `bb gates` (plan 6.5, item 4)

A workspace had six checks - the rule mirror, the skills' renderings, the front door, the plan,
the stage reports, the run reports - and no command that ran them: each was typed by hand when
someone remembered, and the KIT's own `bb gates` checks the KIT. `bb workspace-gates` runs the
six, cheap first, the first red stopping it, and the front door offers it as `bb gates` - the one
task offered under another name, since `gates` in the harness is the KIT's (`door-names`). The
plan check is the one that needs a judgement: it reads every plan document, stage plans among
them, and a plan being written carries its marks on purpose. So while `bb next`'s facts say a
plan is being written - before any stage, or in a stage until its blueprint is signed - its
list is printed as information and the gates go on; past that, a mark left is red. The window
first closed at the stage's approval; a review found stage 0's plan, and every blueprint, are
written after it from a marked template, so it closes at the blueprint's signing. The stage's
end now asks for it green. A workspace made before this gains it at `bb front-door-sync`, and
`bb doctor` already says when a front door lacks a task.

### `bb next` reads the blueprint's human prep before a packet (plan 6.5, item 4)

The proof build signed its stage-0 blueprint with three rows of §0, *Dispatch preconditions*,
still open and each blocking the first packet, and `bb next` named the packet: the Architect's
session read §0 first and listed the prep, but nothing made it, and a person at a terminal would
have gone straight to the dispatch. `bb next` now reads §0's table before it starts a packet's
run: a row whose Blocks names the packet (as a word) or says `every task`, and whose Status is
open, comes first - the person's, with the row and where to mark it done. Status is read
strictly: one that begins `done` is settled, one that begins `per run` is named in the packet's
step as done again before every run, and anything else is open - a note such as "green" on a
check made before each dispatch holds the packet until the row says `per run`, which is what
it is. A run already under way is not held back. The template's §0 says the three words.

### `bb plan-check` reads the plan, and `bb next` keeps a stage past it (plan 6.5, item 4)

The proof build closed stage 0 and `bb next` said "step 2 - the plan, for stage 0". `plan-check`
read every file under the build's `docs/`, and the stage's end writes the security review's
record there: the record quoted the application's deploy file, whose template tags are angle
brackets, and four of them were counted as marks to fill. `bb next` puts plan-check's list before
the stage's status, so a closed stage was sent back to its plan. Two changes. `plan-check` reads
what a plan step and a stage plan write - the Markdown, and a stage's gates record - and never a
record a KIT tool writes beside them: a stage's `*-security-review/` folder whole (its routing
writes Markdown too), the security pack's and the interactions' `.edn`. And `bb next` sends the
workflow to step 2 only before any stage or while stage 0 is unapproved; after that a problem is
said on an `also:` line beside the stage's own step - as the stage plan and blueprint being written until
the blueprint is signed, otherwise as a plan document to fix before the next stage's plan.

### An upgraded workspace drops a skill's old name (plan 6.5, item 3)

The prefix leaves every workspace made before it with `.claude/skills/plan/` and the rest
rendered under the old names, and `bb skills-sync` wrote only what the KIT ships: after an upgrade
`plan` would sit beside `kit-plan`, and the collision the rename fixes would stay. A rendering
says it is one - the generated note after its frontmatter - so a folder carrying the note under a
name the KIT no longer ships is stale: `bb skills-sync` removes its `SKILL.md`, and the folder when
nothing else is in it (a file the sync never wrote is kept, and said); `--check` lists it and
fails; and `bb doctor`, in a workspace, says it with the command that removes it. A project's own
skill has no note and is never touched, under an old name or any other.

### Every KIT skill takes the `kit-` prefix, and every handover names its command (plan 6.5, item 3)

The proof build's handovers used three verbs for one act - "Type next", "Type scoping", "Say
plan" - and the person asked each time whether to type the word or the slash command. The slash
command is the reliable one: Claude Code runs it directly, where a plain word relies on the model
connecting it to the skill. But `/plan` reaches Claude Code's own plan mode, not KIT's skill, and
another seat's client can collide the same way (OpenCode has a plan mode). So the four skills
after `kit-start` take its prefix - `kit-scoping`, `kit-plan`, `kit-stage-plan`, `kit-stage-end` -
and a handover gives the command: "Type `/kit-scoping` to begin step 1, the brief", and from
`kit-stage-end` "Type `/kit-stage-plan`". `bb next` names the command where it named "the plan
skill" (`/kit-start, then /kit-scoping`, `/kit-plan`, `/kit-stage-plan`, `/kit-stage-end`). The
step names stay ("scoping", "the plan, for stage 0"): they name steps, not skills. A handover
whose next act is the person's approval names no command, since none runs it. A review of the
rename found two places it missed: the workspace `CLAUDE.md` that `bb init` writes now says to
start with `/kit-start`, and the skills' README names `kit-scoping`.

### `bb next` holds a sign-off to a name (plan 6.5, item 2)

The sign-off's fix below moved the example, not the mechanism: the gates template now shows
`:by "NAME OF WHO SIGNED"`, no code read `:by`, and a record copied with the example kept passed
`bb next` exactly as `"the person"` had. Found by a review of that fix. `bb next` now reads a
passed approval or sign-off whose `:by` is missing, blank, not a string, the template's words or
"the person" (any case) as not given, and says so at the gate's own step before anything after it:
"stage 1's :stage/approved has :by \"the person\" - a sign-off names who signed", with the fix to
run. The list is those two and no more - a wider one ("the owner", a role's name) would be a
guess. `:stage/closed` names no signer and is not held.

### A sign-off records who signed (plan 6.5, item 2)

The proof build's first blueprint read "SIGNED OFF by the person", and its gates record had
`:by "the person"` in both the approval and the sign-off - the records the loop proceeds on,
naming a role and not a signer. The cause was the templates: the gates record's comments gave
`:by "the person"` beside `"YYYY-MM-DD"`, so it read as a value to keep, and the stage plans' cap
lines marked the cap and the date but not the name. Now the gates template gives `:by` as
`"NAME OF WHO SIGNED"`, in capitals like its `"YYYY-MM-DD"` - not a mark, which `bb plan-check`
would hold against a copy's comments - and says it is never a role. The cap lines read "approved
by `<who approved - a name>` on `<YYYY-MM-DD>`", a mark filled when the plan is approved, as the
date already is (not `<name>`, which the check takes for the project's name and passes). The
status lines of the stage plans and the blueprint say what they read once approved or signed - in
words, not a mark, since `bb plan-check` reads a stage plan before its approval. `plan`,
`stage-plan` and `stage-end` take the name from the brief's answer on who signs off, or ask; and
`bb next` asks for `{:date :by <name>}`. No code reads `:by`'s value; `bb stage-report` prints it,
now a name.

### Scoping opens with its question in so many words (plan 6.5, item 2)

The proof build's scoping opened with "In your own words, what are you asking for?", and the
person asked whether it meant what the project is about. It did: the method calls the brief "the
person's one-page ask, in their words", but neither the method nor the skill gave the opening
question, so the session made it from "ask". The skill now gives it - "In your own words, what do
you want built, and why? A sentence or a page; paste an existing description if you have one" -
and the answer is the brief's ask, before the six groups draw out the rest.

### The orientation names `bb next` without running it (plan 6.5, item 2)

The proof build's clean start showed two "nexts" again at the end of `kit-start`'s part 3: the
session ran `bb next` and showed its "next: step 1 - scoping" directly above the parts list's
"4 of 4: the roles and their models - NEXT". Plan 6.4 had said "nothing about scoping until the
handover", but part 3 said to show "that `bb next` says at any point which one is next", which
invites running it, and the more specific instruction won. Part 3 now names `bb next` as what
says the next step after the orientation - named, not run - and the rule every part ends by says
`bb next` is not run before the handover, where its "next: step 1 - scoping" and "Type scoping"
agree.

## 0.6.4 — plan 6.4's boundary: the proof build's first findings

Plan 6.4 added a `self-check` report that ends with READY and the next step; `bb init`'s closing
message ending with the first move (`cd ..`, a session, `/kit-start`); `kit-start` saying at each
part's end which of its four parts are done and what to type, and where the project is among the
phases; and a stage's documents named by what they are (`stage-N-plan.md`), the stage's name
optional. A workspace made before it runs `bb skills-sync` after the pull.

## 2026-10-12

### The self-check report says what it means (plan 6.4, item 1)

The proof build's first screen was `bb self-check`: 58 lines, some 300 columns wide, the optional
tools beside the required with a `missing` that is not a fault, five security warns with no word
that they are expected, a subject column out of line, and no last line saying the machine is ready.
The doctor's table is in two parts - what KIT's gates and a loop need, then the optional, whose
absence reads `not installed` - and what a tool is for, with its fix, shows only when it is not ok.
The health table's columns line up, no detail line is wider than 100 columns, a long one wrapped
with a hanging indent, and one line above the security pack's warns says they are the template's
known gaps and fail nothing. A healthy run ends with a summary - READY, KIT's tag and commit, the
template, the platform; the toolchain; the checks; what was expected - and `self-check` adds the
next step. Rows 124 and 125 are fixed. The tag's first Linux run, with six optional tools not
installed, printed a 123-column toolchain line: every line of the summary now wraps at the width,
under where its text began.

### A stage's documents are named by what they are (plan 6.4, item 4)

The proof build's orientation said "you pick the names", and nothing said whose name or why. The
stage plan was the one file of a stage named by something other than what it is -
`stage-N-<name>.md`, filled by kind (`stage-1-skeleton.md`), by feature (`stage-3-search.md`) or
anything else - while the blueprint and the gates record are named `-blueprint` and `-gates`; and
the skills printed `<name>-build/docs/stages/stage-N-<name>.md`, one placeholder for the project and
the stage. Now each thing has one place: the file names the document (`stage-N-plan.md`), the title
names the document and the stage (`# Stage 3 plan`), the kind is the plan's `**Kind:**` line, a
stage's name is optional - the stage map's column and the title, proposed by the Architect's session
and decided by the person - and the project is the folder (`<xyx>-build/`). No logic changed: the
tools read any `stage-N-*.md` as the plan; KIT's own examples and tests now use the new name. And a
template instruction `bb plan-check` could not see, hidden by a mark nested inside it, is one mark now.

### `kit-start` says where you are and what to type (plan 6.4, item 3)

The proof build's first session read part 1's end - "scoping is next after this. Questions on the
workspace, or on to part 2?" - as two next steps, and nearly typed "scoping". Every part now ends
the same way: the four parts as a list, each DONE or NEXT, and one instruction ("Type next, or ask a
question about the workspace"); scoping is named only at the handover, after part 4, as "Type
scoping to begin step 1, the brief". And part 2's phases carry a Status column from the workspace's
files - setup done, stage 0's phase in progress - so the approach says where the project is in it.

### `bb init` ends with the first move (plan 6.4, item 2)

The proof build's first workspace showed that `bb init`'s closing message did not say how to begin:
it said to start sessions in the workspace and that `bb next` says what comes next, but not the
README's next step. It now ends with it - a Claude Code session opened in the workspace folder,
Opus 5.5 at effort high, and `/kit-start` - with the `cd` into the workspace as an absolute path, so
it is right wherever `bb init` was run from; `bb next` stays the answer at any point after.

## 0.6.3 — plan 6.3's boundary: the adopter's workflow, simple and clear

Plan 6.3 added the README's *How to use KIT* - set up the workspace (`bb self-check`, `bb init`, named
after its folder), then build the application (`/kit-start` in a session there) - and what it rests
on: the workspace's front door, every KIT command run from the workspace folder (`bb
front-door-sync` writes it again after a pull); `bb brief` and scoping in the workspace; the
`kit-start` skill; the roster's model for the Architect's session; the method's five phases
grouping the stages, setup not a stage; and `build-docs/` for `plan-template/`.

## 2026-10-09

### Every command where a reader looks for it (plan 6.3, item 7)

The audit's E findings. `AGENTS.md`'s task table had no row for `bb security-review` or `bb
security-route`, which the front door offers and the stage-end skill runs, nor for the KIT's own
security fixture, scoring and bake-off; its `run-loop` steps and the harness README's lacked
`mutation`. Task docs - what `bb tasks` and the front door print - left out `bake-off new` (and the
README `reprice`), `balance`'s optional profile, `security-bake-off --report`, `health --record`
(which `self-check` passes on), `kit-status`'s health records, and what `init` writes at the
workspace root. The README's developer commands name the commit hook, `kit-status`, `tag-check`
and the container runs, and `method.md` §02 names `bb init`'s `--dry-run`, `--app`, `--build` and
`--seat`.

### The harness README's numbers and gate advice, and "the build" for the build (plan 6.3, item 7)

The audit's D4-D7. The harness README's size was a count from 2026-09-24; it is the same command's
today, with the numbers it printed. It told a project to put its commands in
`gates/default-gate-seq`, in the clone no project edits; they are in the build's `loop.edn`
`:gates`, which `bb init` writes, and the default is only the fallback - its own docstring says so
too, and an orphan comment about it is gone. "The plan repository" is the build repository, as the
code and the rest of the documents call it (`method.md` §02, the harness README, `build-docs`'
README, two docstrings). And the health check writes a record only with `--record`, which the
setup section now says.

### The roles counted, and "code reviewer" in the documents (plan 6.3, item 7)

The audit's D2 and D3. `method.md` §05 said six roles over a table of ten and called the one off
the loop the seventh; method-and-tooling §1 said six and, like its §12, left out the Blueprint
reviewer the profile requires - both now count what they list, and the template has the Blueprint
reviewer's row (one more mark to fill). Three docstrings counted the profile's roles as they were,
and one called every stop a person's; they say the roles, and each stop's owner. Since the security
reviewer became a role, a bare "Reviewer" names two: the documents say "code reviewer" for the
`:reviewer` role. What the code prints and the models are told - stop messages, prompts, the rule
source, the profiles' comments - still says "the Reviewer": `NOTES.md` row 126, carried, since a
change to what a model reads is measured before it ships.

### The loop as the code runs it (plan 6.3, item 7)

The audit's B findings, older than plan 6.3: documents described the loop as it is not. The retry
cap is per task, spent in dispatch rounds - the first and one per triage decision - not per role.
A task has three worktrees and two nREPLs, the Coder's and the Tester's; the gates run in the gate
worktree as the commands `loop.edn` names, not in a warm REPL. `bb profile` holds every verifier to
a family other than the Coder's, not three families or the seat's. A code reviewer's rejection goes
to triage under the cap, as a red gate does, and the spec review's limit stops for the person, not
the Architect. Triage fires on a red gate, a note or a rejected review; the spec review runs once
per spec, at `start`; `record` copies the run record, and `RUNS.md` is published with `bb report`.
`method.md` §07's quality-loop diagram checks the cap before triage and draws triage's routes - a
retry, the Architect's stop, the person's. `portability.md` says the harness runs Coder and Tester
one after the other, and its `claude` role table and `agy-ide` example are the shipped profiles'.

### The gate order, said the same everywhere (plan 6.3, item 7)

The audit's D1: four places gave `bb gates`' order short or wrong - the README and the root
`bb.edn` without the boundary, skills, inventory and health-block steps, the harness README with the
records before the health block, the harness `gates` task's own doc without boundary and inventory.
All four now say what the task runs and `CLAUDE.md` and `AGENTS.md` already said: doctor, format,
lint, boundary, rules, skills, inventory, health block, the records, test.

### Documents and generated text say where commands run: the workspace folder (plan 6.3, item 7)

The audit's C findings, plan 6.3's own drift. The skills are rendered into a workspace only, not the
clone (the `skills-sync` task, the harness README, `AGENTS.md`, which now lists `kit-start`, and the
README). "In the KIT's `harness/`" is "from the workspace folder" wherever a project runs the
command: the `rules.edn` and `profile.edn` headers, the build README, `RUNS.md` (which named the
KIT's path and sent adopters to `bb gates`, a KIT-only task) and the application's `AGENTS.md`
frame that `bb init` writes; the doctor's lines and the driver's plan-check hint; `build-docs`'
README, method-and-tooling and blueprint template. The harness README says how a project's commands
reach the harness - the front door, its path rule, `front-door-sync` - and the README calls the root
`bb.edn` KIT's own task list, so "front door" means the workspace's alone. Method-and-tooling §6
shows today's setup; scoping is stage 0's first step, in phase B; `kit-start` comes before scoping;
the plan review before `bb plan-check`; and two section references follow the method's new shape.
The README's *How to use KIT* numbers its two steps 1 and 2, so the letters A to E mean only the
method's phases.

### The front door: a slug is not a path, `bb next` prints paths that run, and `bb front-door-sync` (plan 6.3, item 7)

The code-and-documents audit before the tag found three faults in what plan 6.3 added. The front
door made every argument with a `/` a path under the workspace, so `bb models openai/gpt-6.1-sol`,
`--model "anthropic/… high"` and `--base origin/main` reached the harness as workspace paths; an
argument is now a path when it exists, when it has a `/` and its folder exists (a file a command
writes), or when it is a bare `.md` or `.edn` name - never when absolute - and a test runs the front
door's own code over each case. `bb next` printed `docs/stages/…` in the commands it gives, which do
not resolve from the workspace folder where they are run; it now puts every `docs/` path under the
build folder (`xyx-build/docs/stages/…`), and the stage-plan and stage-end skills do the same. And a
workspace kept the front door `bb init` wrote: a command the KIT added later never reached it, and
nothing said so. `bb front-door-sync` writes it again from the pulled KIT (from the workspace, or
with `--workspace` in the KIT's `harness/` for a workspace with none), refusing a `bb.edn` that `bb
init` did not write; `bb doctor` in a workspace says when the front door is missing or lacks a task
this KIT offers.

### `plan-template/` is `build-docs/` (plan 6.3, item 7)

The folder holds the templates of every document the build keeps - the seven of the project plan,
the stage plan, the blueprint, the pre-release and release plans, the gates record - not a plan's
template, and `bb init` copies it to `<xyx>-build/docs/`; the name now says where it lands. Every
reference follows: the commit check's list of folders that need a DEVLOG or NOTES entry, `bb
doctor`'s diff of what changed since a workspace was made, `bb init`'s copy and its "not a clone"
check (`build-docs-files`, `:build-docs`), `bb plan-check`'s given parts, the health check, the
skills, the method, the READMEs and this repository's `CLAUDE.md`. "The plan" stays the word for
what `bb plan-check` and `bb plan-review` read.

### Phases group the stages, and setup is not a stage (plan 6.3, item 7)

The walk-through's findings 5 and 11 had one cause: the method's three phases - Plan, Foundation,
Stages - no longer matched how a project runs. Setup (`bb self-check`, `bb init`) had moved before
scoping, Foundation's other half was the plan step and stage 0's first packet, and every stage has
the same four steps. `method.md` now has five phases, each a group of stages by kind: A · SETUP (no
stage: once, by the person, no plan, cap or model spend), B · PROVE (stage 0, the spike), C · BUILD
(the walking skeleton and the increments), D · PRE-RELEASE, E · RELEASE - the opening block, §01's
table and the Quick start say so, and stage 0 owes working software deployed locally, however
small ("Welcome to KIT" is enough). §02 is setup: the toolchain and the workspace, XTDB, the REPL,
the gates, `bb health`, setup's two checks. §03 is stage 0: scope, plan (the planning text, its
exit criteria with `bb plan-check` added), implement (the first packet proves the loop with your
models), deploy locally. §07 names its parts by the stage step they belong to - the blueprint, the
spec review, dispatch, the quality loop, the merge, the slice - and not as a third set of numbered
steps; its Step 5 was two things, the per-task merge and the stage's slice. Its quality-loop
diagram sent a code reviewer's rejection to a person; the loop sends it to triage. Two words now
mean one thing each, both in the words table: **machine** is the computer (hardware, operating
system, toolchain), **loop** the build machinery; the plan template's "the Foundation" is gone,
`03-method-and-tooling.md` named for what it is and the stage-0 template proving the loop.
`bb plan-review` reads its checklist from §03 and ends it with the plan step, and `bb
blueprint-review` reads §07's blueprint section by its new heading. `workflow.md`'s diagram is
grouped by phase; its step numbers stay, since `bb next` prints them. Every citation of a moved
section - "§02's checklist", "§03 step N", "§07 step N", "before Foundation" - re-aimed.

### One placeholder for the project folder: `<xyx>` (plan 6.3, item 7)

The walk-through's finding 7: the README writes the project folder `<xyx>`, while `method.md`
§03's block, `workflow.md`'s diagram and two docstrings (`harness.setup.workspace`,
`harness.loop.driver`) wrote it bare, `xyx-app`. Now `<xyx>` everywhere a folder is meant
(`&lt;xyx&gt;` in the diagram's labels, as `&lt;stage&gt;` already is). `<name>`, the project's
name where the documents mean the name and not the folder, and the tests' `xyx`, a real name the
code runs on, are unchanged. The `workspace.edn` example in `harness.setup.workspace` names the
clone `KIT`, as the README's clone makes it.

### What a newcomer's walk-through of How to use KIT found, fixed (plan 6.3, item 7)

A fresh agent with none of plan 6.3's context followed the README's *How to use KIT* from an empty
folder on the branch tip: section A for real (`bb self-check` healthy in 79s, `bb init` named after
the folder), section B as the first session would, through `kit-start`'s orientation to the
hand-over to scoping. No blocker; sixteen findings. Fixed here: `bb init` shown with a name where
the README runs it without (the clone's `bb.edn` header, the plan template's README; the README now
says `bb init <name>` names it otherwise); the workspace's generated `README.md` sending `bb
plan-check` to "the KIT's `harness/`", and listing neither `bb.edn`, `CLAUDE.md` nor `.claude/`;
"every command from there" beside `cd <xyx>-app && bb serve` - KIT's commands run from the workspace,
the application's own in its folder, as `init`'s message, method §03 and the workspace `CLAUDE.md`
now say; the roster's "six documents" (seven), its "stage document" (stage plan) and "(below)" for a
paragraph above; `workflow.md`'s "a command in the KIT's `harness/`"; and README A.2's sentence on
what the person makes in the folder. Left for the person: §02's and §03's two "Step 1"s.

### `kit-start`: the orientation a project's first session begins with (plan 6.3)

A new workspace had no way in: the README's *How to use KIT* says to start a session there and
type `/kit-start`, and nothing answered. `kit-start` is a fifth shipped skill, rendered into every
workspace by `bb init`: it walks the person through the workspace's folders, KIT's approach (the
opening of `method.md`, to the "Distilled from…" callout), the steps in order (`workflow.md`), and
the roles and the models they run on, with the roster's recommendation for this session - Opus 5.5,
effort high, set with `/model` and `/effort`, the person deciding - then hands over to `scoping`, or
names `bb next` once a brief is filed. Not a step of the workflow, and it writes nothing, but in the
one anatomy the other four keep (its walk-through is its *Asks*, its *Writes* is nothing). The
workspace's `CLAUDE.md` routes a first message to it while no brief is filed, and says the
commands it names are run from the workspace, not "in the KIT's `harness/`" (three places item 3
missed). `bb next`'s step 1, `workflow.md`'s row 1, `skills/README.md` and the README's `skills/`
row name it. Checked: a real `bb init` in a scratch folder renders `kit-start` beside the four, its
`CLAUDE.md` (28 lines) carries the routing line, and `bb next` there says "kit-start, then the
scoping skill"; the walk-through itself is item 7's to try, in a fresh session.

### The documents give the steps as How to use KIT does (plan 6.3, item 5b)

With scoping in the workspace (5a), every document that gives the steps now gives them in the same
order as the README's *How to use KIT*. `method.md` §02 opens with *Step 0 — Setup, the machine and
the workspace* and then *Step 1 — Scoping, the first conversation in the workspace*, whose brief
`bb brief` files; its quick start puts setup first, out of Foundation; §03's Step 1 block is the
README's: a project folder, KIT cloned into it as `KIT/`, `bb self-check`, `bb init`, `cd ..`.
`workflow.md`'s diagram and its table's rows 0 and 1, the plan template's README and `source.md`,
the root README's `skills/` and `workflow.md` rows and its commands block, `harness/README.md`'s
command block and inventory row, `harness/AGENTS.md`'s task table and the `next` task's doc follow;
the `scoping` skill cites the renamed heading.

### Scoping in the workspace: `bb brief`, and `--brief` gone (plan 6.3, item 5a)

Scoping is the project's, so it runs in the workspace, the first conversation there, and KIT
guides it. `bb init` no longer takes `--brief`: it copies `source.md` as shipped. The `scoping`
skill runs in the workspace's session - refusing where there is no `workspace.edn`, and saying setup
is step 0 - and writes the brief to `work/brief.md`; the new `bb brief`, on the workspace's front
door, files it into the build's `docs/source.md` as §1's first row and Appendix A, in the format
`--brief` used, and refuses a missing file, a missing `source.md`, and a second filing - scoping
runs once. `bb next` says setup (`bb self-check`, `bb init` in KIT's clone) outside a workspace and
scoping in one with no brief: setup is step 0, scoping step 1. KIT's clone no longer carries a
rendering of the four shipped skills - only a workspace does - and `bb skills-sync --check` there
checks every skill's citations and says nothing is rendered. The `plan` skill and `skills/README.md`
follow. Checked: a real `bb init` in a scratch `probe-site/`, then from there `bb next` (step 1,
scoping), `bb brief` with no file (refused), with one (filed), again (refused), `bb next` (step 2,
the plan). The method, `workflow.md` and the other documents that give the steps follow in 5b; until
then the `scoping` skill still cites the method's old heading for step 0.

### `bb self-check`: the doctor, then the health check (plan 6.3)

The README's setup checks the machine in one step. `bb self-check`, at the clone's root, runs
`bb doctor` and, only when its loop verdict says yes - a JVM included, what a project needs - `bb
health`, which takes minutes and starts JVMs and so is not started on a machine that cannot pass it.
On a no it says to fix what the doctor's table shows and run it again, and exits with the doctor's
code; flags are passed on to the health check (`--keep`, `--selfcheck-only`). A setup command, so
in the clone's `bb.edn` and not the workspace's front door. Checked: `bb self-check` here - both
verdicts yes, then healthy, 9 checks in 81s, exit 0, nothing left listening on port 8000; the
doctor's no path by reading it, not run.

### The workspace's front door: every command run from `<xyx>/` (plan 6.3)

A project's session sits in the workspace folder, but its commands ran in KIT's `harness/` - two
folders, and a relative path meaning a different file in each. `bb init` now writes `<xyx>/bb.edn`,
generated (`harness.setup.front-door`): one task per command a project runs - `bb next`,
`bb plan-check`, `bb plan-review`, `bb run-loop`, `bb profile` and the rest, each with the harness
task's own doc - handing on to the same task in KIT's `harness/`, which it must run in (it reads
`resources/` and finds KIT as the folder above), with `KIT_WORKSPACE` set to the workspace and every
argument that looks like a path made absolute from it. KIT's folder is read from `workspace.edn`
when a task runs, so the workspace may move and the clone be renamed; a command's exit is the task's.
The KIT's own development tasks - the gates, the syncs, the security fixture, the tour, `init` and
`health` - are not offered, and a test holds every harness task to exactly one of the two lists, so
a new command cannot miss the front door unnoticed. `bb init`'s closing message, the workspace
`CLAUDE.md` and the `stage-end` skill say "from the workspace folder" - which also makes the security
review's relative `--out <name>-build/…` mean the build repository, where from `harness/` it meant a
folder inside KIT's clone. Checked: a real `bb init` in a scratch `probe-site/`, then from there
`bb next` (exit 0), `bb plan-check` (exit 1 on an unfilled plan, passed through) and a relative
path resolved inside the workspace.

### `bb init` with no name: the project is named after its folder (plan 6.3)

The README's setup is a project folder, KIT cloned into it, and `bb init`. Until now `init` needed
the name typed again; with none it now takes the name of the folder the clone is in - the folder
that is already its default workspace - so `bb init` alone makes `<xyx>-app` and `<xyx>-build` in
`<xyx>/`. A name given still wins. A folder name that cannot be a project's (lowercase letters,
digits, single hyphens, starting with a letter: it becomes folder names and the application's
namespace) is refused in a sentence that says to rename the folder, with a name made from it
(`MyProject` → `myproject`), or to give one; the default folders made from a bad name are no longer
refused a second time. README A.2 states the rule. Checked: `bb init --dry-run` in a folder named
`probe-site` lays out `probe-site-app` and `probe-site-build`; in this clone's folder, `KIT`, it
refuses once and suggests `kit`.

### The roster recommends a model for the Architect's session (plan 6.3)

The acts done in the seat's own session - the plan, each stage document, each Blueprint, each spec,
the loop's `architect` stops - said only "the session's own; not in the profile", so an adopter
starting a project was given no model for the session that does them. `harness/roster.md` now
recommends **Opus 5.5, effort high**: what KIT was built and tried with, for acts where a weaker
model's mistakes become the contract every dispatched role follows. Still not in the profile - the
session is the person's to start - and `kit-start` (plan 6.3, item 6) says so at a project's first
session.

### How to use KIT: the README for adopters first (plan 6.3)

Starting a project took eight steps across four documents. The README now opens, after its
introduction, with **For adopters**: the three installs, then *How to use KIT* in two steps - A. set
up the workspace (a project folder, KIT cloned into it as `KIT/`, `bb self-check`, `bb init`) and B.
build the application (a Claude Code session in the project folder, `/kit-start`) - then where KIT
was last shown to work, whether it is worth it, and its known limitations. **For KIT developers**
follows: the five parts, the commands, and where things live. The section was written with the
person as the TARGET: `bb self-check`, `bb init` with no name, `/kit-start` and the roster's
recommendation for the Architect's session are plan 6.3's items, built next. The table of lowercase
`kit` uses is gone: the template is named in full, `ontopro/clojure-stack-lite` at tag `kit-v1.2`,
and the table's two other facts - the clone may be renamed or kept outside the workspace; the
fork's `kit` branch is KIT's line and its `master` the upstream mirror - are under "Where things
live". `CLAUDE.md`'s Vocabulary rule is sub-bullets and says **KIT**, with no article; the README's
prose follows.

### The build repository is `<name>-build` everywhere it is named (plan 6.3)

Row 87 renamed `bb init`'s second repository from `<name>-plan` to `<name>-build` on 2026-10-06,
and these still said the old name: both `bb.edn`s' `init` help (the root's said the default folders
were `<name>-app` and `<name>-plan`), `CLAUDE.md`'s line on where a project's records go,
`method.md`'s step 1 block, and `plan-template/`'s `02` and `03` - which `bb init` copies into every
new workspace. Found while fixing row 123. `NOTES.md`'s rows and this file keep the old name where
they record what was so.

### `bb init --brief` works from the KIT's root (plan 6.3)

Walking through how an adopter starts a project, the first command after scoping failed: `bb init
<name> --brief <file>` at the clone's root printed "`--brief --dry-run is not a file`". The root
`bb.edn` parsed `init`'s arguments with its own loop, which knew only `--seat` as a flag with a
value, so the brief's path was taken for the workspace folder; from `harness/` it worked, and
`workflow.md`'s diagram shows the root form. Now one parse serves both: `harness.setup.init-args`,
which requires nothing so that the root (no dependencies on its classpath) can load it, parses for
`init` and builds what the root hands on, with the folder AND the brief made absolute - a relative
brief typed at the root would otherwise be read from `harness/`. Row 123, fixed.

## 0.6.2 — plan 6.2's boundary: the KIT's own development checked

A workspace made before `0.6.2` needs nothing: this plan changed only how the KIT itself is
developed - a commit check, an item's-end report and a tag check, run from the KIT's root and never
copied into a workspace - and nothing a workspace takes from the KIT changed (`git diff 0.6.1 0.6.2
-- skills plan-template harness/resources tools` is empty), so `bb doctor` run in one says how far
the KIT has moved and lists nothing to act on.

## 2026-10-09

### A browser that may not start says how to allow it (plan 6.2)

In `0.6.2`'s first tag run Firefox could not start: the application the session ran in was not
allowed to read Firefox's folder. `bb health` said only "run once from a terminal and grant it the
permission", which named neither where the permission is nor what to grant it to. The health
check's detail, the browser pack's printed message and its README now give the steps: on macOS,
System Settings > Privacy & Security > Files & Folders (or Full Disk Access) > the application the
session runs in - Terminal, iTerm, the IDE, the Claude app - allowed, or the check run once from
that application and its prompt answered; then the check run again. With access granted, the
rerun was 9 of 9 on both platforms.

### The KIT session's routine is a skill (plan 6.2)

The commands this plan added each check one step; the order the steps come in - start a session
from the plan's task list, commit an item through the gates and its document, read `bb kit-status`
at its end, take a tag through `bb tag-check --run`, the documents, the person's merge, tag and
push and `--landed` - was still in whoever remembered it. It is now the `kit-session` skill in the
clone's own `.claude/skills/`. It is not one of the KIT's shipped skills: those live in `skills/`,
which is what `bb init` renders into a workspace and `bb skills-sync` holds to the method, and
this one is about developing the KIT, which no workspace does.

### An item's end is reported, not remembered (plan 6.2, items 7, 8, 9, with 2 and 6)

At the end of each piece of work there were things to look at - whether the health records still
covered the code, whether a test had flaked, which register rows the plan had opened and left,
whether the plan's own log had been written, whether working folders said what they held - and
each was looked at when someone remembered to. `bb kit-status` prints all five, each `ok` or a
list, and never refuses: an item's end is a judgement, and a row left on watch can be right. The
health report is `bb tag-check`'s own check; the flaky tests are the gates' kept runs since the
last version tag; the register's rows are those `NOTES.md` has that it did not have at that tag,
still open or on watch; the plan is the file the `plan-v*` branch names, behind when it was last
written before HEAD's commit; and every `.local/` folder in the clone is asked for a `README.md`
that names each entry in it. Its first run found eight working folders the KIT's own `.local/`
README did not name; they are named now.

### A tag is checked before it is cut (plan 6.2, item 10)

The pre-tag checklist at the head of this file was five steps in an order a session had to keep.
`bb tag-check <tag>` reads each one off the repository and prints a line per check: the tree clean
on a plan branch or `main`; the health records fresh on both platforms; the tag's heading here and
its line; the root README naming it; the tag absent here and on origin; the pinned template's tag
on the fork at the pinned commit; the security pack's secrets scan over the commits since the last
tag; the gates record green for HEAD's tree; and the fresh-machine container green for HEAD. It
never cuts the tag. A health record is stale when anything `bb health` exercises - the harness's
source, resources and health folder, its `bb.edn`, `tools/`, the `Dockerfile`, the root `bb.edn` -
changed between the commit it names and HEAD; the records' own commit changes none of that, so a
record committed after its run stays fresh. `bb docker-gates` now writes a record of the commit it
cloned and its exit, red or green, which is what the last check reads; it says when the tree had
uncommitted changes the container did not see. `--run` does what is stale in the checklist's
order and stops at the first red; after new health records it stops, since the containers clone
HEAD and the records must be committed first, and a second `--run` goes on to the gates and the
container. `--landed`, after the person's push, checks that origin's tag and `main` are both at
the tag's commit. Tried on a clone at `0.6.1`: every check of content held, and the three it
could not pass there - the tag exists, a fresh clone has no gates records - said so.

### A project's name is refused at commit (plan 6.2, item 5)

The KIT's documents speak of the projects built with it by their order - "the first project built
with the KIT" - and never by name, so that nothing here points at records this repository does
not hold. That was a rule for whoever wrote the line. The commit check now reads every line a
commit adds and refuses one that carries a name from a wording list kept outside the repository
(`.local/wording/names.txt`, gitignored, so the list never names a project in a committed file),
saying the file, the line and the name. A name matches as a whole word, in any case: no letter or
digit just before or after it, so it is caught at the start, in the middle or at the end of a
hyphenated, underscored or dotted compound and not inside another word, and a numbered variant is
listed as its own entry. A clone with no list says so at every commit and checks nothing. Only
added lines are read: a name already committed is not this check's to find.

### A NOTES.md edit moves its stamp (plan 6.2, item 4)

`NOTES.md` opens with `**Updated <date> <time>`, and the stamp is how a reader knows the register
is current; an edit that leaves it behind says the register is older than it is. The commit check
now reads the staged `NOTES.md` and refuses it when its stamp is not later than HEAD's, when it is
later than the clock (to the minute, in local time, as the stamp is written), or when there is
none. A commit that deletes the file is not asked. What the stamp's summary says is not checked.

### Code is committed with its document (plan 6.2, item 3)

"A finding is not finished until a document carries it" was a working rule, and it was broken
within the hour it was written: two findings went into a commit message and nowhere else. The
commit check now holds it for the commits it can see. A staged change under `harness/src/`,
`tools/`, `skills/` or `plan-template/` is refused unless `DEVLOG.md` or `NOTES.md` is staged
with it, and the refusal names the paths. It cannot tell whether the entry is about the code,
only that one was written in the same commit; tests, records and the other documents are not
asked for one.

### A flaky test is named, not rerun until it passes (plan 6.2, item 2)

A test that fails and then passes on a rerun of the same code was, until now, seen once by
whoever reran it and then forgotten. `bb gates` now keeps every run's record under
`.local/gates/runs/` beside `last.edn`, and after a run that reached the tests it names each test
that failed in an earlier run on the same tree and passed in this one: the same tree means nothing
changed but the run, so the test is flaky. A pass on another tree is a fix and is not counted, nor
is a later run that stopped before the tests. The check is one function, which the item-end report
planned for this plan will call as well. The records are kept, all of them.

### A commit the gates did not pass is refused (plan 6.2, item 1)

"`bb repair && bb gates` green before committing" was a working rule, and a rule a session has
to remember is the kind this repository keeps finding broken. `bb gates` now writes a record of
every run, green or red, to `.local/gates/last.edn` at the repository root: the tree it ran on,
the step that failed and the tests that failed by name, HEAD, the start and the end. The tree is
the working tree as git would commit it with `git add -A`, taken before and after the run, so
an untracked file counts and an edit made while the gates ran is seen. A pre-commit hook,
committed in `.githooks/` and switched on once per clone by `bb hooks-install`, runs `bb
commit-check`, which refuses a commit when there is no record, when it is red, or when the tree it
names is not the tree being committed. So a file edited after the gates, a partial commit, or a
stray untracked file at gates time is refused until what was gated and what is committed are the
same. A git hook rather than an agent client's hook because it holds every commit, made by a
session started in any folder or by hand. `git commit --no-verify` skips it; that is said here,
not hidden. A container's clone writes its own record, which nothing reads.

## 0.6.1 — plan 6.1's boundary: security asked and tried

A workspace made before `0.6.1` lacks the security reviewer role in its profile, has the application
template pinned at `kit-v1` where the KIT now pins `kit-v1.2`, has a rule mirror without the rule that a
request value is checked before it is used, and has an older architecture document template, stage
templates and three skills; `bb doctor` run in it lists each with the command that shows the
difference, and nothing is migrated - the role's block is copied from the shipped example, and an
application already generated keeps the template it was made from.

## 2026-10-08

### Every stage's end reads the code for what a request cannot show (row 121 fixed)

The security pack tries from outside what a request can; the security reviewer now reads the merged
application at every stage's end for the rest. The `stage-end` skill runs it on a fresh clone of the merged
tip, its change measured from the previous stage's tag, with the person's agreement to its cost; routes what
it reproduced; and goes through every finding with the person. A fix drafted for the coder becomes a packet of
the stage, and the stage does not close until the security reviewer's own test passes on the merged tip. A
gap in the design is the Architect's, a risk is the owner's to accept or decline in the threat model, and a
hypothesis is always a line there. The method's security paragraph says what the security reviewer reads and
why its findings are routed rather than fixed; the roles table and the roster carry it.

### The security reviewer is a profile role (row 121)

The eighth role, and required, as the plan reviewer was when it became one: a profile without it is
refused, and `bb doctor` in a workspace made before says the role is missing. It reads what the Coder wrote
in order to disagree with it, so it is a verifier and `bb profile` holds it to another family than the
Coder's. Both shipped examples set it to GPT-6 Astra at high effort, pinned to OpenAI - the choice of two
bake-offs on the planted-fault fixture, said in the profile's comment in words. `bb security-review` reads
that role when it is not given a model, so a stage's end runs it from the workspace's own profile.

### A request value is checked before it is used: a KIT rule (row 121)

The login flaw in the fixture passed every role that wrote or read the code, because the type the code
expected was a string and nothing told any role a request can send something else. The architecture
template says so of the pin, but the Coder, the Tester and the code reviewer read the rule source and their
packet, not the architecture document. A non-negotiable rule now tells all three: a request value is checked
to be the type its use expects before it reaches a query, a file path or a page, and a value of the wrong
type is in the domain, since nested parameters can send a map. The security reviewer should now meet this
at a stage's end only where a role missed the rule. Triage of security findings stays on the shipped
model, by the person's decision; its refusals are watched.

### Security triage tried: the model declined one call in four, and triage now says so (row 121)

`bb security-route` ran twice for real over the two flaws the security reviewer found in the fixture, with
the shipped triage model. The logout finding went to the architect both times - the design never said a
logout ends a session. The login finding went to the coder once, citing the architecture's sentence that a
request value reaching a query is checked for its type; the second time the model stopped partway through
the same verdict, its finish reason `content_filter` and its native one `refusal`. Triage had read that as an
answer with no verdict in it. It now reports a refusal as one, and the finding goes to a person as before;
the check is shared with the security review. The prompt was not reworded to get past it.

### A reproduced security finding is routed, not fixed (row 121)

A security finding is a blocker only when the security reviewer reproduced it, and even then the next step
is not obvious: the finding passed every packet's gates, tests and code reviewer, and the stage's own
tests, so it is more often a gap in the contract - a check nobody asked for, or one on the wrong route or
method - than a slip a Coder patches. Turning it straight into a fix task would hide the gap. Triage gets a
fourth trigger, `:security-finding`, with three routes - `coder`, `architect`, `human` - and a prompt that
carries the finding, the security reviewer's test, the files the finding names as merged and the
architecture's security sections, and says where the finding came through. `bb security-route` asks it
for each reproduced finding of a review. Nothing is dispatched: a `coder` route writes a fix packet's
draft in the Blueprint's format, with the file and the promise triage named and the slice left for the
Architect; the security reviewer's test is the fix's acceptance check, never the Tester's context, since
it was written from the code. A hypothesis is listed for the threat model and never routed.

### Two more limits of the template, said where a project reads them (row 121)

The two flaws the security reviewer found in the fixture were a template default meeting a feature, so any
project with accounts can meet them. The person decided to record them, not to change the template: the
architecture template's §4 now says, under "Not given", that a logout cannot end a session the server keeps
no copy of, and that a form field can arrive as a map, which HoneySQL formats as SQL; its SQL row points
there. Checked against the pin's HoneySQL: a map in a value's place is formatted as SQL, a vector of strings
stays parameters, so the line names maps only.

### Bake-off 1 re-scored by the final rule (row 121)

Its fourth reading scored, no model call: GPT-6 Astra 6 of 6, every claim shown by its tests, none failing
on the careful branch, $1.26 and 9:55 of work; GLM-5.3-prime 3 of 6, the round limit on both branches, $2.71
and 26:52. The figures first given as provisional stand.

### Two flaws in the fixture's careful feature, found by the security reviewer (row 121)

The careful branch is meant to have nothing to find, and a reviewer's test that fails there is either a
wrong test or a real flaw nobody planted. Three such tests from one model's reading were run by hand on a
fresh clone. Two were real. The template's parameter middleware makes a map of `username[raw]=...`, and
HoneySQL reads a map where a value belongs as SQL, so a login could `UNION` another account's password hash
in and sign in as the first account - the administrator - with the attacker's own password. And a logout
cleared the browser's cookie but not the login: the session is a signed cookie the server keeps no copy
of, so a copy of it still read a private note. The login now takes strings only, and an account carries a
session generation that a logout advances. A control reference test for each holds on both branches and
fails with only its own fix taken out; the builder's key holds. The third was a test fault: it counted a
query only the faulted branch makes. Both flaws are a template default meeting a feature; whether the
template should change is the person's call.

### The security reviewer is named as one (row 121)

`reviewer` is the code reviewer, the `:reviewer` role every task's patch passes; the security reading's
names had grown up beside it without saying which reviewer they meant. Its tools and its sandbox are now
`harness.models.security-review-tools` and `harness.models.security-review-sandbox`, the request script
lives in `resources/security-review/` (the container still mounts it at `/review`), and the docstrings,
task docs, inventory rows and register row say "security reviewer" where they said "reviewer". New names
for it start `security-`. The prompts the model reads are unchanged, so the bake-offs' figures stand.

### `bb security-review`: a review whose findings are failing tests (row 121)

The reviewer is a model with the five tools in the sandbox, in one of two stances: the tracer, given the
repository and asked to follow requests through routes, middleware and the state they share, and the
change reviewer, given the stage's diff. The prompt asks for a test of its own for each concern, run to
see it fail, and says in so many words that finding nothing is a complete answer and an invented finding
costs its readers more than a missed one; an answer with no findings block is reported as saying
nothing, not as a clean review. The tests it wrote are collected from the clone and kept beside the
record. The command is a real model call and says so before it spends.

### Bake-off 2, scored: Astra 5 of 6, GLM-5.3-prime 3 of 6; one reviewer for now (row 121)

Same two models, tracer only, sixty rounds, with a review able to rewrite its own tests and its tests run in
a fresh copy. GPT-6 Astra: five of six planted faults (it missed the race), thirteen completions on the
faulted branch, $2.41 and 18 minutes of work for both readings, 598,185 tokens. GLM-5.3-prime: three of six
(the same three as every earlier run), no answer on either branch, $2.85 and 43 minutes of work,
2,415,005 tokens; its careful-branch reading alone took 34 minutes and $2.37 and hit the round limit with
nothing to find. Whole run $5.26, 3.01M tokens, 1:01 of work. Across the six faulted runs scored so far
GLM never found a fault Astra missed and wrote no answer in half of them, so the person decided on ONE
reviewer for now - Astra - with GLM kept as a recorded result, not erased. Bake-off 1 was being re-scored
by the rule below when it was stopped, three readings of four done; the figures first printed for
bake-off 2 are superseded by these.

### A namespace ran or it did not (row 121)

The scorer's test for "does not compile" scanned a run's whole output for compile-error text, and was wrong
a third time: in bake-off 2 five of Astra's careful-branch tests that ran and failed with ordinary
assertions (`expected 200, actual 500`) were called not compiling, because the application's own log
lines in the same output can say `Unable to resolve symbol` of a request. A namespace that loads and runs
prints `Ran N tests`; one that does not prints no such line. The status is now read from that: ran with a
failure is a failure, never ran is not compiling. No output text is guessed at. Every score is re-made
again; the bake-off 2 figures first printed are superseded.

### The report is two tables (row 121)

The person asked to see the bake-off report as a table and to make that the default. The report was a
section for time, one for cost, one for tokens; it is now one table by model - the metrics as rows, a
column per candidate, so two models read side by side - and one by reading, then a line for the whole
run with its wall-clock, and short notes on what a dash means. The figures are the same.

### A report after every bake-off (row 121)

At the person's request, a run no longer ends at a table. `bb security-bake-off` records each invocation
(when it started and finished, the models, the settings, the KIT commit) and writes `report.md` from the
folder's records: time per reading and model split into the sandbox start, the review and the scoring,
with the work added up against the wall-clock; cost per reading and model, with a cost a host has not yet
reported said and left out; tokens - uncached and cached input, output, reasoning - per reading and
model; how each reading went (completions, tool calls by kind and per completion, errors, tests written
and rewritten, how it ended); and the result per fault found, in cost, work and tokens. Every figure is
from a record. `--report <dir>` writes it again for a run already made - bake-off 1's was made that way,
without a sandbox start or a wall-clock, which it was not recorded with.

### The reviewer's tests run in a fresh copy, not in the clone they were written in (row 121)

One of GLM-5.3-prime's tests wrote a decoy file into `target/` and threw `FileNotFoundException` when scored
in a fresh clone. In the review it had passed that line, because the model's own whole-suite run (completion
ten) had made `target/coverage` there; the clone also held the live application's database, an `exports/`
folder and a `.cpcache/` from earlier steps. So the evidence a model gathered for "reproduced" came from a
different tree from the one that is scored: a test could pass in the review and fail fresh. Every test run
in the sandbox now starts by copying the application into an empty folder - the files git tracks plus the
untracked ones it does not ignore, so the reviewer's own tests come along and `target/`, `db/` and
`.cpcache/` do not - and runs there. Tried on the dirty clone: the same `5 failures, 1 errors` and the same
`FileNotFoundException` the scorer sees, and the clone's folders unchanged by the runs. The live
application, which the `request` tool talks to, still runs in the clone.

### A stack trace is not a compile error (row 121)

The scorer called a test file "does not compile" when its output said `Compiler`. A test that fails with
an exception has `clojure.lang.Compiler` frames in its stack trace, so GLM-5.3-prime's change-A review -
five tests that fail properly on the faulted branch, one with an error - scored 0 of 6 with every test
"does not compile", and its four findings looked not reproduced. Run in the clone it was written in, and
again in a fresh clone of the faulted branch, the file loads and fails as the model said. The check now
looks for what a namespace that will not load actually says (a syntax error, an unresolved symbol, a
namespace or class that cannot be found), with a test for a run whose trace names the compiler. The
second scoring bug of the day; both undercounted a model, which is how they were found. Every score made
before this is re-made: the table below the next heading was scored with this check and is superseded.

### The first security bake-off, scored: six of six against three of six (row 121)

Tracer only, the fixed fixture, sixty rounds, each model on the faulted branch and on the careful one; the
four readings re-scored by the per-test scorer after the first scoring undercounted a bundled file.
GPT-6 Astra: all six planted faults, seven claims each shown by a test of its own, no test failing on the
careful branch, twelve completions, four minutes, $1.26 for both readings. GLM-5.3-prime: three of six by
tests that separate the branches, no answer written on either branch, the round limit hit twice, nine and
ten minutes, $2.71. One sample of each; GLM answered in 46 completions on a different day, so its result
varies. The model that declined the review outright is not in the table.

### Why GLM-5.3-prime used all sixty rounds and wrote no answer (row 121)

Both its readings in the first bake-off ended at the round limit with no answer, and the records say why;
none of it is a refusal or an error - all sixty completions ended in a tool call, the last included.
It sends one tool call per completion (1.2 on average, against 2.4 and up to 8 for the other candidate,
which finished in twelve completions with a quarter of the output tokens). On the faulted branch it read
for nine rounds, then spent thirty-five on single HTTP requests, then wrote tests until the limit. On the
careful branch, where there was nothing to find, it sent fifty-two single requests and one test run and
never concluded that nothing was wrong: nothing in the prompt says when to stop. And its own tool cost
it rounds: `write_test` refused to overwrite a file that existed, so a wrong test could not be fixed in
place, and it wrote `web_authz`, `…2`, `…3`, `…4`, `…5` and `notes_search_injection`, `…2`, `…3` - twelve
writes for about five tests, the broken ones left behind as the files that do not compile.

The refusal to overwrite was there to protect the application's tests; it never needed to cover the
reviewer's own. A review may now rewrite a test file it wrote itself, and nothing else. The other two
causes - no stated budget or stopping rule, and probing by single requests instead of a test - are
not yet changed.

### The scorer judges the `deftest`, not the file (row 121)

In the first bake-off the first candidate's faulted reading scored 0 of 6 where the same model had scored
6 of 6 an hour before. The review was no worse: this time it put seven checks in one test file, and a rule
that a file must pass when one fault is reverted cannot attribute a bundle, since no single revert makes
the whole file pass. The first review had happened to write a file per concern. The unit is now the
`deftest`: each namespace is still run alone, and each test in it takes its status from the failures the
run names; a namespace that does not compile fails every test in it. A finding names a file, so it gets the
best class of the tests in that file; the count of planted faults found is by test and does not depend on
that. A reading whose review is in the folder and whose score is not is scored again without a model call,
so the four readings of the first run were re-scored by the corrected scorer at no cost.

### `bb security-bake-off`, and GLM-5.3-prime with room to finish (row 121)

With sixty rounds GLM-5.3-prime finished in forty-six (515 s, $0.97) and reported five reproduced findings
and, honestly, a sixth it could not demonstrate - the quota race, which it suspected but whose test it could
not get to run against the in-memory test database. The thirty-round limit was the whole of the first
failure. Scored: three planted faults detected by tests that separate the branches (the note read by id,
the export path, the search); two more claims are true - the administrator route and the SQL search are
faulted - but their tests fail on the careful branch too, because they are wrong (one registers its
"plain user" first, and the first account is the administrator; one asserts a name that is legitimately
on the user's own page), so they do not count; two files do not compile. The first candidate's six of six
stand against three of six detected and five claimed.

The comparison of models is its own command, not the plan-reading bake-off: that tool compares
one-completion readings under a judge, and a review is minutes of tool calls in a container scored by
arithmetic. `bb security-bake-off` runs each model as the tracer on the faulted branch and on the careful
one, scores every reading, and prints planted faults found, claims its tests did not show, what is left for
the person, tests failing on the careful branch (the inventions), completions, minutes and cost. Tracer
only for the first comparison: the diff reviewer would read the same code here, as this fixture's diff is
the whole feature.

### GLM-5.3-prime's first review: no answer, no faults found (row 121)

The second candidate, `z-ai/glm-5.3-prime` at high effort as the tracer on the fixed fixture, used all
thirty rounds (456 s, $0.64) and never wrote an answer. It sent its requests one per completion - ten in a
row - where the first candidate batched four reads to a completion and finished in fifteen, so a round
limit that suited one model may have cut the other off before it concluded. Scored on its tests alone, since
it reported nothing: none of the six planted faults. Of its eight test files, four pass on the faulted
branch - among them its attempts at the export path and the note isolation, both of which are defects there,
so those tests were wrong rather than the concerns - and three do not compile. One run of each model is
an anecdote; the thirty-round limit is the first thing to change before the bake-off.

### `bb security-score`: which fault a test detects is mechanical (row 121)

The by-hand scoring of the first real review compared the faulted and the careful branch, which says a
test discriminates but not which of six faults it is about. The builder now makes a variant per fault -
the faulted feature with only that fault reverted - and checks each (the other five reference tests fail,
the reverted one's passes); the scorer runs each test the reviewer wrote alone, on the faulted branch and
the careful one and, for those that discriminate, on every variant: the variant on which it passes names
the fault it detects. What does not discriminate is classified, not judged - fails on both (a flaw
nobody planted, an invention, or a setup the application refused; the first failure is quoted, for the
person to mark), passes on the faulted branch, does not compile. On the first review, against the fixed
fixture, it finds six of six, attributes the two search-injection tests to the one fault, shows the
refused-setup test as one to mark and the fixed registration race as claimed and not reproduced. Eight
minutes, no model.

### The fixture's careful feature is fixed where the reviewer found it broken (row 121)

The first-administrator race and the username that became a file name were the fixture's own mistakes, not
planted faults, so a control that fails them is not a control. Two reference tests that hold on both
branches came first and failed on the unfixed feature (the registration one ten runs of ten); then the
feature was fixed - a username is validated, and whether an account is the first is decided inside the
statement that inserts it - and the builder's key held: the careful branch fails nothing, the faulted one
exactly the six. The earlier reviewer's twelve test files, run again on the fixed fixture, still separate
the six planted faults; its registration-race test now passes everywhere, and two of its tests that
register a hostile username as their setup now fail on both branches because the application refuses it -
a test that fails in its setup is not a reproduction, which the scorer will have to tell apart.

### GPT-6 Astra did the review: all six planted faults, and two nobody planted (row 121)

The same hand-try with `openai/gpt-6-astra` at high effort - the same price as the model that declined -
ran fifteen completions in 372 s, read the files, wrote twelve test files and ran them ten times, and
reported eight findings, each with a test of its own. Run one namespace at a time on the faulted branch
and on the careful one, six of the eight fail on the faulted branch only and are exactly the six planted
faults (the search injection by two tests); the other two fail on both. They are real: the first account
is made administrator by a count and an insert in two steps, and a username with path characters becomes
the name of the export file - flaws in the fixture's careful feature that its author did not know of.
Of the twelve files, one is a concern that did not hold (it passes both ways, and was not reported), one
does not compile (left over, not reported, and it breaks a whole-suite run), one is a helper. The whole
scoring was done by hand here; it is the scorer's job next.

### Fable 5.1 declined the review; the tool now says so (row 121)

The third real review, with the step diagnostic in place, showed what the empty completion was: finish
reason `content_filter`, native reason `refusal`, and a `refusal` field in the reply. Fable 5.1 declined
to do the review - after reading fifteen of the application's files - in all three runs. It was not an
output cap, a tool fault or an empty answer, and both earlier explanations here were wrong. A refusal is
the model's own safeguard, so the review is now reported as one (`:refused?`, a message saying the review
did not happen, and a failing exit) instead of as an empty answer; in a bake-off it is a result for that
candidate. No prompt was reworded to get past it: whether and how to proceed with a model that declines
is the person's decision. About $3 was spent finding this out.

### A completion that returns nothing now says why (row 121)

The larger output ceiling did not change the first real review: the same six completions, 106 s, eighteen
files read, and a last completion with no text and no tool call - after 2,248 output tokens, a fraction
of any cap, so it was not cut off. The earlier explanation was wrong, and the record could not say what
was right because a step kept no finish reason. A step now keeps the host's `finish_reason`, and for a
reply with neither text nor a tool call the names of the fields it did carry - names only, since a
reasoning field can be long. The next review will say what the empty reply was.

### The first real review came back empty: its output ceiling was a route's, not a reviewer's

Fable 5.1 at high effort, as the tracer on the fixture's faulted branch: six completions, 105 s, eighteen
files read and one search, no test written, and a last completion with no text at all. The family's route
caps output at 16,000 tokens - sized for an answer - and a reviewer thinking at high effort over a dozen
files can spend that before writing a word; the model itself allows 128,000. That is the likely cause
and was not seen directly: the record kept only the cost, and the late generation record was not yet
there. The review now asks for 64,000 (`--max-tokens` changes it), keeps each completion's step and a
compact transcript in the record, and says so when the final completion is empty.

### A candidate line is expanded by the catalogue

The bake-off turned a line like "anthropic/claude-fable-5.1 high" into a role block with three functions of its
own, and the security review needs the same line read the same way - while the bake-off will in turn run
the review as one of its acts, so neither could require the other. The three functions use only the
catalogue and its routes, so they moved there; nothing about them changed, and their tests moved with
the calls.

### The security reviewer's tools begin: a registry of its own, and a search (row 121)

A security reviewer reads the code and checks a concern by writing a test and seeing it fail, so
its tools are a test harness's: read a file, search the tree, send one request to the application
under test, write a test, run the tests - and no REPL. They are the same for every model that fills
the role, so a comparison measures the models and not their tools. They live in
`harness.models.review-tools` as a registry of the shape of `tools/specs`; `declarations` and
`invoke` take an optional registry and `converse!` an option to name one, which leaves the coder's
five tools - a test pins them - exactly as they were. The registry came with the
search; `write_test` follows - a new `*_test.clj` file below `test/` and nothing else, since the
application's own tests are not the reviewer's to change and a test altered to pass would hide what it
was written to show - then `run_tests` - the whole suite by default, or one namespace, in a sandbox the tool is handed as
two functions and knows nothing more of, so the tools are tried without a container and the container
is its own commit. The coverage runner's output is cleaned first: its progress bar, rewritten with
carriage returns, was five-sixths of a failing run's output (7.7 KB to 1.5 KB on a saved log, the
failure and the totals kept). Then `request`: one request to the application under test, the path as
written - a step out of a folder is a request a test may need - but refused if a space, a control
character or a line break in a header would make it more than one, with a budget on the reading and
the answer cut to size. The container that the two sandbox functions run in is `harness.models.review-sandbox`: a first container
with the network warms the dependency cache for every alias the review uses (the application's own
`bb.edn` has dependencies too, which a first attempt missed), a second has `--network none`, the cache
read-only, limits on memory and processes and no capabilities, and the application is started in it and
waited for. `bb test` is not run in it - babashka's own `clojure` helper downloads its tools into the
container - the same `clojure -X:test` the task wraps is. Tried by hand against the fixture: up in 11 s,
all five tools work through it, and a test that reaches for the internet from inside throws.

### The `z-ai` family has a route

The bake-off's second candidate, `z-ai/glm-5.3-prime`, was in the catalogue with no route, and a
family with no route is refused by name. It is reached through OpenRouter like the others, pinned
to Alibaba - the one host OpenRouter lists for it, and one of many for `glm-5.3`, so the pin
holds if the person moves to that one - and both take tools and `reasoning_effort`.

### The security reading's fixture: six faults planted, a test each (row 121)

The two auditors the plan takes on - an architecture tracer and an attacker on the diff, models
chosen by a bake-off - need a known answer to be measured against: a reading of a real project
cannot be scored, since nobody knows what it missed. The generated application has two routes and
nothing to attack, so `bb security-fixture <dir>` adds a feature to it twice, from the same base:
accounts, private notes with a quota of three, an export, an administrator's page - once careful,
once with two architectural faults (a race on the quota, the administrator check on the GET method
only) and four in the diff (raw SQL in the search, a delete by GET, a path out of the exports
folder, a note read by its id). The branches have neutral names and which is which is written
outside the repository; the faults are data in the KIT, a reference test each, and the build runs
the tests and refuses a fixture whose key does not hold. Two of the first tests passed for the
wrong reason and were caught by running them on the faulted branch: Hiccup escaped the apostrophe
the IDOR test looked for. The race's test, the one that depends on timing, failed ten runs of ten
with its fault and none without.

### `bb health` and every stage's end run the two scans; row 118 fixed

With the pin past its advisories, the scans are wired where the plan put them. `bb health`'s
`app security` row runs `deps` and `secrets --all` beside `check` in the generated application,
each with its record, so an advisory published against the pin after it was made fails the next
health run rather than a project's stage; nine of nine on `kit-v1.2`. The `stage-end` skill runs
both at every stage's end - `secrets` over the stage's commits, from the tag before it - and their
records sit beside the stage plan with the checks' record; no failing row is an exit criterion of
the stage and pre-release templates, `bb stage-report` prints a Dependencies and a Secrets line,
and 03 §17 asks for them. What a project accepts or allows goes in its routes file with a reason.

### The pinned template is `kit-v1.2`: its dependencies past their advisories (row 119)

The `deps` scan's first run, on the health application, failed six libraries: Jetty 12.1.0 from
the Ring adapter and Jackson 2.18.2 from jsonista, each with published HIGH advisories, and
Logback with three LOW. The newest releases of what brings them still lagged the fixes, so the
fork's `kit-v1.2` moves the adapter and Logback and pins Jackson and Jetty, each family whole, at
the top level of the template's `deps.edn` - the person's choice of three, measured first on a
copy. The same tag pins every GitHub Action a generated project uses to a commit, since a tag
moves at its owner's word and runs with the workflow's secrets. The pin moves to it; `bb health`
on it is nine of nine and its application has no published advisory. The architecture
document's §4 now says what the template gives for dependencies, and that no dependency stays
clean. A workspace made before it keeps `kit-v1.1` in its application, and `bb doctor` there
says the pin moved.

### A pin bump passes the gates before it is committed (row 120)

The upgrade test expected a workspace made at HEAD to see no pin move, reading HEAD's pin from
git and this clone's from the working tree; moving the pin to `kit-v1.2` failed it before the
commit could be made, on exactly the change the doctor's moved-pin line is for. The test now
asks git whether the pins file differs from HEAD and expects the move then and only then.

### The security pack scans the dependencies and the commits (row 118)

Two commands beside `check`: `deps` and `secrets`, over the source where `check` sends requests.
Each was chosen by a run, not by name. For the advisories, OSV's batch query against
clj-watson's GitHub-advisory mode on the health application: OSV found every advisory clj-watson
found and thirteen more, on a library clj-watson did not report though it is in the tree, in
half a second and with no token. For secrets, a scanner of our own against gitleaks on a scratch
repository of nine planted fake secrets and four decoys: ours found the nine and the committed
`.env`, gitleaks six, missing the OpenRouter key the KIT itself uses; neither flagged a decoy or
anything in the KIT's history. The first rule was entropy above 3.5, and measuring it showed one
random 32-character hex key in five scoring below that while placeholders scored up to 3.6, so the
rule reads the value's shape instead: long hex, or mixed character classes that are not words
joined by separators. The value is never printed or written. The tests build their fake keys
from pieces when they run, so no committed file is shaped like a secret.

Not wired yet: run on the health application, `deps` fails today - the pinned template ships
Jetty, Jackson and Logback versions with published HIGH advisories - so `bb health` and the
stage's end take the scans once the pin moves past them.

### A checklist before a tag

The tag convention had one step: `bb docker-gates` green on the commit the tag names. Row 117
changed the health check after the health records were made, so a tag cut then would have named
code its records never ran. The convention at the head of this file is a checklist now: the work
committed, the health records on that tree (the host's, then the container's, then the README
block), the tag's documents, the gates and `bb docker-gates` on the final commit, then the person's
merge, tag and push. `CLAUDE.md` points to it.

### `bb health`'s serve check asks whether anything answers (row 117)

Row 102's cause had a second place to bite. The serve check called port 8000 free when a
wildcard bind on it succeeded, then read `GET http://localhost:8000/` as the application's answer;
on macOS that bind succeeds on a port another program holds on 127.0.0.1, and the application binds
the wildcard too, so the check could start the application and read the other program's answer.
A port is free now when the bind succeeds and nothing accepts a connection at any address
`localhost` resolves to. A test holding a port on 127.0.0.1 fails on the old check and passes on
the new. The browser and security packs already asked whether anything answers.

### Every test stub binds 127.0.0.1; row 102 fixed

A stub-model test failed once more and passed on the rerun, the second recurrence since the
watch, so the fix was taken - and it was neither of the two the row named. Every stub bound the
wildcard address on port 0, and macOS may give that bind a port another program already listens on
at 127.0.0.1, where every stub's client connects; the other program answers. A loop of 15,000 stubs
gave 5-10 foreign answers a run, each from a port `lsof` lists as another program's loopback
listener - an editor's "Invalid CSRF token", and "WebSockets request was expected", the answer the
row opened on. By hand, a stub on one of those ports binds on the wildcard and is refused on
127.0.0.1. The eighteen stubs bind 127.0.0.1 now, and 16,000 of them gave no foreign answer. It
depended on what else the machine was running, which is why a rerun was always green. The stop
deref and `Connection: close` of 2026-10-07 stay; they were not the cause.

### `bb plan-check` reads a mark wrapped across lines (row 116)

A mark was `<` to `>` on one line, and the template wraps its longer marks as prose: eight of
them, six in `03-method-and-tooling.md` and one each in the stage-0 and stage-N templates, were
invisible to the check, so a plan could leave them standing and pass. A mark may now wrap across
lines but not across a blank line, and is reported on one line. Compared over every `.md` in the
repository, old pattern against new, the change finds those eight and nothing else; the template's
count fixture counts them.

### The root README names the security pack; row 115 fixed

The parts table's `tools/` row named only the browser pack; it names the SVG check and the
security pack beside it now. Row 115 is fixed: the KIT asks about security in the architecture
document's three parts, a pre-release stage signs the threat model, and what a request can try is
tried at every stage's end and in every health run. The scans over the source - dependency
advisories, a secret committed by mistake - and a security reading stay paused until a project
holds accounts or data.

### Every stage's end runs the security pack, and the stage report reads its record

Row 115. The `stage-end` skill runs the pack after the browser's, from the application's folder
with the project's routes file and `--record docs/stages/stage-N-security.edn`, so the record is
committed beside the stage plan and the gates record; a pre-release stage runs it against the
rehearsed host, `--running --base <its url>`, so TLS is tried on the deploy and not only skipped
on localhost. No failing row is an exit criterion of every stage (`stage-N-template.md` §11,
"deployed locally") and of the pre-release stage (its §7, each warn answered in §15 or a
decision); the skill's done-when names the record. `bb stage-report` reads it and adds a line,
"Security checks: n ok, n warn, n fail, n skipped (date)", for a stage with the record or any
pre-release stage, which says when it has none - so a block published before the line existed
reads the same and `--check` holds it. `bb next` is unchanged: the pre-release signature already
stands between the stage and its close. The `stage-plan` skill adds a stage's forms and login
routes to the routes file when it amends §8 and §15; `03-method-and-tooling.md` §17 asks a
project which routes file it keeps; `method.md` §02 says what is tried besides what is decided,
which its paragraph of the day before left for when the pack existed.

### Health records with the security check: macOS and the container, 9 of 9 each

Row 115. Both run on `d50125f`, the commit that added the check: macOS 27.0.1 in 77 s and the
container (Ubuntu 24.04.5, arm64) in 79 s, 9 of 9 each, the security check 10 ok, 5 warn, 0 fail,
3 skipped on both - the same rows (`bb health --record`, `bb docker-health`; the README's block by
`bb health-sync`). The known-good set is unchanged.

## 2026-10-07

### `bb health` runs the security pack against the generated application

Row 115. The health check's ninth check, `app security`, after the browser's: the KIT's
security pack run in the generated application, serving it and stopping it as the browser
check does, ok when it exits 0 with its record. So every claim of `02-architecture.md` §4 a
request can try is tried on the day of a health run against the application the pin generates,
and a claim that stops holding fails the run instead of waiting for a project to find it. The
warns and skips travel into the detail, one line each - no CSP, Jetty's version, Jetty's own
400 pages, and nothing to try for forms, logins or TLS in an application that has none - and
neither fails the run. The pack needs only `bb`, so the check is never skipped for a missing
tool, and the doctor gains no row. The records follow in their own commit, run on this one.

### The architecture document's headers claim, as a request found it

`02-architecture.md` §4 said the template's headers are on every response, a page not found, a
static file and an error page included. The security pack's first run found the exception: a
request Jetty refuses before the application - a malformed path, a `..` or an escaped one - gets
Jetty's own 400 page, with none of the headers and Jetty's version in its body. The claim was
read in the source and tried with curl on ordinary paths, which is why it held there. It now says
"every response the application makes", and the list of what is not given has the line for
Jetty's page. Not a fix to the template: nothing of the application is in that page and no link
leads to it, so the pack warns on it rather than failing, and the claim is the thing that was
wrong.

### The security pack: what a request can try, tried from outside

Row 115's second half begins. `tools/security/` is the fourth part's third pack, run as the
browser pack is - `bb --config <kit>/tools/security/bb.edn check` from the application's
folder, serving it through the browser pack's own `server.clj` or, with `--running`, checking
the one already up. It reads nothing of the application, only its answers: the response
headers on every answer it got, the CSP (absent a warn, a fail once the routes file says
`:csp :required`), a `Server` header with a version (a warn), every cookie's flags, a POST
without its token, the routes that need a login, the error pages, the static folders, TLS on an
https base. Only a failing row fails the run; every failing or warned row names what fixes it,
the file and function in the pinned template where the fix is the template's.

The routes that need a login and the routes that take a form cannot be found without reading
a framework, so the project types them, in `docs/security-routes.edn` of its build repository;
a public site with neither has no file. With no form named, `/` gets the POST, and a 404 or 405
there is a skip that says to name one - not a pass.

Two choices the first run made. Every request is sent as written, HTTP/1.0 over a socket (a TLS
socket for https, the certificate and host name checked): an HTTP client tidies `..` and a bad
escape away, and those are the paths that matter. And a header missing only on a request a
browser never sends - a malformed path, a `..` - is a warn: Jetty refuses those before the
application with a page of its own, which carries none of the application's headers, and no
link leads to it. Against an application generated from `kit-v1.1` (a `bb health --keep`
copy): 10 ok, 5 warn - those three header rows on Jetty's pages, no CSP, `Server:
Jetty(12.1.0)` - 0 fail, 3 skipped (no form route, no login route, http). The same application
with a routes file naming `/` as needing a login, `/health` as a form and `:csp :required`
failed those three rows and exited 1.

The judgements are pure, in `tools/security/src/security_check.clj`, and tested on canned
responses in the harness's suite (`test/harness/tools/security_check_test.clj`, 17 tests); `bb gates` formats,
lints and tests the pack's source with the harness's. The other packs have no pure part to test
that way.

### The method says why security is asked and signed, not gated

Row 115, the last of its first half. `method.md` §02 has a subsection, "Security is a decision in
stage 0 and a signature before release, not a gate", with its point in a callout: what an
application should hold, and who should reach it, is a decision, and a gate holds only what a
tool can decide. It says where each part lives - what the template gives in the architecture
document's §4, the five choices in §8, the threat model in §15 - why the choices are stage 0's
(they shape the stage map), that a stage which changes what the application holds or who reaches
it amends both, and that the pre-release stage signs the threat model. Phase A's exit criteria
gain the choices and the threat model, and §04's pre-release stage its signature, so the method
says what the `plan` and `stage-end` skills already ask; the `plan` skill cites the new heading.
The pack that tries the given part against a running application is not in the method yet: it
is said when it exists.

### The three planning skills ask the security questions where their steps meet them

Row 115. The architecture document's security sections and the pre-release gate are only asked
if the steps that fill and close the plan ask them. The `plan` skill now reads the architecture
document's §4, §8 and §15, asks the five security choices against what §4 says the template does
not do, writes the threat model from them, and is not done until both are answered. The
`stage-plan` skill reads the threat model and the pre-release template's exit criteria: a
pre-release stage's plan carries the threat model to be answered and signed, and any stage that
changes what the application holds or who reaches it - a form, an editor, accounts - says so and
amends §8 and §15 with it. The `stage-end` skill reads the threat model: a pre-release stage's
end reads every line with the person, each with its answer and evidence, and the person signs it
in the gates record before the close. Every new citation is a heading `bb skills-sync --check`
holds to its file; renaming §15's heading for a moment failed the check on all three skills.

### A pre-release stage closes with its threat model signed: a fourth gate in its record

Row 115. The threat model is answered by the time the application is released or it is not
answered: the pre-release stage template gains an exit criterion - every line of the
architecture document's §15 answered, by a line of its §4, a decision or the stage's own work,
each with its evidence, and a line that cannot be answered this release a decision that says so -
and a row in what the stage takes. The person's signature is a key of the stage's gates record,
`:security/signed`, beside the three every stage has, so the tools read it as they read the
others. It is in the one gates template, nil for every kind, rather than in a second template for
pre-release stages that `bb init`, `bb next` and the plan check would all have had to know.

The record does not know a stage's kind; the stage plan does, on its `**Kind:**` line. `bb next`
reads that line now, and for a pre-release stage whose packets are merged it names the threat
model in the stage-end step, before the close; a pre-release stage closed without the signature
is the person's next step, ahead of pulling the next stage. Another kind is never asked.
`bb stage-report` puts a "Threat model" line in a pre-release stage's block, signed with its date
or not signed, and in the block of any stage whose record carries the signature - and in no
other, so a block published before the line existed is still held equal to its records.

### The architecture document asks about security, in its three parts

Row 115. Nothing the KIT gave a project said anything about security: the gates, the readings
and the stage-end checks were silent, the architecture document's cross-cutting concerns named
"authn/authz" in a list, and the pre-release stage held "the security engineering" as one more
requirement to write. A project could reach release without being asked what it holds or who
reaches it, and without being told what the template already did.

`02-architecture.md` now carries the question where the document's parts already split what is
given from what is decided. Part 1's new §4 says what the pinned template does for sessions, the
session secret, CSRF, the response headers, errors, SQL, static files and HTML - each line
naming the generated file and the function or key that does it, never a line number, which the
next template version would move - and then says as plainly what it does not do: no CSP, no
session expiry, no accounts, the htmx token, TLS in the deploy's proxy, the `Server` header and
the image's user, no size or rate limit in the template's code. It is the pin's, so it names the
pin it was checked at, and a test fails when the pin's tag moves and the section still names the
old one. Part 2's new §8 asks the five things decided once in stage 0: what the application
holds, who reaches it, the auth model, where it is deployed, the CSP. Part 3's new §15 is the
threat model - assets, actors, one line per threat with its answer or the stage that answers it -
three lines for a public brochure site, which is the right length for one. The sections after
each insertion are renumbered, and the two references `03-method-and-tooling.md` made to them
follow; cross-cutting concerns hands authn/authz to §8, and the technology summary gains the
given sessions row and the chosen auth model. `bb plan-check` reads the new marks and
instructions as it reads the rest, with no code change: the plan as `bb init` leaves it fails
with them counted, and the filled fixture passes with them filled.

### `bb doctor` says when the application template's pin moved

Row 114's residue. The doctor's report in a workspace - what a pulled KIT expects that the
workspace lacks - compared the plan template, the rules' guidance, the mirrors, the skills and
`workspace.edn`'s keys, and not the pin, so a pull that moved it, as the move to `kit-v1.1` did,
said nothing to a workspace whose application came from the one before. It reads the pins file
at the commit `bb init` recorded, compares the default entry - the only one `bb init` generates
from - with the KIT's now, and when the commit differs says both pins, that the application is
the project's own and nothing in it changed, and where the template's change is seen: GitHub's
compare page where the template is there, the git command for a clone where it is not. The
comparison is a pure function tested on data, not on a past commit of this repository, which a
squash merge would take away; the line was tried on the pins at the commit before the move.

### The pinned template is `kit-v1.1`: its security defaults completed

Row 114. The plan template's architecture document is gaining a security section whose first
part says what the pinned template already does, each claim with the file that holds it. Read at
`kit-v1`, with the sources of the libraries the generated application resolves, and then tried
with curl against a generated application with three probe routes, the template held most of
what a server-rendered site wants - encrypted cookie sessions, anti-forgery on every non-GET,
parameterised SQL through HoneySQL, static files with no listing and no way out of their folder,
escaped HTML - and five things a project would have inherited without knowing:

- the security headers were route middleware, so a page not found and every static file went
  out without them - the resource and default handlers sit beside the router, outside it;
- an exception answered 500 with a JSON body carrying its message, its ex-data, and its class,
  method, file and line; a failed response coercion answered with the schema and the value; and
  a request body that failed to decode passed the exception middleware by, to Jetty, which
  answered a bare 500 without the headers - empty only because its own page, which carries the
  message, was over its size limit;
- no `Referrer-Policy`;
- the session cookie carried no `SameSite`;
- `X-XSS-Protection: 1`, which asks for a filter no current browser has and which, in the old
  browsers that kept it, could be made to strip a page's own scripts.

`kit-v1.1` fixes each in the fork, a commit and a test each, every test red without its fix: the
headers wrap the whole handler; an exception middleware of the template's own, FIRST in the
route chain, answers the error page and puts the rest in the log; `Referrer-Policy:
strict-origin-when-cross-origin`; `SameSite=Lax`; `X-XSS-Protection: 0`. The tests are a new
`server_test.clj` in the generated application: the whole handler called as a function, and
each error tried through it with its routes swapped for a probe route - a swap the namespace
says is safe only under the template's single-threaded test runner, and not a pattern to copy.
The error pages carry no detail in development either; the log has it.

Not among the fixes, and said in the security section instead: a `Content-Security-Policy`,
because the template's Alpine build needs `'unsafe-eval'` and htmx injects inline styles, so any
policy is a project's choice and not a default; a session that expires on the server; and
accounts, which the KIT does not generate - the template's auth variant is left as it is.

The pin moves, and both platforms' health records are run again on it. An application already
generated is a copy and keeps `kit-v1`, and nothing tells its workspace the pin moved: `bb
doctor`'s upgrade lines compare the plan template, the rules' guidance and the mirrors, not the
pin - fixed the same day, in the entry above.

## 0.6.0 — plan v6's boundary: the guided lean workflow

A workspace made before `0.6.0` lacks the plan reviewer role, the four skills under
`.claude/skills/`, the stage gates records, the lessons document, and the renamed `loop.edn`
and `workspace.edn` keys; `bb doctor` run in it lists each with the command that shows the
difference, and nothing is migrated.

## 2026-10-07

### Two test fixtures named a project; they name the README's placeholder now

A search of `main` before the `0.6.0` push, for anything the scrub rule says is absent -
a project's name, an experiment's folder, a secret's shape, a home path - found one thing, and
it was already public: the `bb init` layout tests and the application-mirror test used a real
project's two folder names as their fixture values, twenty-two lines across two files. The rule
is that no code, test or document here names a project; the fixtures now use `xyx`, the name
the root README's quick start uses, with `xyx-shop-site` and `xyx-shop-plan` where the test
needs folder names that are not the defaults. Nothing under test changed. Everything else the
search looked for was absent.

### The fresh-machine check is a container: the toolchain alone, a clone of HEAD inside

Row 113. Until today every gates run and the one health record were one macOS machine's, where
the toolchain had grown with the KIT, and "no CI" meant nobody had asked whether the gates pass
on a machine that has only what the harness README's prerequisites name. Decided with the
person: the fresh machine first, as a Docker container, and the GitHub workflow later on the
same image, so the two halves of "no CI" share one definition of the machine.

The `Dockerfile` at the root is the toolchain and nothing of the KIT: Ubuntu 24.04 with Temurin
21 from Adoptium's own image, the Clojure CLI, Babashka, git built from source at its known-good
version (the distribution's is older), bbin with the three clojure-mcp-light tools by the
doctor's own install command and held to the commit known-good records, cljfmt, clj-kondo,
Firefox ESR from Mozilla's repository and geckodriver. Every version is a build argument, and
the two root tasks read each value out of `harness/resources/known-good.edn`, so the container
is the known-good set and a recorded health run moves it - there is no second list. Three
things that file does not carry are pinned in the Dockerfile alone and said in its header:
Temurin's build number, geckodriver's version, and Firefox ESR, which the repository serves as
its current ESR. The build context is empty by `.dockerignore`: the KIT reaches the container
by `git clone` from a read-only mount of the repository, never by a copy or a mount of the
working tree, so what is checked is what is committed and nothing the container writes reaches
the tree. `bb docker-gates` runs the doctor and the gates there and requires `git status` empty
after; `bb docker-health` runs `bb health --record`, copies the container platform's record out
through a second mount into `harness/health/records/`, and leaves the known-good set the host
run's. Both log to `.local/gates/`.

The first run found two things that had passed on one machine for the wrong reason, each fixed
in its own commit and entry above: a test fixture that was red only by BSD printf's exit code,
and the platform label that on Linux named the kernel. Then the doctor in the container showed
every row at known-good exactly, the gates passed, and the health check passed all eight checks
with Firefox opening the page; its record is the README's second platform row. Two lessons
beside the list: git 2.56 builds its Rust parts by default and wants cargo, so the build says
`NO_RUST=1`, optional until git 3.0; and a background shell's exit code is the wrapper's, not
the command's - the run's exit is read from its log, where the task writes it.

### A health record's platform label names the Linux distribution, not the kernel

The first Linux health record (the container's, below) labelled its platform "Linux
7.0.14-linuxkit arm64": the JVM's `os.version` is the OS version on macOS and the KERNEL's on
Linux, and in a container the kernel is the virtual machine's, which says nothing about the OS
the tools were installed on. On Linux the label now takes the distribution's name from
`/etc/os-release` - "Linux Ubuntu 24.04.5 LTS arm64" - and falls back to the kernel where that
file is missing. The record's key, `linux-arm64`, was right and is unchanged.

### A test fixture was red on macOS and green on Linux, by printf's exit code

The first run of the KIT's gates on a Linux machine (the fresh-machine container, below) failed
one test, the loop's "a red gate triage-routed to tooling" case: its gate command was `printf
'FAIL in …' && false`. The gate runner tokenizes a gate command itself and runs the program
directly, no shell, so `&&` and `false` reached printf as arguments. BSD printf exits 1 on an
argument with no format to take it; GNU printf warns and exits 0. The gate was red on every
macOS run for the wrong reason and green on Linux, and the test expected red. The fixture now
runs through `sh -c`, as a gate that is a shell chain must, and says why beside itself. Nothing
in the harness changed: a gate command that needs a shell names one, which is what the shipped
`loop.edn` defaults do.

### A bake-off's records keep what a late cost is fetched by

Row 112's residue. A completion from the router carries no price; the harness asks the
generation endpoint by the completion's id, and that record often lags the call by seconds.
Every other call site keeps the ids for a later fetch - a run's steps, a reading's record -
and `bb reprice` fills what was missing. A bake-off's records kept the cost alone, so when
every generation record lagged on the day's bake-off, the table said the bake-off cost
nothing and the account balance gave the figure.

Each candidate's record and each judge's now carries its generation ids, its endpoint and the
NAME of its key's variable, through `agent/call-record` as a reading does, and `bb bake-off
reprice <dir>` prices every record under `records/` through the repricer's reading path,
rewrites what changed, and re-renders the table; a record from before the ids were kept says
so and stays, and `bb reprice` given a bake-off folder points at the command. It is the
bake-off's command rather than the repricer's because of the layers declared this morning:
the repricer sits below the loop and the bake-off above it, so the bake-off requires the
repricer and not the other way, and `layers.edn` says so. The day's own records cannot be
repriced; the balance stands for them, in words.

### The shipped reader moves to the newest of its line, on a bake-off

Row 112. `bb profile` had said it on the day it could: the OpenAI reader behind the
Reviewer, the spec reviewer and the plan reviewer of the shipped `claude` example had two
newer models in its line at the same price. The method's rule is that a selection is measured
before it changes, so plan v6 §5a's bake-off ran: eight of the first project's saved specs,
read in place, by the reader of record and by the line's newest at high effort, a judge of
another family reading the answers blind. The person dropped the middle model after one
reading; the run was stopped twice and the tool learned to read in parallel and to resume on
the way (rows 110 and 111), and the whole bake-off, repeats included, cost under two dollars
and took twenty minutes once it ran eight at a time.

What the judge said, shown and never counted: the newer model raised half as many findings
and nearly all of them were real; the older raised twice as many and fewer than half were,
the rest being the "behaviour on an invalid argument is unspecified" kind the method already
answers with its types rule. Each found a handful the other missed, so neither wins on
recall; for a list a person reads at a stop, fewer findings that are mostly real is the
better economy, and the newer read faster. The person decided on the judge's reading rather
than marking the rows, and the three reading roles moved together, since they are one reader
read three ways: `openai/gpt-6.1-sol` at high, from 2026-10-07, with the measurement in the
profile's comment in words. The records are the KIT session's working material and stay out
of the repository, as the earlier selections' did.

The run also showed the tool's own gap: every reading's record had no cost, because the
generation record lagged the call and a bake-off's records keep nothing to fetch it by later,
so the table said the bake-off cost nothing and the account balance gave the figure. Open in
the row.
### A bake-off reads in a bounded pool, and a stopped one resumes from its records

Rows 110 and 111, one commit: the two halves of one afternoon. The shipped reviewer's bake-off
(plan v6 §5a) started with three candidates on eight saved specs, sequentially, and the first
reading took seven minutes at high effort; the person dropped a candidate and the run was
stopped and started again, which read the first spec a second time, since the runner wrote a
record per reading and never read one back. Then the person asked why the readings could not
run at once. They can: the sequence the loop keeps for its roles is for worktrees, an nREPL and
a gate run per dispatch, and a reading is one completion with no tools and a record file of its
own.

`:parallel` in the three-line spec, four when left out, runs that many readings at a time,
batch by batch, results in the spec's order; the judges the same, one call per case after its
readings. Cost is unchanged, and a provider that pushes back costs a retry the agent loop
already makes; the progress lines interleave, which is the price. A record a run already wrote
- a candidate's or the judge's, when it is an answer and not a failed call - is reused and
said so on its line, so a stopped run resumes rather than repeats, and deleting `records/`
reads everything again. The bake-off of the day went on with the new tool, eight at a time,
its one finished reading reused.

### Every stub model waits for its stop and answers `Connection: close`

Row 102, from watch to fixed. The row opened on 2026-10-06 when a second stub-model test
failed once and passed on the rerun, and said the fix would be taken when a third kind failed
or it cost a commit; on the morning of the 7th a third and a fourth kind failed once each, the
repricer's stub and the agent's, in two gates runs of one branch. The cause was two things the
seventeen stubs across ten test namespaces all left open. `server-stop!` was called in a
`finally` and not waited for, so a stopped server's connections lived on into the next test;
and every stub answered keep-alive, so the client's connection pool could hand a later test's
request to an earlier test's server once its port came round again - which is what "another
server's words" in the triage failure were. Every stub now derefs its stop and sends
`Connection: close`; the change is two substitutions per file, and a stub written later by
copying any of them gets both. Five test runs in a row and a gates run were clean on the day,
against about one run in three with one failure before.

### The KIT carries a version tag at each plan's boundary

Row 109. The clone is upgraded with `git pull`, and what a workspace was made at was a commit
hash in `workspace.edn` and a date in the DEVLOG; nothing short named where a plan's work
ended and the next began, and a person reading "made at commit 1799a80" learned nothing a
version would have told them. A reading of a Clojure web framework that tags each release and
heads its changelog with what an upgrade needs named the shape: a tag at each plan's boundary,
not the template fork's `kit-vN`, a DEVLOG heading per tag with the one line an older
workspace needs, the version recorded beside the commit, the root README naming the tag.

`bb init` now records `:workspace/kit-version` - the nearest tag at or before HEAD, nil where
there is none - beside `:workspace/kit-commit`, for a person; the doctor computes from the
commit as before and prints the version beside it where one is known, at both ends of "made
at ... the KIT is N commits later". The DEVLOG's head says how it is versioned, and the
heading above is `0.6.0`'s, written here before the tag exists because the tag is the person's
to make on `main` after this lands: a version named before it is made is a promise, and the
heading is where the promise is kept. The key is not among the ones the doctor reports as
missing: a workspace without it is older, not wrong.

### The alignment sentence is a project's formatter setting, said in its data conventions

Row 108. `:align-forms` ended with a sentence about cljfmt - that it does not align `cond` or
`let` values, and that `:else    x` fails the fmt gate - which is true of the KIT's own
`.cljfmt.edn`, where `:remove-multiple-non-indenting-spaces? true` makes it so, and false of a
project whose formatter aligns: a reading of a Clojure web framework whose config turns
alignment on showed a role following the rule and gate 0 rewriting its file the other way. A
rule that misdescribes its gate erodes every other rule, and this one described a setting as a
property of the tool. The sentence now says what is true everywhere - alignment is the project's
formatter setting, the data conventions say which way it is set, and a form padded against the
setting fails the fmt gate - and the `:data-conventions` placeholder asks the project to say it,
with what each setting means at the gate. `bb rules-sync` re-rendered the two mirrors; a
project that filled the placeholder before this hears of the changed guidance from `bb doctor`.

### The harness is under its own boundary gate: folders say topic, layers say dependency order

Row 107, and row 106 extended. Gate 4 - a project's `layers.edn` against its `src/` - ships
with the KIT and runs in every generated application's gates, and the harness never ran it on
itself. Its forty-three namespaces depended on each other as they happened to: item 2 met a
dependency cycle at load time, and the stage report, a day old, required the loop's
orchestrator for one function. A reading of a project whose every piece declares its layer
named the thing to do, and the person's point settled the how: clean, well-defined layers are
an asset, so fix the upward edges rather than declare them.

The attempt came first, as the plan asked, and it showed less spaghetti than the folder names
suggested. Placed by what they require, the namespaces already form layers - leaves that
require nothing of the harness; foundations on them; models and money; the plan and the
readings; the loop; the roots nothing requires - with one upward edge, the stage report's,
whose `owner` now lives in the event log beside the stops it reads, where both the loop and
the report can require it. The rest of what looked upward was namespaces filed under the
wrong folder: `setup.doctor` and `setup.workspace` are leaves, `contract.spec-review` is a
reading, `models.bake-off` is a root. `harness/layers.edn` declares, per namespace, exactly
what it requires today, ordered bottom-up with the principle in its header, and `bb boundary`
runs in `bb gates`: a new upward require fails with the namespace named, a conversation before
a dependency.

The principle the person asked to be highlighted is in the README's layers section: **folders
say topic; layers say dependency order.** The tree groups by what a namespace is about, which
is the axis a reader looking for a thing wants; the ruleset is the other axis, held by the
gate; and the inventory block shows both, its new layer column computed as one more than the
deepest layer of what a namespace may require - never typed. Nothing moves a file for this;
the tree is one axis and the code has two. The gate's count also caught the inventory's own
miss on the day: its glob matched files in subfolders only, so `harness.rules` was never in
the block, forty-two where the gate counted forty-three.

The person then pulled the principle out of the README's layers section into a GitHub callout
(`> [!NOTE]`) and the paragraph under it became a list, one axis per item. The convention is a
working rule in `CLAUDE.md` now: the important point of a section in one of the five callouts,
each for one kind of thing, the rest as prose or a list beneath - and never inside
`plan-template/`, where the plan check reads every blockquote line as an instruction to delete.

### The harness inventory is rendered from the source, not counted by hand

Row 106. The harness README opened its inventory with "thirty-seven namespaces in six groups",
typed by hand, while `src/harness/` held forty-two; three of them had no row in the table at
all. The README's own health block is rendered and drift-gated because a number nobody can
re-derive is the thing this repository is most against, and the sentence above it was that
number. A reading of a project that commits every generated thing and gates its drift named
the shape to take.

`bb inventory-sync` reads every `ns` form under `src/harness/` and renders the count, the
groups with their sizes, and a table - namespace, group, the first line of its docstring -
into the README between markers, the way the health block and the rule mirror are rendered.
The hand-written table beneath stays prose: what each namespace is for and the lesson it
encodes, in the order a run meets them. The check, `--check` in `bb gates`, holds the block to
the source and the hand-written part to the source by name alone - a namespace the source
has that the table never mentions fails, since a lesson nobody can find by the namespace it
belongs to is the omission the README was written against. The first run of the check named
the new namespace itself as unnamed; its row was written, and the three that had been missing
- the targets gate, the repricer, the upgrade report - have theirs.

### `bb stage-report` rolls a stage's records up beside its cap

Row 105, and the hand-kept figures of rows 82, 23 and 86. The method makes the stage the unit
of money - approved with a cap, closed with its cost and its runs in the lessons document - and
the harness rendered one run at a time. What a stage cost was a rounds table, a ledger and a
spend sheet beside the first real project's plan, each re-added after a merge and re-derivable
by nobody. A reading of another project's design, which renders a stage's figures from its
records, named the thing to build.

`bb stage-report <stage-plan.md>` reads the stage's packets off its blueprint, the run records
that name those tasks under the build's `runs/`, and the stage's readings - the plan review's
and the blueprint review's records under `reviews/<stage>/` with their histories, the spec
reviews inside each run - and renders: money by role, with the readings beside the dispatch
money; rounds, one plus the triage events as the run report counts them; the retries' cost
and its share of the dispatch money, a second triage among them; the reviews rejected; the
stops by owner, through the loop's own `owner`, and by kind; each packet's status, a packet
without a record said. The first line sets the total against the cap from the gates record
and against the stage before, one line of it. The block goes into the stage plan's
exit-criteria section, before the heading that follows it, and names itself on its first line
as a run report does, so `--check` finds it without a marker: every stage plan whose packets
have records is held to a re-render, both directions, in `bb gates` in a workspace's clone.
Said, not counted: nothing here stops a run or a merge; the cap stays the person's stop. The
stage plan template says the section's figures are never typed by hand, the lessons template
takes its cost and runs from the block's first line, and the `stage-end` skill runs it.
Rendered read-only against the first real project's records the day it landed, it said of its
second stage what the spend sheet had said by hand, and the check named both of its stage
plans as publishing no block - which is what a workspace from before this should hear.

### `bb next` says the workflow's next step from the build's files

Row 104. The loop has answered "what happens now?" for a run since it was rebuilt:
`next-action`, pure over the run's state, table-tested, with every routing decision in one
place because a control flow scattered through the steps that perform it can only be tested
by performing them. The workflow had no such answer. Fourteen steps, three human gates and
four conversations, and a session opened cold found its place by reading `workflow.md`
against the build by hand; a skill that ended with "then do X" carried a hand-off that was
the workflow's, not the skill's. A reading of a skills-only project named the shape to take:
a workflow-level `next-action`, over facts that are files.

Five facts, every one a file. A `workspace.edn` at or above here - none, and the next
action is scoping from the clone's own skill, which is the plan's step 0 before step 1 made
real. `bb plan-check`'s list - anything left, and the plan skill is next, the brief first if
`source.md` §1 still carries the template's mark. The stage in progress - the highest-numbered
under `docs/stages/` with any file - and its gates record, the three human gates the person
writes: not approved, and the next action is the approval (stage 0) or the stage plan and
then the approval (a later stage); approved with no blueprint, the blueprint; a blueprint
with no reading under `reviews/stage-N/`, the review; reviewed and not signed, the sign-off.
Its packets, in the blueprint's order, against the merged records under the build's `runs/`:
the first without one is the next packet, and when its latest record says otherwise the stop
is named - a merge for the person, an escalation for a person, a run to continue. All merged
and not closed: the stage's end. Closed: pull the next by kind. Each answer carries the step's
number from `workflow.md`, its owner, the command or skill, and the fact it rests on, and a
fact the command cannot read is said as such rather than guessed - a workspace with no
records key gets no records, not a stack trace, which the first project's workspace taught
on the day. Information, never a gate: exit 0 always. A first cut, and the docstring says
where it stops: a run in flight is known only by its last record, the plan review's findings
are not read, and the stage map is not consulted for which stage the person meant to pull.

### The four conversational steps are skills: the KIT's fifth part, held to the method

Row 103. Four of the workflow's steps are conversations with a person that end in documents -
scoping, the plan for stage 0, a stage's plan, a stage's end - and the method says what each
asks and writes. Nothing ran them: a session read the method whole, or remembered the last
time. A reading of a skills-only project showed the shape a conversational step takes when it
is packaged - what it reads without asking, when it refuses to start, what it writes, how the
person knows it finished - and that the zero-setup step ships where the person opens first;
and it found a defect in this plan as first written, the scoping skill installed by `bb init`
into a folder `bb init` makes.

`skills/` holds four, one `SKILL.md` each, in one anatomy and one order: when it runs, reads,
refuses when, asks, writes, done when. Each is a thin layer: its Reads table cites the headings
of `method.md` and the templates it works from, and it asks the questions written there rather
than restating them; it reads its inputs from files, never from the conversation, so a stage's
plan can be written in a fresh session from what the stage's end wrote; it refuses by name
before anything is spent - `stage-plan` with the previous stage's gates record not closed,
`plan` with no brief; and it ends by naming the next command, `bb next` among them, since the
hand-offs are not inside the skills. Claude Code loads a skill from `.claude/skills/`, so the
folder is rendered twice: the clone's own copy, committed, so that `scoping` runs in a session
opened in the clone before any workspace exists; and a workspace's, written by `bb init` beside
the agent file and re-rendered by `bb skills-sync` after a pull. A rendering is the source with
one generated note after its frontmatter naming the KIT commit; `bb skills-sync --check`, in
the gates, compares everything but the commit - so the clone's copy does not drift at every
commit - and holds every cited heading to the file it names, so the method can be renamed but
not out from under a skill. `bb doctor`'s workspace report says when a copy is missing or
edited. A folder under `.claude/skills/` that is not a shipped skill is a project's own and is
never touched; the harness README's adapting section now ends with one list of what is open
and what is closed. The other four seats follow the same sections by hand, which
`portability.md` says in its seats table; the skills are the Architect's session's and never a
dispatched role's. The README names the fifth part.

## 2026-10-06

### A stub-model test that fails once and passes on the rerun is watched

Row 102. Two gates runs tonight, an hour apart, each reported one failing test against a local
stub model and passed green on the rerun with nothing under test changed between: the triage
fallback test's stub answered with another server's words, and the API runner's cost test saw
an answer that never arrived. A spec-review stub test did the same once on 2026-09-24, kept
then as a watch by decision with the rule that a second such failure opens a row. This is it.
The likely cause is the ephemeral port a stub takes per test and gives back; what would close
the row is a stub shared per namespace or one retry of a refused connection, decided when it
fails a third kind of test or costs a commit. Until then the rule from the slip of 2026-10-06
stands: every gates run goes to a log, its exit code is read, and a rerun is the answer to a
failure of this shape - after reading which test failed, since the rerun is only the answer
when nothing under test changed.

### `bb profile` says when a selection has a newer model in its line

Row 101. A profile's models are dated selections, and the question of whether the catalogue
had listed a newer model of the same line since the date was answered by hand: `bb models`
read for each role, the answer written into a plan. The KIT's own plan did it once for the
five selections of the shipped example, and a reading of another project's design, which
carries a table of model advice updated by copying, named the smaller thing worth taking: the
age of a selection said by a command, on the machine that runs the profile.

A model's LINE is its name with every version segment removed - `openai/gpt-5.6-sol` and
`openai/gpt-6.1-sol` are `gpt-sol`, `anthropic/claude-opus-5.5` and the direct name
`claude-opus-5-5` are `claude-opus` - and its VERSION is the numeric segments as a vector,
compared padded, so `6` is after `5.6` and `6.1` after `6`, whatever the listing's dates say.
Newer in the line means the same line, the same family where the slug names one, a later
version; a `:batch` or `:thinking` variant is not a model in the line and is left out, as
the aliases are, and a slug with no version has nothing newer by number. `bb profile`
prints one line per role with any, after its checks, worded as the doctor words *newer than
tested*: information, never a violation, ending in "measure before changing (bb bake-off)",
since the listing says nothing about whether the newer model reads a contract better.
`--offline` skips the listing, and the examples-only fallback - what runs with no profile of
its own - never fetches it, so the gates run on a plane. Run on the shipped `claude` example
the day it landed, it said what the KIT's plan had found by hand that morning: the OpenAI
reader's three roles have `gpt-6.1-sol` and `gpt-6-sol` behind them, and the other three
selections nothing.

### `bb plan-check` holds the citations in two directions

Row 100. The template has asked since the morning that every requirement name the observation
of `source.md` it rests on, `S-n.m`, or be marked `(inferred)` as the Architect's own, and
that the inferred ones be shown to the person at the stage's approval; and the scope section
says the observations nothing claims are the backlog the stage's end revises the lists
against. Nothing read either. A reading of one project's design showed the first half - every
requirement cited or marked - and a reading of another the second - the uncited list - and
the two are one check in two directions.

The plan check reads every table with a Source column in the governing documents - the
requirements document and every stage plan written since - and a row whose first cell is a
requirement id and whose Source cell names no observation and says nothing of inference fails
by name, with the document. The template's own `S-n.m` fails that way, which is what a row
left standing should do, and the fixture fills it. After the verdict the check prints two
lists that fail nothing: the requirements marked inferred, with the document each is in, for
the person; and the observations `source.md` defines - its `**S-n.m**` bullets - that no
other governing document mentions, in the source's order, for the stage's end. Any mention
counts as a claim: a requirement's Source cell, a deferral, a decision's evidence. `start`
runs the check and stops on the same failures; the two lists are `bb plan-check`'s alone,
since a stop before a dispatch is not where a person reads a backlog. The index and the
scope lists have no Source column and are not read as citations.

### The plan review runs in every stage's plan step, and asks the step's three questions by name

Rows 93 and 98, the harness's half. The method's §02 said since the morning that the pass runs
in every stage's plan step - the whole set in stage 0, the stage plan and the documents it
revised after - and that its checklist opens with three findings a plan that commits to one
stage at a time gets wrong; `bb plan-review` still read the seven documents once, with a
prompt that said "before its first stage is built" and ten kinds that did not include them.
The checklist itself was never stale, since it is read from the method at the call; the
prompt around it and the command's reach were.

`bb plan-review <stage-plan.md>` is a later stage's reading: the stage plan first, named as
under review, then the seven documents as the context it revised, the record written to
`reviews/<stage>/plan-review.edn` beside the blueprint review's, keyed by the `stage-N` the
file name starts with as that review keys its own. With no file, or a plan folder, the
reading is stage 0's as before. The prompt says which reading it is given, and names the plan
step's three findings as the first three kinds - `requirement-for-stage-not-pulled`,
`risk-without-owning-stage`, `lesson-unanswered` - with a sentence each on what they mean and
one saying the empty lessons document of a plan before stage 0 ends is not a finding; the
test holds the method's checklist to the three by their headings. The bake-off's
`plan-reviewer` act has a case per reading a build makes: the set, then each stage plan on
disk, templates and blueprints excluded. The harness has no way to know which documents a
stage revised, so it sends the set; the prompt says so, and the reader can see what the stage
plan cites.

### The plan reviewer is a role of its own, set to the spec reviewer's selection

Row 99. `bb plan-review` read the profile's `:spec-reviewer` block, by a decision that still
holds as an economy: the plan review and the spec review are the same kind of reading - cold,
the reader has seen nothing of the work and gets the documents and the checklist in one call;
adversarial, asked for faults and not confirmation - and what differs is the checklist, which
is text sent with the call. What the borrowing hid was the fact: the profile named no reader
of the plan, `bb profile` printed none, a project could not set a cheaper reader for the plan
than for a contract or bake off the plan's reader alone, and the roster had to say in prose
which block the plan review took. The person decided the role for transparency and so an
adopter can configure it apart.

`:plan-reviewer` is the seventh role, in the profile shape with the other six and no fallback:
a profile without it is refused by name, and `bb doctor` in a workspace made before this
change lists it as the missing role - the report of what a pulled KIT expects already read
the roles off the shape, so the line needed nothing. `bb profile` holds it to §05's family
rule as it holds the spec reviewer, by the same route: the plan and every stage plan are the
seat's writing, and a reader of the seat's family reads a sentence the way its author did. It
is first in the roles' order, since a build meets it first, in stage 0's plan step. Both
shipped examples set it to the spec reviewer's selection, block for block, with a comment
saying why the two are the same reader by default, and a test holds the two blocks equal, so
the cost is what it was. The bake-off's `plan-reviewer` act fills this role now rather than
the spec reviewer's. The roles tables - the roster, the harness README, `workflow.md` (its
reviews table has the role column back), method §05, the plan template's method document,
`portability.md` - carry the row. Going forward only: nothing is owed to a workspace made
before, beyond the doctor's line.

### The workflow follows the shape: scoping first, stage 0 the spike, every stage the same four steps

Row 91, the workflow's half. `workflow.md` drew setup, plan, Foundation, stages, loop in a
line and numbered fourteen steps as if each happened once. The diagram now begins with
scoping - the six groups of questions, the brief written to a file - and setup files the brief
with `bb init --brief`; the plan is for stage 0, approved with a cap that a record carries;
stage 0 builds Foundation and runs the spike's packets, the first of them the machine's
proving run; every stage from 1 on is one box of four steps - scope with the lessons read
first, the stage plan reviewed cold and approved with its cap, the blueprint reviewed and
signed, implement through the loop, deploy locally with the browser pack and the owner's
walk - and its end pulls the next increment, a pre-release stage, or the release, which
returns to the next increment. The table says which steps are stage 0's once and which
are every stage's, and a second table puts the four reviews by the step of a stage they
belong to. The three things the diagram says gained a fourth reading and the gates as
records. The root README's one line on the workflow says the same.

### The lessons have a document and a reader, and a stage's gates are a record

Row 98. Two things a reading of another project's design made visible. The method's stage
end wrote the lessons and named no reader: `stage-end` appended to a document nothing
created, and the next stage's plan started from its own sections. `05-lessons.md` is the
plan's seventh document now - one section per stage, closed with its date, cost and runs,
each lesson ending as a rule in the overlay, a line in the next stage plan, or a decision,
and saying which; a lesson that ends as none of them is a remark and goes in the stage's
§12. The stage plan template reads it first, the review pass asks for a lesson the new plan
does not answer, and the plan check reads it like the other six.

The three human gates of a stage - the plan approved with its cap, the blueprint signed off,
the stage closed - were a status line changed in prose, which nothing could read, while the
merge has been a file since the loop was rebuilt ("a question that cannot be answered
tomorrow, or recorded, is not a gate"). `stages/stage-N-gates-template.edn` is the record:
each gate nil until the person passes it, then a map with the date; copied beside the stage
plan when the stage is pulled. The workflow-level `bb next` that reads it is the KIT's local
plan's item 7; the plan check skips the template and reads a copied record. The template's
README has the tree and the order of writing as they now are: scoping first, the plan
committing to stage 0, every later stage plan written when pulled.

### The stage templates: the stage plan's twelve sections, and three kinds new

Row 94, the templates' half. `stages/stage-N-template.md` is the stage plan the method's
§04 lists: twelve sections, the requirements this stage adds as §2 with their source or an
inference mark, breadth written beside depth in §4 - what it shows, what it does not yet
show - and the exit criteria carrying "deployed locally", the owner's walk and the stage's
end: the lessons written, the index appended, the scope lists revised, the stage map
re-ranked; the cap approved and recorded. Three templates are new. `stage-0-spike-template.md`
is the spike's: the risks it proves with their pass criteria and fallbacks, the Foundation
built inside it with the readiness checklist closed against its first packet, what runs at
its end, what is kept - and that nothing in it is owed to stage 1. `stage-prerelease-template.md`
takes a share of what release needs, writes the non-functional requirements as any stage
writes its own, and rehearses the deploy path against a throwaway host. `stage-release-template.md`
packages and deploys or publishes with the three criteria from reading a project that
releases: the artifact started as packaged on an empty home, the previous release's data
opened by the new one, a publish that finishes when re-run; the changelog's Upgrading
paragraph; the rollback. The blueprint and method templates say stage plan and blueprint; the
scaffold is adapted in stage 0. The plan check still excludes every `*-template.md` under
`stages/`, and its fixture counts the three new ones.

### The four project-level templates follow the method: the plan commits to stage 0

Rows 93 and 94, the templates' half. The method's §02 and §04 changed on 2026-10-06 and the
templates still asked for what they had asked before: every requirement in one document,
three lists headed MVP, post-MVP and non-goals, a stage map with the MVP marked in advance
and Foundation before stage 1, spikes that run in stage 1. Now `source.md` says where the
brief goes - its first row and Appendix A, as `bb init --brief` files it - and names the
content apart from the rest. `01-requirements.md` holds stage 1's requirements, each with
its source observation or marked as the Architect's inference, and an index of every
requirement by the stage that wrote it, since a later stage writes its own in its stage
plan; its scope is three lists - ships first, deferred with the stage that takes it, out of
scope with its reason and the decision it is - revised at every stage's end against the
source, and the MVP is named afterwards. `00-overview.md`'s stage map is by kind, stage 0
the spike with Foundation inside it, with a cap on the stage in progress alone; its document
map names the stage plan, the blueprint and `05-lessons.md`; its review findings say the pass
runs in every stage's plan step. `04-decision-log.md`'s provisional entries carry the pass
criterion stage 0 applies, and its spikes run in stage 0. The placeholder counts the plan
check is held to moved with them. The stage templates and the lessons document follow.

### `loop.edn`'s keys are described once, and an unknown one is refused by name

Row 97. The keys a run's `loop.edn` may hold were written in four places - the driver's
docstring, the merge of defaults in `resolve-config`, the header `bb init` writes, the plan
template's §13 - and held by none: the map was open, so a misspelt key was a default
silently applied, and the spec review's default spends money (the DEVLOG of 2026-09-18 has
two live calls bought by a fixture that lacked the key). The harness README had made the
argument itself about `AgentResult`. A reading of another project's design, which
describes each setting once and derives its form, its parser and its schema from the
description, named the shape to take.

`shapes/loop-keys` is the one description: every key with its type, its default - literal,
computed, or none for a required key - what it is for, and the namespace that reads it;
the three keys `resolve-config` derives from the workspace are there too, marked derived,
so a resolved config is described as well. `resolve-config` merges the literal defaults
from the table, fills the computed ones as before, and refuses a key the table does not
know - by name, with the known keys listed and where they are explained. `bb init` renders
the file's header from the table, one line per key, and a test holds the plan template's
§13 to it: a key it names that the table lacks fails the gates. The four bare keys are
named by what they configure, as the rest already were - `:spec-review/run?` beside
`:spec-review/max`, `:plan-check/run?`, `:notes/pause?`, `:repo/allow-dirty?` beside
`:repo/root` - and `:architecture`, a map with keys of its own, is the one bare key left,
said so in the table. A `loop.edn` written with an old spelling is refused at `start` with
the new spelling in the list, which is the test's example.

### `bb doctor` in a workspace says what a pulled KIT expects that the workspace lacks

Row 96. The root README said the clone is upgraded with `git pull`, and the DEVLOG recorded,
one entry at a time, what that sentence left out: a `workspace.edn` from before two keys
got no copy until the lines were added; a sentence to edit by hand, once; a project pulls
and runs `bb rules-sync`; a placeholder whose guidance improved after a project had filled
it, unseen because the overlay replaces the text. This change adds four more of the same
kind in one release - a profile role, skills to install, a renamed key, documents rewritten
under copied ones. A reading of another project's design, which opens every earlier version's
data and carries old configuration over, showed the half the KIT can have without a
migration: the list.

`bb init` now records the KIT's commit in `workspace.edn` (`:workspace/kit-commit`), and `bb
doctor` run in a workspace prints, after its table, what this KIT expects that the
workspace lacks: the recorded commit against HEAD and how many commits later; `workspace.edn`
keys missing, or renamed (`:workspace/plan` is `:workspace/build` now); plan-template files
changed since; placeholder rules whose shipped guidance changed since the overlay filled
them; a rule mirror that does not match the source; a role the profile shape names that the
profile lacks. Each line carries the command that shows the difference. It changes no
verdict and rewrites nothing: a workspace's copies are its decisions. `harness.setup.upgrade`
is pure over facts, so every line is tested without git; the facts are read from the
workspace and asked of git in the clone, and one test reads them against this clone at its
own HEAD. A workspace with no recorded commit gets the lines that need none and one saying
how to add it.

The harness met a dependency cycle a third time on the way: the doctor required the new
namespace, which requires the profile, which requires the doctor for its probe. The doctor
stays free of it - its main returns the exit code instead of exiting - and the composition
lives in the new namespace, which the `doctor` task calls. A second slip cost an hour: the
body was first named `run`, the name of the doctor's probe runner, and every probe then ran
the whole doctor again. The harness has no boundary gate of its own; that is a commit beside
this plan's list, and this is its second argument.

### The brief has a place: `bb init --brief` files it as the source's first row and appendix

Row 95. The method's step 0 is a conversation before a workspace exists, and its output is
the brief; `bb init` then copied `source.md` as shipped, its §1 row still `<the brief>`, and
the brief was pasted in by hand or left out. A reading of another project's design showed
the defect: the plan for the skills had the scoping skill installed by `bb init`, into a
folder `bb init` makes. Now `bb init <name> --brief <file>` writes `source.md` instead of
copying it: §1's first row names the brief, received today from the person, and Appendix A
carries it verbatim; §2 and the rest stay the Architect's to fill. Without the option the
document is copied as before; a `--brief` that is not a file is refused before anything is
written. The method's step 0 says where the brief goes.

A slip on the way, recorded because the DEVLOG already records its kind: the previous commit
was made on a gates run that reported four test failures, because the run was piped through
`tail` and the chain read `tail`'s exit code - row 28's slip, repeated. A rerun of `bb gates`
and of `bb test` passed with nothing changed, so which four tests failed is not known.
From here every gates run goes to a log and its own exit code is read.

### The repository beside the application is the build, and `bb init` names it so

Row 87, fixed. `bb init` made `<name>-plan`, and the KIT called it the plan throughout,
while it held the plan's documents and, beside them, the build's settings and its records;
a person meeting `profile.edn` there took it for an application setting. The default is
`<name>-build` now, the option that names it is `--build <folder>` (the `--plan` spelling
is gone, not aliased: nothing on `main` used it), and `workspace.edn`'s key is
`:workspace/build`, which every reader of the workspace - the driver, the plan check, the
profile, the readings, the bake-off, the balance - now reads. The files `bb init` writes
say the build where they meant the repository: the workspace's README and `CLAUDE.md`, the
build's own README ("the build's side of the project, which is why it is called the
build"), `loop.edn`'s header; so do the harness README and its `AGENTS.md`, the workflow's
two mentions, the plan template's README and the shipped profiles' headers. `bb plan-check`
and `bb plan-review` keep their names, because the plan is still the plan: the document set
the build holds. Going forward only, as the person decided: a workspace made before carries
`:workspace/plan`, which the harness no longer reads, and is owed nothing.

### The quick start follows the shape

Rows 93 and 94, residue. §13's checklist still began "name the MVP and what is post-MVP" and
ended "mark which stage completes the MVP", and §02's de-risking list spoke of post-MVP
features. The checklist now walks the shape: scope first, the three lists, the risks stage 0
proves with their criteria, the candidates as provisional entries, the stage map by kind with
a cap on stage 0 alone, the review's two questions, stage 0 approved; Foundation inside stage
0, the live run its first packet; stage 0 a spike and stage 1 the skeleton, the stage document
with the lessons read first and the requirements it adds, the WIP limit as one stage and one
task and one packet of ten to twenty targets, the stage closed on deployed locally and the
walk, the MVP named at the first boundary where "could we ship?" is yes, then pre-release and
release. The same method, read in one page.

### The stages have kinds, the skeleton has a breadth, and release is a stage

Row 94. §04 said the first stage should be a thin, full-depth vertical slice, and a project
heard the depth half: full depth over every input it had. The section now names the five
kinds in order. Stage 0 is a spike over the foundational choices, owing decisions and running
software and no code; stage 1 is the walking skeleton with both halves said - full depth and
the smallest breadth a person can walk, a home page and two pages for a site, one public
function for a library; stages 2 to N are increments; pre-release stages hold what release
needs and nothing earlier; release repeats. The stage document gains the requirements the
stage adds as its second section, since §02 moved requirements there, and its approval with
the stage's cap; it is written with the previous stage's residual risks and `05-lessons.md`
read first.

"Deployed locally" is an exit criterion from stage 1 on - served from a clean checkout, the
browser checks run against it, the owner's walk done on it - and stage 0's criteria are its
pass criteria answered. A stage's end writes three things before the next is pulled: the
lessons, the scope lists revised against the source, the stage map re-ranked. The MVP is the
first boundary where "could we ship?" is yes, named afterwards.

Two subsections are new. A table for a reader who knows Scrum or Kanban, so that nobody
re-litigates "why no sprints": the method is Kanban's shape with two of Scrum's ceremonies at
the gate, and stage is the word. And pre-release and release, host-neutral: what a
pre-release stage holds and its rehearsal against a throwaway host; the release stage's three
criteria - the artifact started as packaged on an empty home, the previous release's data
opened by the new one, a publish that finishes when re-run - with the changelog and the
rollback. Those criteria come from reading two projects that release, one of them nine times
in six days; the KIT had no practice of its own.

This closes the method's part of the change. The templates, the workflow, the harness and the
skills follow, each its own change.

### The plan commits to stage 0, requirements are written per stage, and the review reads every stage

Row 93. §02 said "decide as little as possible" and then described a document set whose
requirements document held every requirement, reviewed once before Foundation and approved
whole. A project did exactly that. The section now says what little is: the plan commits to
stage 0 and nothing after it - the risks ranked, the candidate architecture as provisional
decisions each with the pass criterion stage 0 applies, a stage map by kind with a cap on
stage 0 alone, the three scope lists. Requirements are written per stage, when the stage is
pulled, in its stage document with a permanent ID; the requirements document keeps the index.
A requirement written for a stage not yet pulled is a finding of the review pass, as a
decision made too early is. Approval and the cap are per stage, never the whole plan at once.

Scope is three lists revised at every stage's end: what ships first, deferred with the stage
that takes it, out of scope with the reason. "Non-goals, never" is gone: an exclusion is a
decision with an ID, reversed only by a later decision that cites it - never reargued in
passing, never sealed - and the brief is a starting point, not a contract. The MVP is named
afterwards, as the first stage boundary at which "could we ship?" is yes.

The plan-review pass runs in every stage's plan step, over the stage document and the
documents the stage revised, so the stage document - which now carries requirements and a cap
- is read cold before a Blueprint is cut from it. The pass's two words are said: cold, because
the writer cannot find where their own sentence reads two ways; adversarial, because "looks
good" is a failed reading. Three findings join the list it sends: a requirement for a stage
not pulled, a risk with no owning stage, a lesson of the last stage the document does not
answer. The document set gains `05-lessons.md`, one section per stage, read first when the
next stage is planned. Phase A's exit criteria are rewritten around stage 0, and the MVP is
named among what is NOT on the list.

§03 follows: Foundation is built inside stage 0, before its first packet; the smallest machine
is the one that lets stage 0's packets dispatch; and the live run with the project's own models
is stage 0's first packet, since a spike's packets are what prove the machine - a rehearsal on
a trivial task stays an option, torn down as before. The harness's `plan-review` still reads
the checklist from the same heading and sends what it finds there; teaching it the three new
findings as kinds is a harness change of its own.

### The method says what its words mean, and draws how they relate

Row 92. §01 had the three phases and two rules, and the words the rest of the document
leans on - stage, spike, increment, scope, risk, decision - were defined nowhere, each
reader supplying their own meaning. The words are now a table in §01, seventeen of them,
each meaning one thing: a stage is a unit of scope with a gate at its end and never a unit
of time; a spike owes decisions and not code; the walking skeleton is stage 1 in its strict
sense; scope is three lists, and an exclusion is a decision reversed only by a later one;
the build is the repository beside the application that holds the plan and the records.
Under the table a Mermaid graph draws the relations, with the stage as the hinge:
everything in the plan feeds it, everything the project produces leaves through it.

The phase table moves with the shape: Phase A commits to stage 0 and nothing after it;
Foundation is built inside stage 0 and lets its packets dispatch; Phase C names the kinds.
The two rules become three: the Kanban words pull, work-in-progress limit and definition of
done are written as rules, because each named something the method did without saying -
the first project's fifty-target packet was a WIP breach by another name.

### The method's shape is layered by stage kind, and scoping is its step 0

Row 91. The drawing at the top of `method.md` was three phases in a line - plan, Foundation,
stages - and the first project that read it wrote its requirements complete before Foundation,
approved the whole plan at once, and built its first stage over every input it had. The
method's text already said the opposite in §01 and §04: everything beyond the stage in
progress is provisional; flow, not timeboxes; the first stage a thin vertical slice. The
picture did not, and a picture is what a reader keeps.

The new drawing has five layers. Stage 0 is a spike: scope, plan, Foundation, implement,
deploy locally, and what it owes is decisions confirmed or reversed and running software,
with code kept only where it passed its criterion. Stage 1 is the walking skeleton in the
strict sense - one small function end to end through every layer, the first thing the owner
walks. Stages 2 to N are increments, each the same four steps, looping. Pre-release is one
stage or more holding what release needs and nothing a user-visible stage needed earlier.
Release packages and deploys or publishes, then returns to the next increment, and a later
release has a smaller pre-release before it. Foundation runs under every stage from stage 0.
Stages are numbered through and each has a kind; the names stay the project's.

§02 now opens with step 0, scoping: the conversation before a workspace exists. Six groups of
questions and only those - who it is for, what it shows or does, what material exists and
which part of it is content, what must be proved early, the first thing worth seeing, the
money and the stops - with the answers recorded as given in `source.md` §1 and the brief as
its appendix, so a requirement can cite what it rests on. A question the person cannot answer
becomes a risk with a stage or an open decision with an owner, never a blocker. Scoping does
not ask for features, a data model or a stack. It is a subsection of §02 rather than a section
of its own because the harness and the templates cite the method's sections by number -
`bb plan-review` reads §02's checklist by its heading - and renumbering would touch every one
of them for no gain.

This is the first of four commits on the method; the vocabulary and the Kanban rules in §01,
the plan committing to stage 0 in §02 and §03, and the stage kinds with the term table in §04
follow. After the method come the templates, the workflow, the harness, the skills, and two
new commands - the order is the KIT's local plan for this change, and each lands with its
own row.

## 2026-10-05

### The second tool pack: a check of the SVG files a project produces

Row 90, from the first real project's remark N-4. It recolours its icons by script, dozens of
files, and the only check was the person's walk at the stage's end over every one. Another
project on this machine rasterises SVG with Apache Batik in the JVM in milliseconds, which is
the whole check an icon needs before a person looks: does it render, is anything drawn, is it
the colour it should be. `tools/svg/` does that - `bb --config <kit>/tools/svg/bb.edn check
[--color #rrggbb] <files or folders>` - writing a PNG per file and a report, exiting 1 on a
file that does not render or draws nothing, and reporting the colour share without judging it:
an icon all one brand colour and a diagram mostly not are both right. The pack is handed to
the Clojure CLI as a local dependency, so it runs from any folder with the caller's paths.
Opt-in, and the README says why: Batik is ten megabytes fetched on the first run, a JVM is
needed, and neither is a dependency of every application; it cannot render a page and does not
replace the browser pack. Tried on four sample files - a filled shape (a third of the pixels
drawn, all the colour), a thin stroke, an empty file (draws nothing), a broken one (no render,
the parser's reason on one line) - each reported as it is.

### The shipped profiles move with the first real project's decisions

The selections in the shipped profiles are dated, and until now dated from the KIT's own
development, whose records are not in this repository. The first real project made three
decisions with evidence it wrote down, and the `claude` example follows them. The Coder is
Sonnet 5.5 at medium: with Fable at low it was most of a run's cost, Sonnet lists at a fraction
of the price, and on every packet of ten to twenty targets after the first task it was right in
its first round - the comment says that condition, because the fifty-target packet before it
defeated the dearer model too. The orchestrator is Opus 5.5 at medium: sixteen routes on Fable
had fifteen right, so judgement was not the reason; price was, and a bias - triage judging the
Coder's code against the Tester's test should not be the Coder's own model, which Sonnet now
was. The family rule is for verifiers and says nothing here; the comment calls it the
convention it is, and row 2 records how the watch was answered. The Tester carries
`:max-rounds 40`, the project's number, with where it came from and that no Tester reached it
on the smaller packets; row 82's "the number" is answered as far as one project can.

The `agy-ide` example, which no build has used, changes by analogy and says so: its Reviewer
and spec reviewer are Opus 5.5 at high, direct to Anthropic on the `:anthropic` shape - they
are the KIT's only worked example of that shape and stay direct for it - with the pricing block
at Opus's rates as `bb models` listed them today and Anthropic's cache multipliers, dated. The
reasons are the Reviewer's price being the seat's biggest lever and Opus being the Blueprint
reviewer both examples already trust; what was measured before was Fable's effort, which does
not carry to another model, and the comments say so. Its orchestrator stays the Coder's model,
with a comment naming that as the bias the other seat moved away from and why it is left: a
model chosen for an unused seat would be a guess dressed as a selection.

### What the first real project's close taught the method

Four of the lessons the project wrote down at its close are the method's, and two of them
confirm what the register already watched. In the KIT's words:

- **A packet carries ten to twenty targets**, and the fixture a test needs is named in the
  input target that needs it - §06's twelfth rule. The project's first run carried fifty, cost
  a third of the whole build and merged nothing; the same work merged for a third of that once
  it was two packets. The Blueprint review reads §06, so it reads this.
- **The terms a Tester otherwise learns one try at a time** are in the `:data-conventions`
  placeholder's own prompt now: that to refuse is to throw, that a shape var is data and not a
  function to call, what a fixture is, and that a test file given to it exists to be edited.
  The placeholder asked for the first in general words; a project filled it late, a try at a
  time. The mirrors are re-rendered; a project pulls and runs `bb rules-sync`.
- **Before the Architect reads a rejection, run the gated code over the real input** - §07
  step 5 and the plan template's §5. Free, and it says whether the finding is about the code or
  about an input the sources cannot produce; the second kind is answered with scope, never a
  rule in the contract. Rows 34 and 36 record that this held on the project from its second task
  on, and that the two-reviews brake was judged right at the close.
- **The owner walks through every page the stage made, after the checks and before the tag** -
  §04's exit criteria and the stage template's §11. Ten changes no target could have named.

### A role whose target already exists is told to read it once and edit it

Row 89, the first of the lessons the first real project wrote down at its close. Its Tester,
given a test file to extend, took every round and wrote nothing - six times, four of them on a
first try - and ended each time with a note saying it understood the task. The deliverable's
words are for a file that is not there: *write the complete file, call write_file early*. A
Tester with a file in front of it read it in slices through the REPL and reasoned; the retry
whose feedback said "edit first, at most a few checks" wrote in a third of the rounds, every
time. The project's lesson was that a retry on an existing file should carry those words; the
KIT's is that a first try should.

The runner knows which of a packet's targets exist in the worktree at dispatch - it reads
the worktree anyway, for the files it will report written - and `packet-prompt` says so above
the packet: the file by name, read it once and whole, `edit_file` what the packet asks for
into it within the first few rounds, no rewrite, no slices, at most a few checks before the
first edit. A retry builds its prompt the same way, so the words reach it too. Tests: the
paragraph appears for an existing target and not for a new one, before the packet, and the
runner finds the existing targets itself. Not measured against a model: these are the words
that worked as retry feedback, moved to where the first try reads them.

### The KIT's fourth part: tool packs, and the browser checks are the first

Row 88, from the first real project's remark rather than a fault: it built the stage-end
browser driver the method asked for and shipped nothing of, and paid for three things the next
project would have paid for again. Where to put the generic half took two tries.

**The template, for a day.** On 2026-10-03 the scripts went into the template fork as a
`kit-v2`: every generated application would carry `tools/browser/` and `bb browser-check`.
Tried end to end on a workspace generated from the local template - served in six to eight
seconds, one screenshot, two axe findings on the template's own home page, stopped, the port
free - and committed and tagged there, unpushed. The person then asked the right question: a
project that starts clean, brings its own application, or stands on another framework gets
none of it. The loop's own rule is that it never looks inside a framework; the checks the
method asks for at a stage's end are the same for every project; so they belong where every
project reaches them. The fork went back to its pushed tip and the tag was dropped, before
anything left the machine.

**`tools/`, the fourth part.** Beside the method, the plan template and the harness: tool
packs a project runs from the KIT's clone against its own server, each with its own `bb.edn`
and its own dependencies - `bb --config <kit>/tools/browser/bb.edn check --serve "<cmd>"
--health <path> --base <url> [path ...]` - the way a project already runs the boundary gate.
`git pull` upgrades every project's packs at once; the harness's `bb.edn` stays free of Etaoin;
the template carries nothing. The browser pack is the driver (Etaoin under Babashka, a
headless Firefox through geckodriver, no Node, knowing nothing of any framework), screenshots
as tall as the page with the `scrollWidth`/`innerWidth` measure, an axe-core scan fetched once
and never committed, `check`, which serves, runs them and stops, and `server.clj`, the
serve-check-stop skeleton any stage-end script stands on, whose stop goes through the process
tree and then whatever still listens on the port - a serve task's JVM is a grandchild, and a
tree kill was seen to miss it twice. The three hazards are in the README beside the scripts:
the permission on macOS is the terminal application's, a shell under a daemon is refused
silently and `geckodriver --version` cannot tell; a WebDriver screenshot is of the viewport;
a key action left by one check changes the next. Output goes to the workspace's
`work/browser/<app>/` when the application is in one, else `.local/browser/`. What stays the
project's is its interaction checks and its keyboard walk, scripts of its own on the pack's
driver.

In the harness: `bb health` gains an eighth check, `app browser`, which runs the pack against
the generated application with screenshots only on `/` - a probe that performs, where the
doctor's version check passed the day the project lost an hour to a browser that could not
start. Where geckodriver or Firefox is absent the check is skipped, the record carries
`:skipped? true`, and the README's row says "7 of 8 ok, 1 skipped" rather than counting a
check that did not run as passed. `bb doctor` has geckodriver as an optional row in no tier.
The plan template's §14 names the pack as the worked example and §17 carries the hazards and
the accessibility scan; `method.md` §04 says the KIT ships it; the root README has four parts.

### The plan's README says the repository is the build's side

Row 87, written, from a remark the first real project kept rather than a fault. The person,
reading the plan repository, met `profile.edn` beside the requirements and asked whether it
should not live in the application's repository - an application setting, by its look and its
neighbours. It should not: it configures the build, the dispatched roles never see it, and a
change of model is a commit in the plan and never in the application's history. The project's
own word for the repository was "the build information", and the KIT's word, "the plan",
names a third of what it holds.

The README `bb init` writes into the plan now says so in its first lines: the settings and the
records beside the plan, and why they are not in the application. The name itself is left: a
default such as `<name>-build` would say it, `bb init --plan` already lets a project choose one,
and changing the default touches every document that says "the plan" - a boundary's work, not
a stage's. `init_test` asserts the sentence is written.

### A triage call keeps its completion ids

Row 86. The triage call is recorded as a dispatch is - `triage/measured` shapes its result so
`report/dispatch-step` makes the step - but the meta it shaped carried no completion ids, the
one thing the two late-cost fixes of 2026-10-02 fetch by. So when the generation record
lagged, the triage step stayed at cost nil for good, and the first real project's spend sheet
says of run after run that the triage call is not priced, the figure taken off the balance.
`measured` keeps the ids; nothing else changes, since `record` and `bb reprice` already read
them off the step. The triage test asserts the id is there.

### `teardown` refuses before the record, and finishes after it

Row 85, two more things from the same trial's end. Row 78 said the branches are the evidence
until the record is taken, and the teardown of an unrecorded run kept them and said so. The
premise was wrong: the roles' files are uncommitted in the worktrees, and `record` copies them
from there while the worktrees exist. A teardown typed before a record that had refused (row
84's drift) removed the worktrees, kept three branches that held nothing of the files, and the
record that followed wrote an empty `final/`; the project rebuilt the files from the
transcripts' write and edit calls. Then the teardown after that record failed on the worktrees
already gone - `git worktree remove` on a path that is not there - and stopped before the
branches, which were deleted by hand.

`teardown` now refuses a dispatched, unrecorded run before it touches anything, and the message
gives the order: `record`, then `teardown`. `--discard` is the way to drop a run nobody will
record; it removes the worktrees and the branches and says what it loses. And a worktree
already gone is torn down rather than an error: `git worktree prune` for the stale entry, then
the branches. Row 78's rule stands with its premise corrected - the record is the evidence, and
until it exists the worktrees are. Tests: the refusal with no worktree touched, the discard,
and a second teardown on a worktree that is gone.

### A reset removes the event log with the state

Row 84, found at the end of the same trial run. `teardown` of a run that dispatched nothing
removes `state.edn` and resets, so `start` can run again - that was the fix for a failed
provision leaving `start` refused. It left `events.log`, which `harness.loop.log` only ever
appends to; the second start appended its events after the first start's, and `record` at the
end refused the run: 23 events in the state, 24 in the log, the extra one the first start's
balance event. The drift gate did exactly its job on a drift the KIT itself had made, and the
project moved the first line aside by hand to record the run.

The reset removes the log with the state, and the message says both. Nothing is lost: a start
that dispatched nothing wrote a start event, a balance and a provisioning failure the stop had
already printed. The test on the reset asserts the log is gone.

### A run's branches are named by the run, so two runs of one task can exist

Row 83. The loop's identity is `loop.edn`'s `:run/id` - one run directory each, unique by
construction - but the branches and worktrees a run provisions were named from the spec's
`:task/id`, which every run of a task shares. The first real project ran a second Tester on the
same spec as a trial, with its own run id and profile: the dispatched-roles bake-off that row
46 has waited for, done by hand. The second run stopped at provisioning on the first run's
branch, with git's one useful line left in the exception's data where the stop did not print
it; then `teardown` of the failed run, deriving names from the task, reported the first run's
branches absent while they stood. The project gave the trial its own task id and went on.

`provision!` takes `:run/id` and names the branch and the worktree `<run-id>-<role>`; the
session records its branch; `teardown` and `merge` read it from the session, or derive it from
the run id when a session is gone; with no run id the task id names them, so the tests and any
session from before are unchanged. A git failure's message now carries git's words. Tests: two
runs of one task provisioned side by side, the task's name when no run id is given, and the
message on a branch that exists.

### `bb reprice` fills a priced step's provider and tokens too

Row 80's residue, and a sentence of this log's own that was wrong. The entry below said
`bb reprice` on an older record "fills what the old one lacked"; the command selected steps
with no cost, so a step priced by the old command - a cost, no provider, no tokens - was never
looked at, and the project's two published tables kept *by provider unknown* after a reprice
that answered "nothing to reprice". The claim came from the test fixture, not from the command
run on a record of that shape - the failure `CLAUDE.md` names, in the KIT session's own
message to the project.

`reprice/unpriced` takes a dispatch step with ids and no cost, or no provider, or no tokens;
`reprice-step` fills only what is missing and leaves a cost the step has as its own source;
the line says what was filled. Test: the old shape is a target, its cost untouched, its
provider and tokens filled.

## 2026-10-02

### The rounds a dispatched role may take are the project's to set

Row 82, the fourth finding of the first real project's first day, and the one that stopped a
task. A dispatched role works in rounds - one completion, one tool result, again - and the
harness stopped a role after 24 of them, a number the seed carried with no reason recorded.
The runner's docstring said its options reach `converse!`; the loop built the runner with none;
so nothing in a plan, a profile or a `loop.edn` reached the cap. The method calls the retry cap
a project's own policy, and this was a second cap beside it, fixed in the clone. A Tester on a
contract of fifty targets finished on its 24th round, then ended at the limit on its two
retries - once having written nothing, a failed dispatch - and the retry cap counted each, so
the task stopped `capped` one assertion short of green. The project counted the rounds by
hand from the generation ids and kept the table in its stage document.

`:max-rounds`, optional, per role, in the profile's role block; `bb profile` checks it through
the schema; the API runner passes it to `converse!`, and the default applies where the profile
says nothing - the default's comment now says it is the harness's and not a policy. The record
keeps each dispatch's rounds (`:step/rounds`, from the runner's iteration count) and the limit
the role ran under (`:run/roles`), and the report prints a *Dispatch rounds* line - `tester 24
of 24, AT THE LIMIT` - only when a step recorded its rounds, so every published report
re-renders unchanged. The shipped example sets no number and says why; the plan template's §13
table has the row beside the retry cap. The number itself is the project's to measure.

### A reading keeps what a late cost is fetched by

Row 81. The three reading acts - the plan review, the Blueprint review, the spec review - are
one completion each through the same `converse!` the dispatches use, and on the day the
generation records lagged, each wrote `:cost nil` with nothing beside it: no id, no endpoint.
The dispatches had kept their ids since the fifth build for exactly this; the readings had
been written before that and never caught up. The person took the three figures off the
account balance, and said so in the plan's spend sheet.

`agent/call-record` is what a reading records about its call - the model as served, the cost,
the completion ids, the endpoint and the NAME of the key's variable - and the three readings
write it, their `:reviews` history keeping it per entry, the spec review's event in the run
carrying it too. `bb reprice` takes a reading's file as well as a run record and fills the
reading's cost and its history's; `record` does the same for the run's `:spec-review` event
with the one-request fetch the previous entry describes. Tests: a reading repriced through
its history by its own ids, a reading with a cost or without ids left alone and told why, a
missing record leaving it unpriced, the command on a review file, and the three readings'
records carrying the handles.

### The record after a late cost: priced at `record`, whole when repriced, summed whatever the provider

Row 80. The first real project's first run was recorded with `cost=null` on every dispatch:
the generation records had lagged past the dispatch's twenty-second wait, as the provenance
namespace's docstring allows for, and `bb reprice` by hand put the three costs in minutes
later. The report then contradicted itself three times over - *by provider unknown* over steps
whose records named the host, *no dispatch carries usage* beside a filled tokens column, and a
sum of $0.000000 over OpenRouter steps beside a total of fourteen cents. Each had its own
cause, and the project found all three in one reading.

Four changes. `record!` prices its own unpriced steps before writing, one request per id with
no waiting: by then the last completion is minutes old, so most records are whole when
written, and the command remains for the ones that are not. `reprice-step` fills the provider
and the tokens from the generation records where the step has none, the providers joined when
one step's completions were served by two hosts, and never over a value the dispatch did record.
`dispatch-event` keeps the completion's `:usage` whether or not a cost came with it; it had
travelled only beside a `:cost-source`. And the OpenRouter sum takes every priced step not
served by Anthropic's API, provider known or not - a `some->` over a nil provider had dropped
the repriced ones. Tests for each; `bb reprice` on a record written before this fills what the
old one lacked.

### The `AGENTS.md` sentence names the plan folder the workspace has

Row 79's residue, the first real project's first finding after `bb init`. The application's
`AGENTS.md` has a hand-written part above the markers - a reader's orientation, which `bb
rules-sync` never touches - and its sentence on the rules overlay was built from the project's
name: `../<name>-plan/rules.edn`. With the plan folder named by `--plan`, that path pointed at
nothing, while `workspace.edn` had the right one and every rendering read it. The project left
the sentence alone, since the text was the KIT's, and wrote the finding down.

`app/agents-md` takes the plan folder; `app-fn` hands it on from `init`, whose layout already
knew it. The default stays the name's, so a workspace made without `--plan` is unchanged. A
workspace made before this edits the one sentence by hand, once; the markers' block is not
involved. Tests: the sentence with a chosen folder, with the default, and through `app-fn`.

## 2026-10-01

### A project names its repositories; `bb init` takes the names

Row 79, the first finding of the first real project, before its first line of code. `bb init
<name>` made `<name>-app/` and `<name>-plan/`, the name being also the application's root
namespace. The project keeps its source material, its site and its plan as three repositories
under one folder, named as a set; the namespace stays short. There was no way to say so, and
renaming the folders after `init` would have been the silent workaround the plan rules out.

`--app <folder>` and `--plan <folder>`, defaults unchanged. The folders were already data -
`workspace.edn` names them and every later command reads them from there - so `init` is the one
place that knew the suffixes, and the rule mirror now follows the folder it is in. A folder is
held to the name's pattern (a path or a space in `workspace.edn` would reach every task) and the
two must differ. `init_test` covers the layout, the orientation files naming the chosen folders,
and the three refusals.

In the same commit, carried since the scrub: `method.md` §10 lesson 1 loses the three figures
measured on the source project (a share of wall time, two durations, a run count) and keeps the
point in words, the one place in `method.md` a figure from outside this repository remained.

## 2026-09-30

### A change to an existing file says what it did to the forms

Row 10's enforcement half, built before the deliverable it will enforce. `harness.gates.forms`
is the fourth stack-specific namespace beside `repair`, `stub` and `sigs`: a Clojure file's
top-level forms as a table - kind, name, line range, a hash - and the difference between two
tables, as lost, gained and changed names. Edamame reads, which Babashka ships and
`clj-paren-repair` already parses with: reader conditionals as `:clj` with splices flattened,
tagged literals it has no reader for, syntax-quote, `#_` discards dropped. A `defmethod` is named
with its dispatch value so two methods are two rows. The hash is of the form, not the bytes, so
what gate 0 reindents is not a change. Nil and silent when a file does not parse, the rule
`sigs` follows: gate 0 has already named the bracket.

The first use is one line in the after-write report, for `edit_file` and for `write_file` over
a file that existed: *forms 3 -> 2, lost: helper; changed: f.* The row's opening case - one
expression asked for, helpers renamed and a stylesheet's hooks dropped, every target holding -
would have been three names in that line, in front of the Coder that did it. What it is not yet:
the check that a MODIFY packet's unnamed forms are untouched, which waits on a packet that can
name them. Tests: a plain file, a `.cljc` with a conditional, a splice, a discard and a bare
value, an unparsable file, the delta, and that reformatting is not a change. 526 tests.

### A dispatched role can edit a file it wrote

Row 10, the half of it that was an incentive rather than a design. A dispatched role had one way
to change a file: `write_file`, the whole file again. The transcripts of the builds that tried the
KIT were read for what that cost: a role calling `write_file` twice or more on its one target was
common, not rare - each time a complete rewrite paid in output tokens, usually to fix a lint
warning or a bracket the after-write report had just named. The same tool is the incentive
behind the row's opening case, a Coder asked to change one expression rewriting the namespace
around it: when *replace the file* is the only tool, that is what a model reaches for.

`edit_file` (`harness.models.tools`): a path, an old text and a new text. The old text must occur
exactly once; zero matches and several matches both change nothing and say why. Zero matches is
usually gate 0's doing - the file on disk was repaired and reformatted as it was written, and the
model's old text is what it remembers sending - so that refusal carries the file as it is now when
it is small, and says `read_file` when it is not. The same refusals as `write_file` (outside the
workspace, not in `:files/target`) and one more: the file must exist, since the first version is
`write_file`'s. The same after-write follows an edit: repair, lint, report. The Reviewer is not
offered it, as before. One sentence in the packet prompt, for the roles that write, says to change
a written file by editing it rather than resending it; the tool's description alone was not
trusted to change a habit the deliverable's own wording (*write the complete namespace*) feeds.

What this does not do: say *this file is right except for X*. That is the MODIFY deliverable
row 10 still waits for, and the check that goes with it - the forms a packet did not name left
byte-identical - is the next entry. The measurement is the real project's transcripts: calls of
`write_file` per role transcript, counted the same way as before. 519 tests.

## 2026-09-28

### The documents prepared for `main`: what the KIT ships says only what it can show

The person's decision: the builds that tried the KIT are over, the next project built with it
is a real one, and it should stand on `main`. So the development branch's documents were read
for everything a reader of `main` could not resolve, and four kinds of thing went.

**Names and paths.** A project built to try the KIT is named by its order and nothing else; no
document, comment or test carries a project's name, a path on the machine the KIT was developed
on, or where the older records are kept. Two tests and a docstring used a project's namespace as
their example; they use `app.` now. The workflow's diagram names its example project `xyx`, as the
README does.

**Figures.** A cost, a count or a share measured on one of those builds is not quoted, because
nothing in this repository can re-derive it - the rule `CLAUDE.md` has always stated, applied to
the figures that had been let through as evidence. What was learned stays, in words: the register,
the two profile examples' comments, the rule source's comment on `:data-conventions`, and this
log. One sentence had to change its claim and not only its wording: the harness README quoted
what a spec review costs; it now says what the cost depends on and that a project's own records
give the figure (row 42).

**Commits of the development branch.** The branch reaches `main` as one commit, so a hash from
it would point at nothing. The register's Status column keeps its dates; this log's entries are
referred to by date. Hashes of the template fork and of the upstream harness stay: those
repositories hold them.

**Plan labels.** Step names from the working plans (a letter and a number) meant something only
beside plans that are not in the repository. Each is replaced by what the step did.

**The register** is cut where it could be: a fixed row whose lesson lives in code, a test or a
document is one line; a row that is open, watched, provisional or written keeps what remains to
be done, since that text is the only specification of the work. 78 rows before and after.

**Sentences that had stopped being true.** `portability.md` still said the dispatch harness was
set up for nothing and that the loop, triage and the run log were out of scope; it says what was
built, and keeps the design's reasoning. `harness/AGENTS.md`'s hand-written frame told a reader
it was probably a dispatched agent; the file is the harness's own mirror, for work on the
harness by hand and as the first file `bb rules-check` holds, and says so. `harness/CLAUDE.md`
said to copy the off-loop role by hand, which `bb init` does.

Checked: `git grep` over the tracked files for the development machine's home path, the
projects' name, and the name of the folder and the branch the older records are kept in prints
nothing; for the word that folder is named by, one line, the plan template's own sentence about
superseded plan documents; every line naming a run table or the ignored working folder read, each
a project's report or the KIT's own folder; `bb repair && bb gates` green, 513 tests, after the code and test edits and again after
this entry.

### Teardown deletes a recorded run's branches

Row 78, from a build's closing note: dozens of task branches left in its application,
rehearsal and merged alike, because `teardown` removed the worktrees and kept every dispatched
run's three branches for ever, so the work stayed reachable. It is reachable elsewhere: `record`
copies the gated bytes, the final files and the transcripts into run.edn, and a merged run's gate
commit is on the base branch by the --no-ff merge. After the record, the branches hold nothing
it does not, and nothing said whether they were evidence or litter.

The line is the record. `teardown` on a run that dispatched and is recorded deletes its three
branches and says so; on a run that dispatched and is not recorded it keeps them and says
`record` first; `--keep-branches` keeps them for a run somebody means to reopen; a run that
dispatched nothing is reset as before. `merge` records and tears down, so a merged run's
branches go with the merge. The happy-path merge test, which asserted the branches stay, now
asserts they are gone and the work is on main; a new test covers the three cases. The `:merged`
next-steps line and the usage say it. 513 tests.

### Row 62 measured: the CSS watcher does not outlive the JVM on the known-good set

The person asked what could be fixed before the next build and chose row 62 among three: the template's
`bb serve`, stopped by killing its JVM, leaves its Tailwind watcher behind, from the fifth
build's note. Measured before touching the fork: an application generated from the fork at
`kit-v1` through `KIT_TEMPLATE_LOCAL`, served, its four processes listed by pid, and four stops
tried - SIGTERM and SIGKILL to the JVM, SIGTERM and SIGINT to `bb serve` alone. Nothing survives
any of them; the watcher is the JVM's child through the library's process component and dies
with the JVM's pipes. The fifth build's stop command matches no process on this machine, so its
observation cannot be reconstructed.

Two things worth keeping from the way there. The first pass reported SIGINT to bb alone as a leak
that left everything alive, and a one-line hardening (`:shutdown p/destroy-tree` on the serve
task's shell call) was applied to the fork's working tree, an application generated from it, and
the four stops run again - and the SIGINT case was unchanged. The harness was the leak: a job
started with `nohup … &` from a non-interactive shell inherits SIGINT ignored, so bb never saw
the signal. With the disposition reset before `bb serve` starts, the unfixed application is
clean on SIGINT too. The hardening was reverted; no fork commit, no `kit-v2`, no pin bump. The
row goes to watch with the table and what reopens it. The second: a measurement that contradicts
a note is not the last word until the harness that took it has been checked, which cost one
extra cycle here and would have cost a template version otherwise.

### The stop says how many reviews the run has bought

Row 16, open since the first project: the retry cap counts rounds, and a `check` typed after a
rule-source or Blueprint change buys a fresh Reviewer dispatch that is not a round, so one run
took five reviews for most of its money with nothing saying so. `orchestrate/reviews-bought`
counts the Reviewer's dispatch events and prices them from the report steps of the same names
(`:reviewer`, `:reviewer-r1`, …), and `announce!` prints the line at every stop that follows a
review - the count, the cost when the steps have one, and that none of it counts against the
cap. The cap itself is unchanged: each review is a person's choice, and the number in front of
them at the moment of choosing is what the row asked for. Two tests: the count and the pricing
as data, and the line in the reviewed stop's output.

### Row 47 closed: the plan template knows what the KIT decided, and the check over the given parts had landed

From the person's question of what could be fixed before the next build: the register's
open rows read in full, and row 47 turned out to be done. Its three halves landed on three days -
the rules overlay (2026-09-24), the three-part `02` and `03` (the same day) and the check
over `layers.edn` not loosened and the gate keys in order (`plan/given-problems`, tested as
"the given parts: layers loosened, gates reordered") - and the row's last sentence still said the
check was left to do. Status to fixed, the closing sentence written; no code changed. What the
three parts cost was row 76, fixed on 2026-09-27.

## 2026-09-27

### The `layers.edn` entry's route to a run is written where an adopter reads

Row 77, the proof build's third finding and the last of the six to land. The KIT's rules keep
every dispatched role inside its target, the boundary gate fails a namespace nobody declared,
and the way out - the Architect's `layers.edn` in the run directory's `arch/`, named as
`:architecture {:from "arch" :files ["layers.edn"]}` in the run's `loop.edn`, copied by assembly
into the gate worktree - was in the driver's docstring, the provisioning code and the health
check's one task, and in none of the documents that tell an adopter to declare the namespace.
The adopter's plan review asked where the executable sequence was; the first resolution was
wrong; the right one came from reading `health.clj`.

The sentence now sits in three places an adopter meets in order: the architecture template's §4
note (one row here, one entry there, and this is how the entry travels), `method.md` §03 step 3
right after the gate that makes it necessary, and the header comment of the `loop.edn` that
`bb init` writes into the plan, which every run directory copies - with the key to add. No code
changed; `init_test` asserts the header carries it.

With this the six findings of the proof build are ported, one commit each.

### The plan check leaves a given part's blockquotes alone

Row 76, the proof build's second finding. `bb plan-check` took every blockquote line of
every template document as an instruction - the template's notes to the reader, which a filled
plan deletes once acted on - and the template's rewrite had put `02` and `03` in three parts, the first of which,
Given, is the KIT's own prose and is not edited. `03` §10 carries a five-line blockquote on rule
layers inside that part. The adopter kept it, as the README says to, ran the check, and was told
five lines of the template's instructions were still standing; the check gates the first
dispatch, so the lines lost their `>` markers and the given part was edited to satisfy the rule
that says not to edit it.

`plan/without-given-part` cuts a template document from `# Part 1 — Given` to the line before
`# Part 2` before `instruction-lines` reads it, so a given part's blockquote is prose and the
opening note before Part 1 and every blockquote in the chosen and domain parts are still
instructions. The test reads the shipped template: `03` §10's line is not an instruction, `02`
§4's and `03`'s opening note are, and the cut is checked on its own for a document with no Part 1
or no Part 2. The plan template's README says it in one sentence. 511 tests.

### `bb init` writes the records' folder and their document

Row 75, the proof build's first finding, seen when its workspace was made and left
for the adopter to find. Four documents - the method's §03, the plan template's README, and the
workspace and plan READMEs that `bb init` writes - said the plan holds `runs/` and `RUNS.md` from
`bb init`; `workspace.edn` pointed at both; and `bb init` wrote neither. Row 65 (records in the
plan) gave `record` the copy and `report-check` the hold, and the documents were written as if
the folder and the document came with them. The adopter found no folder, watched `record` make
it on the first copy, and wrote `RUNS.md` by hand with `bb report`'s output pasted in before the
gate would pass.

`bb init` now writes both: `runs/`, empty until the first record (git tracks it from then), and
`RUNS.md` with a header that says how a report gets in and that publishes none - a report is a
plain fenced block opening `Run <id> ·`, which is what `published-reports` finds - so
`report-check` holds an empty folder to it from the first gate run. Asserted in `init_test`
(`:both`, no published report, no drift) and checked live in a scratch clone: `bb init n1check`
makes both, the plan's first commit tracks the document, `bb report-check` from that clone says
"reports in sync: 0", and nothing of the project is in the clone. The plan template's README stops counting "the
four beside `docs/`" - its tree lists six - and says which `bb init` writes and which command
makes `reviews/` and `bake-offs/`.

### The KIT's gates pass in a workspace's clone

Row 74, the proof build's sixth finding and the one that failed the build's own
verification. The adopter ran `bb gates` in its clone at the build's end, as `harness/README.md` says a
workspace does, and the test gate was red on two assertions of one test: `balance/split-args`
given a record alone was expected to return no profile, "outside a workspace" - which the KIT's
development folder is, and a workspace's clone is not. The seven other gates were green; the
KIT's clone stayed clean. Every gate run before had been in the development folder, so a test
that was true only there had never been false.

`split-args` now takes the workspace's profile as a second argument, and the one-argument form
supplies `profile/project-profile` as before; the test passes nil and a path and asserts both
ways, including `bb balance` alone from a clone meaning the project's profile. Verified where it
failed rather than where it had always passed: a clone of the previous commit inside a
scratch workspace (a `workspace.edn`, a plan folder with the shipped `claude` profile and the
overlay `bb init` writes) failed the same two assertions; the same clone with the fix copied in
runs 510 tests green, and `bb gates` there is green. A first attempt to reproduce by exporting
`KIT_WORKSPACE` was not the adopter's situation: the variable wins over the walk-up and four
workspace tests that probe the walk-up with an explicit directory fail under it. Noted in the row
and left - it is a hazard of the development folder, not of a clone.

### A `:deps-sigs` entry is qualified, and both readers of the slice say so

Row 73, the proof build's second finding. Its home-page task wrote `:deps-sigs [(base [title
content]) (header [...])]` the way `method.md` §06's example packet and the Blueprint template's
packet showed it - unqualified - and `start` passed it: `sigs/violations` resolved an unqualified
name by searching every context file for a definition, and a test asserted that a truthful slice
may hold one. But `sigs/undeclared-calls`, the `calls` gate, grants by `[ns name]`, so the same
entry granted nothing, and the Coder's first `views/base` was a red gate after a Coder and a
Tester had been paid and triage had routed it to the Architect. Two readers of one key, two
rules; the documents taught the one that fails late.

One rule now: an entry is `ns/f`. `violations` reports an unqualified entry as `:unqualified`
with the form to write, in place of the search; `sigs/check-deps-sigs` is the same as pure data in
`stub/check-slice`'s shape (an entry `parse-sig` cannot read is named too), and
`blueprint/spec-for` and `driver/start!` run it beside `check-slice` and `check-files`, before
the schema, the spec review and provisioning. The example packet, the method's sentence on
`:deps-sigs`, the template's packet and a new note in its §4, and the Blueprint reviewer's rule
text all say qualified. The ambiguity case (two context files defining one name, once skipped
"rather than guessed") is refused as unqualified now and resolves when qualified, which the same
test still checks. 510 tests.

### A two-file packet is refused before a review is paid for

Row 72, from the proof build: an adopter session that built a two-stage project on the KIT
after the state move and wrote nothing into its clone. Its fourth task named
`handlers.clj` and `routes.clj` under one `:files/impl`. `shapes/TaskSpec` said `[:vector {:min 1}
:string]`, so the extraction validated it, `sigs` passed it, and `start` ran two spec reviews
and provisioned three worktrees before `stub/write!` threw the one-file rule; the run was
torn down and the task split, one run more than planned. The rule was always right - a slice is
one namespace's shapes and signatures, so a second file has no way to say which interface is
whose - but only the last reader enforced it, which is the same shape as the slice entries the
stub could not read (the fourth project's crash, `stub/check-slice`).

The fix follows that precedent: `stub/check-files` returns the rule as data in `check-slice`'s
shape; `blueprint/spec-for` runs it first and refuses by name (`:blueprint/error :multi-impl`,
"split it into one packet per namespace"); `driver/start!` runs it beside `check-slice`, before
the spec review and before anything is provisioned, so the refusal has nothing to tear down. The
schema now says `{:min 1 :max 1}` as well, for a reader that validates without the rule; the
Blueprint template's packet comment and `method.md` §06 say one file per task. Three tests:
the check and `write!`'s message; the extraction refusing a two-file packet from the template
fixture; `start` refusing it with no provisioning, no signature check, no `state.edn`.

The build's other five findings follow, one commit each; the adopter's own records stay in its
workspace.

## 2026-09-25

### Bake-offs are a tool: `bb models`, `bb bake-off`, and a judge that reads blind

Row 46, deferred since the first bake-offs were run as throwaway scripts
and scored by hand. Decided with the person before the proof build: a bake-off is data - an act,
candidates, a judge - and the mechanism is the same for every act; the person types three lines
and the tool generates the rest; the judge maps consensus and disagreement rather than deciding;
one pass per candidate, no samples.

`bb models <query>` (`harness.models.catalogue`) is the lookup every brief did by hand: OpenRouter's
public listing as rows, newest first, the family read off the slug's prefix, prices per million
through BigDecimal (0.0000016 × 1e6 in doubles prints 1.5999999999999999), whether reasoning is
taken, the providers serving one model; no key is sent. `resources/routes.edn` is how each family
is reached - endpoint, key variable, shape, provider pin, effort parameter and levels, `max_tokens`
- what the two shipped profiles already said, written once so a slug can become a role block.

`bb bake-off run <plan>/bake-offs/<id>/bake-off.edn` (`harness.models.bake-off`) expands the spec -
`{:role :blueprint-reviewer :candidates ["anthropic/claude-opus-5.5 high" "grok medium"] :judge
"openai/gpt-6-astra high"}` - through the catalogue and the routes into `RoleProfile` blocks,
written beside as `resolved.edn`; refuses by name a model the listing lacks, a family with no route,
an effort the route does not know, fewer than two candidates, no judge, a judge that is a candidate.
The act names the profile role, how its cases are found on disk (the plan; each Blueprint under
`docs/stages/`; each `spec.edn` under `work/runs/`) and how one case is run: the three review
namespaces gained a `read!` that takes a role block instead of a profile file and returns the
findings, the model as served, the cost, the time and the text sent, so a candidate sits in the
seat without touching the plan's real review files. One record per case per candidate under
`records/`; a candidate whose call fails is recorded and the others go on. Then the judge, one call
per case, given the candidates' input and their answers as A, B, C in a shuffled order the record
keeps, asked for one row per distinct finding with the letters that raised it, a note where they
disagree and its own opinion; the letters are mapped back only in the record. `TABLE.md`: per
candidate the findings raised, rows raised, rows raised alone, cost, time, and real findings per
dollar from the person's `marks.edn` alone (a row index per case → true/false); per case the
cross-reader table. `bb bake-off table` re-renders after marks; `bb bake-off-check` holds every
table to its records and is in `bb gates`' workspace half beside `report-check`. `bb bake-off new`
asks in order at a terminal - the act by number, each candidate resolved and confirmed back, the
judge held to the rule and asked again, the cases on disk - and writes the spec; without a
terminal it says to write the file. The dispatched roles' runner - a loop run per candidate - is
the row's residue.

Checked: `bb gates`; the catalogue on a canned listing in the listing's shape (a word → the newest
slug, an alias skipped, an exact slug, an empty word names nothing, prices, the family, the routes
file's shape and a family with no route refused); the expansion and every refusal; the letters'
seeded shuffle; the run end to end against a stub that answers by who is asking (records, the
judge's record with its order and rows, the table before and after marks, the check catching an
edited table and a lost record; a dead endpoint for one candidate recorded and the others judged);
`new` on a scripted terminal. Live, free: `bb models grok` and `--endpoints` for one model. Live,
paid: one bake-off on an earlier build's stage 1 Blueprint - three readers and a fourth model
as judge: every row raised by all three was judged real, and most rows raised by one reader
alone were the slowest reader's literal readings of the one-promise rule; what the calls
reported as their cost is what the balance moved by once the last call had posted. The table
and the records are kept outside the repository. `fs/glob` matches files and not
folders, found by the check's test. Row 46 fixed with its residue.

### The Blueprint is read before sign-off: `bb blueprint-review`, and a sixth role in the profile

Row 70, opened the same morning by the roster, closed. `method.md` §07 step 2 promised a read of
the Blueprint - the Orchestrator asks whether it is over-engineered, then the human signs off - and
nothing ran it: the harness's orchestrator role is triage, and a Blueprint's packets met a model
one at a time, in the spec review, which cannot see a layer with one use, a shape nothing needs, or
a packet that builds for a later stage. Two projects wrote the read as a throwaway script each.

Now `bb blueprint-review <blueprint.md>` (`harness.setup.blueprint-review`) sends the stage
document the Blueprint's header names as its input, then the Blueprint, then two sections read
from `method.md` at the call - §07's strategic-planning step and §06 from its data-shapes-first
rule through the rules for property targets - to a new profile role, `:blueprint-reviewer`: one
completion, no tools, the prompt saying which parts are given. The findings are parsed as the plan
review's are, printed with the cost, and written to `<plan>/reviews/<stage>/blueprint-review.edn`
with the history of earlier reads; the Architect resolves them in the stage document, then signs
off. Not a gate. A Blueprint not under `docs/stages/`, a profile without the role, a stage document
the header does not name (the read goes on, saying so) and a missing method heading are each said
by name.

The role: `harness.contract.shapes/Profile` closes over six roles now, `profile/roles` lists it
first (a build meets it before any `start`), both shipped examples carry it - Claude Opus 5.5 at
high effort over OpenRouter pinned to Anthropic, no cache, no pricing. In the `claude` example that
is the seat's family, which §05's reasoning forbids as it forbids it for the spec reviewer; the
decision (the person's, 2026-09-25) is an accepted exception from a measurement - on one build
three families read both Blueprints cold and this one alone found each round's load-bearing
defect - written in the profile's comment, left unchecked by `bb profile` on purpose, and watched
as row 71 with what would reverse it. In the other example it is not the seat's family and needs
no exception.

Checked: `bb gates`; the checklist (both sections, their ends, each missing heading refused by
name); the stage document from the header; the reviews folder from the file name; the input's
order; the prompt; the command against the stub model server (the write and its history, the
missing stage document named, an answer with no block, the two refusals); the profile schema, the
role order, the exception's test; `bb profile --seat claude` and `--seat agy-ide` green. Then ONE
LIVE CALL, on a real stage 1 Blueprint and its stage document from an earlier build, copied to
scratch with the shipped profile; the cost the call reported is what `bb balance` showed
before and after. It found name divergences between the stage document and the
Blueprint, a boundary error the seam does not name, bare-name vars, targets requiring a
var no interface asks for, one target that is a description, and several findings reading a target as
two promises; no over-engineering finding, which on a tight stage is the right answer. The written record
is kept outside the repository. `method.md`
§05 (a row) and §07 step 2, `harness/roster.md`, `workflow.md`, `portability.md`'s role table,
`harness/README.md` (the pieces table, the profile row's count, "Adapting it"), `harness/AGENTS.md`'s
task table and the workspace `CLAUDE.md` and plan README `bb init` writes name the command.

### The roster and the workflow: who acts, and in what order

Two documents, written from a question about how many reviews a build has. Nothing in the KIT
mapped every act in a build - each review, gate, dispatch and human gate - to the role behind it:
`method.md` §05 has the roles and `portability.md` the roles' models, and the answer was a table
neither held. `harness/roster.md` is that table: Act, When, Kind, Profile role, Model (the shipped
`claude` example, dated; `bb profile` is the live answer), Notes - and writing it found the row with
nothing behind it, the Blueprint review, opened as row 70. `workflow.md`, at the root beside the
method, is the sequence the roster's acts happen in: one Mermaid diagram in five parts - setup, the
plan, Foundation, a stage, the loop per task - the diamonds the human gates, and a table of the
fourteen steps with who, the question each answers, what runs and what exists afterwards. The root
README's document map, the harness README, `method.md` §05 and `portability.md` point at them.

Checked: the diagram rendered (the machine's Chrome behind the Mermaid CLI): every node in its
part, the loop its own box, the return edge to the next stage. Row 70 written open.

### The Blueprint's packet is the spec the harness reads, and a command pulls it out

Row 69, written and fixed in this commit.

§4 of the Blueprint template wrote a packet with keys the harness does not read - `:contract` for
the slice, `:workspace {:repl/port :dir}`, `:gates {:cmd "bb gates" :retry-cap <n>}` - and without
two it does, `:task/title` and `:property-targets`. `PacketBase` reads `:blueprint/slice {:shapes
:interfaces :deps-sigs}`, `:files/target`, `:files/context`, `:layer/name`, `:property-targets`,
`:task/title` and `:gates {:retry-cap}`; the worktree and the port are the driver's, bound at
dispatch from what it provisions. The method's §06 block has been held to the schema since the
seed - it is `shapes/example-packet`, and `bb test` validates it - but the template, the document
an Architect actually fills, was held to nothing, and the step from a Blueprint to a run's
`spec.edn` was a hand copy: open the Blueprint, take the fence, paste in every shape it named from
§1, rename the keys. Every build did it, each a little differently.

Now the template's packet is a `TaskSpec` - a new schema in `harness.contract.shapes`, the part of
`PacketBase` the Architect writes: `:files/impl` and `:files/test` where a role's packet has its
`:files/target`, no role, no worktree, no port, `:gates` optional - and the note under it says what
the driver adds at `start`. `blueprint_test` reads the fence out of the template, placeholders
standing, validates it as a spec, checks its slice with `stub/check-slice` and cuts the Coder's,
the Tester's and the Reviewer's packets from it with a fake session, so the template and the
schema cannot drift apart again. The placeholders are single tokens for that reason - `<Shape>`,
`<schema>`, `<Named>`, `[<args>]` - and the quotes are gone, since the driver reads `spec.edn` as
EDN and EDN has none. The mark count `plan_test` holds the template to is unchanged: a fence is
not counted, and the note carries no angle bracket.

`bb spec-from-blueprint <blueprint.md> <task-id> [<out.edn>]` is the copy as a command
(`harness.contract.blueprint`): every fence whose first form is a map with a `:task/id` is a
packet; the one whose id matches is taken; every shape it names under `:shapes` is replaced by
§1's definition of it, verbatim - a `(def Name schema)` stays a def, a `[Name schema]` a pair,
both of which the stub reads - the named ones first in §1's order and the packet's own inline
entries after; the result is validated as a `TaskSpec`, checked as a slice, and written
pretty-printed in the template's key order, or printed to stdout with the report on stderr so it
pipes into `bb sigs`. A shape §1 does not define, a task id the Blueprint does not carry, a
Blueprint with no `## 1.` section, a packet that is not a spec (in the schema's own words) and a
slice the stub cannot read are each refused by name, exit 1. The tests derive their Blueprint
from the template inside the test, so a placeholder the template drops breaks the derivation and
a test says so: found and inlined in §1's order, the two refusals, the written spec valid and
read back equal, the command's stdout read back as the spec. Run by hand: the template itself is
refused on `<Named>`; the fixture piped into `bb sigs` against a one-file context checks out.
`method.md` §07 says where a packet comes from, the template's note says what the harness reads
and what the driver adds, `harness/README.md` and `harness/AGENTS.md` carry the command.

### The `claude` example ships the route every build ran on

Row 68, written and fixed in this commit; row 25 extended.

The shipped `claude.edn` had its Coder and Orchestrator direct to Anthropic, with `:pricing`
tables and a comment explaining why the cost was computed. No build ran that way. The routing
decided on 2026-09-18 from the probe row 25 records - the Anthropic roles through OpenRouter,
pinned to `anthropic` (Vertex's cache missed intermittently, Anthropic's never), `:cache_control`
asked for on the Coder and not on the triage call, no `:pricing` - was applied by hand to the
plan's profile in each of the three builds since, while `bb init` went on copying the direct
example. The example contradicted the money tooling built alongside it: `bb balance` and the two
lines around a run read OpenRouter's key and account, and Anthropic's API has no balance
endpoint; the report prints a reported cost as the endpoint's word and a computed one marked `~`;
a 402 stops the loop by name, and with every role on one key there is one account to stop it.

Now the file ships that routing, and its comments say why in those terms - one key and one
balance, the cost reported not computed, the credit stop, the generation record - with the
dated decision and the three builds named as builds. The header no longer says this file
exercises the `:anthropic` adapter; it says the pair still covers both tool-call shapes because
`agy-ide.edn`'s Reviewer and spec reviewer are direct, which is where that shape's worked
example now is. The Tester, Reviewer and spec reviewer are as they were. `profile_test`'s
shipped-pair test holds because of `agy-ide`; its provider-pin test now covers two more roles;
a new test holds the example to the row (the route, the pin, the key, no `:pricing`, the cache
on the Coder alone). `harness/README.md`'s profile bullet, `portability.md`'s seat table and
role table and the plan template's §12 say what the example now is, and what a direct role
needs instead. Nothing in the harness changed: the health check's loop copies this profile
and calls no model, and its balance line reads *no role is on Anthropic's API directly*.

### The workspace test holds the sync to what it did, not the clone to being clean

Row 67, written and fixed in this commit.

`app_test`'s *the two parts meet in one workspace* ends by proving that an overlay edit in a
workspace's plan re-renders the application's mirror and reaches nothing in the KIT's clone. It
proved the second half by asserting the clone's `git status --porcelain` for `harness/AGENTS.md`
and `harness/resources/` was empty. That is a claim about the tree the test runs on, not about
the sync: any uncommitted edit under those paths failed it, and the repo's rule is to run
`bb gates` before committing - exactly when such an edit stands. It failed once, when the health
record was written, on the known-good set `bb health --record` had just written; a rule-source
edit before its commit would have failed it the same way, and the message would have said the
sync leaked.

Now the test reads the clone's state before the sync - the status of the two paths, the
mirror's contents, the rule source's contents - and asserts the state after is equal. A dirty
tree stays dirty and passes; a sync that wrote into the clone changes the mirror's contents or the
status and fails. Checked with an untracked file standing under `harness/resources/`: the
namespace green, 4 tests and 26 assertions; then `bb repair` and `bb gates` on the dirty tree
with this entry and the row standing.

## 2026-09-24

### The documents say what the state move did, and the health check records its loop

Stage 2's last step before its proof: the documents written against everything the state move
changed, the doctor's seat rows made honest, and one extension to `bb health`. Rows 49 (its
residue), 65 and 66.

**The documents.** After the six steps before this one, a project's state - its rules overlay,
its profile, its run records - lives in the plan repository, `bb plan-check` reads the filled plan
before Foundation and `bb plan-review` runs §02's pass; and the documents an adopter meets first
still described the clone as the place the profile went and the plan as a folder of documents.
Now: the workspace `CLAUDE.md` `bb init` writes names the two commands in the plan's own bullet
(the file is held to one screen, its line bound raised by three for them); the workspace README's
plan row and the plan's README name `bb plan-check` as what reads the plan before the first
dispatch; `harness/README.md`'s "Adapting it" gains the bullet for the two commands, its pieces
table the rows for `harness.setup.init` + `app` + `workspace` and for `plan` + `plan-review`
(thirty-three namespaces now, and the size sentence re-measured); the root README's three-part
table says the plan template is half-written on purpose and that `bb init` copies it beside the
overlay, the profile and the records; `method.md` §03 step 1 lists what the plan repository holds
and its readiness checklist's item 2 now ends with `bb plan-check` passing; the plan template's
README shows the four files beside `docs/` in its tree, since an adopter reads it from inside the
plan. Nothing in the KIT's own words changed meaning; the words caught up with the code.

**The seats.** All five stay, by decision. So each seat's doctor entry says where it stands:
`claude` is the seat every build has run from and the one the health check's profile names;
`agy-ide` has its profile example and no build yet; `opencode`, `opencode2` and `pi` have no
example, so `bb init --seat` refuses them by name - which was true since the profile moved into the plan and said nowhere an
adopter would read before being refused. `harness/README.md`'s profile bullet and
`portability.md`'s seat table say the same. Row 66 holds the open half: an example per seat and a
build from each, the person's, after the KIT is proved in `claude`.

**The health check records its loop.** The records' move left the health loop stopping `:awaiting-merge` and
tearing down without `record`, so the copy in the plan's `runs/` and the three commits were held by
a hand step and the unit tests. `check-loop` now runs `record` before the teardown, as `method.md`
§03 step 5 tells an adopter to, and holds the record to what it claims
(`harness.setup.health/loop-problems`, pure, one sentence per breach, tested): `run.edn` written
with status `:awaiting-merge`; `:run/kit-commit`, `:run/app-commit` and `:run/plan-commit` each
equal to that repository's HEAD as the check reads it now, nil only where the workspace has no such
repository; the copy at `<plan>/runs/health.edn` present and byte-equal to `run.edn`; and the
loop's rules overlay and profile resolved under the plan, since a loop that read the clone's
would prove the wrong thing. The selfcheck subject has no plan and is held to a nil plan commit and
no copy. The detail line says which files the loop read and where the record went. Still seven
checks: the record is the loop's last step, not a check of its own, and the README's health block
is unchanged - its row is a dated claim about the run it names. Run on the working tree: healthy,
7 checks, 58 s; the kept workspace's record, read by hand, carried the three HEADs, and `bb report`
printed them on its `Commits:` line. One correction to the loop check's docstring while there: it
said *no network*, and `start` has asked the OpenRouter key's status since the balance line was
added, when a key is in the shell - never failing the run; `record` asks once more. Said so.

### A filled plan is read before Foundation: `bb plan-check` is the gate, `bb plan-review` the reading

Row 49. The plan is the Architect's session's to fill, and nothing read it: a mark left standing
reached the next reader as literal text - the rule source's failure, one level up - and
`method.md` §02 described a review pass that two projects ran as a throwaway script each, with the
checklist pasted in by hand. Two commands now, in `harness.setup.plan` and
`harness.setup.plan-review`, and one hook.

**`bb plan-check [<plan-dir>]`** is the gate, and it is cheap. Over the governing documents - everything
under `docs/` except the template's own `README.md`, which explains the marks and so carries one, and
the two stage templates, copied per stage with their marks standing by design - it reports every
`<mark>` left (`plan/placeholders`) and every line of the template's instructions
still standing. An instruction is known by its content, a set of every blockquote line the shipped
template carries, so a project's own quotation passes and a stage document filled from the stage
template is read under its new name. Then, when the plan is in a workspace: the overlay's
placeholders (`rules/unfilled` over the merged set), and the given parts - the overlay's refusal of a
KIT rule, said by name; the application's `layers.edn` against the pin's layers, where a template
namespace may not require a template namespace the pin did not let it (a layer the project adds,
and a template layer that uses it, are the chosen part and pass); the plan's `loop.edn` gate keys
in the KIT's order, a project's gate after them. Exit 1 with the list, one sentence each. On the
template as `bb init` writes it, fifteen sentences; on a filled fixture, none, and each way of
breaking it is one.

**`start` runs it once per workspace**, before the contract's slice is read and before a spec
review is paid for. Once is known by a hash of everything the check reads - the governing
documents, the overlay, `layers.edn`, `loop.edn` - kept at `work/plan-check.edn`; a plan that has
not changed prints *unchanged since* and is not read again, one that has is read again, and a
failure does not move the hash on. The stop is `:plan-check`, the Architect's, like the spec
review's; `:plan-check? false` in `loop.edn` switches it off, which the health check's generated
application sets - its plan is the template as shipped, unfilled on purpose, and the health check
proves the machine, not a plan.

**`bb plan-review [<plan-dir>]`** is §02's pass, run: the six Phase A documents in the order they are
written, a missing one named as such, and §02's checklist - read from `method.md` at the call, from
its heading to the section's end, so the method and the review cannot drift apart - to the
profile's `:spec-reviewer`, one completion, no tools. The prompt says which parts are given and
not the project's to have decided, that PROVISIONAL-with-a-spike and OPEN-with-an-owner are the
method working, and that RESOLVED-on-no-evidence is a finding. Findings are printed with the
call's cost and written, with the history of every earlier review of the plan, to
`<plan>/reviews/plan-review.edn`; the person resolves them in the overview's table. Not a gate: a
review that found nothing proves nothing. Tested against the same stub model server the spec
review's tests use.

**Under the check, the template.** Two marks in `03-method-and-tooling.md` had a mark nested inside
(`<… with \`<type>\`.>`): the fixture counted the inner one, and an adopter who filled it met the
outer one as a new mark. Both un-nested; `03` counts 37 now. `method.md` §02, the template's README
and the overview's §5 note name the two commands; the workspace documents `bb init` writes follow in the next step.

### The plan template knows what the KIT decided: given, chosen, theirs

Rows 47 (the template half), 48 and 59. The plan template predated the inversion: `02-architecture.md`
asked which datastore and `03-method-and-tooling.md` asked the adopter to fill in roles, packet,
isolation and gates as if choosing them, when adopting the KIT had fixed all of it. Both are now in
three parts. GIVEN states what adopting fixed, as references to where the KIT says it and not as
questions - the stack as pinned, the six layers as `layers.edn` declares them, the roles and the
independence rule, the packet, the three worktrees, the five gates with their keys, triage and the
routes, the stops and their owners, the tooling, the rule source and the precedence of its layers -
and is not edited; changing a given part leaves the certified pair. CHOSEN is decided once, in
Foundation, and recorded in the log - the layers above the template's, the seams, the datastore, the
overlay's three placeholders, the models per role, the numbers with their keys and defaults
(`:retry-cap` 3, `:spec-review/max` 2, the money cap and floor a person holds), the write-time hook
and the stage-end driver, the pins. THEIRS is the domain: the diagram, the surfaces, the property
targets, the stage-end checks, the readiness record - whose fourth line now says the trivial task is
run to `:awaiting-merge`, recorded and torn down, never merged, as `method.md` §03 step 5 and its
checklist now say too (two projects merged theirs; one paid a run for the flaky test it left).
`01-requirements.md` gains a *Derived from* line and a *Revisions* section naming the mechanism a
stage's lesson enters through. A new `source.md`, written first, is the record of what the plan
derives from - handed over, read, asked - so a requirement cites evidence by number instead of
carrying it; `reviews/` is named as the home for a review's raw material. The README, the overview's
document table, `method.md` §02's document set and the orientation `bb init` writes say all of this.

A namespace to read a plan by: `harness.setup.plan/placeholders` is the definition of a mark a
project fills - an angle bracket outside a fenced block, not an autolink or a comment, and not
`<name>` or `<kit>`, which the workspace knows - and `placeholder-counts` over the shipped template
is the fixture the check over a filled plan is written against.

Checked: `bb gates`; `bb init --dry-run` into a scratch folder counts nine plan documents where the last commit had eight; the definition of a mark (the two known tokens, a fence, an autolink, a comment,
inline code counting); the counts per shipped document, and that they cover exactly what `bb init`
lists; the given part of each of the two documents carrying no mark past its header and opening
note. Rows 48 and 59 fixed; row 47 extended, open for the check over `layers.edn` and the gate keys.

### A KIT kept outside its workspace is pointed at it, and a run's workspace is the command's

Row 45, open since `bb init` first allowed `bb init <name> <dir>`: every command in `harness/`
found its workspace by walking up from the working directory, so a clone kept outside the
workspace - or one clone serving several projects - found nothing. The rule tasks then missed the
application's mirror (a generated file nothing checked), and since the overlay landed the same
lookup carried the project's rules, so from such a clone every rendering read the source alone,
placeholders and all, with nothing saying why. Now one function answers where a command runs,
`workspace/current`, in order: the run's workspace, when a loop command is running - `run-loop`
binds it from the run directory, which walks up as before, so a run's rules are its own workspace's
whatever the shell says; the folder `--workspace <dir>` names; the folder `KIT_WORKSPACE` names;
else the walk-up from `harness/`, unchanged. The flag is on `rules-sync`, `rules-check`, `profile`
and `report-check`, taken out of the arguments before the rest are read (the mirror list is now
computed inside `rules/-main`, after the flag, rather than in `bb.edn` before it); the variable is
read by every command, so `bb balance` and `bb rules-prompt` follow with no change of their own. A
named folder with no `workspace.edn` at or above it is refused by name, flag or variable, exit 1 -
a KIT pointed at nothing is otherwise indistinguishable from a KIT in no workspace. The walk-up
answers nil as it did. `:workspace/from` in the map says which of the four answered.

Checked: `bb gates`; the flag taken out of the arguments and refused without a value; the flag
over the variable over nothing; `current` from inside a workspace (walk-up), from outside (nil),
pointed by the flag from outside (found, paths absolute), pointed at a folder under the workspace,
pointed at nothing (refused, naming the flag or the variable), and under the run's binding (the run's,
over the flag); `mirror-paths`, `plan-profile` and `check-targets` each from a folder outside the
workspace, bare and pointed. Then from this clone against a scratch workspace kept elsewhere, with
its overlay filling one placeholder: `bb rules-check --workspace <ws>` reported the application's
mirror drifted, `bb rules-sync --workspace <ws>` rendered it with the overlay's text and the clone's
own two mirrors untouched, `bb rules-check` pointed by the flag and by the variable then passed;
`bb profile --workspace <ws>` printed the plan's profile; `bb report-check` pointed both ways found
the records folder and failed on the missing table, as it should; the flag with a folder in no
workspace, and with no folder, each refused in a sentence. Row 45 fixed.

### A run record has a home in the plan, and names the commits it was taken at

The third part of stage 2's state move, and the last file of a project's that had nowhere to go.
`record` wrote `run.edn` into the run directory - scratch under `work/` - and two builds copied
it into their plan by hand, by a convention nothing enforced; which KIT, application and plan
commit a record ran against was answered from memory. Now `workspace.edn` names
`:workspace/records` (`<name>-plan/runs`) and `:workspace/run-tables` (`<name>-plan/RUNS.md`),
`bb init` writes both, and `find-workspace` makes them absolute like the rest. `resolve-config`
keeps the records folder and the three repositories in the run's config at `start`, as it keeps
everything; `record` copies `run.edn` to `<records>/<run-id>.edn` and writes `:run/kit-commit`,
`:run/app-commit` and `:run/plan-commit` from `git rev-parse HEAD` in each - nil where there is no
such repository, and the report then prints `—`; a record from before them prints *not recorded*,
as the roles line does. The schema has the three as optional strings. `bb report-check` takes its
two targets from `workspace.edn` when given none, and `bb gates` now runs it: in a workspace's
clone a record nobody published, or a table whose record is gone, fails the gates there; in the
KIT's own folder there is no workspace and it says so and passes. One target given is a usage
error, not a guess at the other.

Checked: `bb gates`; `find-workspace` with and without the keys; `resolve-config` in a workspace
and bare (the KIT then the repository this runs from); `record` in a workspace fixture with a
commit in each of the three repositories - the copy by run id, the same record in it, the three
hashes - and outside one (no copy, the application's hash, nil for a folder that is not there
and for the plan it has none of); the schema; the report's line and the older record's; the
targets from arguments, from a workspace, from one without the keys, and with one argument. Then
end to end: `bb health --keep` green (7 checks, 62 s), `bb run-loop record` on its kept run
copied the record into the generated workspace's `hc-plan/runs/health.edn` with the three hashes
equal to `git rev-parse HEAD` in the KIT, the application and the plan; `bb report-check` on that
plan failed with the record unpublished and passed once `RUNS.md` carried the rendered report.
Row 65 written and fixed.

### A project's profile lives in its plan, and the driver reads it there

The second part of stage 2's state move. The profile - which model answers for which role, and
the seat - was the one file the README still sent into the clone: `resources/profile.edn` on a
branch of the KIT, the destination row 55 named a day ago because the folder beside it is held
to the shipped examples by the KIT's own tests. Now `bb init` writes `<name>-plan/profile.edn`
- the shipped example for the seat, whole, its header kept, under a line saying what the copy is
- and the plan's `loop.edn` says `:profile "profile.edn"`. `--seat <name>` picks another example
(default `claude`; a seat the KIT ships no example for is refused by name, before anything is
written). The driver's `resolve-config` resolves `:profile` against the workspace's PLAN when
`workspace.edn` names one, and against the working directory otherwise - the health check's
selfcheck has no plan and keeps the shipped path - so `start`, `run-loop run` and `bb reprice`
read the plan's file with no change of their own. `bb profile` with no argument checks the plan's
profile of the workspace this clone is in (`profile/project-profile`, found by walking up) and
falls back to the shipped examples, structurally, outside one; `bb balance` no longer requires
the profile argument - a first argument that reads as a profile (a map with `:roles`) is one,
anything else is a record and the profile is the workspace's - so the stop text's bare
`bb balance` now works from a workspace's clone. The shipped examples' headers say where the
copy goes. The clone now carries nothing of a project's configuration: rules and profile
are both in the plan.

Checked: `bb gates`; `resolve-config` in a workspace with a plan, without one, and with an
absolute path; `context` from a run directory under `work/` with the plan's copy of the shipped
example; the layout's file (the default seat's example, `--seat agy-ide`'s, the refusal for a
seat with none) and the parsed arguments; `project-profile` outside a workspace, in one with no
profile yet, and from the clone's `harness/`; `bb balance`'s argument split; `bb health` green
(the generated application's loop ran from the plan's profile). Row 55 extended.

### A project's rules live in its plan, and the KIT's clone is never edited for them

The first half of stage 2's state move. Until now the rule source's three placeholders were filled
on a branch of the KIT's clone, so `git pull` was a merge and a project's rules sat in a repository
that was not its own - row 47's *nothing holds the given parts*. Now `bb init` writes
`<name>-plan/rules.edn` - the three placeholder rules, text as shipped - and `workspace.edn` names it
(`:workspace/rules-overlay`). `harness.rules/overlay` merges it over the source by id: an entry for a
placeholder rule replaces its text, an entry with a new id is a project rule and must be whole, and
an entry for any other rule of the source is refused by name, every problem at once - the check that
holds the KIT's own rules against an edit made by accident. `load-rules` returns the merged set, so
every rendering reads it: each role's prompt, the spec review's, triage's data conventions,
`bb rules-prompt`, and `start`'s list of what still stands, which now names the overlay as the place
to fill. A mirror renders from the project's rules only when its workspace lists it
(`rules-for-mirror`): the KIT's own `AGENTS.md` and the selfcheck's sit inside the workspace by
default and keep rendering from the source, so `bb rules-check` in the clone is green whatever the
project fills, and the application's mirror drifts when the overlay changes until `bb rules-sync`
runs. The plan is now written before the application, so the application's mirror is rendered from
the overlay at generation. A `workspace.edn` without the key, from before, reads the source alone;
one that names an overlay it does not have is a fault, not the source.

Checked: `bb gates`; the merge (replace, add, an empty overlay, the refusals by name, a rule that is
not whole, a duplicate id); a workspace fixture where the application's mirror renders the fill and
the KIT's mirror inside it does not; `bb init`'s layout (the file's ids and text are the source's
placeholders; the plan before the application); a created workspace whose mirror follows an edit to
the overlay with nothing reaching the clone; `bb health` green. Rows 45 and 47 extended; row 47 stays
open for the template's rewrite and the check over `layers.edn` and the gate keys.

### A `tooling` route: the loop can now say the machine is at fault, not the run

Two gaps, one namespace apart. On a note, the routes were contract-shaped - `continue`,
`architect`, `human`, the noting role - so a Coder's note that the harness itself was broken could
only be `continue`d, rightly, and the defect cost the next role a third of its turns (row 12, open
since the first project). On a red gate, a merged property test that rounds onto its bound on
some seeds failed a later task's gate in the fifth build; no role in that run owned the file,
triage routed `human`, rightly, and the fix went through the loop as a run of its own (row 61).
Now `tooling` is a route. On a note it is always offered. On a red gate it is offered only when
the driver's proposal says so: `propose-routing` proposes `:tooling` when the test gate's every
failing namespace is none of the impl, test or dependent files' - `:foreign-namespaces` names
them, `:retry-role` is nil - so the model is not handed an exit from every hard call. The loop
stops `:tooling`, person-owned; on a red gate the reason names the namespaces; the next steps are
`check` again for a seed-dependent failure, else a fix outside the run and, on a red gate, the
task started again (the worktrees cannot see a fix made after they were cut), or on a note a
`continue` with the decision that the machine is fixed. The fallback on a foreign failure is the
same stop.

The finding under it: `failing-namespaces` read only clojure.test's `Testing <ns>` lines, and the
pinned template's runner prints none - it names the namespace on the failure, `FAIL in
app.util-test/clamp-above-hi-spec (util_test.clj:46)`. The fifth build's proposal therefore named
no file and no owner where the namespace was on the line. It reads both shapes now.

Checked: `bb gates`; the proposal (foreign alone, foreign beside the run's own, a lint gate), the
offer (a note always, a red gate only on the proposal, never a rejection; a verdict of `tooling`
where it is not offered falls back), and both stops in the loop, with a `continue` after the
note's. One paid replay of the fifth build's red-gate triage prompt with the route spliced in as
`render-prompt` now emits it, against the run's own orchestrator model: it routed `tooling` and
named the namespace and the mechanism. Rows 61 and 12 fixed.

### `bb reprice` fills the cost a generation record was too late to give

A dispatch whose generation record lagged past the fetch budget is written with no cost; the
footer says how many steps the total covers, the sum sits under the key's counter by that
step, and nothing could put a later-fetched number in the record (row 17, open since the first
project and confirmed on every build since). Now `bb reprice <run.edn>` fetches the records of
every dispatch step with no cost and fills it - marked `:cost-source :repriced`, the endpoint's
word fetched later, and only when every one of the step's ids answered, since a partial sum is
the understatement the footer exists to prevent - recomputes `:run/cost`, rewrites the record
and says that a document publishing the table now fails `bb report-check` until re-rendered.
The report's footer counts the repriced steps.

The finding under the fix: the completion ids the runner always had never reached the record.
`dispatch-step` kept model, provider, cost and tokens from the runner's meta and not the ids,
and the provenance docstring said they were recorded "so a run log can be backfilled" - true of
a map that lived for the length of one dispatch. The step now keeps `:step/generation-ids`, the
record's `:run/roles` keep each role's endpoint and the NAME of its key variable (never a key),
and the schema admits both, optional, so older records are unchanged. Which means nothing
written before this commit can be repriced: the fifth build's one unpriced Coder step included.
`bb reprice` on a scratch copy of that record says so in one line and touches nothing; the
planned live check on it is therefore not possible, and the first record this can fix is the
next build's. Row 17 fixed.

Checked: `bb gates`; the stub-server tests (both ids answer, one does not, no endpoint on the
roles and a `--profile` supplying it, an endpoint with no generation record, `-main` rewriting
a temp file and a second run leaving it alone); the live run on the copy, above.

### `start` says what the checkout holds that the worktrees will not see

The worktrees are cut from the last commit of the application, and nothing said so: an edit
sitting in the checkout - a hand fix a session meant to commit, a document half written - is
invisible to every role and every gate of the run, and the difference surfaces later, as a red
gate on a file nobody in the run touched or as a merge that carries the committed version over
it. `start` now runs `git status --porcelain` in the application's root before provisioning,
leaving out the run directory and the worktrees folder when they sit inside the repository (the
loop's own files, not the application's), and warns with the count and the lines; `:allow-dirty
true` in `loop.edn` silences the warning. Warns, not refuses: a person may mean it. Either way
a `:dirty-tree` event carries the count, the first twenty lines and whether it was allowed, so
the record says what the run was cut from. A checkout that is not a repository, or no `git`,
yields nothing and never fails the run. Row 64 written and fixed.

Checked: `bb gates`; the unit on a scratch repository (a modified file, an untracked one, an
ignored one, the run directory inside it), and the loop test with and without the key.

### A provider refusing for credit is a stop of its own, and its text stays out of the record

A 402 was a failed dispatch like any other: the stop said *retry it*, and the retry was refused
for the same reason with the same text - which, from OpenRouter, is a message carrying a dashboard
URL with the key's id, and the first project built on the KIT had it in the console, `state.edn`
and the run log before anyone read it (row 13, open since then). Now the adapter reads a 402 -
the status, or the code inside a 200 body - as `:harness/error :credit` and cuts its message to
the first line, before any URL, before anything keeps it; the agent adds the role's endpoint and
the NAME of its key variable; the dispatch event carries `:error/kind`; and `next-action` stops
`:credit`, person-owned, ahead of `:dispatch-failed`, saying who answered 402 to which dispatch
and which variable holds the key. Its next steps are `bb balance` and a `retry` after the top-up,
not a retry. The spec review's own 402 says the same in its exception. Every other status is what
it was. Tests: the adapter on a 402 with a URL and a second line, the agent against a stub server
answering 402, the event's kind, and the loop stopping `:credit` with the Tester never
dispatched and a second `run` the same stop. Row 13 fixed.

Checked: `bb gates`.

### A stage's end checks what no gate can: the interaction check, named in the method

Two builds of one project: the fourth accepted its search swap unverified, since its tests call
a handler as a function of a request map and can prove the fragment comes back with the right
header but not that the page swapped it in; the fifth verified it with a ten-line script driving
headless Chrome at both widths, outside the loop, for one `npm install`. The method's §04 exit
criteria now ask for that check for every behaviour the tests verify only as an HTTP contract -
not a gate and not a test in the suite, because it starts a server and a browser, which the
Testers' rule keeps out; recorded beside the screenshots with the driver named. The plan
template's `03-method-and-tooling.md` gains §7.7, stage-end checks: real-viewport screenshots
with a measurement file (the fourth build had shot 390 px through an iframe because headless
Chrome's window floor is wider), one script per contract-only behaviour, the driver the
adopter's with `puppeteer-core` as the worked example, and the process-group kill until the
template's serve stops cleanly (row 62). Row 63, written and closed.

### `bb balance` reads the account's credit beside the key's limit

`/key` gives a key's spending limit and what is left of it; `/credits` gives the account's
purchased credit and its usage, and they are not the same number: a key limited to $80 on an
account holding $30 stops at $30. The line showed the key's figure alone and called it the
balance (row 26, open since the second project). `openrouter-key-status` now reads both and the
line names each - the account's as *the number a cap is really against* - or says the account's
is unavailable when that endpoint does not answer. Stub-server tests for both cases. Row 26
fixed.

Checked: `bb gates`; one live read of both endpoints on the key in use, the figures never
printed.

### Row 14 to watch: an application is never an empty `src/` since `bb init`

The row (a greenfield project's empty `src/` dropped from the classpath for the first task's
REPLs) came from a bare-`deps.edn` project. Every application since is generated from the pinned
template with its `src/` populated; the case survives only on the bring-your-own-application
path, which no build has taken. Watched, not fixed; the first of a batch of small fixes made before stage 2.

### The fifth build: the same contracts on the fixed KIT, and what the register learned from it

A fifth build reran the fourth's tasks on the KIT with the day's fixes, the plan and
Blueprints reused as resolved and no model reads: every run merged (one a fix of Foundation's own test
through the loop), no rejection, for less money than the fourth's runs.
Where the fourth paid a person stop per task on the stub and read its red test gates by hand,
the fifth met neither: its red gates were read whole by triage and routed to the Tester
against the mechanical proposal, right each time; rework fell to a small share of the loop.
That is the measurement the fixes were made for; its figures are kept outside this repository.

Two things the register did not have. **Row 61:** a property test merged in Foundation's trivial
run built a value that rounded onto its bound and failed a later task's gate at random on the
seed; no role in that run owned the file, triage routed `human`, rightly, and the fix went
through the loop as a new run - the loop has no route for *a merged file, or the tooling, is at
fault*, on a gate as on a note (row 12). **Row 62:** the template's `bb serve`, stopped by killing
its JVM, leaves its CSS watcher behind - the template fork's to fix. Five rows extended with what
the build confirmed: 2 (triage on the seat's family: every route agreed with the reader, the
first live evidence), 10 (a Tester's own parse helper cost a round twice before it became a
sentence), 17 (an unpriced step, again), 36 (a second read of an amended spec drew many
findings of one shape the rule source already decides), 42 (a long conventions rule did not
price the reviews out). No code in this entry; the one-line fix is the next.

### A clean spec review says it carries on

`start` reviews a spec and, on zero findings, carries on - the stop exists so a list is read,
and an empty list has no reader (2026-09-21). The line printed beside a clean review still read
*fix the contract or not, then `start`*, the stop's instruction, above a loop that had already
started: on the fifth build, in most runs. It now says *0 findings, so the loop carries
on*. One conditional in `spec-review!`.

Checked: `bb gates`.

### The data-conventions placeholder asks whether the template's own suite is the pattern

*Nothing in a test starts a server* is the guidance the placeholder gives for Testers, and the
pinned template ships a system smoke test that boots Jetty and the database under `bb test`,
on purpose - which caught a closed component schema before a merge on the fourth project,
where five merged tasks had once failed to start on the third. The project worded its rule as
*nothing a Tester writes starts a server* and named the smoke test as the one exception; the
placeholder now asks every adopter to do the same: say whether the template's suite is the
pattern, name the exception, keep the rule for what a Tester writes. One sentence in the
placeholder, mirrors re-synced. Row 60, opened this morning, closes; the rerun's Foundation is
its first check, since that fills the placeholders again.

Checked: `bb rules-sync`, `bb gates`.

### The register, read against the fourth project

Seven rows the build touched get a sentence saying so: 3 and 4 (the Tester's slips and the
cap - one capped run whose cause was the harness's, row 50), 17 (the counter
lags, every run), 23 (`amend` before `start`, met on the first run), 25 (caching through
OpenRouter: most of the Coder's prompt tokens were cache reads), 38 (a closed component schema, this time caught by
the template's smoke test inside the gate, before a merge) and 39 (its neighbour, row 54). Two
rows open: 59, the plan template's missing slots - for what a plan is derived from, and for a
review's raw material - which is stage 2's beside rows 47-49; and 60, the template's own smoke
test booting a server inside the test gate against the Testers' rule, which earned its place
and needs one sentence in the `:data-conventions` placeholder. No code.

### Four one-sentence fixes from the fourth project, one commit each

Each was a row in that project's findings that cost minutes or a round and needed a sentence:

- **The profile's destination** (register row 55). `resources/profiles/` is held by
  `profile_test` to exactly the shipped examples; the shipped profile's header said to copy it to
  `resources/profile.edn`, but the README and the `loop.edn` that `bb init` writes never named
  that path, and the project's profile placed in the folder failed the clone's `bb test`. The
  README's adoption list and the `loop.edn` comment now name `resources/profile.edn` on the
  project's branch and say the folder is reserved.
- **What to do at the spec-review stop** (row 56). `amend` needs a started run, and the stop
  where an Architect most often amends is before `start`; two projects met the refusal there.
  Both messages - the refusal and the stop - now say to edit `spec.edn` in place, keep the
  previous file beside it for the diff, and `start` again, which reviews the edited spec.
  Recording that amendment as an event is still row 23's.
- **What the balance line calls Anthropic spend** (row 57). It summed every provider whose
  name contained *Anthropic*, the serving provider behind OpenRouter included, and printed
  "Anthropic spend" with a sum on a project where no role was on Anthropic's API. It now counts only
  `Anthropic API` steps and prints the clause only when a role is on that API directly;
  otherwise it says the key's usage is the whole spend. The report's line prints only when there
  is such spend. A test covers the OpenRouter-only case.
- **A generator is a value** (row 58). Testers called a generator as a
  function and failed their file at load, a round each; the rule said only *def, not defn*.
  `:generators-are-values` gains the clause with the example; the mirrors are re-synced.

Checked: `bb rules-sync`, `bb repair`, `bb gates` once over the four together (each changes a
different file), then one commit per row.

### Where the ask for presentation lives, and a packet example that shows what the roles receive

Two method gaps the fourth project paid for. Rule 11 of §06 kept every review and test off
styling, as designed, and said nothing about where the ASK for styling lives; a Coder's packet
holds targets and rules, neither says *style it*, and the project's first four merged views
arrived with no classes at all. One sentence in `:data-conventions` naming the theme tokens
fixed it for the next stage with no hand change, so rule 11 now says that: the ask is a
conventions sentence every role reads - the Reviewer included, so it knows the classes are
expected and not review material - or a packet instruction labelled as a working instruction,
never contract. And the packet example showed `:shapes [Concept Release]`, names; the project
copied the form, the spec review reported the schemas undefined, and every role would have
received bare symbols. `shapes/example-packet`, the method's §06 block and the Blueprint
template's packet now define one shape inline and name one, with the function and the var
interface forms beside them (previous entry), and a note under the block says the roles receive
exactly what the packet holds: inline what the task defines, name what arrives as context, and
a convention true of one Blueprint only - which has no place in the rule source - goes into
every target that uses it, in full. Register rows 53 and 54.

Checked: `bb gates` (the example packet still validates against the schema).

### A var is a deliverable, and a slice the stub cannot read is refused by name before provisioning

The fourth project's routing task delivered a `def` of route data. The packet had no form for
that: `:interfaces` was read only as `(name [args])`, a bare symbol threw *Don't know how to
create ISeq from Symbol* from the stub generator - after three worktrees and two nREPLs
existed, with an error pointing at the `let` in `-main` and nothing to say which key - and an
empty `:interfaces` was refused outright. The spec went out with a placeholder signature
`(routes [])` and the truth in its title. Now `(name)` is a var to define: the one-element form
`sigs/parse-sig` already read under `:deps-sigs` for a var a task may read, so the two halves
of a contract say "var" the same way. The stub emits `(declare name)` for it - unbound, so a
test that uses it errors, the guarantee a throwing body gives a function. `stub/check-slice`
(previous entry) now covers `:interfaces` too: empty, a bare symbol, a list with no argument
vector, each named with its reason, and `start` refuses on it before the spec review and
before a worktree exists. The Coder's deliverable and the Blueprint template say which form
is which. Register row 52.

Checked: `bb repair`, `bb gates`. New tests: a bare symbol and an argless list are refused by
name while the function and the var pass; `(routes)` renders a `declare`, the stub loads, the
var is unbound.

### A gate's output is what a terminal would show, and triage reads its tail

The fourth project's test gate is cloverage over eftest, which draws a progress bar by
rewriting one line with a carriage return, hundreds of times. Captured to a string, every
rewrite survived: a red test gate's output was kilobytes of `0/27 0% […] ETA`, and the clip triage
receives kept the first 6000 characters - the progress, never the failure report the runner
prints last. Three red test gates were routed to a person with the assertion unseen and read by
hand in the gate worktree; each was a one-line route to the Coder had the output been whole,
and each cost a triage call for the wrong answer. Two changes, one commit. `gates/tidy-output`
collapses carriage-return rewrites at capture, in `run-gate`, so every reader - triage, the
routing proposal's scan for file names, the record - sees what a terminal would have left;
and `triage/clipped` keeps the head AND the tail of a long output, 1,500 and 4,500 of the
6,000 characters, with the omission marked between them, because a runner's report is at the
end. Register row 51.

Checked: `bb repair`, `bb gates`. New tests: a synthetic progress-bar capture collapses to its
last line and the report, through `run-gates!` as well as the function; a 20 KB output whose
report is last reaches the clip's tail with its head intact.

### The stub reads a `(def …)` shape, escapes its docstring, and refuses what it cannot read before anything is provisioned

The fourth project built with the KIT inlined every shape into its packets - as the method asks,
so the roles receive the contract and not a name - and wrote them as `(def Name schema)` forms.
`stub/shape-def` read only `[Name schema]` pairs, so every one fell through as a shape merely
*named* and was `str/join`-ed into the stub's docstring; the first shape carrying an
`:error/message` string put an unescaped quote inside that docstring and the Tester's namespace
would not load. More than half the runs paid a Tester note, a triage call and a person stop for it,
and one Tester spent its whole iteration budget on the unloadable stub and wrote no file - the
project's one failed dispatch. Three changes, one commit: `shape-def` reads the def form as the
pair it is; the docstring is escaped whatever reaches it; and a new `stub/check-slice` returns
every `:shapes` entry the stub cannot read, as data, which `driver/start!` runs over `spec.edn`
before the spec review is paid for and before a worktree exists - the refusal names the entry
and the key, and there is nothing to tear down. `render` refuses the same entries, so a slice
that reaches it by another path fails the same way. Register row 50.

Checked: `bb repair`, `bb gates`. New tests load the rendered stub with a quoted string in both
shape forms and check the refusal names its entries. One `bb gates` run failed a single
assertion and the two after it passed unchanged (419 tests, 2201 assertions); the first run's
output was not kept, so it joins the register's timing-dependent test as noted, not explained.

---

## 2026-09-23

### Who fills the plan, said where it is read

The plan template's README told a reader to delete the instructional blockquotes once acted on,
without saying who that reader was. It is the Architect's session - the person, or the model they
work with in the session started at the workspace root - in Phase A, before any loop exists; no
dispatched role writes a plan document, and nothing in the KIT had said so. Two sentences: the
template's README says it, and the workspace `CLAUDE.md` `bb init` writes says the plan is filled
in that session, in the README's order, reviewed by `method.md` §02, and that a `<placeholder>`
left standing is not a decision - the failure the rule source's own placeholders had (row 15),
one level up. For that the README now travels with the documents into `<name>-plan/docs/`
(eight files, not seven): the order of writing belongs beside what is written in that order.

Checked: gates; a workspace generated from the working tree has `docs/README.md` and the
`CLAUDE.md` sentence. Not built, noted for stage 2: a plan review read cold the way `spec-review`
reads a spec, and a check that no angle bracket remains before Foundation.

### `skeletons/` is `plan-template/`

The KIT ships two templates and `bb init` generates from both: the application template - the
pinned fork - and the plan template, the seven documents that were `skeletons/`. The old name
said what the files looked like; the new one says what they are for, in the vocabulary the KIT
already uses (`template-pins.edn`, `harness.setup.template`). Not `plan/`: in a workspace the
KIT's clone sits beside `<name>-plan/`, which IS the plan, and a `plan/` inside the KIT would read
as the KIT's own. The person's choice, 2026-09-23. `init/plan-template-files`, the request key
`:plan-template`, the refusal's wording, the READMEs and `method.md`'s links follow; the folder's
own README no longer says to copy it by hand, which `bb init` had made stale.

Checked: `bb gates` from `harness/` and the root (the root once exited 1 in a chained command
and 0 on two plain re-runs; the log of the failing run was not kept, so it is noted, not
explained); `bb init zz --dry-run` found 7 plan documents (8 once the README travelled with them, next entry).

### The KIT publishes no run records of its own

`runs/` (`a1`–`a4`) and `RUNS.md` are gone from the KIT. They were the loop with real models
against what was then `sandbox/`, on 2026-09-16/17 - the KIT's own runs, not a project's - and
the KIT carried them to every adopter's clone, naming `harness-seed` and `sandbox/`, both gone,
while `report-check` guarded two files that would never change again. They are not lost: they are
kept outside this repository, with the runs before them and the first bake-offs. The person's
decision, 2026-09-23, on that basis.

What replaces them as the KIT's committed evidence is `harness/health/records/`: re-run on every
machine, rendered into the README, drift-gated. What stays is the mechanism a project needs -
`record` writes `run.edn`, `bb report` renders it, `bb report-check <markdown> <records-dir>`
holds a publishing document to its records - now with its two targets required rather than
defaulting to files the KIT no longer has; `bb gates` no longer runs it. `CLAUDE.md`'s rule about
committed records is rewritten to say this; the README's document table, the harness README and
`portability.md` say the same. Where a project's records live is stage 2's question.
This log's entries of those dates still cite the run ids; they are history and were left.

Checked: `bb gates` from `harness/` and the root; `bb report-check` with no arguments prints
usage and exits 2; the report tests run the check with explicit targets and pass unchanged.

---

## 2026-09-22

### `sandbox/` is `harness/health/selfcheck/`

The checked-in project the harness is run against had one job left - the first subject of
`bb health`, the harness's mechanics shown in seconds before an application is generated - and a
name that said a place to play. The person chose `selfcheck` (2026-09-22), and it moved inside the
harness under `health/`, which now holds everything the health check owns: the project and the
records (`health/records/`, from `resources/health/`). The project's root namespace is `selfcheck`;
its `deps-check` reaches the harness by `../../bb.edn`; its mirror is listed in the harness's own
`resources/rule-mirrors.edn`, and the root `rule-mirrors.edn` - which existed only to list a mirror
from outside - is gone. `--sandbox-only` is `--selfcheck-only`. The harness's tests that use
`sandbox.x` as example namespace names, and the docstrings that tell what the sandbox taught, are
left as they are: those are fixtures and history, not the folder.

One slip on the way: a rewrite script changed `:subject :sandbox` in the committed health
RECORD, which is exactly the hand edit a record must never get; restored from `HEAD`, and the record
below is a new run after the move, citing its commit.

Checked: the harness's gates and the root's, exit 0; the selfcheck's own `bb gates` green, red at
`:deps` after `bb break seam`; `bb doctor` from `harness/` shows no `[pin …]` - the project's
`.mise.toml` is below the harness, not above; `bb health --record` after the move (next entry's
record).

### The namespaces, in six groups

Thirty flat namespaces became six groups - `harness.setup` (workspace, init, app, template,
doctor, health), `harness.loop` (orchestrate, driver, triage, log, provision), `harness.contract`
(shapes, packet, stub, sigs, targets, spec-review), `harness.gates` (`run`, which was
`harness.gates` itself; repair; boundary), `harness.models` (runner, runner-check, agent, adapter,
tools, profile, provenance), `harness.money` (balance, report) - with `harness.rules` left where
it was, since every document names the rule source by that name. Files moved with `git mv`, every
reference rewritten longest-name-first (so `runner-check` went before `runner`), test namespaces
with them, path mentions in the documents too; history (`DEVLOG.md`, `RUNS.md`, `runs/`) left as
written. The grouping was the person's observation on 2026-09-22 and was deferred to here so the
sweep would be one commit a reader can skip.

Checked: gate 0 over the moved files, `bb gates` from `harness/` and from the root, the sandbox's
own gates, `bb example`, `bb health --sandbox-only`; a grep for any flat name left outside
history found one docstring, fixed.

### Step E: five rows closed against the health check, and the documents say what the KIT now is

`harness-seed/` is `harness/` (the path replaced everywhere but history). The documents
that still described a seed an adopter copied now describe the KIT an adopter clones: the root
README opens with *clone it and build beside it* and the three installs; `method.md` §03's
Foundation is `bb doctor` → `bb health` → `bb init`, its steps 2–5 say what the template and the
harness already do, and its readiness checklist is four items, one of them the health check;
§04's example exit criteria include *the system boots*, with the third project's story; §12's
step 1 exits on the two verdicts and the health check, and its checklist and closing paragraph
match; `skeletons/03` §7.1–7.2 are the workspace commands and the record to keep; the harness
README's opening, *Adapting it* (what the harness needs of a project, where the mirror is, where
records stay) and *Versions* (floors, one pin, the known-good set, the fork's pins as the
example) are rewritten, its line counts re-counted; `CLAUDE.md`'s bb-only rule says what is
JVM-free and what is not; `PROVENANCE.md` says what "the seed" below it meant; `NOTICE` carries
the fork's upstream MIT notice; the agent file's comment says who copies it where. Plan v1's
sentence that `neil new` would be pointed at the fork is superseded: nothing points `neil`
anywhere, and `neil` left the toolchain in step C.

Register rows 20, 38, 41, 43 and 44 are FIXED against the health check - each row says what was
observed: a generated application's gates green untouched with its own lint and format config
(20); `bb serve` held to 200 (38); the Foundation wording rewritten (41); `babashka = 1.13.223`
seen in a generated `.mise.toml` (43); no licence file in a generated application (44). Row 32
was closed in B.9.

Not done here, on purpose: the namespace regrouping (six groups over 30 flat namespaces) - a
second mechanical sweep, kept apart from this one so each can be read; and the retirement of
`runs/` and `RUNS.md`, which are the evidence behind sentences this log and the README still
make (run `a4`), and whose home after stage 2 is `<name>-plan/` - the person's call.

Checked: `bb gates` from `harness/`, from the root, and the sandbox's own; `bb health
--sandbox-only` after the rename; the NOTICE licence text diffed against the fork's `LICENSE`
(identical but for trailing blanks). NOT checked: every rewritten sentence against a fresh
reader - the first build in a workspace is that reading.

### Step D, second half: the record, the README block, and the first record

`bb health --record`, on a healthy full run, writes `resources/health/<platform>.edn`: when, the OS
and architecture and nothing else of the host, the KIT commit (and whether the tree was dirty),
the template pin, the required tools' versions the doctor saw, and every check's outcome and time -
never its detail, which carries scratch paths. The same run rewrites `resources/known-good.edn`,
so what the doctor shows as known-good is what a health run saw and not what someone typed (the
hand-written set of step C is gone). `bb health-sync` renders every record into the root README
between `<!-- health:begin -->` markers - one row per platform, and the sentence that a platform
not in the table has none - and `--check`, in `bb gates`, fails on drift, the `rules-sync` pattern;
`rules/splice` now takes its markers. The date is the claim: nothing says it still holds today.

The first commit of this half was made on a red lint gate - three unresolved namespaces - because
the command that committed did not read the gate's exit; amended. Then `--record` wrote the
known-good set beside `bb.edn` and not into `resources/`: the resource NAME was spit to, and the
test had masked it with an absolute path. Fixed, and the record below was made after that fix.

Checked, by running it: `bb health --record` on a clean tree - seven checks, 57s, the
record and the known-good set written; `bb health-sync` renders the block, `--check` in sync,
`bb doctor` shows the set with the record's date and platform; the README carries the first row.
Gates exit 0, 417 tests. NOT checked: a second platform (the table's sentence about them is
rendered, not observed); the age of a record shown anywhere (the date is shown; age is the
reader's arithmetic).

### Step D, first half: `bb health` - one piece of code, two subjects

The health check the plan asked for, and the shape decided before B.9: `harness.health` takes a
SUBJECT - a project directory, its gate commands, its nREPL command, its root namespace - and runs
the same checks against two. The sandbox, COPIED to a scratch workspace and made a repository (the
checked-in one is never written to, and the loop's branches land where the check's cleanup deletes
them, not in the KIT's clone; the KIT's `.gitignore` goes with the copy, or `.nrepl-port` is a
file a role wrote). And an application `bb init` generates from the pinned template into scratch,
by the same code an adopter's command runs. Per subject, each recorded as data: every gate green
untouched; every gate FAILED by a breaker at its own key - the breakers are harness data needing
only the root namespace (mis-indented form, unused binding, false assertion, undeclared namespace),
one form per line because the generated application's `.cljfmt.edn` lays out an `ns` form its
own way and the first multi-line test breaker failed at `:fmt`; one trivial task - §03's `clamp`,
in a new namespace declared through `:architecture`, the case that mechanism exists for - through
the whole loop with a scripted runner in every model's seat and everything else real: three
worktrees, two nREPLs by the subject's command, the REPL probe, gate 0, the gates, the review, to
`:awaiting-merge`, then torn down; and, for the application, `bb serve` until `GET /` is 200, the
process tree stopped, the port free again. A busy port is reported, not tried around: the
template's port is configuration, not a flag (a fork-side `PORT` override would change that).
Never part of `bb gates`; `--sandbox-only` skips generation; `--keep` leaves the scratch.

Checked, by running it: `bb health` here - seven checks, 57s, every one ok, both scratch folders
gone afterwards; `--sandbox-only` 16s; from the KIT's root the same. Before the fix, the
application's test breaker failed at `:fmt` and the sandbox's loop stopped `:assemble-refused` -
both recorded above, both by running it. Unit tests cover the breakers' mechanics against shell
gates that fail exactly when a breaker is present, the trivial task, and the scratch sandbox. NOT
yet: the record and the README block (the second half of step D); a machine with a slow first
fetch (the template was cached here); `--keep`'s folder read by a person.

### Step C: the doctor guides, holds one version, and judges twice

Three projects failed `bb doctor` on a version RULE, never on a missing tool: a calendar version
read as a major, a patch level read as a break, a host's pin read as this project's. The rules are
now: every pin - the nearest `.mise.toml`, walking up, or an entry's `:min` - is a FLOOR, compared
over as many segments as the pin names (`temurin-21` is a major; `21.0.12.1` neither exceeds nor
falls short of it), and newer is said and fine. The one exception is the JDK, `:major 21` on its
own entry, the KIT's constraint rather than any file's: another major is *wrong version* with or
without a pin, and a pin on another major with 21 installed is *change the pin, not the JDK*. The
three clojure-mcp-light tools are probed by RUNNING them - `--help`, empty stdin, five seconds -
because in `bbin ls` and on PATH was reported ok for a tool that could not run; each was checked
to answer `--help` in under a second and write nothing before the probe was written.

Two verdicts - *the KIT's gates can run here* (the Babashka tier) and *a loop can run here* (plus
JDK 21, the Clojure CLI, `clj-nrepl-eval`) - and `--tier` says which decides the exit code:
`bb gates` runs `bb doctor --tier gates`, so the KIT's own gates stay JVM-free; `bb init` refuses
without the loop verdict. Every unusable tool prints its fix: a documented command where there is
one (the three `bbin install` lines are from clojure-mcp-light's README, read 2026-09-22), else the
official page. The KIT guides and installs nothing. Beside what is installed the table shows the
KIT's dated KNOWN-GOOD SET, `resources/known-good.edn` - written by hand today from this machine,
which ran the gates, `bb init` and the generated application's gates on these versions; step D's
health check is to write it from its record - and a newer version is *newer than tested*, never a
fault. `clj-depend` and `neil` left the toolchain: the KIT ships its own boundary gate, and `bb init`
replaces scaffolding by hand. The root README opens with the three installs (JDK 21, the Clojure
CLI, Babashka ≥ 1.12.212), one route each, then the doctor.

Checked, by running it: `bb doctor` here, both verdicts yes, exit 0; `--tier gates` exit 0; against
the template's own `.mise.toml` (comment lines, prefix pin, `[alias]`), every pin read, no notes;
against `java = temurin-21, clj-kondo = 2025.06.05, babashka = 1.12.206` (row 20's case) all ok
with *newer than the pin* notes, exit 0; against `java = temurin-25, clj-kondo = 2027.01.01`: the
JDK ok with *change the pin*, clj-kondo TOO OLD with its fix, both verdicts NO, exit 1; with a
failing shim and a hanging shim ahead on PATH, both DOES NOT RUN, the hang cut at five seconds.
Gates exit 0, 408 tests. NOT checked: a machine with a tool actually missing (every fix line is a
documented command or page, none was run), and the README's install routes on a clean machine.

### Row 32: the boundary gate ships with the KIT, and a generated application has layers

Method §09's gate 4 existed once, as `sandbox/dev/deps_check.clj` - outside what an adopter
took - and the pin's `loop.edn` defaults listed three gates because no generated project had a
`bb deps-check`. `harness.boundary` is that check, ported and generalised: a namespace's OWN tree
is whatever shares its first segment, so no ruleset names a root; and a third violation kind,
`:orphan` - an entry with no file - closes the limitation the sandbox README had recorded. That
kind caught the sandbox's own `sandbox.red-seam` entry, declared for a file that exists only while
`bb break seam` is in place; the ruleset's one keyword key, `:boundary/fixtures`, now says so.

THE GATE RUNS AGAINST THE PROJECT, the way `cljfmt` does: `bb --config <kit>/harness-seed/bb.edn
boundary` from the project's directory. A generated application's `bb.edn` carries nothing of the
KIT (the fork stays app-level); the plan's `loop.edn` gains `[:deps "bb --config
{{kit}}/harness-seed/bb.edn boundary"]` with `{{kit}}` made the KIT's ABSOLUTE path at `bb init`,
because the gate runs in a worktree under `work/` and no relative path to the KIT holds from there.
The application's `layers.edn` is data beside the pin - the template's require graph at the pinned
commit, as tails under the root namespace - written into the second commit with `AGENTS.md` and
`CLAUDE.md`; a template shipping its own is kept. `sandbox/` keeps `layers.edn` and `red/` as the
proof the gate discriminates; its `bb deps-check` delegates to the harness and `dev/deps_check.clj`
is gone. `default-gate-seq` keeps `bb deps-check` - the sandbox's key contract and every recorded
run - and its docstring says what the pin writes instead.

Decided before this step, and recorded for STEP E rather than done here: the health check is one
piece of code with two subjects (`sandbox/` for the harness's mechanics, with red breakers becoming
harness data applied to any project; the generated application for the certified pair);
`harness-seed/` is a misnomer since the design inverted and becomes `harness/`; the 27 flat
namespaces group six ways (workspace & setup, the loop, the contract, gates, models, rules & money)
- one sweep with the document rewrite, not two. B.9 stays flat.

Checked, by running it: in `sandbox/`, `bb deps-check` green (9 namespaces), red at `:deps` after
`bb break seam` (forbidden) and after `bb break deps` (undeclared), `bb gates` green after
`bb restore`. The adopter's way in a scratch folder cloning this working tree: `bb init demo` exit 0,
`layers.edn` in commit two; the gate against `demo-app` green - 6 namespaces, so the pin's declared
graph matches the template's - and red on one added `demo.views` requiring `demo.db`; the exact
string written to `demo-plan/loop.edn` run with `:dir` the application, from the application and
from `/tmp`, exits 0. Gates exit 0, 404 tests. NOT checked: the gate inside a full loop run against
a generated application (no run has yet been made against one); red breakers as harness data (D).

---

## 2026-09-21

### Three projects compared; the kit gets its own template and a health check; the register takes the findings

A new line of work, starting from the harness the third project ran on.

The three projects built on the kit were compared, from their records. What the comparison says
about this repository: rework fell from project to project while the money lost to runs that
never merged rose; two rows this register
had marked *fixed* recurred (20, and 19 one branch over); one defect was made by a port (the
tests of row 27); and the question *can ordinary input reach this?* cost a rejection in every project.

**Decided by the person, 2026-09-20/21, and the reason for the branch:**

- The kit is an opinionated framework on Clojure Stack Lite, and the harness is meant to work with
  the template the kit PROVIDES. Copying `harness-seed/` into an arbitrary project stops being the
  supported path. The template will be a fork the kit controls (MIT, as upstream is), with
  `neil new` / `clojure -Tnew create` pointed at the fork — because six of the third project's
  findings were the upstream template's own and cannot be fixed from outside it.
- The first step on a machine is a health check: prerequisites checked by running them; for anything
  missing, a helpful message and the command that installs it — **the kit guides and does not
  install**; then the check again, certifying the kit ready as of that run.
- A health-check project ships with the harness, separate from `sandbox/`: an instance of the kit's
  template, stub models, ending in a server a person opens at `http://localhost:8000`. It is first
  of all this repository's own regression test — row 20 recurred three times because it was checked
  in an empty repository — and an adopter re-runs it after any JDK, Clojure or tool upgrade, or
  points it at the project they are building.
- Versions: JDK 21 is the one constraint with a reason; everything else is a minimum, plus one dated
  known-good set that is the KIT's own, the template's pins compared against it at each refresh. The
  README carries that report, rendered from a committed record.

**In this commit:** register rows 4, 10, 17, 19, 20, 21 and 23 extended; rows 27–44 added. The XTDB
claim is scoped to what was verified: its documentation states a MINIMUM of 21 (read 2026-09-20), and
*fails at class-load on newer JDKs* was seen on 2.0.0 and 2.1.0 and has not been re-checked — three
sites said "needs 21 exactly".

**Tried and NOT established:** that a bbin-installed tool can sit on the PATH and be unable to run.
One machine's write hook failed repeatedly over five days with *Clojure tools not yet in expected
location*, and stopped the day that folder appeared. Two attempts to reproduce it —
`DEPS_CLJ_TOOLS_DIR` pointed at an empty folder, then the same with a cold `CLJ_CACHE` — both ran
`clj-paren-repair --help` to exit 0 without fetching anything. So the cause is still inferred, the
advice *run `bb clojure -Sdescribe` to fetch the jar* is unverified (with an empty install dir it
printed its map and fetched nothing), and no register row was written. The doctor probing tools by
RUNNING them stands on its own merits; this incident is not yet evidence for it.

**Order of work**: the fixes the
template cannot affect, rows 27 and 28 first, one finding a commit so the upstream backport can take
them alone; then the fork, the health-check project, the doctor and the report; then rows 20, 32, 38
and 41 and the rewrite of the documents that still say *copy the seed*; then the design-sized rows
(23's remainder, 31, 10).

### Row 27: the seed's tests no longer need its placeholders standing

The README tells an adopter to fill the three rule-source placeholders; two tests added with the
placeholder check itself asserted the SHIPPED file still had three, one of them by reading
the live file through `start`. Filled, the seed's test gate went red: 3 failures, 1 error. Both now test
the mechanism on a fixture - `orchestrate_test` by redefining `rules/load-rules`, so `start` is shown
a source with three fixture placeholders and then a filled one. Reproduced before and after in a
scratch copy with every placeholder replaced (`bb rules-sync && bb test`): old tests exit 1, new exit 0.
This had to precede any health check, whose project fills its placeholders by construction.

### Row 33: a worktree's REPL ignores the personal `deps.edn`; `CLAUDE.md` names the driver that exists

The Clojure CLI merges `~/.clojure/deps.edn` into a project's, so a personal alias with a project
alias's name contributes its `:main-opts`. The default `:nrepl/cmd` gains `-Srepro` at both sites
that state it. `sandbox/` defines its own `:nrepl` alias and resolves under it. `CLAUDE.md` said the
driver was `harness-seed/dev/run_loop.clj`, a file promoted to `src/harness/driver.clj` long ago.
Row 28's suspect test was checked too: it already prints its fallback reason, so nothing to change.

### Row 19, the other branch: an amendment makes a GATE result history too

The fix of 2026-09-18 made an amendment set a rejected review aside. A red gate answered by an
amendment took the same wrong path one `cond` clause lower: `gated-current?` was still true - no
role had written since - so `next-action` sent the recorded red result to triage, against a spec
that no longer said what the gate had judged. `amended-since-gates?` now sends it to `:check` first.
`gated-current?` keeps its meaning (the gate worktree holds the latest files), because `record` and
`merge` depend on it and an amendment changes no file. Replayed on the recorded events of the run
that met it: `:triage` before, `:check` after. The lesson is about the first fix, not this one: it
was written to the example in front of it, and the register said *fixed*.

### Row 29: what the spec-review limit counts, when a review stops the loop, what makes a spec new

Three decisions, the person's. The limit counts reviews that FOUND something: a spec whose first
reading was clean had reached it on its first amendment. A review with nothing on its list no longer
stops the loop - the stop exists so the list is read - though an answer with no findings block at all
still does, since nothing was recorded. And a review is of the spec WITHOUT `:files/context`: removing
two paths from that list had made *a different spec* and bought a review. That last one has a price,
written into the register: a context file can change what a target means. `method.md` section 07 says
all three.

### Row 34: the Reviewer and triage are given the question only the Architect had

Two rules met on one finding and pointed opposite ways. *Types are followed as the language defines
them* put `(keyword "")` in the domain; *can ordinary input reach it* put it out, because the
project's content came only from a file format that cannot write it. The Reviewer held the first rule
and rejected; triage routed the Coder; a round was paid; the Architect, who alone held the second, was
never asked. The person approved one sentence for where they meet - **the type decides what a value
is; where the project says its input comes from decides whether it can occur** - and it went to the
Reviewer's deliverable, triage's rejection prompt, the types rule, and `method.md`. It is narrow by
design: this rule was born from an Architect twice calling a reachable input unreachable, so only a
source the rule source names counts, and absent one every value the type admits can arrive.

Triage had never been shown a rule. It is now shown one, on a rejection only: the project's
`:data-conventions`, once filled. Nothing here has been run against a model.

### Row 4, a third time: what a Tester does with a target it cannot test

Capped Tester dispatches had three causes in three projects, and the deliverable had answered two:
tests re-run against a stub that throws, and a file bracket-checked as a string. The third was a target
whose fixture did not exist - turn after turn searching the classpath, no file, no note, a failed
dispatch. The deliverable now gives the general form as well as the case: do not search; write the
rest; mark the target *not a test* with what is missing; say so with `note`. The exemption keeps the
`targets` gate honest and the note pauses the loop, so the gap reaches the Architect for the price of
a triage call instead of a run.

### Row 30: a context file need not be Clojure

`:files/context` is whatever a role should be handed, and the signature check read all of it as
Clojure: a Markdown note on a library - which `method.md` recommends - and a fixture that is
unreadable on purpose each refused a correct spec, under a message that named neither the file nor
the reason. Only source files are analysed now, in `violations` and in the `calls` gate, and the
precondition failure prints every violation.

### Rows 35, 36, 37, 39, 40, 42: six things the method and the skeletons now say

Words, no code. A value handed into a function crosses a seam, and what its caller guarantees is
written like any other guarantee (rule 4; the architecture skeleton's seams note). A spec review's
count is one sample, and a zero is not a pass (section 07). The project's own gates run before any
commit a worktree is cut from (rule 7). Naming a shape does not grant it (the blueprint skeleton). A
project decides who owns presentation after a merge (a new rule 11 - at the end, because rules 4, 6,
7 and 10 are cited by number elsewhere; the first draft of this edit renumbered them and was undone).
And the seed README's line on what a spec review costs was rewritten (since 2026-09-28 it quotes
no price: a project's own records give the figure).

That ends the fixes the kit's own template cannot affect, except the design-sized ones. Rows 20, 32,
38 and 41 wait for the template; rows 10, 23 and 31 want design.

### The design turned over: the kit is what is adopted, and it brings a pinned template

Superseding this day's first entry where they differ. The person's decisions, reached in discussion:

- **The kit is the thing an adopter takes**, and it brings a specific, versioned, tested
  `ontopro/clojure-stack-lite` with it - a PINNED dependency. The fork (public, MIT, default branch
  `kit`, first tag `kit-v1`; its `master` stays an exact mirror of upstream) carries only what the
  application needs on its own: nothing kit-specific, no copy of the harness. The earlier sentence
  *`neil new` / `clojure -Tnew create` pointed at the fork* is withdrawn; so is any idea of the
  template scaffolding the harness. There is one harness, here, and nothing to keep in sync.
- **The adopter's sequence:** clone the kit, install the README's prerequisites, `bb doctor` until
  it certifies the kit ready, `bb init <name>`, `bb serve`.
- **A workspace is three sibling git repositories inside a plain folder** - the kit, the
  application, the plan - as the private harness this one was extracted from already does. The
  person's three reasons: separation of concerns; an adopter may use another framework or none
  (the harness never looks inside one - supported is not the same as possible, and only the pinned
  pair is certified); and three independent lifecycles. So `bb init` is two separable parts -
  create the workspace, generate the application - and the template is named in data.
- **Moving a project's state into its plan folder is a later stage.** Until then it stays beside
  the harness on the adopter's own branch of the clone. One part cannot wait: the harness has to
  be told where the project is.

### A root `bb.edn`: the kit's front door

`bb doctor` and `bb gates` run from the root of the clone. Each delegates to `harness-seed/`;
nothing is implemented twice. ONLY tasks without a path argument are offered there, because a
relative path typed at the root would be read from inside `harness-seed/` and mean another file -
`run-loop`, `balance`, `report` and the rest stay where they were. Checked: `bb doctor` prints
the same report from both places, `bb doctor --edn` passes its flag through, and `bb gates` exits
0 from the root and, unchanged, from `harness-seed/`.

### The template pin, as data

`harness-seed/resources/template-pins.edn` names the template the kit brings:
`io.github.ontopro/clojure-stack-lite` at the full commit behind the fork's tag `kit-v1`, with the
deps-new version that generates from it. `harness.template` reads it and builds - purely, as an
argv - the command `bb init` will run: `clojure -Srepro -Sdeps {…} -X org.corfield.new/create`,
with deps-new and the template as plain dependencies, so an adopter installs no Clojure tool
first. A second entry, or a different default, is a data change; an unknown name is an error that
lists the names, never a fallback. `KIT_TEMPLATE_LOCAL` will point generation at a local clone for
someone developing the template (the function takes `:local-root`; nothing reads the variable
yet - that is `bb init`'s).

Checked, not assumed: the argv the function returns for the shipped pin was RUN, from a directory
with no clone in it, and produced a project carrying the fork's two test namespaces and its dated
pin file. That answers the plan's first open question - deps-new does generate non-interactively
from a pinned git sha. What it needs cached to run OFFLINE is still not known: each run printed
*Resolving … as a git dependency*.

### A name: the KIT

The template fork's working branch is `kit` and its tags are `kit-v1`, `kit-v2` …, and a project's
workspace keeps its clone in `kit/`. So the product gets a written name of its own: **the KIT**,
short for *the Clojure Agent Kit* - the person's decision. The root `README.md` defines it and
lists the three lowercase uses, the fork's README says the same from its side, and `CLAUDE.md`
makes it vocabulary. The one confusion worth heading off is the tag: `kit-v1` is version 1 of THE
TEMPLATE as the KIT pins it, not version 1 of the KIT. Existing prose is not swept - this log is
history, the register is summarised before `main`, and the adopter-facing documents are due a
rewrite anyway.

### The harness is told where the project is

Until now the project was wherever the run directory was: `git rev-parse --show-toplevel` there,
applied AFTER `loop.edn`, so nothing could say otherwise. That was true of every run recorded so
far, because the harness had always been copied into the project it built. In a workspace - the
KIT, the application and the plan as sibling repositories in a plain folder - a run directory
under `work/` is in no repository, and one inside the KIT's clone would have had worktrees of
the KIT cut and called the project.

`driver/project-root` decides, most explicit first: `:repo/root` in `loop.edn`, relative to the run
directory; the application named by the nearest `workspace.edn` at or above the run directory
(`driver/find-workspace`); and only then the run directory's own repository, which keeps every
existing run and test as it was. Whatever is chosen must BE a repository, and an error says which
of the three it came from - a typo in `workspace.edn` should not surface as a failed
`git worktree add`. In a workspace the worktrees also move from the system's temp folder to
`<work>/worktrees/<run-id>`.

This is the one part of moving a project's state out of the KIT's clone that could not wait; the
rest - rule source, profile, `loop.edn`, records into the plan folder - is a later stage.

Checked: a table of the three sources and the three errors; and one whole loop, to the merge
stop, with the run directory in no repository and the application beside it - which passes, and
errors on the driver as it was before this change (`{:pass 3}` against `{:error 1}`, run both ways).

### `bb init`, the first of two parts: the workspace, and nothing about any template

`bb init <name> [dir] [--dry-run]`, at the KIT's root or in `harness-seed/`, creates the workspace:
`workspace.edn`, a `README.md`, a `CLAUDE.md` that imports the KIT's, the off-loop agent file under
`.claude/agents/`, `<name>-plan/` - its own repository, one commit: `docs/` copied from `skeletons/`
with the tree kept so the skeletons' relative links still resolve, a README, `loop.edn` defaults -
and `work/runs/`. **It does not generate the application yet**, and says so when it finishes: the
workspace NAMES `<name>-app` and the harness reports, as before, that the folder is not a
repository. That is deliberate, not a stub left in: `harness.init` takes the application as
`:app-fn`, a function of one folder, and what only a generator knows (`:rule-mirrors`,
`:loop/defaults`) as arguments. Bringing your own application is this part plus a repository put
there. The second part supplies the function for the pinned template.

`layout` is pure - every path and every file's content as data - `refusals` is pure over `survey`'s
facts, and `create!` writes what `layout` said. So the command prints every path before it writes
one, and refuses, giving every reason at once, when: the name cannot be a folder and a namespace; it
is not run from a clone of the KIT; the target is inside the KIT's clone; any planned path exists
(a folder is named once, not once per file; a bare `.claude/` is no conflict); the DEFAULT target -
the folder the clone is in - holds other repositories (an explicit `dir` is consent and is not
second-guessed); or `bb doctor` has a required tool unusable. The KIT is recorded relatively when it
is inside the workspace, so the folder can be moved whole, and absolutely when it is kept elsewhere.

It writes nothing into the KIT's clone, so the application's rule mirror cannot go in
`../rule-mirrors.edn`. It goes in `workspace.edn` as `:workspace/rule-mirrors`, and
`rules/mirror-paths` - which replaces the function `harness-seed/bb.edn` kept in its `:init` - reads
the seed's list, the repository's, and the workspace's. For that the workspace lookup moved out of
the driver into `harness.workspace`: the driver requires `harness.rules`, so the rules could not
have required the driver. It now also makes `:workspace/kit` and the mirrors absolute.

Checked, by running it: `bb init xyx --dry-run` at this clone's root refuses, because
a `CLAUDE.md` is already in the folder the clone sits in; `bb init demo <scratch dir>` from
`harness-seed/` exits 0 and leaves 20 paths, a plan repository with one commit and a clean tree, and
an absolute `:workspace/kit`; a second run exits 1 naming what exists; an unwritable target gives a
sentence and not a stack trace; `../ws-rel-check --dry-run` means `KIT/ws-rel-check` at the root and
`clojure-agent-kit/ws-rel-check` in `harness-seed/`, as the docstring says - and the second is then
refused as inside the clone. `harness.init-test` does the same in a temp folder, and checks
`git status` of the KIT is unchanged by a creation. NOT checked: a machine whose git has no
identity (the plan's first commit would fail, with git's message); a session actually started in a
created workspace reading the imported `CLAUDE.md`.

### KIT development has two parts; the workspace `CLAUDE.md` orients and imports nothing

Decided by the person, 2026-09-21, after `bb init` was whole. **Two parts, not options.** The
DEVELOPER session starts in the development folder - `KIT/`, a plain folder holding the
development clone `clojure-agent-kit/` - and is aware of every experiment beside it: it reads their
records and findings and ports what it learns. An EXPERIMENT is a real adopter workspace made the
adopter's way, `KIT/xyx/`, replacing the hand-built experiment folders of the three projects so far:
`mkdir KIT/xyx && cd KIT/xyx && git clone ../clojure-agent-kit && cd clojure-agent-kit && bb doctor
&& bb init xyx`, then an ADOPTER session started in `KIT/xyx/` in another window. It reads what an
adopter reads and nothing else; its findings stay in the experiment; a fix lands in the development
clone and `git pull` in the experiment's clone is the upgrade path, tested each time.

Two consequences. **The development folder has no `CLAUDE.md`.** Claude Code loads a parent
folder's `CLAUDE.md` at start, so one in `KIT/` would reach every experiment session below it with
the KIT's own development rules; `clojure-agent-kit/CLAUDE.md` loads on the first file read in the
repository, which comes before any edit. And **the workspace `CLAUDE.md` `bb init` writes imports
nothing.** It had imported the KIT's `CLAUDE.md` - the working rules for an agent CHANGING the KIT:
run its gates, write its logs - which would have handed those to a project's Architect. It is now
orientation only: whose session this is, the four folders in a line each, where to read (the method,
the loop's commands, the plan, the application's `AGENTS.md`, the off-loop role), and one sentence
that the KIT's own `CLAUDE.md` is the KIT's rules and not this project's. It restates no rule: the
rule source stays the only place a rule is written.

One guard, from the discussion: `bb init xyx` run from the DEVELOPMENT clone would have made `KIT/`
itself a workspace, and nothing refused it - one repository, nothing planned exists. The default
target is now refused when the folder already holds workspaces (a subfolder with `workspace.edn`),
saying to make the workspace folder and clone the KIT into it. An explicit `dir` is consent, as
before. Also closed here: the root `README.md` name table and `CLAUDE.md`'s vocabulary rule said a
workspace's KIT folder is `kit/`; it is `clojure-agent-kit/`, the name `git clone` gives it.

Checked, by running it: in `KIT/` with a throwaway `zz/workspace.edn` beside the clone, `bb init xyx
--dry-run` refuses naming `zz` and the development folder; without it, after `KIT/CLAUDE.md` was
deleted, the dry run passes. The adopter's four lines in a scratch folder, cloning this working tree:
exit 0; the generated `CLAUDE.md` has no `@` line, and each of the six paths it names exists
(`test -e`, all six). Gates exit 0, 401 tests. NOT checked: a Claude Code session actually started in
a generated workspace, and what it loads.

### `bb init`, the second part: the application, from the pin

`harness.app/app-fn` is the function the first part was waiting for. It runs the argv
`harness.template` builds from the pin, beside the application's folder; commits the result
UNTOUCHED, the message naming the template, its tag and its commit; then writes `AGENTS.md` - a
hand-written frame whose marker block `rules/sync!` fills from the KIT's rule source - and a
`CLAUDE.md` stub importing it, as a second commit of exactly those two files. `git diff` against
the first commit is, for ever, everything done to the application after generation. A template
that ships its own `AGENTS.md` or `CLAUDE.md` keeps it and the command stops; a generation that
fails carries the last fifteen lines of its output; one that exits 0 and leaves nothing is not a
success. `KIT_TEMPLATE_LOCAL` replaces the pinned commit with a local clone, and the first commit's
message then says *NOT the pinned commit*.

What a run must be told about this template's projects is DATA beside the pin, `:loop/defaults` in
`template-pins.edn`: the three gates a generated `bb.edn` has, which replaces the harness's default
sequence and with it `bb deps-check`, a task no generated project has until the boundary gate ships;
and `clojure -Srepro -M:test:nrepl`. `bb init` writes them into `<name>-plan/loop.edn`, and the
application's mirror into `workspace.edn`. `harness.init` still requires no template: its `-main`
is the one place the two parts meet. The command's ending is now `cd <name>-app && bb serve`.

Checked, by running it: `bb init demo <scratch dir>` from the KIT's root, generating from the
pinned GitHub commit - exit 0, under four seconds with the template already fetched (a first fetch
was not timed); the application has the two commits and a clean tree; in it `bb fmt-check`,
`bb lint` and `bb test` each exit 0, untouched. And the DEFAULT layout, in a scratch folder holding
only a clone of this repository with the working files copied in: `bb init demo` exits 0, records
`:workspace/kit "clojure-agent-kit"`, leaves the clone's `git status` as it was, and from that
clone `bb rules-check` lists `demo-app/AGENTS.md` in sync - then exits 1 naming it after one word of
its block is changed. `harness.app-test` replaces the JVM with a function that writes files, and
runs git for real. NOT checked: `bb serve` on THIS generated application (the fork's own check of
it is the evidence so far; step D makes it part of the health check); a run of the loop against a
generated application with these `loop.edn` defaults; a machine with nothing fetched and no network.

One finding, register row 45: a KIT kept OUTSIDE its workspace - which an explicit `dir` allows -
walks up from `harness-seed/` and never finds `workspace.edn`, so its rule tasks do not see the
application's mirror. `bb rules-sync <path>` works there and was run; the check does not.

---

## 2026-09-18

### The `:openai` shape reads cached tokens

A paid probe of serving the `claude` seat's Anthropic roles through OpenRouter found that a profile edit alone turns prompt caching off, that the record could not have
said so either way, and that Vertex's cache misses intermittently where Anthropic's does not. One
thing from it is ported here, because it is small, tested, and everything downstream of a record
depends on it: `parse :openai` now reads the cached-token fields. The rest of what the probe found is
in `NOTES.md` row 25, not done.

It goes in before the next project copies the seed, so that project's baseline has it rather than
carrying it as a divergence from its first commit.

### Port from the second project, tier 3: a place for what the project's data is, and three things about reading a contract

The most productive thing written during the second project was not code and was not in the seed:
a rule saying what a hiccup tree is there and how it is walked. Many of that project's first
spec-review findings were that one question, asked a different way by each spec. The seed had two
placeholders — layers, and the validation library — and neither was for it; and the one the
project's brief pointed at for the seam's guarantee does not reach the Tester. So there is a third,
`:data-conventions`, for every role (`NOTES.md` row 24), and the architecture skeleton's seams
table gains the column it is filled from.

With three placeholders, the first project's finding that an unfilled one reaches agents as literal
text (row 15) could not stay open. Its proposed fix — `rules-check` fails — does not work: the seed
ships with them standing and must pass its own gates. `start` lists them instead.

`method.md` §06 gains two rules on writing targets and an extension to two more, and §07's spec
review says what it buys beyond rounds and what its cap costs. Rules 9 and 10 are both about the
Architect, who in both projects was the session at the seat: *emphasis is a promise* — a person
deleted three words where the Architect had proposed a bound and a rule — and *a finding is dismissed
by asking whether ordinary input can reach it*, which the Architect got wrong in both projects on
the same finding.

### Port from the second project, tier 2: the record tells what a reviewed run cost

Asked whether the first port had covered the run record, the answer was *partly, and then it was
outrun*. Tier 3 of that port put the spec review into the record as one event — correct for a command
typed once before `start`. Two commits later `start` ran the review itself, stopped, and reviewed an
amended spec again, and the record-keeping stayed where it was: the last reading only, and no review
cost in any total. Nothing new had to be collected — `spec-review.edn` kept every reading already,
to enforce the two-review limit — only carried over and printed. `NOTES.md` row 23.

Kept: `runs/README.md`'s line that a review is a desk step and not a row, and the meaning of
`:run/cost`, so that no published report changes. Not done, deliberately: events for the
`:spec-reviewed` stops that happen before a run has state.

A design outrun by the next two commits is the same shape as the rule-mirror list in tier 1: the
mechanism was right and nobody re-ran the case it was for once the ground had moved.

### Port from the second project, tier 1: five fixes a second project's stops paid for

The seed was copied a second time and built the same small
website again, this time with the spec review, stop owners and the first port's fixes. Its
evidence stays with that project, as
the first project's does. It stopped for a person far less often than the first — and two of
its problem stops had a cause the first project had already written down. This tier
is the mechanical half of what it found; `NOTES.md` rows 11 and 19–22 have each one.

- **The leak check no longer refuses the contract's own words** (row 11). Open since the first
  project, where it was `view`; it cost the second a person-owned stop over `header`, which is a
  property target's own word. The row had asked for a design and a word list. The design is one
  sentence — the Tester already holds the title and the targets, so a word that stands in them
  discloses nothing — and needs no list.
- **An amendment makes the review before it history** (row 19). The loop re-triaged a rejection of a
  contract that had just been changed to answer it.
- **A copy of the seed passes its gates as copied** (row 20). Moving the mirror list to data after the
  first project fixed the mechanism and left the defect: the data still shipped this repository's
  sandbox. A fix is not finished until the failing case has been run again, and this one had not been.
- **The spec review's rule block is labelled** (row 21) — changed, not measured.
- **A writer cut off at its cap is named at the stop** (row 22).

Each was checked against the second project's own records rather than a constructed example: the
refused retry replayed through `leaks`, a run's events through `next-action`, its records through
`cut-off-writers`, and the working tree's seed copied into an empty repository and gated there.

### Stops have owners; a spec that keeps drawing findings stops for a person

The user wants the retest as automated as possible: a spec review's findings go to the Architect, who
amends and continues, and a person is asked only when a problem is beyond an amendment. The Architect
is the seat, so this is mostly a protocol between the person and the session driving the loop — but
two things belong in the harness so the protocol is visible and bounded. `orchestrate/owner`: a stop
is the Architect's when its answer is an amendment (the spec review, any `architect` route) and the
person's otherwise; the announce line and `run-task!`'s return carry it. And `spec-review.edn` keeps
the history of reviews of a run's spec; when a spec has drawn findings twice and is amended again,
`start` stops `:spec-review-limit` — a person's stop — rather than review it a third time
(`:spec-review/max`, default 2). The amendments themselves were already recorded by `amend`; the
person reviews them in a batch at the end of a stage.

### The performance section: time and money, derived from the record

The user asked for a report after every run with total time, ACTIVE time (not waiting on a person),
the OpenRouter funds, and time and cost per model and provider — accurately — and agreed six additions:
cost by role, rounds and their causes, tokens with the Anthropic cache share, effort per role, measured
vs list-priced, and the mechanical share. It is a second section below the footer, so the step table
keeps its shape and a record from before a field says "not recorded".

- **Instrumentation, three things:** `event!` stamps `:at` (absolute) into the state's copy of every
  event, not only the log line — the record's `:event/at-ms` is an offset from a counter that
  accumulates across invocations, which is the figure that once read below the sum of the steps;
  `start` and `record` write a `:balance` event from OpenRouter's key endpoint; `record` copies each
  role's model, family and effort into `:run/roles`.
- **Derivation** (`report/performance`): waiting is the gap after each `:stopped` event until the next
  event — a person reading and deciding — so active = total − waiting, per stop. Anthropic stays
  spend, since its API has no balance endpoint, and the line says so.
- **The four published reports were re-rendered** (71 lines added, none changed above the footer);
  `report-check` is green. Their sections say "not recorded" for time, balance and roles, which is
  the truth about them.

Prompted by my telling the user the triage measurement "took 25 minutes" when the timestamps said 18,
and the calls under two: an estimate repeated after the fact as if it were the measurement. Method §10
now has that as its thirteenth item.

### Triage runs at low effort

`claude.edn`'s `:orchestrator` goes from high to low, on a measurement over the first adopter's
saved triage prompts: re-asked at low, blind, they gave the same route on all but one
uncontested call, the same split on the contested one, and on the single disagreement the better
route under the ambiguity rule — for a little less. Not half: triage's cost is its prompt, and effort
changes only what the model writes. The comment that said "high, because this is judgement work"
now says what was measured. `portability.md`'s two tables updated (and the agy-ide Reviewer's medium,
the spec-reviewer rows, which the two-seat table had not caught up with).

### The spec reviewer is a role of its own

The review borrowed the `:reviewer` role. That hid which model reads specs, tied its effort to the
Reviewer's, and let nothing check its independence from the spec's writer. The user decided it should
be explicit: `:spec-reviewer` is the fifth role in `Profile` — required, no fallback to `:reviewer` —
and a verifier in `harness.profile/verifiers`: the Architect who writes the spec is the seat, the
seat's family is the Coder's by convention, so `bb profile` checks it against the Coder's family as
it does the Tester's and the Reviewer's. Both shipped profiles have the entry (claude: the Reviewer's
model, the configuration measured; agy-ide: its Reviewer's, unmeasured); `portability.md`'s table
has the row; method §05's role table has the role.

The agy-ide entry first said effort high with an "unmeasured" note; the user caught it. Fable 5.1 WAS
measured at both efforts on the claude seat, and high cost nearly twice as much a call for no more
recall than low — so the entry is low, and the comment says what was measured and what was not.

Same file, same day: the Reviewer (Fable 5.1) goes from high to MEDIUM effort, the user judging high
overkill for a review; the comment records the one measurement behind it (high found nothing more than
low on the spec review, at twice the price) and that medium is untried on this seat.

### The spec review joins the loop; the money is printed around every run

Two changes the user asked for before the second project:

- **`start` reviews the spec itself.** A hand-run command is a habit, and the point was to have the
  loop enforce it: `start` now runs the review for any `spec.edn` that has no review of THIS spec beside
  it (the hash is of the spec as data, so a comment edit does not re-review and a target edit does),
  prints the list, and stops `:spec-reviewed` before anything is provisioned; the next `start`
  proceeds and records the count. `run` treats that stop as a stop. `:spec-review? false` in
  `loop.edn` skips it — the seed's own tests, and a deliberate re-dispatch.
- **`bb balance`, and two lines around `run`.** OpenRouter's key status is a balance (`/api/v1/key`);
  Anthropic's API has none, so its half is SPEND from the run's steps, and the line says which is which.
  The first adopter was stopped by each provider's credit once, mid-run, with no warning.

**A cost this change incurred.** Two `driver_test` fixtures build their config by hand, without
`resolve-config`, so they had no `:spec-review?` key and the default is on: the first test run after
the change made two live spec-review calls through the shipped profile, a few cents in all, before the
fixtures were given `:spec-review? false`. A default that spends money when a key is absent is the
kind of default this repository keeps finding; it is on because the user wants the review in the
loop, and the tests now say so explicitly.

### Port from the first adopter, tier 3 · the spec review as a step, and the documents

- **`bb run-loop spec-review <run-dir>`** (`harness.spec-review`): the Architect's desk step. One
  call, no tools, to the profile's `:reviewer` role — a different family from the Architect who
  wrote the spec, on purpose — asking for every two-readings place and every unmentioned input.
  Writes `spec-review.edn`; refuses once `state.edn` exists; `start` records what it finds as a
  `:spec-review` event, and the report footer prints the count. An answer with no findings block
  writes nothing, so a silent failure cannot be recorded as a clean review. The prompt carries the
  type rule in the review's own voice, because in the rules alone it changed nothing. Tested
  against a stub model, including that the Architect's comments in `spec.edn` never reach it.
- **`method.md`**: §05 gains what independence does not protect against — a shared misreading —
  and why the spec's reviewer is another family; §06 gains the eight target-writing rules and the
  type rule as the user stated it; §07 gains Step 2½, the spec review. The Blueprint skeleton asks
  what each seam guarantees, and records the spec review's count per packet.
- **`NOTES.md`** rows 10–17, open, one per finding that wants design rather than a patch; row 18,
  watch, for the majority-of-three proposal. `runs/README.md` states the footer line and that
  "whether a reader would have agreed" names the reader.

**Where the evidence is.** The first project is a repository of its own — by the user's decision,
nothing from it is brought in: near-term it is there for reference, and as the harness matures it
becomes noise. The register's rows therefore say what was found in the KIT's own words.

The three tiers are the whole port. Not ported: that project's own rule text and mirror path,
the majority-of-three (a row, not code), and every change to the loop's design (rows 10–17).

### Port from the first adopter, tier 2 · two prompt changes, provisional; two rules a person set

- **The Reviewer says what each finding breaks** (row 7): `target N`, `slice`, `dependent` or `none`, per
  reason; `runner/review-verdict` parses the maps and a bare string still reads. A few reviews of
  evidence, all the right way — provisional.
- **Triage may not describe the implementation to the Tester** (row 8): the leak check sees names,
  and a fact is not a name. A couple of clean guidances of evidence — provisional.
- **Two rules, set by the user from reading verdicts** (row 9). *An ambiguous contract is fixed, not
  interpreted* goes on triage's `architect` line, because blind samples of one rejection divided on
  exactly that policy. *Types are followed as the language defines them*, with its corollary about
  construction order, goes into the rule source for every writing role and into triage's own prompt —
  every reader of one contract, this analyst included, had called a vector's order "unstated". It was
  tested before it came here: the sentence alone did nothing, the corollary did. General on purpose;
  the vector was the instance, not the rule.

Tier 3 — the contract review as a step, and the documents — follows.

### Port from the first adopter, tier 1 · three plain bugs and two evidenced behaviour changes

The kit's harness was copied, untouched, into a throwaway project — a small static
website, in a repository of its own (nothing about it was written here until now, by the user's
rule). The copy's diff against the harness as copied is the mechanical record of what
the project had to change. This commit ports the part with the strongest evidence, with
the tests written there.

- **Three plain bugs** (rows 5, 6, and `bb.edn`): a retried role dispatched into a worktree whose file
  gate 0 had repaired somewhere else; a provider-refused dispatch published as "synthetic"; and
  `rules-check` hardcoding this repository's sandbox mirror, which made the untouched copy fail its own
  gates on the first command. The mirrors are now data, `resources/rule-mirrors.edn`, and `bb rules-sync`
  with no arguments syncs every one.
- **Two behaviour changes with counts behind them** (rows 3, 4): `write_file` repairs and lints on write
  — rounds lost to lint before it, none after — and a Tester deliverable
  that says when to stop, confounded with the sync fix and marked so.

**Not ported, on purpose:** that project's own rule text (its layers, its one seam, its page-set
guarantee) and its mirror path. Those are what an adopter writes into the placeholders; what they
taught about the placeholders themselves is tier 3.

Tiers 2 and 3 — the two prompt changes, the two rules the user set, the contract review as a step,
and the documents — follow in their own commits.

## 2026-09-17

### Milestone 3 · The verdict, a rejection routed, and a merge a person types

The loop now reads the Reviewer's verdict, routes a rejection the way it routes a red
gate, and stops for a merge it never performs. `bb run-loop merge` performs it.

**The Reviewer ends with a block, and a missing block is not a failed dispatch.**
`runner/deliverables` asks for a trailing fenced JSON `{"verdict": "approve" | "reject",
"reasons": [...]}` and says what `reject` is for — a broken target, slice or dependent —
because a rejection costs a paid round and a Reviewer that rejects on taste spends the
cap. `runner/review-verdict` parses it into `:runner/meta :verdict` as `{:verdict
:approve|:reject :reasons [...]}`; `AgentResult` stays closed. The key is present on
every Reviewer result and nil when there was no block, a block that does not parse, or
a verdict that is neither word. Upstream fails the dispatch in that case, which throws
away findings that were paid for; here the dispatch is `:done` and the LOOP stops, with
`:no-verdict`, for a person.

**`next-action` reads the verdict, and nowhere else does.** `approve` is the only way to
`:awaiting-merge`. `reject` under the cap is a third triage trigger, `:rejection`; at the
cap it is the same `:capped` stop a red gate gets, with nothing asked. Triage is shown
the verdict's reasons, the findings in full, and the Reviewer's NOTES — register row 1
said the note would matter once rejection routing existed, and a rejection whose real
finding is "the targets do not say" is the architect's only if triage can see it. A
`coder` route retries with `[:triage :reviewer]` feedback, guidance first. An
`architect` or `human` route is a new stop kind, `:rejected`, and the model's failure to
answer falls back to a person: `propose-routing` reads a gate's output and a rejection
has none.

**Four things the plan did not say, found while building.**

- *The plan's wiring was a dependency cycle.* It had `runner/outcome` call
  `triage/parse-verdict`; `triage` requires `driver`, which requires `runner`. The parser
  moved to `harness.agent/last-json-block` — the one namespace both sides already sit
  above — and `triage/parse-verdict` delegates to it.
- *A Tester routed on a rejection gets the guidance and nothing of the review.* The plan
  said "tester only via `leaks`, else `:human`". `leaks` refuses ANY `:reviewer`-sourced
  feedback, because the Reviewer read the diff — so sending the findings would have
  escalated every Tester route without exception, and the route would have been
  decoration. `triage/feedback-for` now takes the trigger: on a rejection the Coder gets
  the findings and the Tester gets triage's guidance alone, which `shield` then checks
  like any other. The prompt tells the model that its guidance is all the Tester will
  be sent.
- *The old `reviewed?` would have routed one rejection for ever.* It looked at the gate
  runs and the Reviewer's dispatches only, so after a routed Coder retry the last of
  those was still the rejection: triage again, another paid round, until the cap.
  `current-review` looks at every dispatch, so a role sent back makes the review before
  it history and the next action is `check`. Table-tested, and the mutant that restores
  the old filter is killed.
- *The stop has said "read the Reviewer's findings above" since milestone 1, and the
  loop never printed them* — only the dispatch's one-line summary and its notes. Nobody
  needed them at a stop that could only `record`; a person deciding a merge does.
  `dispatch!` now prints the Reviewer's verdict, its reasons and its findings.

**The merge is `driver/merge!`, and the refusals are a pure function.**
`merge-refusal` over the state and the decision: already merged · no decision · the
loop's last `:stopped` event is not `:awaiting-merge`, or anything but a mutation check
(or this command's own unfinished attempt) followed it · the gate run is not current.
A hand-driven run reaches a merge by typing `run`, which costs nothing over a finished
review and keeps the verdict logic in one place. Then, in order: the gate worktree's
diff is taken again and compared with the `.diff` the Reviewer was shown — not in the
plan, and the reason is that `gated-current?` reads events and an edit by hand in the
gate worktree leaves none; one commit of the gate worktree on `<task>-reviewer`,
`<task-id> — run <id>`; a `:merge-commit` event carrying the decision, recorded BEFORE
the merge like every other decision here; `git merge --no-ff --no-edit` in the base
checkout; `:merged`; `record!` (status `:merged`, from the event, whatever the last stop
said); `teardown!`, which keeps the branches. A merge git refuses is aborted, recorded
as `:merge-failed` with git's output, and tears nothing down; run again, it reuses the
commit it already made. Denying a merge is not a command. `run` typed after a merge is a
`:merged` stop rather than a review of worktrees that are gone.

**What upstream does differently, for the backport.** Its human gate is `approve? [y/n]`
on stdin inside `run-task!`; it commits before the review because its diff is
`main...HEAD`; its one worktree means there is no question of WHICH bytes merge; a
`reject` escalates to a person unrouted; and its teardown deletes the branch.
`PROVENANCE.md` has the two new divergence entries.

**Tests.** `orchestrate_test.clj`, in upstream's shape against a scratch repository
with real worktrees, real assembly and real gates: the happy path merged onto the
scratch repo's `main` as one commit under a two-parent merge commit, recorded `:merged`
and torn down with the branches kept · a merge nobody decides awaits, branch and worktree
in place · a rejection routed to the Coder, retried with the findings, reviewed again and
approved · routed to the Tester with the guidance alone · with leaky guidance, stopped ·
routed to the architect, stopped, and refused by `merge` · no verdict, then a fresh
review by `check` · a gate worktree edited after the review, refused · a run that moved
on after the stop, refused until `run` reviews it again · a merge git refuses, aborted
and re-run · the review printed above the stop. `next-action`'s table gains approve,
reject under and at the cap, no verdict, a role sent back, a failed Reviewer and merged;
`driver_test.clj` has `merge-refusal` and `awaiting-merge?` as tables;
`api_runner_test.clj` the verdict's parsing; `triage_test.clj` the rejection prompt, its
fallback and who gets the findings. `bb gates`: 341 tests, 1,691 assertions, green, and
`bb example` exits 0. Twenty-four exact-match mutants over the new code — every refusal
removed, `--no-ff` dropped, the abort dropped, the old `reviewed?` restored, the
findings sent to the Tester, the first block instead of the last — were run against the
four test namespaces with a local mutation script: 24 of 24 killed. Comment-only edits for
the citation sweep were made in other files while it ran.

**Run a4**, after this entry's code was committed: the clamp
task a third time, unchanged, through the merge. Green in one round; the
Reviewer ended with `{"verdict":"approve","reasons":[]}` and left its `NaN` note again
rather than rejecting on something the targets do not decide, which is the deliverable's
definition of `reject` holding on its first live use. `merge`, behind a decision file the
user approved before it ran, made one commit of the gate worktree (the three
predicted files) and one `--no-ff` merge commit on a throwaway branch;
the record says `:merged` and validates; ten events in the state and the same ten in the
log; the sandbox's own gates on the merged tree ran 19 tests against the untouched
tree's 16. The scratch worktree and the branch were removed once the record was taken.
Everything the spec predicted held. Not exercised live: a rejection, a missing verdict, a
refused or failed merge — tests and mutants only. **Readiness criterion 6 is closed for
the aligned loop on a4**, and the documents of the day said so; a4
reached no triage, which a2 and a3 had already taken live.

**What the backport to thub will need** — the next piece of work, and not this branch's.
Thub's `run-task!` holds its routing inside its steps and its state in memory, so the
first thing to carry back is the SHAPE, not a feature: a run directory with `state.edn`
and the append-only log it already has, a pure `next-action` over that state, a
re-entrant `run`, every stop an event with a reason and a route, and the retry cap
counted as rounds (`:triage` events) so a hand retry and the loop spend one cap. On that
shape: triage on three triggers through one seam, with a fallback that reads file
ownership instead of `triage.clj`'s hardcoded gate keys, and the Tester leak check
(`driver/leaks`, `triage/shield`) — which needs the Tester kept from the implementation,
so either the three-worktree layout with assembly as a filter or an honest statement that
thub's single worktree cannot give it; the Reviewer's verdict with no-block as a stop
rather than a failed dispatch, a rejection routed rather than escalated, and the
findings kept from the Tester; the merge as a command behind a decision file instead of
`console-human-gate`'s y/n on stdin, with the commit at merge time, the
changed-since-review refusal, the abort on a failed merge, and a decision about whether
teardown keeps the branch. Thub's callers read `:verdict`, `:session-id`, `:capped?`,
`:provider` and `:tokens` off the top of an open `AgentResult`; closing it behind
`:runner/meta` touches every runner and the loop. Its runners are CLIs with session
resume, and the kit's is HTTP with none, so the backport must decide per role whether to
keep the CLI behind the same `AgentRunner` seam (the verdict parser and the capped-and-
wrote-nothing rule port either way) or move to the profile and the HTTP runner, which
brings provenance, `:pricing` and the `:orchestrator` role with it. Smaller pieces that
port as they are: `gates/failure` and the `GateEntry` schema, `run-gate` catching a
missing binary, `:property-targets` on every packet, `Feedback` with `:triage` and
`:architect`, `for-retry`, the pause on notes, the `:calls` and `:targets` checks,
dependents in `:files/context`, gate 0's hunks in the record, the nREPL log kept beside
the worktree and the process watched, the report and its drift gate, and the two rule
changes (`:precedence`, and `:shapes-are-the-contract` validating once). Thub keeps its
own vocabulary ("corpus") and its own project rules; and its `orchestrate_test.clj`,
whose `deps-for` shape this branch's tests were modelled on, will need the run-directory
fixture in return. `harness-seed/PROVENANCE.md` is the list of divergences with reasons,
and is the document to work from.

**The citation sweep.** The seed's `src/`, `dev/` and `test/` no longer name a register
row, a D-run, a bake-off, a stage or a numbered manual run — each such comment, and one
test name (`a-call-signature-for-a-value-is-a-violation`), reworded to keep the lesson
and drop the pointer. `PROVENANCE.md` states the greps and what each prints.

### Milestone 2 · Model triage on red gates and notes, dispatched on

The loop now asks a model who owns a red gate or a note, and acts on the answer.
Proved live by run `a2`: the Coder left a note, triage routed
`continue` for the reason a person would have given, and the run went green in one
round — the triage call a small part of what a dispatch costs.

**The Orchestrator is the profile's fourth role.** `shapes/Profile` requires it,
`bb profile` prints it, and `harness.profile/verifiers` deliberately does not list
it: triage writes no code and judges no diff, so §05's family rule has nothing to say
about it. `claude.edn` runs it on the Coder's family — Fable 5.1, effort high — by the
decision of 2026-09-16, written into the profile comment, the schema's docstring,
`portability.md`'s six-row role table and `NOTES.md` register row 2, which is what
watches it: a `coder` route over a Tester that had the contract right is the failure
the shared family would produce, and every verdict is recorded with whether the
reader agreed. `agy-ide.edn` gets Gemini at high, unmeasured, with the flash-at-high
caveat its own Reviewer comment records and the reason it does not apply to one
completion with no tools.

**`harness.triage` is thub's `triage_model.clj` reshaped, not ported.** Upstream shells
out to a CLI and scrapes its JSON, fires on a red gate only, and falls back to a
classifier that hardcodes gate keys. Here it is one `agent/converse!` on the
`:orchestrator` role with `:tools #{}` — the same runner, adapter and provenance as the
other three roles, so the call is a report step with a model, a cost and a token count
like any dispatch — on two triggers, `:red-gate` and `:note` (a rejected review joins
them in milestone 3), and it falls back to `driver/propose-routing`, which reads the
spec's file ownership rather than a gate key. The prompt frame is upstream's: the
routes, "route to whichever diverges from the slice", the files as written capped at
8,000 characters. Added to it: the property targets as the contract's behaviour, the
dependents, a `continue` route on a note, the rule that only the role that left a note
may be routed on it, and a paragraph telling the model that Tester-bound guidance may
name only the contract — cheaper than escalating a leak after the fact.

**The routing is dispatched on from the first run, and the safeties are structural.**
An `architect` or `human` route stops the loop with the verdict on the `:stopped`
event. A `coder` or `tester` route goes through `driver/retry-with!` — `retry!` split so
the decision can arrive as data — and leaves the same one `:triage` event a typed retry
does, with `:by :model`, so `rounds` counts routed and typed retries against the same
cap without either telling the other. A `continue` goes through `continue-with!` with
the model's reason as the decision `continue` by hand would have required. Guidance
bound for the Tester, together with the gate output it travels with, goes through
`driver/leaks` in `triage/shield`; any leak turns the route into `:human` with the leaks
listed and a new stop kind, `:tester-leak`, whose next steps are the reworded or
`--allow-leak` retry a person types. Any failure of the call — an API error, no JSON
block, a route the trigger does not offer — is the fallback with `:triage/fallback`
naming why.

**Two things the plan did not say, found while building.** First, `next-action`
needed one more clause: **a last event of `:stopped` is the same stop again.** Without
it, `run` typed twice over an untouched red gate would buy a second triage verdict,
which costs money and is not deterministic; with it, the loop is idempotent on re-run
and any command a person types clears it, because each one leaves an event. Second,
the leak policy applies to the WHOLE feedback a Tester retry would carry, not to the
guidance alone as the plan wrote it: the gate's own output travels with the guidance,
a typed `retry tester` is refused on both, and a routed retry that checked less than a
typed one would be a hole in the shield rather than a shield.

**What a2 exercised and what it did not.** Live: the `:note` trigger, the `continue`
route, the `:continued` event with the model's reason as the decision, the triage step
in the report and in `:run/cost`, the prompt and answer kept whole in the run directory,
and the record validating with a triage step in it. Not live: a red gate, and so the
`coder`/`tester` routes, guidance-first feedback, the leak policy, the `architect` stop
and the fallback. Those are `orchestrate_test.clj` (a routed retry going green, the
architect and human stops, a note continued, sent back and escalated, the shielded
Tester, the cap asking nothing, the measured call as a step) and `triage_test.clj`
(the verdict and prompt against a stub server, every fallback, the note routes, the
leak policy). 320 tests, 1,531 assertions. a3, the plan's task worded to conflict with
a dependent, is the live test of the red-gate route and was not run: the plan reserves
it for a run that triggers nothing, and a2 triggered triage.

**a3, run after all**, at the user's request, so the red-gate route is on
record. The dependent-conflict contract produced three verdicts: `architect` on the
Coder's note, `continue` on a Tester note that described its own test, and `architect`
on the red gate against the driver's `coder` proposal — the reader agreed with all
three, and the third is the first live case of the verdict a rule cannot reach. The
person's `continue` past the first stop is in the record as its own event, which is
also the first live exercise of the re-entrancy the milestone added: the loop resumed
from the stop, did not stop there twice, and did not ask about the same note twice.
Still not taken live: a `coder` or `tester` route, and so the guidance-first retry and
the leak policy.

One thing not resolved: while committing the run, one `bb gates` invocation exited 1
in its test step with the output discarded, and `bb test` alone plus two further
full `bb gates` runs, output kept, were green. Which test failed is not known. The
suite has two wall-clock assertions in `agent_test.clj` (a 1,200ms and a 4,000ms
bound on the generation-record fetches) that a busy machine could trip, and nothing
in this milestone touched them; that is a guess, not a finding, and it is written
here so the next transient failure is caught with its output rather than without.

**Documents.** `portability.md` gains the six-row role table (Role · Constraint ·
Family · Model · Dispatched? · Notes) with the Architect and DevOps as the seat's dated
selections, and the fourth row in the two-seat table; `skeletons/03-method-and-tooling.md`'s
§1 table gains the Dispatched? column and §5 asks who routes; `harness-seed/README.md`'s
pieces table gains `harness.triage` and its triage paragraph says what is dispatched
on; `PROVENANCE.md` gains the row and the divergence; `method.md` §10's closing
paragraph says the seed carries the seam and which two safeties the prompt alone does
not give.

## 2026-09-16

### Milestone 1 · The loop, with every stop handed to a person

`bb run-loop run <run-dir>` drives one task to its next stop. Proved live by run
`a1`: green in one round, `:awaiting-merge`, and the
record validates against `shapes/TaskRun` — which no record in this repository's
history ever had.

**The driver moved to `src/` first, mechanically**, in its own commit, so the
behaviour changes after it read as behaviour changes: `dev/run_loop.clj` became
`src/harness/driver.clj`. `bb run-loop` is still the command a person types, and
`:run-loop/error` is still the ex-data key.

**The loop is two functions.** `orchestrate/next-action` is pure over the run's
state and says what should happen next; `run-task!` executes that, one step at a
time, until the answer is `:stop`. Nothing about which step comes next is buried
inside a step, which is why the control flow is table-tested rather than discovered
by provisioning worktrees and calling models. Upstream's version holds the routing
inside the steps that perform it, and can only be tested by performing them.

**Every stop is a person's, and `run` is re-entrant.** A failed dispatch, a dead
nREPL, a dispatch that left notes with nothing decided, assembly refusing a file,
green gates over an empty diff, a red gate, the retry cap, and the Reviewer having
reported. Each records a `:stopped` event with its reason and route, prints what to
do next, and leaves the run exactly where it is. A person amends, retries or
continues with the commands, and runs `run` again — which is what makes the loop's
routing and a person's the same mechanism rather than two.

**What came back from thub, and what each cost to be without:**

- **The REPL probe**, in `driver/dispatch!` rather than in the loop, so a `retry`
  typed by hand is covered by it too. `gates/failure` gets its first caller after
  sitting in the seed unused since the extraction.
- **`harness.log`**, the append-only event log beside `state.edn`. `state.edn` is
  rewritten on every command, so it is the wrong thing to trust about what
  happened; `record` refuses when the two disagree. That is the drift gate this
  repository already applies to `AGENTS.md` and to a published report, applied to a run.
- **The record asserted against `TaskRun`** — a `RunStatus` taken from the loop's
  own outcome, `:run/cost`, `:run/started-at`. The schema had been there with no
  caller since 2026-09-10, and every record written before this failed it.
  `bb report-check` now
  validates every record as well as every published table, and `step!` asserts
  `RunStep`.
- **The nREPL's own output**, kept rather than discarded. It goes BESIDE the
  worktree, not inside it — the plan said inside, and the first test of it showed
  why that is wrong: a failed launch removes the worktree before rethrowing, which
  deletes the log at exactly the moment it is the only thing that can say what went
  wrong. `wait-for-port` watches the process too, so a dead nREPL fails at once and
  names its log instead of waiting out a sixty-second timeout.
- **The empty-diff guard**, and **capped-and-wrote-nothing as a failed dispatch**
  for the Coder and the Tester. Both are wrong in one direction alone: a capped
  dispatch that DID write files is work the gates should judge, and one that wrote
  nothing is a missing deliverable that assembly refuses a step later with nothing
  to say why.

**The retry cap moved from per role to per task**, counted as rounds recorded in
the state rather than iterations of a function. A per-role cap of 3 dispatches one
task six times before anything escalates, which is not a cap anyone chose; counting
`:triage` events means a hand `retry` and the loop spend the same cap without
either telling the other. And **a failed dispatch now stops `start`'s sequence**,
which is the rule `continue` already enforced at a pause, applied where `start`
would otherwise walk into an assembly that refuses a file nobody wrote.

**Documents.** `harness-seed/README.md`'s "Deliberately left out" loses the loop
and triage and keeps parallel dispatch and the per-gate timeout; its pieces table
gains `orchestrate`, `driver` and `log`. `PROVENANCE.md` gains rows for the two
ported namespaces, five divergence entries, a corrected path to the upstream
checkout, and a corrected count — upstream builds the gate-result shape by hand in
two test files, not three (`grep -rl ':gates/report' test/` at `5df04ad`).
`method.md` §12 says the loop is in the seed and that the order which put it there
is the order §12 recommends. Every file touched lost its `NOTES.md row N` and run-id
citations and kept the lesson.

**What a1 did not exercise:** a retry, a red gate, the cap, a dead REPL, an empty
diff. Those are in `orchestrate_test.clj` against a scratch git repo with real
worktrees, real assembly and real gates — only the dispatch is faked, because it is
the only step that costs money. 295 tests, 1371 assertions.

### Entry 1 · The harness re-aligns to the loop it was extracted from

**What this branch is for.** The kit's harness gets thub's §8 loop shape back —
one function that chains probe → dispatch → gates → triage → retry-to-a-cap →
review → merge gate — while keeping every piece the kit grew that thub does not
have. Then the result is back-ported to thub. That last step is not in this
branch's scope.

**Why now, and why it was right to wait.** The kit was extracted from
`thub-harness` on 2026-09-10 and deliberately left the loop behind:
`harness-seed/README.md` listed *the orchestration loop* and *triage* under
"Deliberately left out", and `method.md` §12 says not to build a harness
speculatively — *"run the loop by hand for the first several tasks … and
automate the orchestration only once you've felt where the manual version
actually hurts."* Twenty-five runs by hand is that
condition met, and they are also what makes the rebuild cheap: the manual driver
is the loop's steps, already written, already exercised.

**What the move lost, which is what this branch puts back.** A comparison of the
two harnesses on 2026-09-16 found seven things the kit has no version of:

- **The REPL probe.** The rule source tells an agent never to edit blind when
  the REPL is gone. Nothing checked, so a dead nREPL would have surfaced as a
  mysterious gate failure several paid attempts later.
- **The record asserted against its schema.** `harness.shapes/TaskRun` has been
  in the seed since the extraction and nothing ever validated a record against
  it. Every record committed before this fails it —
  `:run/status` outside `RunStatus`, `:run/cost` and `:run/started-at` missing.
  A schema nothing calls is a comment.
- **The human merge gate.** Four merges were done by hand in the earlier
  runs and one of them was wrong — it merged the bytes a role wrote rather
  than the bytes the gates passed.
- **The append-only log.** `state.edn` is rewritten in place on every command,
  so a run's history is only as good as its last write.
- **The Reviewer's verdict.** The Reviewer returns prose; nothing parses an
  approve or a reject out of it, so nothing can route on one.
- **The empty-diff guard.** Green gates over no change at all is a pass that
  means nothing, and the loop had no reason to notice.
- **Capped-and-wrote-nothing as a failure.** A dispatch that exhausted its
  iteration budget having written no file reported `:done`.

**The settled design** (decided in discussion on 2026-09-16; not re-opened
during the work):

- **The loop.** `harness.orchestrate/run-task!` composes the driver's existing
  steps behind thub's seams — runners, triage-fn, human-gate-fn, repl-probe,
  `provision!`/`teardown!`. The manual commands stay, because they are how a
  person continues from any stop; `merge` joins them.
- **Kept from the kit**, all of it: three worktrees and assembly-as-a-filter;
  the HTTP runner with no session resume; the `:calls` and `:targets` gates;
  gate 0's hunks in the record; the `Feedback` shapes; the Tester leak check;
  the pause on notes; the report and its drift gate; `propose-routing` as
  triage's fallback.
- **Taken from thub**: the REPL probe before each dispatch; the record asserted
  against `TaskRun`; an append-only event log beside `state.edn`; an nREPL
  process watch and log while waiting for the port; the Reviewer's verdict
  block; the empty-diff guard; capped-and-wrote-nothing as a failed dispatch;
  and a **retry cap of 3 per task** — rounds, not per role, so a hand `retry`
  and the loop count the same thing.
- **Triage is one model call** on three triggers (a red gate, a note at the
  pause, a rejected review), returning `{route reason guidance}` over
  `coder | tester | architect | human`, plus `continue` for an observation note.
  It is **dispatched on from day one**, with escalation as the safety and
  `propose-routing` as the fallback on any model failure; guidance bound for the
  Tester passes the leak check or escalates. It runs as the profile's
  `:orchestrator` role — Fable 5.1, effort high — which shares the Coder's
  family. That is accepted by decision, written into the profile, and watched.
- **Every stop is a person's.** Architect route, human route, cap reached, dead
  REPL, failed dispatch, empty diff, no verdict, and the merge gate. The loop
  records the stop with its reason and prints what to do next; nothing merges by
  itself. `merge` runs from the gate worktree, behind a decision file, and makes
  one commit at merge time.
- **Four dispatched roles** in the profile. The Architect and DevOps stay
  seat-side, as dated selections in `portability.md`.

**What it reverses.** `harness-seed/README.md`'s "Deliberately left out" loses
its first two entries: the orchestration loop and triage are in. The cut reasons
were real and are answered rather than ignored — the client-specific leakage
that made the loop unpleasant upstream (session resume, an undeclared retry key,
per-runner cost semantics) does not arise here, because the seed's runner does
not resume sessions and `AgentResult` is closed with a `:runner/meta` hatch; and
triage hardcoding one project's routing opinion is answered by making the
routing a model call with the mechanical proposal as its fallback. §12's
recommendation is not reversed at all — it says to build the loop *after* the
manual version has hurt, which is the order this followed.

**How it runs.** One plan, three milestones, each ending in a live trivial run
against `sandbox/` and a written record: the loop with every stop handed to a
person; then model triage on red gates and notes; then the verdict, rejection
routing, merge and teardown.

### Step 0 · The branch and its documents

A new line of work from the tip of the earlier one. The run tables, the records, `NOTES.md` and
`DEVLOG.md` start clean; the earlier records are dropped rather than
carried, so no table here is published without the record beside it.
`bb report-check` prints `reports in sync: 0`, which is the empty case of the
gate rather than an exemption from it. The profiles keep their dated selections
and their `:params`, `:pricing` and pins, and lose the bake-off narrative, which
now reads as one line. Documents outside the
seed that cited a run id or a section of the run tables now state the
lesson instead; the seed's own docstrings are swept per file as each milestone
touches them.
