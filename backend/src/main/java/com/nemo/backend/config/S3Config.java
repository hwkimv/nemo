package com.nemo.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.time.Duration;

@Slf4j
@Configuration
public class S3Config {

    @Value("${app.s3.region}")
    private String region;                // LocalStack/실AWS 공통 (예: ap-northeast-2)

    @Value("${app.s3.endpoint:}")         // LocalStack: http://localhost:4566 , 실AWS: 빈칸
    private String endpoint;

    // 비워 두면 IAM Role(인스턴스 프로파일·ECS TaskRole)에서 자격증명을 받는다.
    // 아래 credentialsProvider() 주석 참고.
    @Value("${app.s3.accessKey:}")
    private String accessKey;

    @Value("${app.s3.secretKey:}")
    private String secretKey;

    @Value("${app.s3.pathStyle:true}")    // LocalStack=true, 실AWS=false 권장
    private boolean pathStyle;

    /**
     * 자격증명을 어디서 가져올지 고른다.
     *
     * <h3>세 갈래로 나눈다</h3>
     * <ul>
     *   <li><b>둘 다 비어 있으면</b> {@link DefaultCredentialsProvider} —
     *       EC2 인스턴스 프로파일, ECS TaskRole 순으로 찾는다. AWS 위에서 도는 경우다.</li>
     *   <li><b>둘 다 있으면</b> 그 키를 쓴다. LocalStack(test/test)과 AWS 밖 실행이다.</li>
     *   <li><b>하나만 있으면 즉시 실패시킨다.</b></li>
     * </ul>
     *
     * <h3>왜 반쪽 설정을 그냥 넘기지 않는가</h3>
     * 예전에는 "둘 다 있을 때만 정적 키, 그 외는 Role" 이었다.
     * 그러면 {@code AWS_ACCESS_KEY} 만 넣고 {@code AWS_SECRET_KEY} 를 빠뜨린 설정 실수가
     * <b>조용히 DefaultCredentialsProvider 로 넘어간다.</b>
     *
     * <p>개발자 PC 라면 그 다음 순서인 {@code ~/.aws} 를 뒤져
     * <b>의도하지 않은 다른 AWS 계정과 버킷에 붙을 수 있다.</b>
     * 사진을 엉뚱한 버킷에 쓰고도 성공한 것처럼 보인다.
     *
     * <p>설정 실수는 조용히 다른 동작으로 넘어가는 것보다 기동 시점에 터지는 편이 낫다.
     *
     * <h3>왜 IAM Role이 나은가</h3>
     * 장기 Access Key는 <b>한 번 새면 직접 폐기하기 전까지 계속 유효</b>하다.
     * 환경변수·배포 설정·로그·프로세스 목록 어디에든 남을 수 있다.
     * 인스턴스 프로파일은 <b>수 시간마다 자동으로 도는 임시 자격증명</b>을 준다.
     * 코드도 배포 설정도 키를 알지 못한다.
     *
     * <p>예전에는 {@code StaticCredentialsProvider}만 있어서
     * <b>AWS 위에서 돌아도 인스턴스 프로파일을 쓸 수 없었다.</b>
     * 정적 키를 넣는 것 말고는 방법이 없는 구조였다.
     */
    private AwsCredentialsProvider credentialsProvider() {
        boolean hasAccess = accessKey != null && !accessKey.isBlank();
        boolean hasSecret = secretKey != null && !secretKey.isBlank();

        if (hasAccess != hasSecret) {
            throw new IllegalStateException(
                    "S3 자격증명 설정이 반쪽입니다. app.s3.accessKey 와 app.s3.secretKey 는 "
                            + "둘 다 있거나 둘 다 없어야 합니다. "
                            + "(accessKey=" + (hasAccess ? "있음" : "없음")
                            + ", secretKey=" + (hasSecret ? "있음" : "없음") + ") "
                            + "IAM Role 을 쓰려면 둘 다 비우십시오.");
        }

        if (hasAccess) {
            log.info("[S3] 정적 Access Key로 인증한다 (LocalStack 또는 AWS 밖 실행)");
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        }
        log.info("[S3] IAM Role에서 자격증명을 받는다 (인스턴스 프로파일 / TaskRole)");
        return DefaultCredentialsProvider.create();
    }

    @Bean
    public S3Client s3Client() {
        var creds = credentialsProvider();

        var s3Conf = S3Configuration.builder()
                .pathStyleAccessEnabled(pathStyle)
                .build();

        var http = ApacheHttpClient.builder()
                .connectionTimeout(Duration.ofSeconds(5))
                .socketTimeout(Duration.ofSeconds(30))
                .maxConnections(64)
                .build();

        var override = ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofSeconds(60))
                .apiCallAttemptTimeout(Duration.ofSeconds(30))
                .build();

        var builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(creds)
                .httpClient(http)
                .overrideConfiguration(override)
                .serviceConfiguration(s3Conf);

        if (endpoint != null && !endpoint.isBlank()) {
            builder = builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }
}
