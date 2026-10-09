package demo.chirp

import grails.testing.web.controllers.ControllerUnitTest
import spock.lang.Specification

class AccountControllerSpec extends Specification implements ControllerUnitTest<AccountController> {
    void 'known empty account preserves the JSON contract'() {
        given:
        params.handle = 'alice'
        controller.chirpJsonCacheService = Stub(ChirpJsonCacheService) {
            account('alice') >> '{"handle":"alice","displayName":"Alice","bio":null,"chirpCount":0,"followingCount":0,"followerCount":0,"recentChirps":[]}'
        }

        when:
        controller.show()

        then:
        response.status == 200
        response.json == [handle: 'alice', displayName: 'Alice', bio: null,
                          chirpCount: 0, followingCount: 0, followerCount: 0, recentChirps: []]
    }

    void 'unknown account returns 404'() {
        given:
        params.handle = 'missing'
        controller.chirpJsonCacheService = Stub(ChirpJsonCacheService) {
            account('missing') >> { throw new AccountNotFoundException('missing') }
        }

        when:
        controller.show()

        then:
        response.status == 404
        response.json == [error: 'not found']
    }
}
