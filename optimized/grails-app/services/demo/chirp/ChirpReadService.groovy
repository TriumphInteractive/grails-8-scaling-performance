package demo.chirp

import grails.gorm.transactions.ReadOnly
import groovy.json.JsonOutput

@ReadOnly
class ChirpReadService {
    AccountDataService accountDataService
    FollowDataService followDataService
    TimelineDataService timelineDataService

    String timeline(String handle, int max) {
        Account account = requireAccount(handle)
        if (timelineDataService != null) {
            List rows = timelineDataService.page(handle, max)
            return JsonOutput.toJson([handle: account.handle, chirps: rows.collect { row -> nativeJson(cells(row)) }])
        }
        List<Long> ids = followDataService.findFolloweeIds(account.id)
        List<TimelineRow> hqlRows = ids.isEmpty() ? [] : readRows(ids, max)
        JsonOutput.toJson([handle: account.handle, chirps: renderRows(hqlRows)])
    }

    String account(String handle) {
        Account account = requireAccount(handle)
        Long chirpCount = count('select count(c.id) from Chirp c where c.author.id = :id', account.id)
        Long followingCount = count('select count(f.id) from Follow f where f.follower.id = :id', account.id)
        Long followerCount = count('select count(f.id) from Follow f where f.followee.id = :id', account.id)
        List<TimelineRow> rows = readRows([account.id], 10)
        JsonOutput.toJson([handle: account.handle, displayName: account.displayName, bio: account.bio,
                           chirpCount: chirpCount, followingCount: followingCount,
                           followerCount: followerCount, recentChirps: renderRows(rows)])
    }

    private Object[] cells(Object row) {
        if (row instanceof Object[]) {
            return (Object[]) row
        }
        [row] as Object[]
    }

    private Map<String, Object> nativeJson(Object[] row) {
        Date posted = NativeJson.toDate(row[2])
        TimelineRow mapped = new TimelineRow(asLong(row[0]), (String) row[1], posted, (String) row[3], (String) row[4])
        mapped.asJson(asLong(row[5]), asLong(row[6]), row[10] == null ? null : row[10].toString(),
                NativeJson.list(row[7]), NativeJson.list(row[8]), NativeJson.list(row[9]))
    }

    private Long asLong(Object value) {
        value == null ? 0L : ((Number) value).longValue()
    }

    private Account requireAccount(String handle) {
        Account account = accountDataService.findByHandle(handle)
        if (account == null) {
            throw new AccountNotFoundException(handle)
        }
        account
    }

    private List<TimelineRow> readRows(List<Long> ids, int max) {
        (List<TimelineRow>) Chirp.executeQuery(
                'select new demo.chirp.TimelineRow(c.id, c.body, c.postedAt, a.handle, a.displayName) ' +
                'from Chirp c join c.author a where a.id in (:ids) order by c.postedAt desc, c.id desc',
                [ids: ids], [max: max])
    }

    private Long count(String hql, Long id) {
        (Long) Account.executeQuery(hql, [id: id])[0]
    }

    private List<Map<String, Object>> renderRows(List<TimelineRow> rows) {
        if (rows.isEmpty()) {
            return []
        }
        List<Long> ids = rows.collect { TimelineRow row -> row.id }
        List<Object[]> counts = (List<Object[]>) Cheer.executeQuery(
                'select ch.chirp.id, count(ch.id) from Cheer ch where ch.chirp.id in (:ids) group by ch.chirp.id',
                [ids: ids])
        Map<Long, Long> cheers = groupCounts(counts)
        Map<Long, Long> replies = groupCounts((List<Object[]>) Chirp.executeQuery(
                'select c.replyTo.id, count(c.id) from Chirp c where c.replyTo.id in (:ids) group by c.replyTo.id',
                [ids: ids]))
        Map<Long, List<String>> tags = groupStrings((List<Object[]>) ChirpTag.executeQuery(
                'select t.chirp.id, t.tag from ChirpTag t where t.chirp.id in (:ids)', [ids: ids]))
        Map<Long, List<String>> mentions = groupStrings((List<Object[]>) Mention.executeQuery(
                'select m.chirp.id, a.handle from Mention m join m.mentioned a where m.chirp.id in (:ids)', [ids: ids]))
        Map<Long, List<String>> media = groupStrings((List<Object[]>) Media.executeQuery(
                'select m.chirp.id, m.url from Media m where m.chirp.id in (:ids)', [ids: ids]))
        Map<Long, String> replyAuthors = [:]
        List<Object[]> parents = (List<Object[]>) Chirp.executeQuery(
                'select c.id, a.handle from Chirp c join c.replyTo parent join parent.author a where c.id in (:ids)',
                [ids: ids])
        for (Object[] parent : parents) {
            replyAuthors.put((Long) parent[0], (String) parent[1])
        }
        rows.collect { TimelineRow row ->
            row.asJson(cheers.getOrDefault(row.id, 0L), replies.getOrDefault(row.id, 0L),
                    replyAuthors.get(row.id),
                    tags.getOrDefault(row.id, Collections.emptyList()),
                    mentions.getOrDefault(row.id, Collections.emptyList()),
                    media.getOrDefault(row.id, Collections.emptyList()))
        }
    }

    private Map<Long, Long> groupCounts(List<Object[]> rows) {
        Map<Long, Long> grouped = [:]
        for (Object[] row : rows) {
            grouped.put((Long) row[0], (Long) row[1])
        }
        grouped
    }

    private Map<Long, List<String>> groupStrings(List<Object[]> rows) {
        Map<Long, List<String>> grouped = [:]
        for (Object[] row : rows) {
            Long id = (Long) row[0]
            List<String> values = grouped.get(id)
            if (values == null) {
                values = []
                grouped.put(id, values)
            }
            values.add((String) row[1])
        }
        grouped
    }
}
