package demo.chirp

class Cheer {
    Account account
    Chirp chirp
    Date cheeredAt = new Date()

    static constraints = {
        account nullable: false
        chirp nullable: false, unique: 'account'
        cheeredAt nullable: false
    }

    static mapping = {
        table 'cheer'
        id column: 'id', generator: 'identity'
        version column: 'version'
        account column: 'account_id'
        chirp column: 'chirp_id', index: 'cheer_chirp_idx'
        cheeredAt column: 'cheered_at', sqlType: 'timestamp'
    }
}
