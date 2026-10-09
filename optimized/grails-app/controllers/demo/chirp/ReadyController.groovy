package demo.chirp

class ReadyController {
    static allowedMethods = [index: 'GET']

    def index() {
        render contentType: 'application/json', text: '{"status":"up"}'
    }
}
