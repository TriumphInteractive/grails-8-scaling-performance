package demo.chirp

import grails.gorm.services.Query
import grails.gorm.services.Service
import grails.gorm.transactions.ReadOnly

@Service(Account)
@ReadOnly
interface AccountDataService {
    @Query('select a from Account a where a.handle = :handle')
    Account findByHandle(String handle)
}
