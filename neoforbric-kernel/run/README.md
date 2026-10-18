# Kernel development and verification

Start from the repository root with a full **JDK 21+** and **Python 3.9+**. No Fabric substrate checkout,
player installer, existing game installation, or manually assembled game jars are required for the new
workflow. `tools/dev.py` uses the installer's artifact builder and the kernel from your current source.

## Start a development instance

```bash
python3 tools/dev.py client
```

On Windows, use `py tools/dev.py client` (or `python` if that is your Python command). The first run
prepares the game automatically: it downloads Minecraft 1.21.1 and the NeoForge 21.1.256-patched base,
assembles the NeoForge runtime carrier, checks their links, downloads platform libraries/assets and the
pinned Fabric API (0.116.17+1.21.1) and Energy APIs, remaps the intermediary-named API modules into the
base's Mojmap namespace, builds the canary mods the unit tests read, then builds and launches the kernel. Later launches reuse those inputs and rebuild changed kernel source.

Everything goes under `neoforbric-kernel/.dev/`: `minecraft/` holds upstream downloads and build caches,
`staged/run/` holds the assembled game inputs, `api/` holds the compile APIs, `client/` and `server/`
hold separate instances. That directory is ignored by Git. Put development mods in the instance's `mods/`;
Fabric API and Energy are included automatically. First preparation needs network access and several
minutes; Gradle caches its dependencies and preparation verifies downloaded file hashes.

For a dedicated server, accept Minecraft's EULA for the development instance:

```bash
python3 tools/dev.py server --accept-eula
```

New development servers bind to localhost and allow the client's local identity; edit `server.properties`
inside the instance if your testing needs other settings. The server console is interactive: type `stop`
to save and shut down. The client uses a local development
identity; these commands are for local development, not authenticated online play.

You can also use Gradle (Windows: `gradlew.bat`):

```bash
cd neoforbric-kernel
./gradlew prepareDev
./gradlew runClient
# Or: ./gradlew runServer -Pneoforbric.acceptEula
```

Keep preparation and launch in **separate invocations**. The runtime source set is wired when Gradle
configures the build, before preparation has written the jars. The one-command Python entry point handles
this separation automatically.

## Check the environment and customize a launch

```bash
python3 tools/dev.py doctor          # read-only; exit 2 means preparation is needed
python3 tools/dev.py prepare         # prepare/download without launching
python3 tools/dev.py client --dry-run # build and print the command without opening a game window
python3 tools/dev.py client --jvm=-Xmx4G -- --width 1280 --height 720
```

| Option | Environment variable | Default |
| --- | --- | --- |
| `--mc-dir` | `MC_DIR` | `.dev/minecraft/`; an existing Minecraft directory can be used explicitly |
| `--staged` | `NEOFORBRIC_OLD` | `.dev/staged/`; the directory **containing** `run/` |
| `--instance` | `RUNDIR` | `.dev/client/` or `.dev/server/` |
| `--natives` | `NATIVES_DIR` | `.dev/natives/<platform-architecture>/` |
| `--java` | `NEOFORBRIC_JAVA`, then `JAVA_HOME`, then PATH | JDK home or Java executable |
| Repeat `--jvm=...` | — | Additional JVM arguments |

The portable launcher uses native Java classpath separators and a Java argument file, so Windows paths
with spaces and long classpaths work. Windows Unicode arguments travel through UTF-8 JSON and
classpath entries through URL-encoded manifests, avoiding native-launcher codepage conversion. Only macOS receives `-XstartOnFirstThread`. Native libraries are
selected for the chosen JVM architecture. Developer launches use strict compatibility decisions.

Gradle also accepts `-Pneoforbric.python=<executable>`, `-Pneoforbric.instance=<directory>` and
`-Pneoforbric.dryRun`. Compilation overrides remain `-Pneoforbric.stagedRoot=<run directory>`,
`-Pneoforbric.mcLibraries=<libraries directory>`, `-Pneoforbric.fabricApi=<jar>` and
`-Pneoforbric.rebornEnergy=<jar>`. A partial staged tree fails with a prerequisite error.

## Know what a green result means

| Entry point | What it verifies |
| --- | --- |
| `cd neoforbric-kernel && ./gradlew build` (JDK 21+ on a fresh clone) | Boot compilation and available unit tests; with prepared inputs, also builds the game side |
| `python3 tools/dev.py test` / `./gradlew check` | Unit suite, available transfer tests, Python tooling self-tests and packaged installer link-gate positive/negative controls |
| `python3 tools/dev.py tool-test` / `./gradlew toolTest` | The portable tooling's self-tests; no game jars required |
| `python3 tools/dev.py integration` / `./gradlew integrationTest` | Unit, transfer and tool suites with strict enforcement: missing game inputs, zero tests or **any skipped assertion fail** |

Gradle prints test and skip counts, and HTML reports live in `neoforbric-kernel/build/reports/tests/`.
A boot-only build cannot prove the game or mods work. `integration` prepares the base game automatically,
but the full suite also needs the particular third-party fixtures named in its reports (Create, Carpet,
compatibility packs, etc.). It fails when those are missing rather than claiming complete coverage.
On a freshly prepared clone every test that needs only the base game runs: the tests read Minecraft's jar
and libraries from the directory Gradle compiled against (passed as `MC_DIR`) and the pinned Fabric API
from `.dev/api/`, so what remains skipped, and listed in the report, is the third-party packs alone.

The legacy `gate-m*.sh` integration gates and the `run/compat/` harness were removed. They were Bash tools with
gate-specific packs, worlds and platform assumptions; they needed a developer's own machine and mod packs that
are not in this repository, and the Bash layer could not run on Windows. CI runs the boot build plus tool checks
without game files, and separately checks the portable tools and packaged link gate on Windows, Linux and macOS.
Without game files
about a third of the unit tests skip; CI holds that set to `src/test/skip-baseline/ci-unstaged.tsv`
(`./gradlew check -Pneoforbric.skipBaseline=ci-unstaged`), so a test that starts skipping fails the job. If your
change makes a test skip there, or stop skipping, update that file in the same commit: the failing job prints the
`+`/`-` lines, and its `kernel-test-results` artifact holds the complete `skips-actual-ci-unstaged.tsv`.

## Repository navigation

| Location | Purpose |
| --- | --- |
| `tools/dev.py`, `tools/test_dev.py` | Current portable development entry point and its tests |
| `neoforbric-kernel/src/test/`, `src/transferTest/` | Gradle-managed unit and transfer-engine tests |
| `neoforbric-kernel/canary/` | Tracked probe-mod source; generated jars go to `run/canary/` |
| `neoforbric-kernel/run/build-*-canary.sh` | Fixture builders for the probe mods under `canary/` |
| `neoforbric-kernel/run/evidence.py`, `run/reports/` | Release acceptance gate (called by the installer's `releaseAssets`) and the recorded compatibility evidence |
| `neoforbric-kernel/.dev/`, `run/client-*/`, `run/server-*/` | Ignored local runtime state |
| `neoforbric-kernel/build/` | Build outputs, test reports and gate logs |
| `neoforbric-loader/src/tools/` | Shared standalone merge/link tools; no bootstrap required for `mergeToolsJar` |
| `neoforbric-kernel-installer/` | Current player installer and the artifact pipeline reused by `devToolsJar` |
| `neoforbric-loader/run/launch-*.sh`, `neoforbric-installer/` | First-generation boot and installer paths |

Keep new probes under `neoforbric-kernel/canary/` and verification tools under `run/`, rather than
inside an instance directory. Use the entry points above instead of choosing among them by filename.
