package demo.chirp

import grails.converters.JSON

class ReadyController {
    static allowedMethods = [index: 'GET']

    def index() {
        render contentType: 'application/json', text: ([status: 'up'] as JSON)
    }
}
