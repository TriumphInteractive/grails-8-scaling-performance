package demo.chirp

import grails.converters.JSON
import grails.gorm.transactions.Transactional

class ChirpController {
    static allowedMethods = [save: 'POST']

    @Transactional
    def save() {
        def payload = request.JSON
        def account = payload.handle ? Account.findByHandle(payload.handle) : null
        if (!account) {
            render status: 404, contentType: 'application/json', text: ([error: 'not found'] as JSON)
            return
        }

        if (!(payload.body instanceof String)) {
            render status: 422, contentType: 'application/json', text: ([error: 'invalid'] as JSON)
            return
        }
        def chirp = new Chirp(author: account, body: payload.body)
                .save(flush: true, failOnError: false)
        if (!chirp) {
            render status: 422, contentType: 'application/json', text: ([error: 'invalid'] as JSON)
            return
        }

        render status: 201, contentType: 'application/json', text: ([
                id: chirp.id, body: chirp.body, postedAt: chirp.postedAt,
                authorHandle: chirp.author.handle, authorName: chirp.author.displayName,
                cheerCount: 0
        ] as JSON)
    }
}
