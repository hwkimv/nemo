package com.nemo.backend.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlbumPhotoTagMigrationTest {

    @Test
    void migrationPreservesMembershipAndBackfillsDeterministicSequence() {
        DriverManagerDataSource dataSource = oldSchema("preserve");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO album(id) VALUES (1)");
        jdbc.update("INSERT INTO photos(id) VALUES (20), (10)");
        jdbc.update("INSERT INTO album_photos(album_id, photo_id) VALUES (1, 20), (1, 10)");

        migrate(dataSource);

        List<String> rows = jdbc.query(
                "SELECT album_id, photo_id, sequence FROM album_photos ORDER BY sequence",
                (rs, rowNum) -> rs.getLong(1) + ":" + rs.getLong(2) + ":" + rs.getInt(3));
        assertThat(rows).containsExactly("1:10:0", "1:20:1");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'PHOTO_TAG'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void duplicateMembershipStopsMigrationWithoutDeletingRows() {
        DriverManagerDataSource dataSource = oldSchema("duplicate");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO album(id) VALUES (1)");
        jdbc.update("INSERT INTO photos(id) VALUES (10)");
        jdbc.update("INSERT INTO album_photos(album_id, photo_id) VALUES (1, 10), (1, 10)");

        assertThatThrownBy(() -> migrate(dataSource)).isInstanceOf(FlywayException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM album_photos", Integer.class)).isEqualTo(2);
    }

    private DriverManagerDataSource oldSchema(String label) {
        String url = "jdbc:h2:mem:migration_" + label + "_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE album (id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE photos (id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE users (id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE album_photos (album_id BIGINT NOT NULL, photo_id BIGINT NOT NULL)");
        return dataSource;
    }

    private void migrate(DriverManagerDataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }
}
