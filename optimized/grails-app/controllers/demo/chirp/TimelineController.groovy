package demo.chirp

class TimelineController {
    static allowedMethods = [show: 'GET']
    ChirpJsonCacheService chirpJsonCacheService

    def show() {
        Integer requestedMax = params.int('max')
        int chosen = (requestedMax == null || requestedMax.intValue() == 0) ? 20 : requestedMax.intValue()
        int max = Math.min(Math.max(chosen, 1), 50)
        try {
            render contentType: 'application/json', text: chirpJsonCacheService.timeline(params.get('handle').toString(), max)
        } catch (AccountNotFoundException ignored) {
            render status: 404, contentType: 'application/json', text: '{"error":"not found"}'
        }
    }
}
