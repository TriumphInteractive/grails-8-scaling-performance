# Grails 8 scaling performance

Two Grails 8.0.0 apps, one schema, one seed, one load test. `baseline` is a deliberately naive read path: dynamic finders, then a query per association per row. `optimized` returns the same fields from one hand-written SQL statement per timeline page, then from a rendered-JSON cache. The baseline also carries startup switches for three GORM fetch optimizations, so every step below runs from this one tree. This is not a claim that every Grails application is as slow as the baseline.

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

Steps 3 to 7 run on Corretto 25 with `compileStatic`, held constant, because those two do not move this workload (see [What did not help](#what-did-not-help-on-this-workload)). The same war as steps 3 to 5 with every GORM switch off measured 192, 220, and 211 (mean 208), and step 3's lift is against that control. Step 4 shows two runs because another job on the machine overlapped the third, which is excluded. Step 6 likewise excludes one overlapped run. Step 7 swings widely between runs (see step 7), so quote the mean and the range.

SQL statements per 20-chirp timeline page, counted from the Hibernate SQL log:

| Step | Statements per page |
|---|---:|
| Naive, every switch off | 116 |
| 3, global batch fetch | 59 |
| 4, plus entity batch size | 47 |
| 5, plus grouped counts | 9 |
| 6, hand-written query (cache miss) | 2 |

With steps 3, 4, and 5 switched on, the naive app returns JSON byte-identical to the same war with every switch off, on both the timeline and account endpoints (SHA256 compared). Step 6 is a separate implementation with a slightly different ordering contract (see step 6), so it is not byte-compared with the naive app.

## Each optimization in detail

### 1. The starting point: naive GORM and the stock pool

The timeline controller does what a first version of a Grails app often does:

```groovy
def account = Account.findByHandle(params.handle)
def follows = Follow.findAllByFollower(account)
def authors = follows.collect { it.followee }
def chirps = Chirp.findAllByAuthorInList(authors, [max: max, sort: 'postedAt', order: 'desc'])
def timeline = NaiveChirpJsonService.page(chirps)
```

Then, for each of the 20 chirps, `NaiveChirpJsonService` touches the author proxy, runs `Cheer.countByChirp(chirp)` and `Chirp.countByReplyTo(chirp)`, follows `replyTo` and its author, and walks three lazy collections: `tags`, `mentions` (and each mentioned account), and `media`. That is 116 SQL statements for one page. Open session in view is on, the Grails default, so the Hibernate session stays open while all of that runs.

The pool is Hikari's default: 10 connections, 30 second connection timeout. That is what a stock Grails 8.0.0 app gets, because the documented `dataSource.properties` keys (`maximumPoolSize` and friends) do not reach Hikari on 8.0.0. See https://github.com/apache/grails-core/issues/16567.

Result: 132 req/s.

### 2. Size the pool above the concurrency

Each in-flight request holds a database connection for its whole N+1 render. With 40 users and 10 connections, 30 requests are always waiting in line for a connection, not doing work. Raising the pool to 48, above the 40 concurrent requests, removes the queue. The SQL is unchanged at 116 statements per page; the same work simply stops waiting.

Because the YAML pool keys are ignored on 8.0.0, both apps select a small `HikariDataSource` subclass that sizes itself:

```groovy
class ChirpHikariDataSource extends HikariDataSource {
    ChirpHikariDataSource() {
        int pool = integer('CHIRP_POOL_SIZE', 16)
        setMaximumPoolSize(pool)
        setMinimumIdle(Math.min(pool, integer('CHIRP_MIN_IDLE', 8)))
        setConnectionTimeout(integer('CHIRP_CONNECTION_TIMEOUT', 2000))
    }
}
```

```yaml
dataSource:
  pooled: true
  type: demo.chirp.ChirpHikariDataSource
```

Size the pool to the number of requests that hold a connection at once, not larger: every connection is a Postgres backend, and Postgres defaults to 100.

Result: 231 req/s, 1.75x.

### 3. Batch-fetch lazy collections: `default_batch_fetch_size`

One line of configuration:

```yaml
hibernate:
  default_batch_fetch_size: 50
```

The first time a chirp's `tags` collection is touched, Hibernate now loads `tags` for up to 50 chirps in the session with one `WHERE chirp_id IN (...)` query, instead of one query per chirp. The same happens for `mentions` and `media`. Three collections times 20 chirps, about 60 queries, become about three. Statements per page drop from 116 to 59.

In this repo the setting is passed at startup as `--hibernate.default_batch_fetch_size=50`, so one war can be measured with and without it.

Result: 384 req/s, 1.85x against the same-war control (208).

### 4. Batch-fetch lazy references: `batchSize` on the domain

```groovy
class Account {
    static mapping = {
        batchSize 50
    }
}
```

The same on `Chirp`. These load the single-valued references: `chirp.author`, `chirp.replyTo`, `replyTo.author`, and each mention's `mentioned` account. Each is a proxy that would otherwise load with its own query the first time it is touched. With `batchSize`, touching one loads up to 50 pending proxies of that class in one `IN` query.

Step 3 does not cover these in Grails 8. For a domain with no `batchSize` in its mapping, the GORM Hibernate 7 binder sets the entity batch size to `0`, which turns batching off for that class and overrides the global setting. Unmapped collections get `-1`, which means inherit the global setting. So the global setting batches collections, and each domain whose references you want batched needs `batchSize` in its `static mapping`.

Statements per page drop from 59 to 47. The lift is smaller than step 3 because there is less to remove: a timeline draws from one user's followees, so most authors are the same few accounts already loaded once for the page. In this repo the mapping reads its value from `-Dchirp.gorm.entityBatchSize=50`.

Result: 462 req/s, 1.2x.

### 5. Replace per-row counts with grouped projections

The remaining 47 statements are mostly the two `countBy` calls per chirp. Two GORM criteria projections fetch every count for the page at once:

```groovy
List<Long> ids = chirps.collect { Chirp chirp -> chirp.id }
Cheer.createCriteria().list {
    inList('chirp.id', ids)
    projections {
        groupProperty('chirp.id')
        count()
    }
}
Chirp.createCriteria().list {
    inList('replyTo.id', ids)
    projections {
        groupProperty('replyTo.id')
        count()
    }
}
```

Each returns `[id, count]` rows; an id with no row has a count of 0. Forty count queries become two, and statements per page drop from 47 to 9: the account, follow, and chirp list queries, the two projections, and the batched collection and reference loads. This is still GORM, not SQL, and the JSON is byte-identical. In this repo it is behind `-Dchirp.gorm.groupedCounts=true`.

Result: 1315 req/s, 2.85x. Steps 1 to 5 together are 10x without leaving GORM.

### 6. One hand-written SQL statement per timeline page

The optimized app builds the whole page in Postgres. A timeline cache miss is two statements: the account lookup through a GORM Data Service, then one native query in `TimelineDataService`:

- a CTE finds the followees;
- `CROSS JOIN LATERAL` takes the newest 20 chirps per followee, then the outer query keeps the newest 20 overall;
- correlated subqueries count cheers and replies;
- `json_agg` builds the tag, mention, and media arrays;
- one subquery fetches the reply-to handle.

The rows are rendered straight to a JSON string with `JsonOutput`, without hydrating GORM entities. Open session in view is off in this app. Account pages, 10 percent of the load, use a handful of grouped HQL queries instead of one statement.

The page orders by `posted_at`, then by `id`, and orders each child list by `id`. The naive app orders by `postedAt` only. Treat that as a contract difference, not as part of the throughput gap.

Measured as Java 21 bytecode with `compileStatic` on Corretto 25, platform threads, cache off.

Result: 2661 req/s, 2.0x.

### 7. Cache the rendered JSON

`ChirpJsonCacheService` keeps the rendered JSON string per timeline (`timeline:<handle>:<max>`) and per account page in a Caffeine `AsyncLoadingCache`:

```groovy
Caffeine.newBuilder()
        .maximumSize(20_000)
        .expireAfterWrite(Duration.ofSeconds(20))
        .refreshAfterWrite(Duration.ofSeconds(10))
        .executor(loadExecutor)
        .buildAsync { key, executor -> CompletableFuture.supplyAsync({ read(key) }, loadExecutor) }
```

A hit returns the string without touching the database or rendering JSON. After 10 seconds an entry is refreshed in the background while readers keep getting the old value; after 20 seconds it expires and the next reader loads it. Loads run on a virtual-thread executor and do not hold a map lock across JDBC. Writes do not invalidate entries, so a read can be up to 20 seconds behind a write. That is the trade for this step.

The runs swing from 3594 to 6404 req/s. A likely contributor is the 20 second expiry: a 60 second window catches a different mix of expiries and reloads each time, though cache hit rates were not recorded to prove it.

Result: 4591 req/s, 1.7x, 35x over the stock app.

## What did not help on this workload

| Change | Where measured | Result |
|---|---|---|
| Corretto 25 instead of 21, same war | naive app: 228 vs 224. Optimized, cache off: 2485 vs 2360 | Flat |
| `compileStatic` | naive app: 224 vs 221. Optimized, cache off, six runs per mode in alternating three-run blocks: 2587 dynamic vs 2509 static | Flat |
| Groovy invokedynamic on | naive app: 224 vs 221 | Flat. Grails 8 turns it off by default (`grails { indy = false }`) |
| Virtual threads | naive app: 221 vs 146. Optimized, cache off: 2661 vs 2403. Cached: within the spread | Slower or flat |
| `-XX:+UseCompactObjectHeaders` | optimized, cache off: 2661 vs 2593 (one overlapped run excluded). Cached: within the spread | Flat |

### Why the database set the pace

The load is closed-loop: 40 users, each sending its next request as soon as the last one returns. By Little's law, throughput equals concurrency divided by time per request, so 231 req/s at step 2 means each request took about 40 / 231 = 173 ms end to end. Three things point to the database as most of that time:

- **Statements per request.** A timeline page is 116 statements and an account page 55. With the 85/10/5 mix that averages about 104 SQL round trips per request, each one a trip from the JVM through Docker's port forward to Postgres and back. Spread over 173 ms, that is roughly 1.7 ms per statement. That figure is an end-to-end average: it includes query execution, transport, and the application work between statements, not database time alone.
- **The pool was the first lift.** Going from 10 to 48 connections, with no change to the code or the JVM, gave 1.75x. Requests had been queuing for database connections, not for CPU.
- **Cutting statements gave most of the other lifts.** Steps 3 to 5 change only how many statements a page needs, 116 down to 59, 47, and 9, and throughput rises with each cut. Step 6 also cuts statements, to 2 per timeline page, but it changes more than that: it renders straight from SQL rows instead of hydrating GORM entities, and open session in view is off.

That is an inference from these comparisons, not a measured breakdown of where each millisecond goes. Against it, the JVM-level changes only make the Java and Groovy part of a request faster:

- **Java 25** brings JIT and GC improvements, which lower CPU time per request.
- **`compileStatic`** replaces Groovy's dynamic dispatch with direct calls, again CPU time.
- **Compact object headers** shrink objects, which lowers memory traffic and GC.

On this workload those savings stay inside the run-to-run variation. A repeat of the same naive configuration varied by up to 7 percent, and one optimized cache-off configuration moved 15 percent between consecutive runs (2483 to 2860 req/s). The Java version, `compileStatic`, invokedynamic, and compact-header differences in the table above are all smaller than that.

Virtual threads solve a different shortage: running out of threads. Tomcat has 200 platform threads and there are only 40 requests in flight, so no request ever waits for a thread. The queue is for database connections and database time, and virtual threads do not add either. Here they only added per-request cost, which shows on the naive app (221 down to 146 req/s).

At step 6 the mixed workload averages about 40 / 2661 = 15 ms per request end to end. A timeline read there is two statements, one of them a heavier query with `LATERAL`, correlated counts, and `json_agg`; an account read is a handful of grouped queries. Only the cache in step 7 takes the database off the hot path for most requests.

## How it was measured

| Role | Detail |
|---|---|
| Machine | 12th Gen Intel Core i7-12800H, 14 cores, 20 logical processors, 64 GB RAM, Windows |
| Application JVM | Amazon Corretto 21.0.11 or 25.0.4.1, resolved by Gradle toolchains and Foojay. `-Xms1g -Xmx1g -XX:+UseG1GC -XX:ActiveProcessorCount=12` |
| Gatling JVM | Corretto 21, `-XX:ActiveProcessorCount=6`, logging at WARN |
| Load | 40 closed-loop users: 85 percent timeline reads, 10 percent account reads, 5 percent chirp creates |
| Database | Postgres 16 in Docker, host port 5438 |
| Pool | 48 connections, 48 minimum idle, 2 second connection timeout, except step 1 (10, 10, 30 seconds) |

`ActiveProcessorCount` changes how many processors each JVM reports. It does not reserve CPUs. One app ran at a time, on a fresh JVM per step. The runner checked for other projects' test JVMs before and after every run, and runs that overlapped one are excluded and noted.

## Switches

Build switches. `-PchirpCompileStatic` and `-PchirpJava` default to `true` and `25`.

| Switch | Effect |
|---|---|
| `.\gradlew.bat :baseline:bootWar -PchirpCompileStatic=false` | Naive app, dynamic Groovy. Steps 1 and 2 use this war |
| `.\gradlew.bat :baseline:bootWar -PchirpCompileStatic=false -PgroovyIndy=true` | Naive app with invokedynamic |
| `.\gradlew.bat :baseline:bootWar` | Naive app with `compileStatic`. Steps 3 to 5 and their control use this war |
| `.\gradlew.bat :optimized:bootWar -PchirpJava=21` | Optimized app as Java 21 bytecode, runs on JDK 21 and 25. Steps 6 and 7 use this war |
| `.\gradlew.bat :optimized:bootWar -PchirpJava=21 -PchirpCompileStatic=false` | Optimized app, dynamic Groovy |
| `.\gradlew.bat correttoHomes` | Prints the Corretto 21 and 25 homes the toolchain resolver selected |

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

System properties such as `-Dchirp.gorm.entityBatchSize=50` go before `-jar`; `--` arguments go after the war.

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
