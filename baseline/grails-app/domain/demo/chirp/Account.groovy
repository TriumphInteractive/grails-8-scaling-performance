package demo.chirp

class Account {
    String handle
    String displayName
    String bio
    Date joinedAt = new Date()

    static constraints = {
        handle nullable: false, blank: false, maxSize: 40, unique: true
        displayName nullable: false, blank: false, maxSize: 80
        bio nullable: true, maxSize: 160
        joinedAt nullable: false
    }

    static mapping = {
        int entityBatch = Integer.getInteger('chirp.gorm.entityBatchSize', 0)
        if (entityBatch > 1) { batchSize entityBatch }
        table 'account'
        id column: 'id', generator: 'identity'
        version column: 'version'
        handle column: 'handle', length: 40
        displayName column: 'display_name', length: 80
        bio column: 'bio', length: 160
        joinedAt column: 'joined_at', sqlType: 'timestamp'
    }
}
