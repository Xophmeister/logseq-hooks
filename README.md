# Logseq Git hooks

Two git hooks that, between them, turn the stream of periodic `Auto
saved by Logseq` commits into a history you'd actually want to publish.
(Logseq's auto-commit interval is configurable; mine is 30 minutes, so
the examples below assume that.)

- **`prepare-commit-msg`**: holds back an auto-commit whose staged
  changes do not yet amount to anything and rewrites the message on the
  ones that do go through using Claude Haiku. Commits made by hand are
  never touched.
- **`post-commit`**: pushes when enough work has accumulated or the
  remote has gone stale, with thresholds derived from recent history.

## Layout

```
├── bb.edn                      # development only; the hooks do not use it
├── bin/install
├── logseq-hooks.example.edn
├── post-commit                 # hook scripts must sit at the repo root, so
├── prepare-commit-msg          #   core.hooksPath can point straight at it
├── src/logseq_hooks/           # underscore: Clojure munges ns hyphens to it
│   ├── anthropic.clj           # Messages API client that never throws
│   ├── churn.clj               # how much a diff amounts to
│   ├── config.clj              # defaults, overlaid with .git/logseq-hooks.edn
│   ├── git.clj                 # git CLI wrappers and .git paths
│   ├── message.clj             # prompt, sanitising, deterministic fallback
│   ├── policy.clj              # the decisions, pure and testable
│   └── state.clj               # caches and markers under .git/
└── test/logseq_hooks/
```

The hook scripts have to keep their exact names and live at the root,
since `core.hooksPath` points at a directory of them. Everything else is
a normal Clojure layout.

## Install

From your graph repository:

```sh
git submodule add <url> .hooks
.hooks/bin/install
```

Then put your API key in `.git/logseq-hooks.edn`. The installer is
idempotent, so re-run it after a fresh clone or a submodule bump. It
sets `core.hooksPath`, seeds the config from the example and tells you
what it couldn't do for you.

Requires `bb` on the `PATH` that Logseq is started with.

### Notes on being a submodule

Git runs hooks from the top of the graph's working tree with `GIT_DIR`
set, so `git rev-parse --absolute-git-dir` resolves to the *graph's*
`.git`; which is where the config, threshold cache and deferral marker
all belong, since they are per-graph rather than per-hooks-version.

The corollary is a quiet failure mode: run one of these scripts by hand
from inside the submodule and it will resolve `.git/modules/.hooks/`
instead, find no config and behave as though nothing were set. Drive it
from the graph root, or via an actual `git commit`.

Mounting anywhere other than `.hooks` means setting `:excluded-paths` to
match.  Otherwise a submodule pointer bump counts towards churn and gets
described in a commit message as though it were a note.

## How the two hooks fit together

`prepare-commit-msg` aborts by exiting non-zero. The staged changes
survive, so the next auto-commit sees them plus whatever has been added
since and eventually the accumulation crosses the threshold. Deferral is
recorded by a marker file in `.git/`, whose age is the backstop: once
changes have been held back for `:max-defer-seconds` the commit goes
through regardless, so a quiet week still produces commits and therefore
pushes. `post-commit` removes the marker.

One knock-on effect to be aware of: `post-commit` derives its push
thresholds from the p90 of recent commit sizes and this filter makes
commits fewer and fatter. So its thresholds will drift upward over the
weeks after you turn the filter on, while the 400-commit sample still
has one foot in each regime.

## Tuning

Set `:dry-run true` and edit for an afternoon. Every decision is printed
with its reason and rewrites show the message they would have written
without committing anything. `:word-threshold` and `:block-threshold`
are the numbers to adjust; the defaults of 120 and 20 are guesses, not
findings.

### Why churn is measured three different ways

A Logseq block is normally a single markdown line, however long that
line is.  Line counting is therefore a poor measure of whether anything
was said and it fails in both directions: a 400-word new note scores 1,
and a two-word correction *inside* that note scores 2, because a
modified line appears on both sides of the diff.

So `prepare-commit-msg` asks two questions and lets either one through:

- **blocks touched**: filtered changed lines. Catches a change spread
  thinly across the graph, such as renaming a tag on thirty pages, which
  is barely any words.
- **words changed**: from `git diff --word-diff=porcelain`, which counts
  the words that actually moved rather than the lines containing them.
  Catches a change concentrated in one place, which is barely any
  blocks.

Both discard lines that only set `collapsed::` or `id::`: Logseq stamps
an `id::` onto a block the moment you reference it elsewhere and
rewrites `collapsed::` when you fold one and neither is worth a commit.
The block filter is exact; the word filter is approximate, since
word-diff dissolves line structure and it can only drop runs that
*begin* with an ignored property.

`post-commit` keeps counting plain lines via `--numstat`, deliberately.
It is asking about volume rather than meaning, its thresholds are
self-calibrating percentiles over that same measure -- so a systematic
undercount does not mislead it -- and a word diff over a 400-commit
sample would cost far more than the question is worth.

## Failure behaviour

The hooks are written on the principle that a broken hook must not stand
between someone and their notes.

- Anything unexpected in `prepare-commit-msg` is caught, logged to
  stderr and the commit proceeds with its original message.
- An API failure or timeout falls back to a deterministic subject naming
  the largest-changed page and the word count. Dull, but better than
  `Auto saved by Logseq` and it cannot fail.
- The API call is bounded twice, by the HTTP client's own deadline and
  by the surrounding `deref`, so a client that ignores its deadline
  still cannot stall a commit.
- A failed push is reported and swallowed; git ignores `post-commit`'s
  exit status, so an uncaught exception there would only surface as a
  stack trace in Logseq's log.

## Tests

```sh
bb test
```

`logseq-hooks.policy` holds both hooks' decisions, deliberately
separated from the git and network work that feeds them: it is pure and
takes plain maps, so the behaviour that actually matters is testable
without a repository, an API key or a remote.

```clojure
(policy/commit-decision {:auto? true :blocks 3 :words 11 :deferred-for 60}
                        {:word-threshold 120 :block-threshold 20 :max-defer-seconds 14400})
;; => [:defer "11 changed words in 3 blocks, below thresholds of 120/20"]
```
