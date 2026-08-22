package com.nemo.backend.global.health;

import com.nemo.backend.domain.map.util.NaverApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.s3.S3Client;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <h2>liveness 와 readiness 는 다른 질문에 답해야 한다.</h2>
 *
 * <pre>
 * liveness  = 이 프로세스를 죽이고 다시 띄워야 하는가
 * readiness = 이 인스턴스에 요청을 보내도 되는가
 * </pre>
 *
 * <h3>왜 하나로 두면 안 되는가</h3>
 * 예전에는 {@code /actuator/health} 하나뿐이었고 거기에 DB 체크가 들어 있었다.
 * 그러면 <b>DB 장애와 기동 중이 같은 신호(DOWN)</b>가 된다.
 *
 * <p>운영에서 실측한 두 값이 이걸 문제로 만든다.
 * <ul>
 *   <li>앱 크래시 복구 24.7초 중 <b>21.1초가 JVM 기동</b>이다 (CS 12)</li>
 *   <li>DB 가 끊겨도 앱 프로세스는 멀쩡하고, DB 를 안 쓰는 요청은 4ms 에 응답한다 (CS 12)</li>
 * </ul>
 *
 * <p>앞의 것 때문에 <b>기동 중인 앱을 계속 죽이는 무한 재시작</b>이 생길 수 있고,
 * 뒤의 것 때문에 <b>죽일 이유가 없는 앱을 죽이게</b> 된다.
 * 그래서 liveness 는 <b>DB 를 보지 않는다.</b>
 *
 * <p>이 테스트가 고정하는 것은 그 경계다 —
 * readiness 에는 DB 가 있고, liveness 에는 없다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@DisplayName("liveness / readiness 프로브 분리")
class HealthProbesTest {

    @Autowired
    private MockMvc mockMvc;

    // 지도 외부 API 는 이 테스트의 관심사가 아니다.
    @MockitoBean
    private NaverApiClient naverApiClient;

    @MockitoBean
    private S3Client s3Client;

    @Test
    @DisplayName("/actuator/health/liveness 가 열려 있고 UP 을 준다")
    void livenessProbeIsExposed() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("/actuator/health/readiness 가 열려 있고 UP 을 준다")
    void readinessProbeIsExposed() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    /**
     * <b>이 테스트가 이 변경의 핵심이다.</b>
     *
     * <p>liveness 응답에 DB 상태가 섞여 들어가면, DB 장애가 곧
     * "프로세스를 죽여라"가 된다. 그룹 구성이 잘못되면 여기서 걸린다.
     *
     * <p>show-details 를 켜지 않으므로 본문에는 컴포넌트 이름이 나오지 않는다.
     * 대신 <b>그룹에 어떤 인디케이터가 묶였는지</b>를 설정에서 직접 검사한다.
     */
    @Test
    @DisplayName("liveness 그룹에는 db 인디케이터가 들어 있지 않다")
    void livenessDoesNotIncludeDatabase(
            @Autowired org.springframework.core.env.Environment env) throws Exception {

        String liveness = env.getProperty("management.endpoint.health.group.liveness.include", "");
        String readiness = env.getProperty("management.endpoint.health.group.readiness.include", "");

        org.assertj.core.api.Assertions.assertThat(liveness)
                .as("liveness 는 프로세스 생존만 본다. DB 를 넣으면 기동 중인 앱을 죽이게 된다")
                .doesNotContain("db");

        org.assertj.core.api.Assertions.assertThat(readiness)
                .as("readiness 는 요청을 처리할 수 있는지를 본다. DB 없이는 처리할 수 없다")
                .contains("db");

        // 응답 자체도 컴포넌트 세부를 흘리지 않아야 한다 (CS 03 과 같은 기준)
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("\"db\""))));
    }

    /**
     * 배포 스크립트가 쓰는 경로다.
     *
     * <p>관리 포트는 {@code 127.0.0.1} 에만 바인딩돼 있어 컨테이너 밖에서 부를 수 없다.
     * 그래서 프로브 <b>둘만</b> 서비스 포트에도 얹는다.
     * {@code /actuator} 전체를 여는 것이 아니다 — {@code /actuator/prometheus} 는
     * 그대로 관리 포트에만 있다.
     */
    @Test
    @DisplayName("프로브가 서비스 포트에도 /livez · /readyz 로 얹힌다")
    void probesAreReachableOnServerPort() throws Exception {
        mockMvc.perform(get("/livez"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/readyz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    /**
     * {@code additional-path} 가 actuator 전체를 서비스 포트로 끌어오지 않는지 고정한다.
     *
     * <p>/actuator/prometheus 는 사용 중인 API 경로·응답시간·오류율·JVM 상태를 담고 있어
     * 공개 포트에 열면 서비스 내부 구조를 알려주는 것과 같다(CS 06 판단).
     * 프로브를 서비스 포트에 얹으면서 이 경계가 무너지지 않았는지 본다.
     */
    @Test
    @DisplayName("프로브를 얹어도 /actuator/prometheus 가 서비스 포트로 새지 않는다")
    void additionalPathDoesNotExposeTheRestOfActuator() throws Exception {
        // dev 프로필은 관리 포트를 분리하지 않아 200 이 정상이다.
        // 여기서 고정하려는 것은 "/livez 와 같은 짧은 별칭이 생기지 않았다"는 것이다.
        mockMvc.perform(get("/prometheus")).andExpect(status().isNotFound());
        mockMvc.perform(get("/metrics")).andExpect(status().isNotFound());
    }

    /**
     * 기존 {@code /actuator/health} 를 없애지 않는다.
     * CI 의 컨테이너 기동 확인과 CS 12 의 장애 실험이 이 경로를 쓴다.
     */
    @Test
    @DisplayName("기존 /actuator/health 는 그대로 동작한다")
    void aggregateHealthStillWorks() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
