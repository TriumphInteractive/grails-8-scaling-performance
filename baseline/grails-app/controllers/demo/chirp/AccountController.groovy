package demo.chirp

import grails.converters.JSON

class AccountController {
    static allowedMethods = [show: 'GET']

    def show() {
        def account = Account.findByHandle(params.handle)
        if (!account) {
            render status: 404, contentType: 'application/json', text: ([error: 'not found'] as JSON)
            return
        }

        def chirpCount = Chirp.countByAuthor(account)
        def followingCount = Follow.countByFollower(account)
        def followerCount = Follow.countByFollowee(account)
        def chirps = Chirp.findAllByAuthor(account, [max: 10, sort: 'postedAt', order: 'desc'])
        def recentChirps = chirps.collect { chirp -> NaiveChirpJson.of(chirp) }

        render contentType: 'application/json', text: ([
                handle: account.handle, displayName: account.displayName, bio: account.bio,
                chirpCount: chirpCount, followingCount: followingCount, followerCount: followerCount,
                recentChirps: recentChirps
        ] as JSON)
    }
}
