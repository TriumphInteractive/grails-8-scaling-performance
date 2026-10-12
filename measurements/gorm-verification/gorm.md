# Baseline GORM fetch switches

## Statements and response hashes

| Config | Statements per timeline page | Statements per account page | Timeline JSON SHA256 | Account JSON SHA256 |
|---|---:|---:|---|---|
| G0: all switches off | 116 | 55 | A96A1BDF2DE8443D8CB27C24BF127434A1C61EF96BF75A13E87A1FC7381A67AF | 3599AADAA369F8CE37F901A17D752CE6751A07C5184B744E134E336DD57F674E |
| G1: global batch size 50 | 59 | 28 | A96A1BDF2DE8443D8CB27C24BF127434A1C61EF96BF75A13E87A1FC7381A67AF | 3599AADAA369F8CE37F901A17D752CE6751A07C5184B744E134E336DD57F674E |
| G2: G1 + entity batch size 50 | 47 | 28 | A96A1BDF2DE8443D8CB27C24BF127434A1C61EF96BF75A13E87A1FC7381A67AF | 3599AADAA369F8CE37F901A17D752CE6751A07C5184B744E134E336DD57F674E |
| G3: G2 + grouped counts | 9 | 10 | A96A1BDF2DE8443D8CB27C24BF127434A1C61EF96BF75A13E87A1FC7381A67AF | 3599AADAA369F8CE37F901A17D752CE6751A07C5184B744E134E336DD57F674E |

Acceptance passed: both endpoint response bodies have identical SHA256 hashes in G0-G3. Timeline SQL statements fall at every step: 116 > 59 > 47 > 9. Account statements are unchanged between G1 and G2, then fall with grouped counts.

An additional off-switch regression check used the existing `ladder2\static.war` with the same JVM and pool settings. Its responses match G0 byte-for-byte by SHA256. All 116 timeline statements and all 55 account statements also match G0 in exact SQL text and order, excluding logger timestamps, PIDs, and thread names. Evidence: `GBefore-results.json`, `GBefore-sql.txt`, and an unpublished SQL comparison. After establishing a G0 reference with `.\gradlew.bat verifyGorm`, reproduce the response-hash and statement-count check with `.\gradlew.bat verifyGorm '-PverifyWar=path/to/earlier-static.war'` (the GBefore configuration).

## Diff summary

- `baseline/grails-app/domain/demo/chirp/Account.groovy` and `Chirp.groovy`: add `Integer.getInteger('chirp.gorm.entityBatchSize', 0)` inside each existing mapping; apply `batchSize` only when greater than 1. Preserve every existing mapping line.
- `baseline/grails-app/services/demo/chirp/NaiveChirpJsonService.groovy`: add `page(List<Chirp>)`. Off returns `chirps.collect { of(it) }`. On runs the two requested GORM grouped projections, indexes their `[id, count]` rows, and uses zero for missing counts. Empty pages return before projections. A shared private builder retains the original key order, association traversal, and off-path count query order. Existing `Number` count results are retained, with `0L` for absent grouped rows.
- `baseline/grails-app/controllers/demo/chirp/TimelineController.groovy` and `AccountController.groovy`: replace the per-chirp serializer collect with `NaiveChirpJsonService.page(chirps)` only. Finder queries, limits, ordering, and fetch modes remain unchanged.
- Global collection batch fetching requires no code/configuration edit. It is supplied only as `--hibernate.default_batch_fetch_size=50`.
- No changes made to README, the optimized app, or build configuration. The repository already contained unrelated working-tree changes, including README and optimized/build.gradle; these were left intact. No commit was created.

## Build result

Waited in-band until `ladder done` appeared in `ladder3\progress.log`. No Gradle or Java was run before that marker.

Built on `C:\Program Files\Amazon Corretto\jdk21.0.11_10`, with its bin directory prepended to Path:

```text
.\gradlew.bat :baseline:bootWar -PchirpCompileStatic=true -PgroovyIndy=false --rerun-tasks
BUILD SUCCESSFUL in 24s
```

Log: `build-gorm.log`. Copied `baseline\build\libs\chirp-baseline-0.1.war` to `ladder3\static-gorm.war`; all G0-G3 runs used that same artifact.

The first build failed at the two criteria-result calls with:

```text
[Static type checking] - Cannot find matching method demo.chirp.NaiveChirpJsonService#countsById(java.lang.Object). Please check if the declared type is correct and if the method exists.
```

Explicit `List` casts on the criteria results resolved the error. No `@CompileDynamic` annotation was needed. The successful build retains the asset compiler's Truffle/JDK compatibility warning; it does not prevent building. Optional LSP diagnostics were unavailable: `No LSP server configured for extension: .groovy`. Static compilation and real HTTP verification are the validation evidence.

## Verification method and artifacts

- Corretto 25, now resolved by the Gradle Amazon toolchain launcher rather than a local JDK path.
- Common environment: `CHIRP_POOL_SIZE=48`, `CHIRP_MIN_IDLE=48`, `CHIRP_CONNECTION_TIMEOUT=2000`.
- Common JVM flags: `-Dgrails.env=loadtest -Xms1g -Xmx1g -XX:+UseG1GC -XX:ActiveProcessorCount=12`.
- Common application arguments: `--spring.profiles.active=loadtest --logging.level.org.hibernate.SQL=DEBUG`.
- G1 adds `--hibernate.default_batch_fetch_size=50`. G2 additionally adds JVM property `-Dchirp.gorm.entityBatchSize=50`. G3 additionally adds JVM property `-Dchirp.gorm.groupedCounts=true`. All system properties precede `-jar`.
- The Gradle `verifyGorm` task launches an owned child JVM for each configuration, one server at a time on port 8080, and always stops it in a `finally` block. It polls `/api/ready` until HTTP 200, warms each endpoint once, waits two seconds, captures the log line count, requests the endpoint into its JSON file, then waits two seconds and counts new `org.hibernate.SQL` lines. Like the earlier runner, it checks the existing database without reseeding or load. Counts and hashes depend on that snapshot: the published bodies include load-test writes, not only the seed, so a freshly seeded database need not reproduce the historical counts or hashes.
- Timeline URL: `/api/timelines/user1?max=20`. Account URL: `/api/accounts/user1`.
- `G0-results.json` through `G3-results.json` record statement counts, measurement line boundaries, hashes, and owned Java PIDs. `G<n>-sql.txt`, `G<n>-user1.json`, and `G<n>-account-user1.json` contain the raw evidence. Run `.\gradlew.bat ladderWars` once, then `.\gradlew.bat verifyGorm` to reproduce these single-request checks; it is not a load test. New artifacts and `summary.txt` are written to `build/ladder/gorm/`.
- The first historical measurement attempt encountered a Windows log-sharing error; its owned server was stopped before retrying. The Gradle runner reads the redirected live log directly.
- All launched Java processes were stopped by owned PID after verification. Port 8080 is no longer listening. The pre-existing `grails8-chirp-postgres` container remains running and healthy on port 5438. No reseeding, load tests, container shutdown, or unrelated process termination occurred.
