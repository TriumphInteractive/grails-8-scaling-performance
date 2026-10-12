# Measurements

Raw data behind every number in the [top-level README](../README.md). Local paths are replaced with `<home>`, the user name with `<user>`, and the machine name with `<host>`.

## Files

| Path | Contents |
|---|---|
| `runs.csv`, `runs2.csv` | One row per run: layer, run number, Gatling mean OK req/s, OK and failed request counts, whether another project's test JVM was running at the end of the run (`flaky`), hold seconds (`runs2.csv` only), start and end time |
| `progress.txt`, `progress2.txt` | Runner log: JVM version and flags per layer, database connection count, Tomcat platform request threads, per-run results, CPU medians, pool timeouts |
| `cpu/` | CPU samples, nominally every 5 seconds (the real intervals vary and are in the timestamps), during each run of the `F` and `H` layers: timestamp, application JVM CPU seconds, a Gatling column that is not reliable and not used, Postgres CPU percent from `docker stats` (100 is one core) |
| `gatling/` | Gatling console output for every run |
| `app/` | Application stdout and stderr per layer |
| `gorm-verification/` | SQL statement counts per page and JSON SHA256 comparison for the GORM switches (`gorm.md`), the Hibernate SQL logs and response bodies they came from, and the bytecode checks for each war (`builds.md`). Reproduce the single-request checks with `.\gradlew.bat verifyGorm` after `ladderWars`; new evidence goes to `build/ladder/gorm/` |
| Runner (formerly `scripts/`) | Gradle tasks in `gradle/ladder.gradle`: `.\gradlew.bat ladderWars` builds the variants, then `.\gradlew.bat ladder '-Players=S0,S1,G1' -Pruns=3 -Phold=60` runs layers in order. Quote comma-separated arguments in PowerShell. Use `-Phold=180` for the F series. New logs, CPU samples, and the ledger go to `build/ladder/` |

The published data was collected with an earlier script version of this runner, and the Gradle tasks follow the same protocol.

The runner uses `ProcessHandle` for process discovery and CPU time, with native WMI through JNA for missing Windows command-line metadata so the competing-test-JVM guard works without shell scripts.

`gorm.md` and `builds.md` also mention build logs, `javap` dumps, and an off-switch SQL comparison. Those intermediate files are not published; the checks they support are summarized in those two files.

Rows with `flaky` set to `True` overlapped another project's tests on the machine and are excluded from the top-level README.

## Layer IDs

Every layer used the pool at 48 connections, 48 minimum idle, 2000 ms timeout, unless noted; the JVM flags were `-Xms1g -Xmx1g -XX:+UseG1GC -XX:ActiveProcessorCount=12`; and the hold was 60 seconds unless noted. Corretto 21 is 21.0.11, Corretto 25 is 25.0.4.1.

`runs.csv`:

| Layer | Configuration | Used in README |
|---|---|---|
| L0 | Naive app, dynamic Groovy, Corretto 21 | Platform table, naive column |
| L1 | L0 on Corretto 25 | Platform table, naive column |
| L1i | L1 built with invokedynamic on | Platform table, naive column |
| L2 | L1 built with `compileStatic` | Platform table, naive column |
| L3 | L2 with virtual threads | Platform table, naive column |
| L4, L5 | Optimized app with virtual threads, cache off and on | Not used |
| L1r, L2r | L1 and L2 repeated about 40 minutes later | Run-to-run variation |

`runs2.csv`:

| Layer | Configuration | Used in README |
|---|---|---|
| S0 | Naive app, dynamic Groovy, Corretto 21, pool 10, 10 minimum idle, 30 s timeout | Step 1 |
| S1 | S0 with the pool at 48 | Step 2 |
| G0 | Naive app with `compileStatic`, Corretto 25, GORM switches off | Control for step 3 |
| G1 | G0 with `--hibernate.default_batch_fetch_size=50` | Step 3 |
| G2, G2b | G1 with `-Dchirp.gorm.entityBatchSize=50`, two sets | Step 4 (G2b runs 1 and 2) |
| G3 | G2 with `-Dchirp.gorm.groupedCounts=true` | Step 5 |
| P0 | Optimized app, cache off, platform threads, dynamic Groovy, Corretto 21 | Platform table, step 6 column |
| P1, P1b, P1c | P0 on Corretto 25 | Platform table, step 6 column |
| P2, P2b, P2c | P1 built with `compileStatic` | Step 6 (P2 runs 1 and 3), platform table |
| P3 | P2 with virtual threads | Platform table, step 6 column |
| P4 | P2 with compact object headers | Platform table, step 6 column |
| C0 | P2 with the cache on | Step 7, 60 second holds |
| C1, C2 | C0 with compact object headers, C0 with virtual threads | Not used |
| H0, H1, H2 | Step 5 configuration on Corretto 21 dynamic, Corretto 25 dynamic, Corretto 25 `compileStatic` | Platform table, step 5 column |
| H3, H4 | H2 with virtual threads; H2 with compact object headers (each on its own) | Platform table, step 5 column |
| F0, F1, F2 | Step 7 configuration on Corretto 21 dynamic, Corretto 25 dynamic, Corretto 25 `compileStatic`, 180 second holds | Step 7 (F2), platform table, cached column |
| F3, F4, F4b | F2 with virtual threads; F2 with compact object headers, two sets (each on its own) | Platform table, cached column |
| F0b | F0 repeated at the end of the series | Drift check |

The `P1b`, `P2b`, `P1c`, and `P2c` sets ran in alternating three-run blocks to compare `compileStatic` on the step 6 path.
