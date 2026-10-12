package demo.chirp

class Chirp {
    Account author
    Chirp replyTo
    String body
    Date postedAt = new Date()

    static hasMany = [tags: ChirpTag, mentions: Mention, media: Media]

    static constraints = {
        author nullable: false
        replyTo nullable: true
        body nullable: false, blank: false, maxSize: 280
        postedAt nullable: false
    }

    static mapping = {
        int entityBatch = Integer.getInteger('chirp.gorm.entityBatchSize', 0)
        if (entityBatch > 1) { batchSize entityBatch }
        table 'chirp'
        id column: 'id', generator: 'identity'
        version column: 'version'
        author column: 'author_id', index: 'chirp_author_posted_at_idx'
        replyTo column: 'reply_to_id', index: 'chirp_reply_to_idx'
        body column: 'body', length: 280
        postedAt column: 'posted_at', sqlType: 'timestamp', index: 'chirp_author_posted_at_idx'
    }
}
