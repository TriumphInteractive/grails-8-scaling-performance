\set ON_ERROR_STOP on
-- Run with psql -f against ONE migrated database; repeat for the other database.
-- This replaces all application data in the selected database.
BEGIN;
SET LOCAL TIME ZONE 'UTC';
TRUNCATE TABLE media, mention, chirp_tag, cheer, follow, chirp, account RESTART IDENTITY CASCADE;

INSERT INTO account (id, version, handle, display_name, bio, joined_at)
OVERRIDING SYSTEM VALUE
SELECT account_number, 0, 'user' || account_number, 'User ' || account_number,
       'bio', timestamp '2026-01-01 00:00:00'
FROM generate_series(1, 2000) AS accounts(account_number)
ORDER BY account_number;

WITH pairs AS (
    SELECT account_number AS follower_id, celebrity AS followee_id
    FROM generate_series(1, 2000) AS accounts(account_number)
    CROSS JOIN generate_series(1, 20) AS celebrities(celebrity)
    UNION
    SELECT account_number, ((account_number * 17 + extra) % 2000) + 1
    FROM generate_series(1, 2000) AS accounts(account_number)
    CROSS JOIN generate_series(1, 10) AS extras(extra)
), numbered AS (
    SELECT row_number() OVER (ORDER BY follower_id, followee_id) AS id,
           follower_id, followee_id
    FROM pairs
    WHERE follower_id <> followee_id
)
INSERT INTO follow (id, version, follower_id, followee_id, followed_at)
OVERRIDING SYSTEM VALUE
SELECT id, 0, follower_id, followee_id, timestamp '2026-01-01 00:00:00'
FROM numbered
ORDER BY id;

WITH numbered AS (
    SELECT row_number() OVER (ORDER BY author_id, chirp_number) AS id,
           author_id, chirp_number
    FROM generate_series(1, 2000) AS authors(author_id)
    CROSS JOIN LATERAL generate_series(1, CASE WHEN author_id <= 50 THEN 40 ELSE 10 END)
        AS chirps(chirp_number)
)
INSERT INTO chirp (id, version, author_id, body, posted_at)
OVERRIDING SYSTEM VALUE
SELECT id, 0, author_id, 'chirp-' || author_id || '-' || chirp_number,
       timestamp '2026-06-01 00:00:00' + (id || ' seconds')::interval
FROM numbered
ORDER BY id;

WITH pairs AS (
    SELECT chirp.id AS chirp_id, ((chirp.id + extra * 97) % 2000) + 1 AS account_id
    FROM chirp
    CROSS JOIN LATERAL generate_series(1, (chirp.id % 5)::integer) AS extras(extra)
    WHERE ((chirp.id + extra * 97) % 2000) + 1 <> chirp.author_id
), numbered AS (
    SELECT row_number() OVER (ORDER BY chirp_id, account_id) AS id, account_id, chirp_id
    FROM pairs
)
INSERT INTO cheer (id, version, account_id, chirp_id, cheered_at)
OVERRIDING SYSTEM VALUE
SELECT id, 0, account_id, chirp_id, timestamp '2026-06-02 00:00:00'
FROM numbered
ORDER BY id;

UPDATE chirp SET reply_to_id = id - 1 WHERE id > 1 AND id % 10 = 0;

INSERT INTO chirp_tag (id, version, chirp_id, tag)
OVERRIDING SYSTEM VALUE
SELECT row_number() OVER (ORDER BY id), 0, id,
       (ARRAY['grails', 'java', 'postgres', 'cache'])[(id % 4) + 1]
FROM chirp
WHERE id % 2 = 0;

INSERT INTO mention (id, version, chirp_id, mentioned_id)
OVERRIDING SYSTEM VALUE
SELECT row_number() OVER (ORDER BY id), 0, id, ((id * 13) % 2000) + 1
FROM chirp
WHERE id % 5 = 0 AND ((id * 13) % 2000) + 1 <> author_id;

INSERT INTO media (id, version, chirp_id, url)
OVERRIDING SYSTEM VALUE
SELECT row_number() OVER (ORDER BY id), 0, id, 'https://cdn.example/media/' || id || '.jpg'
FROM chirp
WHERE id % 3 = 0;

DO $$
DECLARE
    table_name text;
    identity_sequence text;
    largest_id bigint;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['account', 'follow', 'chirp', 'cheer', 'chirp_tag', 'mention', 'media'] LOOP
        identity_sequence := pg_get_serial_sequence(table_name, 'id');
        IF identity_sequence IS NULL THEN
            RAISE EXCEPTION 'No identity sequence found for %.id; inspect the Liquibase changelog', table_name;
        END IF;
        EXECUTE format('SELECT max(id) FROM %I', table_name) INTO largest_id;
        PERFORM setval(identity_sequence::regclass, largest_id, true);
    END LOOP;
END $$;
COMMIT;
