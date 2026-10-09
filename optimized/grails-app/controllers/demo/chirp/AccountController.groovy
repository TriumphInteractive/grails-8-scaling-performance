package demo.chirp

class AccountController {
    static allowedMethods = [show: 'GET']
    ChirpJsonCacheService chirpJsonCacheService

    def show() {
        try {
            render contentType: 'application/json', text: chirpJsonCacheService.account(params.get('handle').toString())
        } catch (AccountNotFoundException ignored) {
            render status: 404, contentType: 'application/json', text: '{"error":"not found"}'
        }
    }
}
