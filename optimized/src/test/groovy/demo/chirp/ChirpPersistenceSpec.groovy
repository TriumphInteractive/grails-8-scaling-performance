package demo.chirp

import groovy.json.JsonSlurper
import org.grails.orm.hibernate.HibernateDatastore
import spock.lang.Specification
import spock.lang.Unroll

class ChirpPersistenceSpec extends Specification {
    HibernateDatastore datastore
    ChirpReadService reader
    ChirpWriteService writer
    Long chirpId

    void setup() {
        datastore = new HibernateDatastore([
                'dataSource.url': "jdbc:h2:mem:chirp${UUID.randomUUID().toString().replace('-', '')}",
                'dataSource.driverClassName': 'org.h2.Driver',
                'dataSource.username': 'sa',
                'dataSource.password': '',
                'dataSource.dbCreate': 'create-drop',
                'hibernate.dialect': 'org.hibernate.dialect.H2Dialect',
                'hibernate.generate_statistics': true,
                'hibernate.cache.use_second_level_cache': false
        ], Account, Chirp, Follow, Cheer, ChirpTag, Mention, Media)
        AccountDataService accounts = datastore.getService(AccountDataService)
        reader = new ChirpReadService(accountDataService: accounts,
                followDataService: datastore.getService(FollowDataService))
        writer = new ChirpWriteService(accountDataService: accounts)
        Account.withTransaction {
            Account alice = new Account(handle: 'alice', displayName: 'Alice', bio: 'hello').save(flush: true, failOnError: true)
            Account bob = new Account(handle: 'bob', displayName: 'Bob').save(flush: true, failOnError: true)
            new Account(handle: 'empty', displayName: 'Empty').save(flush: true, failOnError: true)
            new Follow(follower: alice, followee: bob).save(flush: true, failOnError: true)
            Chirp chirp = new Chirp(author: bob, body: 'first', postedAt: new Date(1000)).save(flush: true, failOnError: true)
            chirpId = chirp.id
            new Chirp(author: bob, body: 'second', postedAt: new Date(1000)).save(flush: true, failOnError: true)
            new Cheer(account: alice, chirp: chirp).save(flush: true, failOnError: true)
        }
        datastore.sessionFactory.statistics.clear()
    }

    void cleanup() {
        datastore?.close()
    }

    void 'timeline executes a bounded set of queries with grouped child rows'() {
        when:
        Map json = parse(reader.timeline('alice', 20))

        then:
        json.handle == 'alice'
        json.chirps*.body == ['second', 'first']
        json.chirps*.authorHandle == ['bob', 'bob']
        json.chirps*.authorName == ['Bob', 'Bob']
        json.chirps*.postedAt == ['1970-01-01T00:00:01Z', '1970-01-01T00:00:01Z']
        json.chirps*.cheerCount == [0, 1]
        datastore.sessionFactory.statistics.prepareStatementCount == 9
    }

    void 'account executes a bounded set of queries regardless of recent chirp count'() {
        when:
        Map json = parse(reader.account('bob'))

        then:
        json.handle == 'bob'
        json.displayName == 'Bob'
        json.bio == null
        json.chirpCount == 2
        json.followingCount == 0
        json.followerCount == 1
        json.recentChirps*.body == ['second', 'first']
        json.recentChirps*.cheerCount == [0, 1]
        datastore.sessionFactory.statistics.prepareStatementCount == 11
    }

    void 'empty followee list returns an empty timeline without in clause'() {
        expect:
        parse(reader.timeline('empty', 20)) == [handle: 'empty', chirps: []]
        datastore.sessionFactory.statistics.prepareStatementCount == 2
    }

    void 'query count stays bounded when the timeline and account limits are reached'() {
        given:
        Account.withTransaction {
            Account bob = Account.findByHandle('bob')
            for (int index = 0; index < 30; index++) {
                new Chirp(author: bob, body: "chirp ${index}", postedAt: new Date(2000))
                        .save(flush: true, failOnError: true)
            }
        }
        datastore.sessionFactory.statistics.clear()

        when:
        Map timeline = parse(reader.timeline('alice', 20))
        long timelineQueries = datastore.sessionFactory.statistics.prepareStatementCount
        datastore.sessionFactory.statistics.clear()
        Map account = parse(reader.account('bob'))

        then:
        timeline.chirps.size() == 20
        timelineQueries == 9
        account.chirpCount == 32
        account.recentChirps.size() == 10
        datastore.sessionFactory.statistics.prepareStatementCount == 11
    }

    void 'missing account throws before querying chirps'() {
        when:
        reader.timeline('missing', 20)

        then:
        thrown(AccountNotFoundException)
        datastore.sessionFactory.statistics.prepareStatementCount == 1
    }

    @Unroll
    void 'write validates body #body with status #status'() {
        when:
        ChirpWriteResult result = writer.save('alice', body)

        then:
        result.status == status
        datastore.sessionFactory.statistics.entityInsertCount == (status == 201 ? 1 : 0)
        if (status == 201) {
            Map json = parse(result.json)
            assert json.body == body
            assert json.authorHandle == 'alice'
            assert json.authorName == 'Alice'
            assert json.cheerCount == 0
            assert json.postedAt ==~ /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z/
        } else {
            assert parse(result.json) == [error: 'invalid']
        }

        where:
        body       | status
        'hello'    | 201
        ''         | 422
        '   '      | 422
        'x' * 281  | 422
        null       | 422
        42         | 422
    }

    void 'missing handle returns 404 without inserting'() {
        expect:
        writer.save(null, 'hello').status == 404
        writer.save('missing', 'hello').status == 404
        datastore.sessionFactory.statistics.entityInsertCount == 0
    }

    private Map parse(String json) {
        (Map) new JsonSlurper().parseText(json)
    }
}
