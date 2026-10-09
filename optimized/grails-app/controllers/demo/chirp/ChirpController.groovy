package demo.chirp

import groovy.json.JsonSlurper

class ChirpController {
    static allowedMethods = [save: 'POST']
    ChirpWriteService chirpWriteService

    def save() {
        Object parsed
        try {
            parsed = new JsonSlurper().parse(request.reader)
        } catch (Exception ignored) {
            render status: 422, contentType: 'application/json', text: '{"error":"invalid"}'
            return
        }
        if (!(parsed instanceof Map)) {
            render status: 422, contentType: 'application/json', text: '{"error":"invalid"}'
            return
        }
        Map payload = (Map) parsed
        ChirpWriteResult result = chirpWriteService.save(payload.get('handle'), payload.get('body'))
        render status: result.status, contentType: 'application/json', text: result.json
    }
}
