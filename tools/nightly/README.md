# Nightly on the developer's Mac

CI (`.github/workflows/build.yml`) runs the unit suite and four dedicated-server gates. It cannot run the rest:
the client gates open a macOS window, many gates and staged tests need third-party mod packs that are not in
this repository, and the soak (`gate-m34-soak.sh`) simulates for two hours or more. `run_nightly.py` runs those
on the developer's Mac every night at 02:30, started by launchd.

## What one night does

1. `git fetch`, then puts the worktree `~/Documents/NeoForbric-nightly` detached at `origin/main`, checked out
   with `--force` and cleaned with `git clean -ffdx`, so yesterday's build outputs are not evidence about today.
   The main checkout (`/Users/jerry/Documents/NeoForbric`) is only read: its HEAD, branch, index and files are
   not changed, and whatever branch it has checked out does not matter.
2. Links the fixtures that are not in git from the main checkout into that worktree:
   `neoforbric-kernel/run/client-merged-pack`, `client-popular`, `client-neo-pack`, `client-kernel`,
   `neoforbric-kernel/build/compat-inputs`, `sweep80-mac`, `sweep100-mac-network`, `neoforbric-kernel/.dev` and the
   `fabric-loader` substrate that `./bootstrap.sh` checks out (the installer build compiles it). The gates that write into
   `client-merged-pack` get their own copy from `gates-parallel.py`, never the linked original.
   `NEOFORBRIC_OLD` is the main checkout's `neoforbric-loader` and `MC_DIR` is
   `~/Library/Application Support/minecraft` (the launch scripts' default), unless `--neoforbric-old` / `--mc-dir`
   or `$MC_DIR` say otherwise.
3. Runs `python3 tools/dev.py integration` (strict: a skipped test fails it), then
   `bash neoforbric-kernel/run/compat/gates-all.sh -j auto --skip gate-m34-soak.sh`. On Sundays the soak runs too
   and the gates run as `--release`, which fails on any skipped or still-red gate. Timeouts: 120 min for the
   tests, 180 min for the gates (360 on Sundays); a step that overruns is stopped with everything it started.
4. Commits `results/<YYYY-MM-DD>/summary.md` and `latest.md` to the orphan branch `ci-results` from a second
   worktree, `~/Documents/NeoForbric-nightly-results`, and pushes that branch, nothing else.
5. Sets the commit status `nightly/dev-mac` on the tested commit (`success` or `failure`), linking the summary.

Exit code: 0 passed and published, 1 failed or not published, 2 could not start (bad `--work`, fetch failed)
or could not set the status.

## Install

The Mac has to be logged in for the client gates' window. If it sleeps at 02:30, launchd starts the job when it
wakes. Before installing, check that the main checkout is prepared (`neoforbric-loader/run` staged, the fixtures
above present, Minecraft 26.2 with assets in `MC_DIR`), that `gh auth status` is logged in, and see what a night
would do; a dry run prints every command and changes nothing:

```bash
python3 tools/nightly/run_nightly.py --repo /Users/jerry/Documents/NeoForbric --dry-run
```

Then, once, the worktree the runner itself is taken from (the plist moves it to `origin/main` every night):

```bash
git -C /Users/jerry/Documents/NeoForbric worktree add --detach /Users/jerry/Documents/NeoForbric-nightly-src origin/main
```

and:

```bash
cp tools/nightly/com.neoforbric.nightly.plist ~/Library/LaunchAgents/
launchctl bootstrap gui/$UID ~/Library/LaunchAgents/com.neoforbric.nightly.plist
launchctl print gui/$UID/com.neoforbric.nightly    # loaded? last exit code
launchctl kickstart gui/$UID/com.neoforbric.nightly    # optional: run a night now
```

The plist runs `run_nightly.py` from `NeoForbric-nightly-src` after moving it to `origin/main`, with absolute paths and
`PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin` (launchd's own has no `gh`). Change those
in the plist if this Mac differs, then `bootout` and `bootstrap` again.

## Read the results

- Branch `ci-results`: `latest.md` is the last night, `results/<date>/summary.md` every night. It holds counts,
  test ids and gate verdicts only, never a log line or a local path: the branch is public, and a game log or a
  failing bytecode comparison can carry Mojang code.
- The commit status `nightly/dev-mac` on `main` commits, next to the CI checks; or
  `gh api repos/Ray-T-r/Minecraft-NeoForbric-mod-loader/commits/<sha>/statuses`.
- On the Mac: `~/Library/Logs/neoforbric-nightly.log` (every command the night ran),
  `~/Documents/NeoForbric-nightly/build/nightly/{integration,gates}.log`, and the per-gate logs in
  `~/Documents/NeoForbric-nightly/neoforbric-kernel/build/gates/`. They last until the next night cleans the worktree.

## Disable

```bash
launchctl bootout gui/$UID/com.neoforbric.nightly
rm ~/Library/LaunchAgents/com.neoforbric.nightly.plist
```

To remove the worktrees as well: `git -C /Users/jerry/Documents/NeoForbric worktree remove --force ~/Documents/NeoForbric-nightly`
and the same for `~/Documents/NeoForbric-nightly-results`. The links in the first point into the main checkout;
`worktree remove` deletes the links, not what they point to.
