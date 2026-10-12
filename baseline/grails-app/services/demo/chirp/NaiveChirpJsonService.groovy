package demo.chirp

class NaiveChirpJsonService {
    static Map of(Chirp chirp) {
        build(chirp, null, null)
    }

    static List<Map> page(List<Chirp> chirps) {
        if (!Boolean.getBoolean('chirp.gorm.groupedCounts')) {
            return chirps.collect { of(it) }
        }
        if (chirps.isEmpty()) {
            return []
        }

        List<Long> ids = chirps.collect { Chirp chirp -> chirp.id }
        Map<Long, Number> cheers = countsById((List) Cheer.createCriteria().list {
            inList('chirp.id', ids)
            projections {
                groupProperty('chirp.id')
                count()
            }
        })
        Map<Long, Number> replies = countsById((List) Chirp.createCriteria().list {
            inList('replyTo.id', ids)
            projections {
                groupProperty('replyTo.id')
                count()
            }
        })
        chirps.collect { Chirp chirp -> build(chirp, cheers, replies) }
    }

    private static Map<Long, Number> countsById(List rows) {
        Map<Long, Number> counts = [:]
        rows.each { Object row ->
            Object[] values = (Object[]) row
            counts[(Long) values[0]] = (Number) values[1]
        }
        counts
    }

    private static Map build(Chirp chirp, Map<Long, Number> cheers, Map<Long, Number> replies) {
        [
                id: chirp.id,
                body: chirp.body,
                postedAt: chirp.postedAt,
                authorHandle: chirp.author.handle,
                authorName: chirp.author.displayName,
                cheerCount: cheers == null ? Cheer.countByChirp(chirp) : (cheers.get(chirp.id) ?: 0L),
                replyCount: replies == null ? Chirp.countByReplyTo(chirp) : (replies.get(chirp.id) ?: 0L),
                inReplyToHandle: chirp.replyTo?.author?.handle,
                hashtags: chirp.tags.collect { ChirpTag tag -> tag.tag },
                mentions: chirp.mentions.collect { Mention mention -> mention.mentioned.handle },
                media: chirp.media.collect { Media media -> media.url }
        ]
    }
}
