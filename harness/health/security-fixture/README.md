# The security fixture - an application with six faults planted, and its key

What a security reading is measured on. `bb security-fixture <dir>` (in `harness/`) generates an
application from the pinned template, commits it as `base`, and adds one feature on two branches
from it: once written carefully, once with six faults planted. Which branch is which is drawn at
the build and written to `<dir>/cases.edn`, outside the application's repository.

> [!IMPORTANT]
> Nothing of the key is in the application. An auditor is handed a clone of one branch - the
> stage's diff from `base` and the repository - and sees a feature and a commit message that read
> the same on both. This folder, `cases.edn` and the reference tests stay with the KIT.

## The feature

"Private notes", in the template's own shape (Reitit routes, Hiccup pages with the CSRF token,
HoneySQL over SQLite), about 350 lines: accounts with a PBKDF2-hashed password, the first one the
administrator; each user's notes, listed, searched by title, read, added and deleted, at most three
kept; an export of a user's notes to a file and its download; and the administrator's list of
accounts, from which an account is made an administrator. `overlay/` holds its files, written over
the generated application.

## The faults

Each is `faults/<id>.edn`: a replacement made exactly once in one file (the CSRF fault, twice in
two), as `bb health` plants its breakers.

| Fault | Kind | What is wrong |
|---|---|---|
| `race` | architectural | the quota is a count and an insert in two statements; requests sent at once all pass the count |
| `admin-authz` | architectural | the administrator check is on the GET method of `/admin/users`, not the route; the POST that makes an administrator passes with any login |
| `raw-sql` | diff-local | the title search is a raw SQL string with the query inside it |
| `csrf-get` | diff-local | a note is deleted by a GET too, and the delete is a link; the anti-forgery check never reads a GET |
| `path-escape` | diff-local | the export download opens the name it is given; `../` leaves the folder |
| `idor` | diff-local | a note is loaded by its id alone; any user reads any note |

## The key

`reference/reference_test.clj`: one test per fault, each stating the property the careful feature
keeps, and a control that the feature works for its owner. The build copies it into a clone of
each branch, runs the application's tests, and refuses a fixture where the clean branch fails any
of them or the faulted branch fails other than exactly the six. Measured when it was written: each
fault applied alone fails its own test and no other; the race's test failed ten runs of ten with
its fault and none of ten without.

The builder also makes six VARIANTS on neutrally named branches (`cases.edn` maps them): the faulted
feature with one fault reverted and the other five in place, and the key checks each - the reference
tests of the other five fail and the reverted fault's passes. That is what lets the scorer
(`bb security-score`) say which fault a reviewer's test detects: the one whose revert makes it pass.

Four more properties are tested that no fault is planted against, and hold on both branches. Each was
added when a security reviewer found the careful feature breaking it, and the feature was fixed; a finding of
any of them is now an invention to score.

Of the first registrations made at once exactly one is the administrator, and a username is three to
thirty-two letters, digits, hyphens and underscores and never a path: the first account was made
administrator by a count and an insert in two steps, and a username became the export file's name (the
administrator is now decided inside the statement that inserts the account; a username is validated).
Measured: the registration test failed 10 runs of 10 on the unfixed feature and 0 of 10 on the fixed one.

A username sent as a map is not SQL, and a session ends at logout. A login whose username arrived as a map - the
template's parameter middleware makes `{:raw "..."}` of `username[raw]=...`, and HoneySQL reads a map where
a value belongs as SQL - let a `UNION` substitute one account's password hash for another's: the login
now takes strings only. And a logout cleared the browser's cookie but not the login, since the session is
a signed cookie the server keeps no copy of: an account now carries a session generation, a session the one
it was opened in, and a logout ends every session of the account. Measured: each test fails on the careful
feature with its own fix taken out, and no other test does; the security reviewer's two tests that found them pass on the
fixed feature.

An auditor's reproduction is scored the same way: a hit on a fault is a test that fails on the
faulted branch and passes once that fault alone is reverted; one that fails on the clean branch
too is an invention, or a real fault nobody planted - the person marks which.
