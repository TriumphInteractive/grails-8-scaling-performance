package demo.chirp

import grails.converters.JSON

class TimelineController {
    static allowedMethods = [show: 'GET']

    def show() {
        def account = Account.findByHandle(params.handle)
        if (!account) {
            render status: 404, contentType: 'application/json', text: ([error: 'not found'] as JSON)
            return
        }

        def follows = Follow.findAllByFollower(account)
        def authors = follows.collect { it.followee }
        def max = Math.min(Math.max(params.int('max') ?: 20, 1), 50)
        def chirps = Chirp.findAllByAuthorInList(authors, [max: max, sort: 'postedAt', order: 'desc'])
        def timeline = NaiveChirpJsonService.page(chirps)

        render contentType: 'application/json', text: ([handle: account.handle, chirps: timeline] as JSON)
    }
}
