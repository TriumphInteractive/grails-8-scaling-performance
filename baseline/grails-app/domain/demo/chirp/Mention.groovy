package demo.chirp

class Mention {
    Chirp chirp
    Account mentioned

    static belongsTo = [chirp: Chirp]

    static constraints = {
        mentioned nullable: false
    }

    static mapping = {
        table 'mention'
        id column: 'id', generator: 'identity'
        version column: 'version'
        chirp column: 'chirp_id', index: 'mention_chirp_idx'
        mentioned column: 'mentioned_id'
    }
}
