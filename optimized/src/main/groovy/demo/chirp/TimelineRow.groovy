package demo.chirp

import groovy.transform.CompileStatic
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@CompileStatic
class TimelineRow {
    private static final DateTimeFormatter POSTED_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    final Long id
    final String body
    final Date postedAt
    final String authorHandle
    final String authorName

    TimelineRow(Long id, String body, Date postedAt, String authorHandle, String authorName) {
        this.id = id
        this.body = body
        this.postedAt = postedAt
        this.authorHandle = authorHandle
        this.authorName = authorName
    }

    Map<String, Object> asPostJson() {
        [id: id, body: body, postedAt: POSTED_AT.format(postedAt.toInstant()),
         authorHandle: authorHandle, authorName: authorName, cheerCount: 0]
    }

    Map<String, Object> asJson(Long cheerCount) {
        asJson(cheerCount, 0L, null, [], [], [])
    }

    Map<String, Object> asJson(Long cheerCount, Long replyCount, String inReplyToHandle,
                               List<String> hashtags, List<String> mentions, List<String> media) {
        [id: id, body: body, postedAt: POSTED_AT.format(postedAt.toInstant()),
         authorHandle: authorHandle, authorName: authorName, cheerCount: cheerCount,
         replyCount: replyCount, inReplyToHandle: inReplyToHandle,
         hashtags: hashtags, mentions: mentions, media: media]
    }
}
