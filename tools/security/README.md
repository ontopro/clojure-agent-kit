# tools/security/ - the security checks, tried from outside, and two scans

What `02-architecture.md` §4 says the template gives, and what a project's §8 chose, are claims
until a request tries them. This pack sends those requests to the running application, as a
visitor would, and reads only the answers: the response headers, the cookies, a POST without
its token, the routes that need a login, the error pages, the static folders, and TLS where
the base is https. No dependency and nothing of any framework.

Beside it, two scans over the source that no request can make: the dependencies' published
advisories (`deps`) and a secret committed by mistake (`secrets`) - [below](#the-two-scans).

Run the checks from the application's folder, through the pack's own `bb.edn`:

```
bb --config <kit>/tools/security/bb.edn check [--serve "<cmd>"] [--health <path>] [--base <url>]
     [--running] [--routes <file>] [--out <dir>] [--record <file>]
```

Like the browser pack, it serves the application, checks and stops it - through the browser
pack's own `server.clj`, so the two packs travel together - or, with `--running`, checks the
one already serving. Defaults are the Stack Lite template's: `bb serve`, `/health`,
`http://localhost:8000`.

> [!IMPORTANT]
> Only a failing row fails the run (exit 1). A warn is a choice the project has not made yet,
> or a weakness that is not the application's; a skip says what was not tried and why. Read
> both - a skipped CSRF row on an application with forms means the routes file is missing them.

## The rows

| Check | What is sent | Fails when |
|---|---|---|
| `headers` | read on every answer below | `X-Content-Type-Options: nosniff`, `X-Frame-Options` (or a CSP `frame-ancestors`), a `Referrer-Policy` that is not `unsafe-url` missing; `Strict-Transport-Security` missing on an https base; `X-XSS-Protection: 1` is a warn |
| `csp` | the same answers | a policy lets scripts come from anywhere (`*`, a bare scheme, no `script-src` and no `default-src`); no policy is a warn, or a fail when the routes file says `:csp :required` |
| `server` | the same answers | never: a `Server` header naming a version is a warn |
| `cookies` | every `Set-Cookie` seen | no `HttpOnly`, no `SameSite`, `SameSite=None` without `Secure`; no `Secure` on an https base |
| `csrf` | a POST with no token to each `:forms` route, `/` when none is named | accepted, redirected or an error; a named route taking no POST (the list is wrong). `/` taking no POST is a skip |
| `protected` | each `:protected` route, with no session | answered 2xx, redirected anywhere but `:login`, or a 404 (the list is wrong) |
| `errors` | a page that does not exist, a malformed path, each `:throws` route | the page carries a stack frame, a compiled function's class, a source file and line, a class name, an exception's name or a path on a machine; a missing page answering 200 is a warn |
| `static` | each `:static` folder itself, and five paths out of it (`..` as written, escaped, with an escaped slash) | a listing; a file from outside the folder coming back (a page coming back is a warn) |
| `tls` | a handshake, on an https base | the certificate or the host name does not validate, or the protocol is older than TLS 1.2 |

Every failing or warned row prints what fixes it in the terms of the pinned template, `kit-v1.2`
- the file and the function - where the fix is the template's, and names the plan's section
where it is the project's decision.

**Every request is sent as written.** An HTTP client tidies a path before sending it - resolves
`..`, refuses a bad escape - and the paths that matter here are the untidy ones, so the pack
speaks HTTP/1.0 over a socket (a TLS socket for https, checking the certificate and the host
name as a browser does).

**A request a browser never sends** - a malformed path, a path with `..` - may be refused by the
server before the application sees it, with a page of the server's own. Jetty does this, and its
page carries none of the application's headers. A header missing only there is a warn, not a
fail: the page is the server's, no link leads to it, and the header rules are about the
application's pages.

## The routes file

The pack cannot find an application's routes without reading its framework, so the project
types the ones that matter, in its build repository's `docs/security-routes.edn`, and each
stage plan says what it adds:

```clojure
{:protected ["/admin" "/admin/pages"] ; need a login: refused, or redirected to :login
 :login "/login"
 :forms ["/contact"]                  ; take a POST: refused without the token
 :throws []                           ; a route the project makes throw, where it has one
 :static ["/assets/"]                 ; no listing, no path out
 :static-files []                     ; a file to read a static answer's headers from;
                                      ; default: the first one the home page links to
 :csp :optional}                      ; :required once 02-architecture §8 chose a policy
```

Every key is optional; the defaults are the ones shown for `:static` and `:csp`, and none for
the rest. A public site with no forms and no login has no file at all.

## Where it writes

The table to the terminal, and `security.edn` (the rows, the counts, the base, the KIT commit)
with `security.md` beside it, under `--out`, else `SECURITY_OUT`, else the workspace's
`work/security/<app>/` when the application is in a KIT workspace (scratch, in no repository),
else `.local/security/`. `--record <file>` writes the same `security.edn` where a committed
document can cite it: the `stage-end` skill passes the stage's
`docs/stages/stage-N-security.edn`, beside its gates record, and `bb stage-report` reads it from
there.

## What it does not try

What only the source shows: that SQL is parameterised, that HTML is escaped, that the session
secret is the environment's in production, that the session cookie is encrypted. §4 says where
the template does each, and the template's own tests hold what they can. And an exception
answering the error page needs a route that throws - a project names one under `:throws` if it
has one; for the template, its `server_test.clj` holds it, and `bb health` runs that test in
the generated application's gates.

Nor the dependencies' advisories or a secret committed by mistake: those are scans over the
source, not requests - the two below.

`bb health` runs this pack against the generated application, so every claim of §4 a request
can try is tried on the day of the run; its record's row is `app security`.

## The two scans

```
bb --config <kit>/tools/security/bb.edn deps [--routes <file>] [--out <dir>] [--record <file>]
bb --config <kit>/tools/security/bb.edn secrets (--from <rev> | --all) [--routes <file>] [--out <dir>] [--record <file>]
```

> [!IMPORTANT]
> Neither is a gate. An advisory is published whatever the diff did, so a dependency clean
> yesterday fails today; the scans run at a stage's end and in `bb health`, never in a run's
> gates.

**`deps`**, from the application's folder: the resolved runtime classpath (`clojure -X:deps
list` - what ships, not a test or build alias's), every library asked of
[OSV](https://osv.dev), which carries the GitHub advisory database among its sources, in one
request: no key, no download, network only. One row per vulnerable library - each advisory's
severity, id and CVE, the version that fixes the line in use, and the libraries in `deps.edn`
that bring it, which is the line to change. A HIGH or CRITICAL advisory fails; a MODERATE, LOW
or unrated one warns. No answer from OSV is a skip, never an ok.

**`secrets`**, from the repository to read: every line the commits after `--from <rev>` add, or
HEAD's whole history with `--all` - each commit's own lines, so a secret added and removed
inside the range is still found - and every committed `.env` file (not `.env.example`). The
rules: values that say what they are by their shape (OpenRouter, Anthropic, OpenAI, GitHub,
AWS, Stripe, Slack and Google keys, a private key, a password in a URL), and a random-looking
value given to a name that says it is secret (`secret`, `token`, `password`, `api-key`). Any hit
fails, and says to rotate it: removing the line does not remove it from the history.

> [!CAUTION]
> The matched value is never printed or written. A hit is the file, the line, the commit and
> the rule; the record carries the same and nothing more.

What a project has read and decided goes in the routes file, each with its reason:

```clojure
{:accepted {"GHSA-xxxx-xxxx-xxxx" {:reason "no Digest auth in this application" :until "2026-12-31"}}
 :secrets-allowed [{:file "test/fixtures/revoked.clj" :rule :stripe-key :reason "a revoked test key"}]}
```

An accepted advisory or an allowed hit is a warn naming its reason; an acceptance past its
`:until` accepts nothing. The records are `security-deps.edn` and `security-secrets.edn`, beside
`security.edn` and under the same `--out` rules; `--record <file>` writes a copy where a
committed document can cite it.

What the secrets rules miss: a provider whose keys have no prefix this list knows, and a hex
value shorter than 32 characters given to a secret's name about one time in six (its shape is
too close to a word's). The prefixes are a list in `src/security_scan.clj`; a project whose
provider is missing adds it there in the KIT, not around it.
