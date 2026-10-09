package demo.chirp

class NaiveChirpJson {
    static Map of(Chirp chirp) {
        [
                id: chirp.id,
                body: chirp.body,
                postedAt: chirp.postedAt,
                authorHandle: chirp.author.handle,
                authorName: chirp.author.displayName,
                cheerCount: Cheer.countByChirp(chirp),
                replyCount: Chirp.countByReplyTo(chirp),
                inReplyToHandle: chirp.replyTo?.author?.handle,
                hashtags: chirp.tags.collect { it.tag },
                mentions: chirp.mentions.collect { it.mentioned.handle },
                media: chirp.media.collect { it.url }
        ]
    }
}
