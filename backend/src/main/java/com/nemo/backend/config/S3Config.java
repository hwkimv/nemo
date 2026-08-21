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
     * <h3>왜 두 갈래인가</h3>
     * <ul>
     *   <li><b>키가 비어 있으면</b> {@link DefaultCredentialsProvider} —
     *       EC2 인스턴스 프로파일, ECS TaskRole, 환경변수, {@code ~/.aws} 순으로 찾는다.
     *       AWS 위에서 돌 때 이 방식을 쓴다.</li>
     *   <li><b>키가 있으면</b> 그 키를 쓴다. LocalStack(test/test)과
     *       AWS 밖에서 도는 환경이 여기에 해당한다.</li>
     * </ul>
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
        boolean hasStaticKey = accessKey != null && !accessKey.isBlank()
                && secretKey != null && !secretKey.isBlank();
        if (hasStaticKey) {
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
