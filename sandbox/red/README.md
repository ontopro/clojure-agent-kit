# red — fixtures that make a gate fail on purpose

A gate suite that has only ever been green proves nothing. These four files each violate
exactly one gate, so you can confirm the sequence **discriminates** — and that it
short-circuits at the right key, since triage dispatches on that key and the run log is read
by it months later.

```bash
bb break fmt      # copy one fixture into place
bb gates          # expect failure at the matching gate
bb restore        # remove every fixture
```

Nothing here is on the gate path until `bb break` copies it in, and `bb restore` deletes only
files named `red_*`, so it can never remove real source.
