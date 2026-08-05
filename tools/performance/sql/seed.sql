\set ON_ERROR_STOP on

DO $$
BEGIN
    IF current_database() <> 'nemo_benchmark' THEN
        RAISE EXCEPTION 'Refusing to seed database %', current_database();
    END IF;
END
$$;

BEGIN;

TRUNCATE TABLE
    album_favorite,
    album_share,
    album_photos,
    album,
    refresh_tokens,
    friend,
    timeline,
    photos,
    users
RESTART IDENTITY CASCADE;

INSERT INTO users (
    email,
    password,
    nickname,
    profile_image_url,
    provider,
    social_id,
    plan_type,
    max_photo_count,
    created_at,
    updated_at
)
SELECT
    CASE
        WHEN n = 1 THEN 'benchmark-target@nemo.local'
        ELSE format('benchmark-user-%s@nemo.local', n)
    END,
    '{noop}benchmark',
    format('benchmark-user-%s', n),
    '',
    'local',
    NULL,
    'PLUS',
    5000,
    timestamp '2025-01-01 00:00:00' + n * interval '1 minute',
    timestamp '2025-01-01 00:00:00' + n * interval '1 minute'
FROM generate_series(1, 100) AS n;

INSERT INTO photos (
    user_id,
    image_url,
    thumbnail_url,
    taken_at,
    location,
    brand,
    favorite,
    memo,
    created_at,
    deleted
)
SELECT
    1,
    format('https://benchmark.invalid/photos/%s.webp', n),
    format('https://benchmark.invalid/thumbs/%s.webp', n),
    timestamp '2025-01-01 12:00:00' + ((n - 1) % 365) * interval '1 day',
    format('Seoul-%s', ((n - 1) % 25) + 1),
    CASE WHEN n % 2 = 0 THEN '인생네컷' ELSE '포토이즘' END,
    n % 10 = 0,
    format('benchmark memo %s', n),
    timestamp '2025-01-01 12:00:00' + n * interval '1 minute',
    false
FROM generate_series(1, 1000) AS n;

INSERT INTO album (
    name,
    description,
    cover_photo_url,
    user_id,
    created_at,
    updated_at
)
SELECT
    format('Benchmark Album %s', n),
    'benchmark owned album',
    NULL,
    1,
    timestamp '2025-01-01 00:00:00' + n * interval '1 hour',
    timestamp '2025-01-01 00:00:00' + n * interval '1 hour'
FROM generate_series(1, 100) AS n;

INSERT INTO album_photos (album_id, photo_id)
SELECT ((n - 1) / 10) + 1, n
FROM generate_series(1, 1000) AS n;

INSERT INTO album_share (
    album_id,
    user_id,
    role,
    status,
    active,
    created_at,
    updated_at
)
SELECT
    n,
    n + 1,
    'VIEWER',
    'ACCEPTED',
    true,
    timestamp '2025-02-01 00:00:00',
    timestamp '2025-02-01 00:00:00'
FROM generate_series(1, 20) AS n;

INSERT INTO album_favorite (
    album_id,
    user_id,
    created_at,
    updated_at
)
SELECT
    n,
    1,
    timestamp '2025-02-01 00:00:00',
    timestamp '2025-02-01 00:00:00'
FROM generate_series(1, 20) AS n;

COMMIT;
