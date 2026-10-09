# Grails 8 scaling performance

Two Grails 8.0.0 apps, one schema, one seed. `baseline` is a deliberately naive read path: dynamic finders, then a query per association per row. `optimized` returns the same fields from a rendered-JSON cache, and a miss is two database statements. This is not a claim that every Grails application is as slow as the baseline.

Use the quiet table below in the talk. It is three runs each, Gatling at WARN, on a 12th Gen Intel Core i7-12800H (14 cores, 20 logical processors, 64 GB). The app JVM is started with `-XX:ActiveProcessorCount=12` and Gatling with `-XX:ActiveProcessorCount=6`. That changes how many processors each JVM reports. It does not reserve CPUs. Both processes can still run on any of the 20. Same client: 40 looping users, shared connections, 15 second ramp, 60 second hold, one JVM at a time. Gatling's explicit JVM args replace the plugin defaults, including its 1 GB heap cap. Report OK req/s. The fast path spreads from about 2,600 to 4,600 because cache entries expire after 20 seconds, so quote the mean and the spread, not one minute.

| Step | Mean OK req/s | Versus baseline |
|---|---:|---|
| Baseline, naive reads | 109 |  |
| Full stack, cache on | 3,449 | 32x |

An earlier single run, taken while Gatling logged every request at DEBUG and neither JVM set ActiveProcessorCount, was 105.2 versus 1,110 OK req/s (10.6x). That client log cut the fast path roughly in half and did not move the baseline. Do not use 10.6x in the talk.

## Isolated runs

Same client, same 12/6 processor split, three runs each, Gatling logging at WARN. Report OK req/s. An earlier set was taken while Gatling logged every request at DEBUG. That cut the fast path roughly in half and did not move the baseline. Those numbers are retired. Do not use them in the talk.

| Step | Run 1 | Run 2 | Run 3 | Mean | Versus baseline mean 109 |
|---|---:|---:|---:|---:|---|
| Baseline, naive reads | 94.1 | 116.8 | 115.7 | 108.9 |  |
| Full stack, cache on, warmed | 4,560 | 3,220 | 2,567 | 3,449 | 31.7x |
| Same stack, no warm-up | 4,416 | 3,211 | 2,683 | 3,437 | Inside the full-stack spread |
| Same stack, HTTP virtual threads off | 4,258 | 2,798 | 4,378 | 3,812 | Inside the spread. Not a loss. |
| Same stack, pool max 10 instead of 16 | 3,592 | 4,287 | 2,916 | 3,599 | Inside the spread |
| One SQL query, cache off | collapsed |  |  |  | Not rerun quietly. The logged run was 5.1 OK req/s with 7.7% timeouts. The failure was the server, not the client log. |

The fast path spreads from about 2,600 to 4,600 OK req/s from one minute to the next, because entries expire after 20 seconds. Warm-up, HTTP virtual threads, and pool size 10 versus 16 all land inside that spread. Do not give them their own percents. The HTTP-thread run set `CHIRP_VIRTUAL_THREADS=false`. Cache loads still use a virtual-thread executor.

The change that is not inside the noise is the rendered-JSON cache. Without it, 40 users time out. The one-query miss path is what fills a cache entry. It is not a separate throughput win at this load.

`compileStatic` was not turned off. It stays in the optimized app and is not a line in the talk.

## Machine and processor split

The quiet tables above used these reported processor counts. The retired 10.6x run did not.

| Role | Processors | What that means |
|---|---:|---|
| This laptop | 14 cores, 20 logical processors, 64 GB RAM | 12th Gen Intel Core i7-12800H |
| Application JVM | reports 12 | `-XX:ActiveProcessorCount=12`, heap 1 GB, G1. Not a CPU reservation. |
| Gatling JVM | reports 6 | `-XX:ActiveProcessorCount=6`, set by `loadTest`. Heap is not capped at 1 GB. |
| Windows and Postgres | not reserved | Do not start a second app JVM |

Gatling's 40 users are closed-loop virtual users in a JVM that reports 6 processors, not 40 operating-system threads and not a CPU reservation. `warm` uses 20 concurrent HTTP calls and is only the cache fill. It is not the measured client. Use the same reported counts for baseline and optimized. Do not change them between runs.

## What the optimized app does differently

