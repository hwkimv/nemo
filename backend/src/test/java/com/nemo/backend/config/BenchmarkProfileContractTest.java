package com.nemo.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkProfileContractTest {

    @Test
    void benchmarkUsesDedicatedPostgres() throws IOException {
        PropertySource<?> properties = new YamlPropertySourceLoader()
                .load(
                        "application-benchmark",
                        new ClassPathResource("application-benchmark.yml")
                )
                .getFirst();

        assertThat(properties.getProperty("spring.datasource.url"))
                .isEqualTo("${BENCHMARK_DB_URL:jdbc:postgresql://localhost:55432/nemo_benchmark}");
        assertThat(properties.getProperty("spring.datasource.username"))
                .isEqualTo("${BENCHMARK_DB_USER:nemo_benchmark}");
        assertThat(properties.getProperty("spring.datasource.password"))
                .isEqualTo("${BENCHMARK_DB_PASSWORD:nemo_benchmark_local_only}");
        assertThat(properties.getProperty("spring.jpa.hibernate.ddl-auto"))
                .isEqualTo("update");
        assertThat(properties.getProperty("spring.jpa.properties.hibernate.generate_statistics"))
                .isEqualTo(true);

        String compose = Files.readString(Path.of("..", "compose.yaml"));
        assertThat(compose)
                .contains("postgres-benchmark:")
                .contains("container_name: nemo-postgres-benchmark")
                .contains("POSTGRES_DB: nemo_benchmark")
                .contains("\"55432:5432\"");
    }
}
