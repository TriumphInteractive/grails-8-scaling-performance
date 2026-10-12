# Build switch verification

Commands run from `<home>\Documents\IdeaProjects\grails-8-scaling-performance`. Gradle JAVA_HOME is `C:\Program Files\Amazon Corretto\jdk21.0.11_10`, with its bin prepended to Path in each build/check call. javap uses that same JDK. All WAR builds use `--rerun-tasks`. No load, warm, seed, app launch, or Postgres operation is part of this task.

Context7 documentation lookup returned: `Monthly quota exceeded`. The user-supplied Grails plugin behavior and local build/bytecode evidence are used for this fix.

Build and check results follow as they complete.

## naive

Command: `.\gradlew.bat :baseline:bootWar -PchirpCompileStatic=false -PgroovyIndy=false --rerun-tasks`.

Exit code: 0. Log: `naive-build.log`.

WAR: `naive.war`. SHA256: `D430D5F659A5F69883F1A273282C163AB4D7127B0872D2735D6606AF24CAE3FE`.

| Class | CallSite invocations | invokedynamic instructions | CallSite array present |
|---|---:|---:|---|
| TimelineController | 214 | 0 | True |
| NaiveChirpJsonService | 19 | 0 | True |

Bytecode was captured immediately after this build. Commands: `javap.exe -c -p <baseline\build\classes\groovy\main\demo\chirp\TimelineController.class>` and the corresponding `NaiveChirpJsonService.class`. Full outputs: `naive-TimelineController-javap.txt`, `naive-NaiveChirpJsonService-javap.txt`. Serializer method extract: `naive-service-of.txt`.

## naive-indy

Command: `.\gradlew.bat :baseline:bootWar -PchirpCompileStatic=false -PgroovyIndy=true --rerun-tasks`.

Exit code: 0. Log: `naive-indy-build.log`.

WAR: `naive-indy.war`. SHA256: `BBA78937C99303362AD3ACCF275348FB4C256881FC61ACA9A7135F485C86EA47`.

| Class | CallSite invocations | invokedynamic instructions | CallSite array present |
|---|---:|---:|---|
| TimelineController | 0 | 379 | False |
| NaiveChirpJsonService | 0 | 20 | False |

Bytecode was captured immediately after this build. Commands: `javap.exe -c -p <baseline\build\classes\groovy\main\demo\chirp\TimelineController.class>` and the corresponding `NaiveChirpJsonService.class`. Full outputs: `naive-indy-TimelineController-javap.txt`, `naive-indy-NaiveChirpJsonService-javap.txt`. Serializer method extract: `naive-indy-service-of.txt`.

## static

Command: `.\gradlew.bat :baseline:bootWar -PchirpCompileStatic=true -PgroovyIndy=false --rerun-tasks`.

Exit code: 0. Log: `static-build.log`.

WAR: `static.war`. SHA256: `80B6A9EE3554D87F79946B095FB3FC9ED7CC91D05E2F20EC306CF75DBE221FBC`.

| Class | CallSite invocations | invokedynamic instructions | CallSite array present |
|---|---:|---:|---|
| TimelineController | 4 | 0 | True |
| NaiveChirpJsonService | 2 | 0 | True |

Bytecode was captured immediately after this build. Commands: `javap.exe -c -p <baseline\build\classes\groovy\main\demo\chirp\TimelineController.class>` and the corresponding `NaiveChirpJsonService.class`. Full outputs: `static-TimelineController-javap.txt`, `static-NaiveChirpJsonService-javap.txt`. Serializer method extract: `static-service-of.txt`.

## optimized

Command: `.\gradlew.bat :optimized:bootWar --rerun-tasks`.

Exit code: 0. Log: `optimized-build.log`.

WAR: `optimized.war`. SHA256: `338138A59D4BD3C8090ECA98C64622999996013C2A2605BF872D923DA4A733B9`.

## test

Command: `.\gradlew.bat :baseline:test --console=plain`.

Exit code: 0. Log: `test-build.log`.

## Compile helper invocation failure

Command: `.\gradlew.bat :optimized:compileGroovy`.

Exit code: 1. Log: `compile-build.log`.

The command shown above was intended, but the helper splatted a scalar string instead of an argument array. Gradle reported `Missing task name` and `Cannot locate tasks that match ':'. The path should include a task name (for example ':help' or 'help').` No optimized compile task executed in this invocation. The helper was corrected to retain an array for single-task commands. The proper task invocation below succeeded; the failure log is retained.

## compile

Command: `.\gradlew.bat :optimized:compileGroovy`.

Exit code: 0. Log: `compile-corrected-build.log`.

## Acceptance results

- PASS: naive has CallSite invocations in both classes (TimelineController 214; NaiveChirpJsonService 19) and zero invokedynamic instructions in both.
- PASS: naive-indy has invokedynamic instructions in both classes (TimelineController 379; NaiveChirpJsonService 20), zero CallSite invocations in both, and a SHA256 different from naive.war.
- PASS: static NaiveChirpJsonService.of has exactly two CallSite invocations, with site names countByChirp and countByReplyTo. Field access uses direct getters; collection calls use DefaultGroovyMethods.collect. Its three closure classes have zero CallSite invocations, zero invokedynamic instructions, and no $getCallSiteArray; javap shows direct ChirpTag.getTag, Mention.getMentioned / Account.getHandle, and Media.getUrl calls. Evidence: static-service-of.txt and static-service-closure1/2/3-javap.txt.
- FAIL for the literal controller-only-finder criterion: static TimelineController has four CallSite invocations, not solely the three finders. The site-name array is findByHandle, findAllByFollower, findAllByAuthorInList, invoke. The fourth invocation is in the Grails-generated exception-handler dispatch: getExceptionHandlerMethodFor at bytecode offset 655, followed by dynamic invoke at offset 686. Normal property access, collect, and render are statically dispatched. This generated exception path was not changed.
- PASS: optimized bootWar succeeded with --rerun-tasks, and the proper optimized compileGroovy check succeeded (UP-TO-DATE after the rebuilt WAR).
- PASS for command execution only: baseline:test succeeded, but the task reported NO-SOURCE. There are no executed baseline unit tests to claim passed.
- PASS: git diff --check reported no whitespace errors. Groovy LSP diagnostics were unavailable because no Groovy server is configured; real builds are the compilation evidence.

## Changes in this task

- baseline/build.gradle: removed the overwritten task-level indy setting and comment; added the property-driven Grails extension setting outside the existing compileStatic condition.
- optimized/build.gradle: removed the redundant task-level indy setting; explicitly set Grails extension indy=false with the requested comment.
- Moved baseline/grails-app/controllers/demo/chirp/NaiveChirpJson.groovy to baseline/grails-app/services/demo/chirp/NaiveChirpJsonService.groovy; renamed the class and typed only the three association collect closure parameters.
- Updated TimelineController and AccountController to call NaiveChirpJsonService.of.
- Serializer fields remain ordered as id, body, postedAt, authorHandle, authorName, cheerCount, replyCount, inReplyToHandle, hashtags, mentions, media. Both count finder queries and all association access remain unchanged; no joins, fetch modes, batching, or caching were added.
- Existing working-tree modifications, including README and other build/config files, were preserved. No commit, load, warm, seed, app launch, Postgres operation, or process termination was performed.
