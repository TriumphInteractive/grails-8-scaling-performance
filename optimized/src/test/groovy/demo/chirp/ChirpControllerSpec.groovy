package demo.chirp

import grails.testing.web.controllers.ControllerUnitTest
import spock.lang.Specification
import spock.lang.Unroll

class ChirpControllerSpec extends Specification implements ControllerUnitTest<ChirpController> {
    @Unroll
    void 'POST renders #status and does not invalidate a cache'() {
        given:
        ChirpWriteService writer = Mock()
        controller.chirpWriteService = writer
        request.method = 'POST'
        request.contentType = 'application/json'
        request.content = '{"handle":"alice","body":"hello"}'.bytes

        when:
        controller.save()

        then:
        1 * writer.save('alice', 'hello') >> new ChirpWriteResult(status, json)
        0 * _
        response.status == status
        response.text == json

        where:
        status | json
        201    | '{"id":1,"body":"hello","postedAt":"2026-01-02T03:04:05Z","authorHandle":"alice","authorName":"Alice","cheerCount":0}'
        404    | '{"error":"not found"}'
        422    | '{"error":"invalid"}'
    }

    @Unroll
    void 'POST rejects #label with 422 and does not call the writer'() {
        given:
        ChirpWriteService writer = Mock()
        controller.chirpWriteService = writer
        request.method = 'POST'
        request.contentType = 'application/json'
        request.content = body.bytes

        when:
        controller.save()

        then:
        0 * writer._
        response.status == 422
        response.text == '{"error":"invalid"}'

        where:
        label            | body
        'malformed JSON' | '{oops'
        'an empty body'  | ''
        'a JSON array'   | '[]'
        'JSON null'      | 'null'
    }
}
