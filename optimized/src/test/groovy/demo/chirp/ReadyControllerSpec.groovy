package demo.chirp

import grails.testing.web.controllers.ControllerUnitTest
import spock.lang.Specification

class ReadyControllerSpec extends Specification implements ControllerUnitTest<ReadyController> {
    void 'readiness works without a datastore'() {
        when:
        controller.index()

        then:
        response.status == 200
        response.json == [status: 'up']
    }
}
