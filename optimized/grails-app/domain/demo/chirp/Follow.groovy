package demo.chirp

class Follow {
    Account follower
    Account followee
    Date followedAt = new Date()

    static constraints = {
        follower nullable: false
        followee nullable: false, unique: 'follower'
        followedAt nullable: false
    }

    static mapping = {
        table 'follow'
        id column: 'id', generator: 'identity'
        version column: 'version'
        follower column: 'follower_id', index: 'follow_follower_idx'
        followee column: 'followee_id', index: 'follow_followee_idx'
        followedAt column: 'followed_at', sqlType: 'timestamp'
    }
}
