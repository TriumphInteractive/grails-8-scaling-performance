# Grails 8 scaling performance

Two Grails 8.0.0 apps, one schema, one seed. `baseline` is a deliberately naive read path: dynamic finders, then a query per association per row, 116 SQL statements for one 20-chirp timeline page. `optimized` returns the same fields from one hand-written SQL statement per timeline page, then from a rendered-JSON cache. This is not a claim that every Grails application is as slow as the baseline.

## The ladder

Each step keeps everything above it. Three runs per step unless noted, OK requests per second, zero failed requests in every run. Gatling at WARN, 40 looping users, 15 second ramp, 60 second hold. Every run reseeded the database, ran `ANALYZE`, and warmed every handle first.

| # | Step | Runs | Mean req/s | Step lift | Total |
|---|---|---|---:|---:|---:|
| 1 | Stock: naive GORM on Corretto 21, Hikari's default pool of 10 | 127, 134, 135 | 132 | | 1.0x |
| 2 | Size the pool above the concurrent users (48) | 221, 236, 237 | 231 | 1.75x | 1.75x |
| 3 | GORM: `hibernate.default_batch_fetch_size: 50` (one line of config) | 378, 390, 384 | 384 | 1.85x | 2.9x |
| 4 | GORM: `batchSize 50` on the `Account` and `Chirp` mappings | 456, 467 | 462 | 1.2x | 3.5x |
| 5 | GORM: two grouped count projections instead of 40 `countBy` calls | 1273, 1311, 1360 | 1315 | 2.85x | 10x |
| 6 | One hand-written SQL statement per timeline page | 2458, 2865 | 2661 | 2.0x | 20x |
| 7 | Rendered-JSON cache in front of that query | 6404, 3774, 3594 | 4591 | 1.7x | 35x |

Steps 3 to 7 run on Corretto 25 with `compileStatic`, held constant, because those two did not move the number (next table). Step 3's lift is against that same-JVM control, 192, 220, and 211 (mean 208). Step 4 shows two runs because another job on the machine overlapped the third; an earlier set of the same step measured 431, 458, and 445. Step 6 likewise excludes one overlapped run (2362). Step 7 swings because cache entries expire every 20 seconds, so quote the mean and the range.

SQL statements per 20-chirp timeline page, counted from the Hibernate SQL log. The four GORM rows return byte-identical JSON on the timeline and account endpoints (SHA256 compared).

| Step | Statements per page |
|---|---:|
| Naive | 116 |
| Global batch fetch | 59 |
| Plus entity batch size | 47 |
| Plus grouped counts | 9 |

The optimized app is a separate implementation: a timeline cache miss is one account lookup and one page statement. Account pages (10 percent of the load) still use a handful of grouped queries. Its ordering contract differs slightly (see below), so it is not byte-compared with the naive app.

## What did not help on this workload

| Change | Where measured | Result |
|---|---|---|
| Corretto 25 instead of 21, same war | naive app: 228 vs 224. Optimized, cache off: 2485 vs 2360 | Flat |
| `compileStatic` | naive app: 224 vs 221. Optimized, cache off, six alternating runs each: 2587 dynamic vs 2509 static | Flat. A first set read 2360 vs 2661; the alternating rerun did not hold that up |
| Groovy invokedynamic on (Grails 8 turns it off) | naive app: 224 vs 221 | Flat |
| Virtual threads | naive app: 221 vs 146. Optimized, cache off: 2661 vs 2403. Cached: within the spread | Slower or flat. 40 users never run out of Tomcat's 200 platform threads |
| `-XX:+UseCompactObjectHeaders` | optimized, cache off: 2661 vs 2593 (one overlapped run excluded). Cached: within the spread | Flat |

The naive app spends its time on database round trips, so JVM and compiler changes cannot show. Repeating the JDK 25 and `compileStatic` steps about 40 minutes later read 4.4 and 7.1 percent lower, so treat differences of that size as drift, not results. On the optimized cache-off path a single configuration moved as much as 15 percent between consecutive runs (2483 to 2860 req/s).

## How it was measured

| Role | Detail |
|---|---|
| Machine | 12th Gen Intel Core i7-12800H, 14 cores, 20 logical processors, 64 GB RAM, Windows |
| Application JVM | Amazon Corretto 21.0.11 or 25.0.4.1, resolved by Gradle toolchains and Foojay. `-Xms1g -Xmx1g -XX:+UseG1GC -XX:ActiveProcessorCount=12` |
| Gatling JVM | Corretto 21, `-XX:ActiveProcessorCount=6`, logging at WARN |
| Database | Postgres 16 in Docker, host port 5438 |
| Pool | 48 connections, 48 minimum idle, 2 second connection timeout, except step 1 (10, 10, 30 seconds) |

`ActiveProcessorCount` changes how many processors each JVM reports. It does not reserve CPUs. One app ran at a time. The runner checked for other projects' test JVMs before and after every run, and runs that overlapped one are excluded and noted.

Two setup bugs produced misleading numbers before this set. A Liberica NIK JDK, which compiles with Graal instead of HotSpot, was being used as "JDK 25" and made it look four times slower. The pool was 16 connections with a 2 second timeout, so `compileStatic` and virtual threads looked worse because requests timed out waiting for a connection. Every number above is from Corretto with the larger pool, except step 1, which is the stock pool on purpose.