- A hit returns a rendered JSON string from a Caffeine `AsyncLoadingCache`. It does not touch the database. Entries expire 20 seconds after write and refresh after 10 seconds. Writes do not delete cache entries, so a read can be up to 20 seconds behind a write.
- A miss looks up the account with a GORM Data Service `@Query`, then runs one Postgres statement for the page: followees, the top 20 chirps per author, cheer count, reply count, tags, mentions, media, and the reply-to handle.
- The cache load does not hold a map lock across JDBC.
- The pool is set in `ChirpHikariDataSource` (max 16, min idle 8). `dataSource.properties` is the documented Grails key and does not reach Hikari on 8.0.0. See https://github.com/apache/grails-core/issues/16567.
- The baseline list is ordered by `postedAt` only. The optimized page also breaks ties by id, and its child lists are ordered by id. Treat that as a contract difference, not as part of the throughput gap.

The optimized JVM also has Java 25, virtual threads, `compileStatic` on controllers, services, tag libraries, and GSP, Hibernate ORM 7.4.10.Final, and open session in view turned off. The quiet reruns turned HTTP virtual threads off, skipped the warm-up, and cut the pool from 16 to 10. Cache loads still used virtual threads. None of those moved the number outside the run-to-run spread. Do not cite them as part of the 32x. Compact object headers, JDK 26, JDK 27, and the Grails AOT cache were not part of the measured run. AOT cache training refuses to run on Windows. The second-level cache and the query cache are off.

## How to reproduce the table

Prerequisites: Docker, JDK 21 for Gatling, JDK 25 for the optimized war. Run Gradle itself on JDK 21 when you run `loadTest`, because the Gatling plugin forks with the Gradle JVM. Measure one app at a time. Two JVMs plus Gatling plus Postgres on one laptop steal the result.

Start Postgres:

```text
docker compose -p grails8-chirp up -d
```

Build the war you are measuring:

```text
.\gradlew.bat :baseline:bootWar
.\gradlew.bat :optimized:bootWar
```

Start that one war. Use `grails.env=loadtest`. The `production` block keeps the Forge empty password and port 5432. Heap is `-Xms1g -Xmx1g -XX:+UseG1GC`. The application JVM reports 12 processors. Gatling, started by `loadTest`, reports 6. Neither setting reserves CPUs. Use the same flags for every comparison run. Do not measure both apps at once.

```text
java -Dgrails.env=loadtest -Xms1g -Xmx1g -XX:+UseG1GC -XX:ActiveProcessorCount=12 -jar baseline\build\libs\chirp-baseline-0.1.war --spring.profiles.active=loadtest
java -Dgrails.env=loadtest -Xms1g -Xmx1g -XX:+UseG1GC -XX:ActiveProcessorCount=12 -jar optimized\build\libs\chirp-optimized-0.1.war --spring.profiles.active=loadtest
```

Wait until `http://localhost:8080/api/ready` or `http://localhost:8081/api/ready` returns 200. Then seed only the database for the app that is running. This deletes app data. It does not apply schema DDL. It uses `psql` inside the `grails8-chirp-postgres` container.

```text
.\gradlew.bat seed -PseedTarget=baseline
.\gradlew.bat seed -PseedTarget=optimized
```

`-PseedTarget=both` waits for 8080 and 8081 and seeds each database once. `-DseedTarget` is accepted too. Do not pass both `-PseedTarget` and `-DseedTarget` on the same command.

Warm every handle before the measured minute. This is the step the table used. A Gatling run does not enumerate the feeder, and a 20 second expiry means you should start the measured run as soon as warm finishes. Do not reseed between warm and measure.

```text
.\gradlew.bat warm -PbaseUrl=http://localhost:8080 -Paccounts=2000 -Pconcurrency=20
.\gradlew.bat warm -PbaseUrl=http://localhost:8081 -Paccounts=2000 -Pconcurrency=20
```

Then measure. `-PbaseUrl` and `-DbaseUrl` select the same app for the readiness check and for Gatling.

```text
.\gradlew.bat loadTest -PbaseUrl=http://localhost:8080 -Pusers=40 -PrampSeconds=15 -PholdSeconds=60
.\gradlew.bat loadTest -PbaseUrl=http://localhost:8081 -Pusers=40 -PrampSeconds=15 -PholdSeconds=60
```

Stop the war before starting the other one. When you are finished:

```text
docker compose -p grails8-chirp down
```

Add `-v` only when you mean to drop the database volume.

`bootRun` is the iterate path, not the path that produced the table:

```text
.\gradlew.bat :baseline:bootRun -Dgrails.env=loadtest --args=--spring.profiles.active=loadtest
.\gradlew.bat :optimized:bootRun -Dgrails.env=loadtest --args=--spring.profiles.active=loadtest
```

`:load-tests:generateHandles -Paccounts=2000` rewrites the weighted feeder. The seed still creates 2000 accounts. Change both together if you change the account count.
