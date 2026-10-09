package demo.chirp

import grails.gorm.services.Service
import grails.gorm.transactions.ReadOnly
import org.hibernate.Session

@Service(Chirp)
abstract class TimelineDataService {
    private static final String PAGE = '''
        WITH authors AS (
            SELECT f.followee_id AS id
            FROM account a
            JOIN follow f ON f.follower_id = a.id
            WHERE a.handle = :handle
        ),
        page AS (
            SELECT c.id, c.body, c.posted_at, c.reply_to_id, au.handle AS author_handle, au.display_name
            FROM authors a
            CROSS JOIN LATERAL (
                SELECT c.id, c.body, c.posted_at, c.reply_to_id, c.author_id
                FROM chirp c
                WHERE c.author_id = a.id
                ORDER BY c.posted_at DESC, c.id DESC
                LIMIT :maxInner
            ) c
            JOIN account au ON au.id = c.author_id
            ORDER BY c.posted_at DESC, c.id DESC
            LIMIT :maxOuter
        )
        SELECT p.id, p.body, p.posted_at, p.author_handle, p.display_name,
               (SELECT count(*) FROM cheer ch WHERE ch.chirp_id = p.id),
               (SELECT count(*) FROM chirp r WHERE r.reply_to_id = p.id),
               COALESCE((SELECT json_agg(t.tag ORDER BY t.id)::text FROM chirp_tag t WHERE t.chirp_id = p.id), '[]'),
               COALESCE((SELECT json_agg(ma.handle ORDER BY m.id)::text FROM mention m JOIN account ma ON ma.id = m.mentioned_id WHERE m.chirp_id = p.id), '[]'),
               COALESCE((SELECT json_agg(md.url ORDER BY md.id)::text FROM media md WHERE md.chirp_id = p.id), '[]'),
               (SELECT pa.handle FROM chirp parent JOIN account pa ON pa.id = parent.author_id WHERE parent.id = p.reply_to_id)
        FROM page p
        ORDER BY p.posted_at DESC, p.id DESC
    '''

    @ReadOnly
    List page(String handle, int max) {
        List rows = []
        Chirp.withSession { Session session ->
            rows = session.createNativeQuery(PAGE)
                    .setParameter('handle', handle)
                    .setParameter('maxInner', max)
                    .setParameter('maxOuter', max)
                    .getResultList()
        }
        rows
    }
}
