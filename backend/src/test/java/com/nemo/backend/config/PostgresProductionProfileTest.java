package com.nemo.backend.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class PostgresProductionProfileTest {

    private PropertySource<?> baseProperties;
    private PropertySource<?> localProperties;
    private PropertySource<?> productionProperties;

    @BeforeEach
    void loadProductionProfile() throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        baseProperties = loader
                .load("application", new ClassPathResource("application.yml"))
                .getFirst();
        localProperties = loader
                .load("application-local", new ClassPathResource("application-local.yml"))
                .getFirst();
        productionProperties = loader
                .load("application-prod", new ClassPathResource("application-prod.yml"))
                .getFirst();
    }

    @Test
    void defaultExecutionUsesDevUnlessProfileIsExplicitlyProvided() {
        assertThat(baseProperty("spring.profiles.active"))
                .isEqualTo("${SPRING_PROFILES_ACTIVE:dev}");
    }

    @Test
    void productionProfileUsesPostgresWithEnvironmentBackedCredentials() {
        assertThat(property("spring.datasource.driver-class-name"))
                .isEqualTo("org.postgresql.Driver");
        assertThat(property("spring.datasource.url"))
                .isEqualTo("${DB_URL}");
        assertThat(property("spring.datasource.username"))
                .isEqualTo("${DB_USER}");
        assertThat(property("spring.datasource.password"))
                .isEqualTo("${DB_PASSWORD}");
        assertThat(property("spring.jpa.database-platform"))
                .isEqualTo("org.hibernate.dialect.PostgreSQLDialect");
        assertThat(property("spring.jpa.hibernate.ddl-auto"))
                .isEqualTo("validate");
    }

    @Test
    void productionSecretsAndPublicUrlComeFromEnvironment() {
        assertThat(property("app.jwt.secret"))
                .isEqualTo("${JWT_SECRET}");
        assertThat(property("app.public-base-url"))
                .isEqualTo("${PUBLIC_BASE_URL}");
    }

    @Test
    void localProfileDoesNotStoreDatabaseJwtOrPublicUrlValues() {
        assertThat(localProperty("spring.datasource.url"))
                .isEqualTo("${DB_URL}");
        assertThat(localProperty("spring.datasource.username"))
                .isEqualTo("${DB_USER}");
        assertThat(localProperty("spring.datasource.password"))
                .isEqualTo("${DB_PASSWORD}");
        assertThat(localProperty("app.jwt.secret"))
                .isEqualTo("${JWT_SECRET}");
        assertThat(localProperty("app.public-base-url"))
                .isEqualTo("${PUBLIC_BASE_URL:http://localhost:8080}");
    }

    @Test
    void productionProfileDisablesDevelopmentSurfacesAndLimitsActuator() {
        assertThat(property("spring.h2.console.enabled")).isEqualTo(false);
        assertThat(property("springdoc.api-docs.enabled")).isEqualTo(false);
        assertThat(property("springdoc.swagger-ui.enabled")).isEqualTo(false);
        assertThat(property("management.endpoints.web.exposure.include"))
                .isEqualTo("health,info,prometheus");
        assertThat(property("management.endpoint.health.show-details"))
                .isEqualTo("never");
    }

    @Test
    void productionKeepsMetricsOffThePublicPort() {
        // /actuator/prometheus는 사용 중인 API 경로·응답시간·오류율·JVM 상태를 그대로 담는다.
        // 공개 포트에 열면 서비스 내부 구조를 알려주는 것과 같으므로,
        // 관리 포트를 서비스 포트와 분리하고 루프백에만 바인딩한다.
        assertThat(property("management.server.port"))
                .as("지표는 서비스 포트가 아닌 별도 관리 포트로 나가야 한다")
                .isEqualTo("${MANAGEMENT_PORT:9090}");
        assertThat(property("management.server.address"))
                .as("기본값은 루프백. 외부에서 직접 접근할 수 없어야 한다")
                .isEqualTo("${MANAGEMENT_ADDRESS:127.0.0.1}");
    }

    @Test
    void postgresDriverIsAvailableAtRuntime() {
        assertThatCode(() -> Class.forName("org.postgresql.Driver"))
                .doesNotThrowAnyException();
    }

    private Object property(String key) {
        return productionProperties.getProperty(key);
    }

    private Object baseProperty(String key) {
        return baseProperties.getProperty(key);
    }

    private Object localProperty(String key) {
        return localProperties.getProperty(key);
    }
}
