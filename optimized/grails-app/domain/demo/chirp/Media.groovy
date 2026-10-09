package demo.chirp

class Media {
    Chirp chirp
    String url

    static belongsTo = [chirp: Chirp]

    static constraints = {
        url nullable: false, blank: false, maxSize: 200
    }

    static mapping = {
        table 'media'
        id column: 'id', generator: 'identity'
        version column: 'version'
        chirp column: 'chirp_id', index: 'media_chirp_idx'
        url column: 'url'
    }
}
