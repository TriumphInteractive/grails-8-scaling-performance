package demo.chirp

import grails.gorm.services.Query
import grails.gorm.services.Service
import grails.gorm.transactions.ReadOnly

@Service(Follow)
@ReadOnly
interface FollowDataService {
    @Query('select f.followee.id from Follow f where f.follower.id = :accountId')
    List<Long> findFolloweeIds(Long accountId)
}