## Switches

Build switches. `-PchirpCompileStatic` and `-PchirpJava` default to `true` and `25`.

| Switch | Effect |
|---|---|
| `.\gradlew.bat :baseline:bootWar -PchirpCompileStatic=false` | Naive app, dynamic Groovy. Steps 1 and 2 use this war |
| `.\gradlew.bat :baseline:bootWar -PchirpCompileStatic=false -PgroovyIndy=true` | Naive app with invokedynamic |
| `.\gradlew.bat :baseline:bootWar` | Naive app with `compileStatic`. Steps 3 to 5 and their control use this war |
| `.\gradlew.bat :optimized:bootWar -PchirpJava=21` | Optimized app as Java 21 bytecode, runs on JDK 21 and 25. Steps 6 and 7 use this war |
| `.\gradlew.bat :optimized:bootWar -PchirpJava=21 -PchirpCompileStatic=false` | Optimized app, dynamic Groovy |
| `.\gradlew.bat correttoHomes` | Prints the Corretto 21 and 25 homes Foojay resolved |

Runtime switches.

| Switch | Effect |
|---|---|
| `CHIRP_POOL_SIZE`, `CHIRP_MIN_IDLE`, `CHIRP_CONNECTION_TIMEOUT` environment variables | Hikari pool. Defaults 16, 8, 2000 ms |
| `--hibernate.default_batch_fetch_size=50` | Step 3 |
| `-Dchirp.gorm.entityBatchSize=50` | Step 4 |
| `-Dchirp.gorm.groupedCounts=true` | Step 5 |
| `CHIRP_CACHE_ENABLED=false` | Optimized app without the cache (step 6) |
| `CHIRP_VIRTUAL_THREADS=false` | Optimized app on platform threads. Steps 6 and 7 used `false` |
| `--spring.threads.virtual.enabled=true` | Virtual threads on the naive app |

## What the optimized app does differently

- A hit returns a rendered JSON string from a Caffeine `AsyncLoadingCache`. It does not touch the database. Entries expire 20 seconds after write and refresh after 10 seconds. Writes do not delete cache entries, so a read can be up to 20 seconds behind a write.
- A timeline miss looks up the account with a GORM Data Service `@Query`, then runs one Postgres statement for the page: followees, the top 20 chirps per author, cheer count, reply count, tags, mentions, media, and the reply-to handle.
- The cache load does not hold a map lock across JDBC.
- Both apps set the pool in `ChirpHikariDataSource`. `dataSource.properties` is the documented Grails key and does not reach Hikari on 8.0.0, so a stock app runs Hikari's default of 10. See https://github.com/apache/grails-core/issues/16567.
- Open session in view is off in the optimized app. The baseline keeps the Grails default, on. The second-level cache and the query cache are off in both apps.
- The baseline list is ordered by `postedAt` only. The optimized page also breaks ties by id, and its child lists are ordered by id. Treat that as a contract difference, not as part of the throughput gap.

## How to reproduce a step

Prerequisites: Docker, and Gradle running on Corretto 21 (`JAVA_HOME`), because the Gatling plugin forks with the Gradle JVM. Measure one app at a time.

Start Postgres and print the Corretto homes the toolchain resolver selected:

```text
docker compose -p grails8-chirp up -d
.\gradlew.bat correttoHomes
```

Build the war for the step (see Switches), then start it with the step's switches and the Corretto `java` for that step, not whatever `java` is on `PATH`. Steps 1 and 2 use Corretto 21, steps 3 to 7 use Corretto 25. Baseline listens on 8080, optimized on 8081. For example, step 3 in PowerShell, with `$corretto25` set to the home `correttoHomes` printed:

```text
$env:CHIRP_POOL_SIZE = '48'
$env:CHIRP_MIN_IDLE = '48'
& "$corretto25\bin\java.exe" -Dgrails.env=loadtest -Xms1g -Xmx1g -XX:+UseG1GC -XX:ActiveProcessorCount=12 -jar baseline\build\libs\chirp-baseline-0.1.war --spring.profiles.active=loadtest --hibernate.default_batch_fetch_size=50
```

Wait until `http://localhost:8080/api/ready` returns 200. Before each run, reseed the app's database and refresh planner statistics. This deletes app data.

```text
.\gradlew.bat seed -PseedTarget=baseline
docker exec -e PGPASSWORD=chirp grails8-chirp-postgres psql -U chirp -d chirp_baseline -c "ANALYZE;"
```

For the optimized app (steps 6 and 7) use `-PseedTarget=optimized`, `chirp_optimized`, readiness at `http://localhost:8081/api/ready`, and `-PbaseUrl=http://localhost:8081` in both commands below. Then warm every handle and measure:

```text
.\gradlew.bat warm -PbaseUrl=http://localhost:8080 -Paccounts=2000 -Pconcurrency=20
.\gradlew.bat loadTest -PbaseUrl=http://localhost:8080 -Pusers=40 -PrampSeconds=15 -PholdSeconds=60
```

Report Gatling's mean throughput for OK requests. Stop the war before starting the next one. When you are finished:

```text
docker compose -p grails8-chirp down
```

Add `-v` only when you mean to drop the database volume.

`:load-tests:generateHandles -Paccounts=2000` rewrites the weighted feeder. The seed still creates 2000 accounts. Change both together if you change the account count.
