\set ON_ERROR_STOP on

SELECT
    (SELECT id
     FROM users
     WHERE email = 'benchmark-target@nemo.local') AS target_user_id,
    (SELECT count(*) FROM users) AS users,
    (SELECT count(*) FROM album WHERE user_id = 1) AS owned_albums,
    (SELECT count(*)
     FROM photos
     WHERE user_id = 1 AND deleted = false) AS photos,
    (SELECT count(*)
     FROM album_share
     WHERE status = 'ACCEPTED' AND active = true) AS shares,
    (SELECT count(*) FROM album_favorite WHERE user_id = 1) AS favorites;
