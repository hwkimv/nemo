// backend/src/main/java/com/nemo/backend/global/config/SchedulingConfig.java
package com.nemo.backend.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @Scheduled}를 켠다.
 *
 * <p>지금은 S3 정리 워커 하나가 쓴다. Spring Boot에 이미 들어 있는 기능이라
 * 새로 붙이는 인프라나 라이브러리는 없다.
 *
 * <p>기본 스케줄러 스레드는 1개다. 정리 워커는 주기가 길고 하는 일도 가벼워 충분하다.
 * 스케줄 작업이 늘어 서로 밀리기 시작하면 그때 풀 크기를 손댄다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
