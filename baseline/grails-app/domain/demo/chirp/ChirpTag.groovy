package demo.chirp

class ChirpTag {
    Chirp chirp
    String tag

    static belongsTo = [chirp: Chirp]

    static constraints = {
        tag nullable: false, blank: false, maxSize: 40
    }

    static mapping = {
        table 'chirp_tag'
        id column: 'id', generator: 'identity'
        version column: 'version'
        chirp column: 'chirp_id', index: 'chirp_tag_chirp_idx'
        tag column: 'tag'
    }
}
