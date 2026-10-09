package demo.chirp

import grails.testing.web.controllers.ControllerUnitTest
import spock.lang.Specification
import spock.lang.Unroll

class TimelineControllerSpec extends Specification implements ControllerUnitTest<TimelineController> {
    @Unroll
    void 'max #requested becomes #expected and returns rendered JSON'() {
        given:
        ChirpJsonCacheService cache = Mock()
        controller.chirpJsonCacheService = cache
        params.handle = 'alice'
        if (requested != null) {
            params.max = requested
        }

        when:
        controller.show()

        then:
        1 * cache.timeline('alice', expected) >> '{"handle":"alice","chirps":[]}'
        response.status == 200
        response.json == [handle: 'alice', chirps: []]

        where:
        requested | expected
        null      | 20
        '0'       | 20
        '-5'      | 1
        '1'       | 1
        '50'      | 50
        '100'     | 50
    }

    void 'unknown handle returns 404'() {
        given:
        controller.chirpJsonCacheService = Stub(ChirpJsonCacheService) {
            timeline('missing', 20) >> { throw new AccountNotFoundException('missing') }
        }
        params.handle = 'missing'

        when:
        controller.show()

        then:
        response.status == 404
        response.json == [error: 'not found']
    }
}
