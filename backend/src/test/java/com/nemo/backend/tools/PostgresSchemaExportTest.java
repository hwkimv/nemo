package com.nemo.backend.tools;

import com.nemo.backend.domain.map.util.NaverApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.s3.S3Client;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 엔티티에서 <b>PostgreSQL용 DDL</b>을 뽑아낸다.
 *
 * <h3>왜 필요한가</h3>
 * 운영 프로필은 {@code ddl-auto: validate}다. 스키마를 자동으로 만들지 않는다.
 * 그래서 <b>비어 있는 DB에 붙이면 애플리케이션이 아예 기동하지 않는다.</b>
 * 누군가 먼저 테이블을 만들어 줘야 한다.
 *
 * <p>운영 DB에 {@code ddl-auto: update}를 한 번 켜서 만드는 방법도 있지만 쓰지 않는다.
 * 무엇이 실행되는지 보지 못한 채 스키마가 바뀌고, 되돌릴 방법도 남지 않는다.
 * <b>DDL을 파일로 뽑아 눈으로 보고 적용</b>하는 편이 안전하다.
 *
 * <p>이 프로젝트에는 아직 Flyway 같은 마이그레이션 도구가 없다.
 * 그때까지는 이 스크립트가 그 자리를 대신한다.
 * ({@code tools/storage/sql/storage-cleanup-task.sql}과 같은 방식이다)
 *
 * <h3>어떻게 도는가</h3>
 * 실제 PostgreSQL에 붙지 않는다. Hibernate가 <b>엔티티 메타데이터 + 방언</b>만으로
 * 스크립트를 만든다. {@code ddl-auto=none}이라 테스트용 H2도 건드리지 않는다.
 *
 * <pre>
 * ./gradlew exportPostgresSchema
 * → backend/build/generated-schema/schema-postgres.sql
 * </pre>
 */
@Tag("schema-export")
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        // 실제 DB는 건드리지 않는다. 스크립트만 만든다.
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=create",
        "spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target="
                + PostgresSchemaExportTest.TARGET,
        // 배경 워커가 돌면서 아직 없는 테이블을 조회하면 로그가 지저분해진다
        "app.storage.cleanup.enabled=false"
})
@DisplayName("PostgreSQL 스키마 DDL 추출")
class PostgresSchemaExportTest {

    static final String TARGET = "build/generated-schema/schema-postgres.sql";

    @MockitoBean
    private S3Client s3Client;

    @MockitoBean
    private NaverApiClient naverApiClient;

    @Test
    @DisplayName("엔티티 전체에 대한 CREATE TABLE 스크립트를 만든다")
    void exportSchema() throws Exception {
        Path out = Path.of(TARGET);
        assertThat(Files.exists(out))
                .as("컨텍스트가 뜨면서 스크립트가 만들어져야 한다: %s", out.toAbsolutePath())
                .isTrue();

        String ddl = Files.readString(out);
        System.out.println("\n===== 생성된 DDL (" + ddl.lines().count() + " 줄) =====");
        System.out.println(ddl);

        // 앱이 반드시 필요로 하는 테이블이 빠지지 않았는지 확인한다.
        assertThat(ddl.toLowerCase())
                .contains("create table users")
                .contains("create table photos")
                .contains("create table storage_cleanup_task");
    }
}
