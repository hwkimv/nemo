package com.nemo.backend.domain.map.util;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>지도 API 키가 하나도 없어도 애플리케이션이 뜨는지</b> 확인한다.
 *
 * <p>예전에는 {@code @Value("${NAVER_LOCAL_CLIENT_ID}")} 에 기본값이 없었다.
 * 그래서 키 4개 중 하나만 빠져도 컨텍스트 생성이 실패했고,
 * <b>지도 하나 때문에 앨범·타임라인·인증까지 전부 뜨지 않았다.</b>
 *
 * <p>실제로 AWS 배포를 준비하다 걸렸다. prod 프로필에 naver 블록이 없어서
 * 환경변수를 넣지 않으면 앱이 기동조차 못 하는 상태였다.
 *
 * <p>S3PhotoStorage 와 같은 기준을 따른다(CS 05) —
 * 외부 의존성의 장애 범위를 그 기능으로 좁히고 서비스 전체를 죽이지 않는다.
 * 키가 없으면 지도 API 만 실패하고, 기동 시 경고 로그를 남긴다.
 */
@SpringJUnitConfig(classes = {
        NaverApiClient.class,
        NaverApiClientNoCredentialsTest.TestConfig.class
})
@DisplayName("지도 키 없이도 기동한다")
class NaverApiClientNoCredentialsTest {

    @Autowired
    private NaverApiClient naverApiClient;

    @Test
    @DisplayName("NAVER_* 환경변수가 하나도 없어도 빈이 만들어진다")
    void contextStartsWithoutAnyMapCredentials() {
        assertThat(naverApiClient)
                .as("""
                        지도 키가 없다고 컨텍스트가 죽으면
                        앨범·타임라인·인증까지 함께 못 뜬다.""")
                .isNotNull();
        assertThat(naverApiClient.localSearchCache()).isNotNull();
        assertThat(naverApiClient.reverseGeocodeCache()).isNotNull();
    }

    @Configuration
    static class TestConfig {
        @Bean
        RestTemplate restTemplate() {
            return new RestTemplate();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
