package demo.chirp

import groovy.json.JsonSlurper
import org.grails.orm.hibernate.HibernateDatastore
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Shared
import spock.lang.Specification

class TimelineNativeQuerySpec extends Specification {
    @Shared
    PostgreSQLContainer postgres = new PostgreSQLContainer('postgres:16-alpine')

    HibernateDatastore datastore
    Long chirpId

    void setupSpec() {
        postgres.start()
    }

    void cleanupSpec() {
        postgres.stop()
    }

    void setup() {
        datastore = new HibernateDatastore([
                'dataSource.url': postgres.jdbcUrl,
                'dataSource.driverClassName': 'org.postgresql.Driver',
                'dataSource.username': postgres.username,
                'dataSource.password': postgres.password,
                'dataSource.dbCreate': 'create-drop',
                'hibernate.dialect': 'org.hibernate.dialect.PostgreSQLDialect',
                'hibernate.generate_statistics': true,
                'hibernate.cache.use_second_level_cache': false
        ], Account, Chirp, Follow, Cheer, ChirpTag, Mention, Media)
    }

    void cleanup() {
        datastore.close()
    }

    void 'native timeline keeps a comma in a media URL and matches the GORM timestamp'() {
        given:
        AccountDataService accounts = datastore.getService(AccountDataService)
        TimelineDataService pages = datastore.getService(TimelineDataService)
        ChirpReadService reader = new ChirpReadService(accountDataService: accounts, timelineDataService: pages)
        Date posted = Date.from(java.time.Instant.parse('2026-01-02T04:00:00Z'))
        Account.withTransaction {
            Account alice = new Account(handle: 'alice', displayName: 'Alice', bio: 'hello').save(failOnError: true)
            Account bob = new Account(handle: 'bob', displayName: 'Bob', bio: 'there').save(failOnError: true)
            new Follow(follower: alice, followee: bob).save(failOnError: true)
            Chirp chirp = new Chirp(author: bob, body: 'hello', postedAt: posted).save(failOnError: true)
            new Media(chirp: chirp, url: 'https://cdn.example/a,b.jpg').save(failOnError: true)
            new ChirpTag(chirp: chirp, tag: 'grails,core').save(flush: true, failOnError: true)
            chirpId = chirp.id
        }
        Date reloaded
        Account.withTransaction {
            reloaded = Chirp.get(chirpId).postedAt
        }

        when:
        datastore.sessionFactory.statistics.clear()
        Map json = (Map) new JsonSlurper().parseText(reader.timeline('alice', 20))
        String gormPosted = new TimelineRow(chirpId, 'x', reloaded, 'bob', 'Bob').asPostJson().postedAt

        then:
        json.chirps.size() == 1
        json.chirps[0].media == ['https://cdn.example/a,b.jpg']
        json.chirps[0].hashtags == ['grails,core']
        json.chirps[0].postedAt == gormPosted
        datastore.sessionFactory.statistics.prepareStatementCount == 2
    }
}
