package com.nemo.backend.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h2>base 스키마와 Flyway 마이그레이션이 같은 것을 만들지 않는지 확인한다.</h2>
 *
 * <h3>왜 필요한가 — 실제로 배포가 깨졌다</h3>
 * 이 프로젝트는 스키마를 두 갈래로 만든다.
 * <pre>
 *   tools/schema/sql/schema-postgres.sql   base (빈 DB 부트스트랩용, 수동 적용)
 *   db/migration/V*.sql                    Flyway (기동 시 자동 적용)
 * </pre>
 *
 * <p>base 는 엔티티에서 자동 생성한다({@code ./gradlew exportPostgresSchema}).
 * <b>Hibernate 는 Flyway 를 모른다.</b> 그래서 재생성하면 V1 이 만드는 것까지 전부 들어온다.
 * 그 상태로 빈 DB 에 적용하면 기동 시 Flyway 가 이렇게 죽는다.
 *
 * <pre>
 *   ERROR: column "sequence" of relation "album_photos" already exists
 *   → 컨테이너 재시작 루프
 * </pre>
 *
 * <p>2026-08-21 AWS 배포에서 실제로 겪었다. 그때는 DB 를 되돌려 손으로 고쳤는데,
 * 손으로 고친 것은 다음에 또 깨진다. <b>이 테스트가 그걸 막는다.</b>
 *
 * <h3>왜 실제 DB 를 띄우지 않는가</h3>
 * "빈 PostgreSQL → base → Flyway → validate" 를 돌리는 것이 가장 확실하다.
 * 다만 그러려면 Testcontainers 나 살아 있는 PostgreSQL 이 필요하고,
 * CI 와 개발 환경 모두에서 Docker 를 전제하게 된다.
 *
 * <p>여기서 막으려는 실패는 <b>"같은 객체를 두 번 만든다"</b> 하나다.
 * 두 파일을 읽어 겹치는지 보면 그 실패는 확실히 잡힌다.
 * 실제 DB 검증은 CS 12 에 수동 절차로 남겼다.
 */
@DisplayName("base 스키마 ↔ Flyway 겹침 검사")
class SchemaFlywayOverlapTest {

    private static final Path BASE = Path.of("../tools/schema/sql/schema-postgres.sql");
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    /** {@code create table <name>} 에서 이름만 뽑는다. */
    private static final Pattern CREATE_TABLE =
            Pattern.compile("create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([a-z_][a-z0-9_]*)",
                    Pattern.CASE_INSENSITIVE);

    /** {@code alter table <t> add column <c>} 에서 (테이블, 컬럼)을 뽑는다. */
    private static final Pattern ADD_COLUMN =
            Pattern.compile("alter\\s+table\\s+(?:if\\s+exists\\s+)?([a-z_][a-z0-9_]*)\\s+"
                            + "add\\s+column\\s+([a-z_][a-z0-9_]*)",
                    Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("Flyway 가 만드는 테이블을 base 가 미리 만들지 않는다")
    void baseMustNotCreateTablesThatFlywayCreates() throws IOException {
        String base = read(BASE);
        List<String> baseTables = matchAll(CREATE_TABLE, base, 1);

        for (Path migration : migrationFiles()) {
            String sql = read(migration);
            for (String t : matchAll(CREATE_TABLE, sql, 1)) {
                assertThat(baseTables)
                        .as("""
                                %s 가 만드는 테이블 '%s' 를 base 스키마도 만들고 있다.
                                빈 DB 에 base 를 넣고 기동하면 Flyway 가
                                "relation already exists" 로 실패해 컨테이너가 재시작 루프에 빠진다.

                                base 에서 '%s' 를 빼라. Flyway 가 만들게 둔다.
                                (exportPostgresSchema 로 재생성했다면 다시 빼야 한다 —
                                 Hibernate 는 Flyway 를 모른다)"""
                                .formatted(migration.getFileName(), t, t))
                        .doesNotContain(t);
            }
        }
    }

    @Test
    @DisplayName("Flyway 가 추가하는 컬럼을 base 가 미리 만들지 않는다")
    void baseMustNotCreateColumnsThatFlywayAdds() throws IOException {
        String base = read(BASE);

        for (Path migration : migrationFiles()) {
            Matcher m = ADD_COLUMN.matcher(read(migration));
            while (m.find()) {
                String table = m.group(1).toLowerCase(Locale.ROOT);
                String column = m.group(2).toLowerCase(Locale.ROOT);
                String block = createTableBlock(base, table);
                if (block == null) continue;   // base 에 그 테이블이 없으면 겹칠 일도 없다

                assertThat(block)
                        .as("""
                                %s 가 %s.%s 컬럼을 추가하는데 base 스키마의 create table 에도 있다.
                                빈 DB 에 base 를 넣고 기동하면 Flyway 가
                                "column already exists" 로 실패한다.

                                실제로 2026-08-21 AWS 배포에서 이 조합으로 기동이 깨졌다."""
                                .formatted(migration.getFileName(), table, column))
                        .doesNotContainPattern("(?i)\\b" + Pattern.quote(column) + "\\b");
            }
        }
    }

    @Test
    @DisplayName("검사 대상 파일이 실제로 존재한다 (조용히 통과하지 않도록)")
    void filesExist() throws IOException {
        assertThat(Files.exists(BASE))
                .as("base 스키마를 못 찾으면 위 두 테스트가 아무것도 검사하지 않고 통과한다: %s",
                        BASE.toAbsolutePath())
                .isTrue();
        assertThat(migrationFiles())
                .as("Flyway 마이그레이션을 못 찾으면 검사가 무의미해진다: %s", MIGRATIONS.toAbsolutePath())
                .isNotEmpty();
    }

    // ─────────────────────── 도우미 ───────────────────────

    private static List<Path> migrationFiles() throws IOException {
        if (!Files.isDirectory(MIGRATIONS)) return List.of();
        try (var s = Files.list(MIGRATIONS)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList();
        }
    }

    /** 주석을 걷어낸 SQL 본문 */
    private static String read(Path p) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String line : Files.readString(p).lines().toList()) {
            String stripped = line.replaceAll("--.*$", "");
            if (!stripped.isBlank()) lines.add(stripped);
        }
        return String.join("\n", lines);
    }

    private static List<String> matchAll(Pattern p, String text, int group) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(text);
        while (m.find()) out.add(m.group(group).toLowerCase(Locale.ROOT));
        return out;
    }

    /** base 에서 특정 테이블의 create table (...) 블록만 잘라낸다. */
    private static String createTableBlock(String sql, String table) {
        Matcher m = Pattern.compile(
                "create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?" + Pattern.quote(table)
                        + "\\s*\\((.*?)\\)\\s*;", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(sql);
        return m.find() ? m.group(1) : null;
    }
}
