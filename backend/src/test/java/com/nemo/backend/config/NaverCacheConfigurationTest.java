package com.nemo.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 캐시 정책이 <b>설정으로 분리돼 있는지</b>를 application.yml에서 직접 확인한다.
 *
 * <p>코드에 상수로 박아 두면 운영에서 값을 바꾸려고 재배포해야 한다.
 * 그리고 Local Search와 Reverse Geocoding이 <b>각각</b> 조절돼야 한다.
 * 하나로 묶여 있으면 성격이 다른 두 데이터를 같은 TTL로 강제하게 된다.
 */
class NaverCacheConfigurationTest {

    @Test
    void cachePolicyDefaultsAreConfigurable() throws IOException {
        PropertySource<?> properties = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .getFirst();

        // 업체 검색 결과: 바뀔 수 있으므로 5분
        assertThat(properties.getProperty("naver.cache.local-search.ttl-seconds"))
                .isEqualTo("${NAVER_LOCAL_SEARCH_CACHE_TTL_SECONDS:${NAVER_CACHE_TTL_SECONDS:300}}");
        assertThat(properties.getProperty("naver.cache.local-search.maximum-size"))
                .isEqualTo("${NAVER_LOCAL_SEARCH_CACHE_MAXIMUM_SIZE:${NAVER_CACHE_MAXIMUM_SIZE:1000}}");

        // 좌표 → 행정구역: 거의 안 바뀌므로 30분
        assertThat(properties.getProperty("naver.cache.reverse-geocoding.ttl-seconds"))
                .isEqualTo("${NAVER_REVERSE_GEOCODING_CACHE_TTL_SECONDS:${NAVER_CACHE_TTL_SECONDS:1800}}");
        assertThat(properties.getProperty("naver.cache.reverse-geocoding.maximum-size"))
                .isEqualTo("${NAVER_REVERSE_GEOCODING_CACHE_MAXIMUM_SIZE:${NAVER_CACHE_MAXIMUM_SIZE:1000}}");
    }
}
