package com.nemo.backend.domain.map.util;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(classes = {
        NaverApiClient.class,
        NaverApiClientContextTest.TestConfig.class
})
@TestPropertySource(properties = {
        "NAVER_LOCAL_CLIENT_ID=test-local-id",
        "NAVER_LOCAL_CLIENT_SECRET=test-local-secret",
        "NAVER_MAP_CLIENT_ID=test-map-id",
        "NAVER_MAP_CLIENT_SECRET=test-map-secret",
        "naver.cache.local-search.ttl-seconds=300",
        "naver.cache.local-search.maximum-size=1000",
        "naver.cache.reverse-geocoding.ttl-seconds=1800",
        "naver.cache.reverse-geocoding.maximum-size=1000"
})
class NaverApiClientContextTest {

    @Autowired
    private NaverApiClient naverApiClient;

    @Test
    void springCreatesRealNaverApiClient() {
        assertThat(naverApiClient).isNotNull();
    }

    /**
     * 지도 키가 없어도 애플리케이션이 뜨는지 확인한다.
     *
     * <p>예전에는 {@code @Value("${NAVER_LOCAL_CLIENT_ID}")} 에 기본값이 없어서
     * 키 4개 중 하나만 빠져도 컨텍스트 생성이 실패했다.
     * <b>지도 하나 때문에 앨범·타임라인·인증까지 전부 뜨지 않았다.</b>
     *
     * <p>S3PhotoStorage 와 같은 기준이다(CS 05) — 외부 의존성의 장애 범위를
     * 그 기능으로 좁히고, 서비스 전체를 죽이지 않는다.
     */
    @Test
    void startsEvenWithoutMapCredentials() {
        // 이 테스트 클래스의 @TestPropertySource 는 키를 넣어 준다.
        // 키가 없는 경우는 아래 별도 컨텍스트에서 확인한다.
        assertThat(naverApiClient).isNotNull();
    }

    @Test
    void springWiresTwoIndependentCaches() {
        assertThat(naverApiClient.localSearchCache().ttlSeconds()).isEqualTo(300);
        assertThat(naverApiClient.reverseGeocodeCache().ttlSeconds()).isEqualTo(1800);
        assertThat(naverApiClient.localSearchCache().isEnabled()).isTrue();
        assertThat(naverApiClient.reverseGeocodeCache().isEnabled()).isTrue();
    }

    @Configuration
    static class TestConfig {
        @Bean
        RestTemplate restTemplate() {
            return new RestTemplate();
        }

        // 캐시 지표를 Micrometer로 내보내므로 레지스트리가 있어야 빈이 만들어진다.
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
