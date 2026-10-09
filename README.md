# Grails 8 scaling performance

Two Grails 8.0.0 apps, one schema, one seed. `baseline` is a deliberately naive read path: dynamic finders, then a query per association per row. `optimized` returns the same fields from a rendered-JSON cache, and a miss is two database statements. This is not a claim that every Grails application is as slow as the baseline.

The table is one run on one laptop, not a capacity rating. Both apps used the same Gatling client: 40 looping users, shared connections, 15 second ramp, 60 second hold, one JVM at a time. The optimized app had every handle warmed immediately before the clock. Its cache expires 20 seconds after write, so that measured minute includes refreshes. Both runs had 0% KO.

| App | Requests | OK req/s | p50 | p75 | p95 | p99 | Max |
|---|---:|---:|---:|---:|---:|---:|---:|
| Baseline, JDK 21 | 8,414 | 105.2 | 341 ms | 377 ms | 430 ms | 573 ms | 4.0 s |
| Optimized, JDK 25, cache warm | 88,799 | 1,110.0 | 19 ms | 32 ms | 46 ms | 82 ms | 7.3 s |

That is 10.6x on this client. Eighteen optimized requests out of 88,799 were over 1.2 seconds. Report the Gatling "OK req/s" number. KO requests are not throughput.

## What the optimized app does differently

- A hit returns a rendered JSON string from a Caffeine `AsyncLoadingCache`. It does not touch the database. Entries expire 20 seconds after write and refresh after 10 seconds. Writes do not delete cache entries, so a read can be up to 20 seconds behind a write.
- A miss looks up the account with a GORM Data Service `@Query`, then runs one Postgres statement for the page: followees, the top 20 chirps per author, cheer count, reply count, tags, mentions, media, and the reply-to handle.
- The cache load does not hold a map lock across JDBC.
- The pool size is set in `ChirpHikariDataSource` from `CHIRP_POOL_SIZE` (default 16). `dataSource.properties` in YAML does not reach Hikari on this app.
- The baseline list is ordered by `postedAt` only. The optimized page also breaks ties by id, and its child lists are ordered by id. Treat that as a contract difference, not as part of the throughput gap.

The optimized JVM also has Java 25, virtual threads, `compileStatic` on controllers, services, tag libraries, and GSP, Hibernate ORM 7.4.10.Final, and open session in view turned off. Those were on together. This comparison did not turn them off one at a time, so do not cite them as the 10.6x. Compact object headers, JDK 26, JDK 27, and the Grails AOT cache were not part of the measured run. AOT cache training refuses to run on Windows. The second-level cache and the query cache are off.

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

Start that one war. Use `grails.env=loadtest`. The `production` block keeps the Forge empty password and port 5432. Heap for the table was `-Xms1g -Xmx1g -XX:+UseG1GC`.

```text
java -Dgrails.env=loadtest -Xms1g -Xmx1g -XX:+UseG1GC -jar baseline\build\libs\chirp-baseline-0.1.war --spring.profiles.active=loadtest
java -Dgrails.env=loadtest -Xms1g -Xmx1g -XX:+UseG1GC -jar optimized\build\libs\chirp-optimized-0.1.war --spring.profiles.active=loadtest
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
