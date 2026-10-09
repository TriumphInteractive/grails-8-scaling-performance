package demo.chirp

import grails.gorm.transactions.Transactional
import groovy.json.JsonOutput

@Transactional
class ChirpWriteService {
    AccountDataService accountDataService

    ChirpWriteResult save(Object handle, Object body) {
        Account account = handle instanceof String ? accountDataService.findByHandle((String) handle) : null
        if (account == null) {
            return new ChirpWriteResult(404, '{"error":"not found"}')
        }
        if (!(body instanceof String)) {
            return new ChirpWriteResult(422, '{"error":"invalid"}')
        }
        Chirp chirp = new Chirp(author: account, body: (String) body).save(flush: true, failOnError: false)
        if (chirp == null) {
            return new ChirpWriteResult(422, '{"error":"invalid"}')
        }
        TimelineRow row = new TimelineRow(chirp.id, chirp.body, chirp.postedAt, account.handle, account.displayName)
        new ChirpWriteResult(201, JsonOutput.toJson(row.asPostJson()), account.handle)
    }
}
