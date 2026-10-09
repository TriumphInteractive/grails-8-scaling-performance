package demo.chirp

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Specification
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ChirpJsonSpec extends Specification {
    void 'projection JSON preserves the baseline UTC shape and Long cheer count'() {
        given:
        TimelineRow row = new TimelineRow(12L, 'hello "world"', Date.from(java.time.Instant.parse('2026-01-02T03:04:05.678Z')), 'alice', 'Alice')

        when:
        Map json = (Map) new JsonSlurper().parseText(JsonOutput.toJson(row.asJson(0L)))

        then:
        row.asPostJson().keySet() == ['id', 'body', 'postedAt', 'authorHandle', 'authorName', 'cheerCount'] as Set
        json == [id: 12, body: 'hello "world"', postedAt: '2026-01-02T03:04:05Z',
                 authorHandle: 'alice', authorName: 'Alice', cheerCount: 0,
                 replyCount: 0, inReplyToHandle: null, hashtags: [], mentions: [], media: []]
        row.asJson(2L).cheerCount instanceof Long
    }

    void 'native lists keep commas and timestamps use the caller zone'() {
        expect:
        NativeJson.list('["https://cdn.example/a,b.jpg","plain"]') == ['https://cdn.example/a,b.jpg', 'plain']
        NativeJson.list('[]').isEmpty()
        NativeJson.list(null).isEmpty()
        LocalDateTime local = LocalDateTime.of(2026, 1, 2, 4, 0)
        NativeJson.toDate(local, ZoneId.of('America/New_York')).toInstant() == local.atZone(ZoneId.of('America/New_York')).toInstant()
        NativeJson.toDate(local, ZoneId.of('America/New_York')).toInstant() != local.toInstant(ZoneOffset.UTC)
    }

    void 'rendered JSON expires 20 seconds after it is written'() {
        given:
        def expiry = new ChirpJsonCacheService().cache.synchronous().policy().expireAfterWrite()

        expect:
        expiry.present
        expiry.get().expiresAfter == Duration.ofSeconds(20)
    }

    void 'cache stores rendered strings and does not reload an account after a write'() {
        given:
        ChirpReadService reader = Mock()
        ChirpJsonCacheService cache = new ChirpJsonCacheService(chirpReadService: reader)

        when:
        String first = cache.timeline('alice', 20)
        String second = cache.timeline('alice', 20)
        cache.account('alice')
        cache.account('bob')
        cache.account('alice')
        cache.account('bob')
        cache.timeline('alice', 20)

        then:
        first == '{"handle":"alice","chirps":[]}'
        second == first
        1 * reader.timeline('alice', 20) >> '{"handle":"alice","chirps":[]}'
        1 * reader.account('alice') >> '{"handle":"alice"}'
        1 * reader.account('bob') >> '{"handle":"bob"}'
        0 * reader._
    }

    void 'cache disabled goes directly to reader'() {
        given:
        ChirpReadService reader = Mock()
        ChirpJsonCacheService cache = new ChirpJsonCacheService(chirpReadService: reader, enabled: false)

        when:
        cache.timeline('alice', 1)
        cache.timeline('alice', 1)
        cache.account('alice')
        cache.account('alice')

        then:
        2 * reader.timeline('alice', 1) >> '{"handle":"alice","chirps":[]}'
        2 * reader.account('alice') >> '{"handle":"alice"}'
        cache.cache.synchronous().estimatedSize() == 0
    }

    void 'missing account failures are never cached'() {
        given:
        ChirpReadService reader = Stub() {
            account('missing') >> { throw new AccountNotFoundException('missing') }
        }
        ChirpJsonCacheService cache = new ChirpJsonCacheService(chirpReadService: reader)

        when:
        cache.account('missing')

        then:
        thrown(AccountNotFoundException)

        when:
        cache.account('missing')

        then:
        thrown(AccountNotFoundException)
    }

    void 'concurrent same-key requests load only once'() {
        given:
        AtomicInteger loads = new AtomicInteger()
        CountDownLatch entered = new CountDownLatch(1)
        CountDownLatch release = new CountDownLatch(1)
        ChirpReadService reader = Stub() {
            timeline('alice', 20) >> {
                loads.incrementAndGet()
                entered.countDown()
                assert release.await(5, TimeUnit.SECONDS)
                '{"handle":"alice","chirps":[]}'
            }
        }
        ChirpJsonCacheService cache = new ChirpJsonCacheService(chirpReadService: reader)
        ExecutorService executor = Executors.newFixedThreadPool(8)

        when:
        List<Future<String>> requests = (1..8).collect {
            executor.submit({ -> cache.timeline('alice', 20) } as java.util.concurrent.Callable<String>)
        }
        boolean loading = entered.await(5, TimeUnit.SECONDS)
        release.countDown()
        List<String> results = requests.collect { it.get(5, TimeUnit.SECONDS) }

        then:
        loading
        loads.get() == 1
        results.every { it == '{"handle":"alice","chirps":[]}' }

        cleanup:
        release.countDown()
        executor.shutdownNow()
    }
}
