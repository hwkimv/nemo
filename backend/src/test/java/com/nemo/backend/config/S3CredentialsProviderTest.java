package com.nemo.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3 자격증명을 어디서 가져오는지 고정한다.
 *
 * <p>예전에는 {@code StaticCredentialsProvider}만 있어서
 * <b>AWS 위에서 돌아도 IAM Role을 쓸 수 없었다.</b> 정적 키를 넣는 것 말고 방법이 없었다.
 *
 * <p>장기 Access Key는 한 번 새면 직접 폐기하기 전까지 계속 유효하다.
 * IAM Role은 수 시간마다 자동으로 도는 임시 자격증명을 준다.
 * 이 테스트는 <b>키를 비우면 Role을 쓴다</b>는 것을 못 박는다.
 */
@DisplayName("S3 자격증명 선택")
class S3CredentialsProviderTest {

    private AwsCredentialsProvider providerFor(String accessKey, String secretKey) {
        S3Config config = new S3Config();
        ReflectionTestUtils.setField(config, "accessKey", accessKey);
        ReflectionTestUtils.setField(config, "secretKey", secretKey);
        return (AwsCredentialsProvider) ReflectionTestUtils.invokeMethod(config, "credentialsProvider");
    }

    @Test
    @DisplayName("키가 비어 있으면 IAM Role에서 받는다")
    void blankKeysUseIamRole() {
        assertThat(providerFor("", ""))
                .as("""
                        AWS 위에서는 정적 키를 두지 않는다.
                        인스턴스 프로파일이 주는 임시 자격증명을 쓴다.""")
                .isInstanceOf(DefaultCredentialsProvider.class);
    }

    @Test
    @DisplayName("키가 있으면 그 키를 쓴다 (LocalStack·AWS 밖 실행)")
    void staticKeysAreUsedWhenPresent() {
        assertThat(providerFor("test", "test"))
                .as("LocalStack은 test/test 정적 키를 쓴다. 이 경로가 깨지면 로컬 개발이 멈춘다")
                .isInstanceOf(StaticCredentialsProvider.class);
    }

    @Test
    @DisplayName("한쪽만 있으면 정적 키로 보지 않는다")
    void partialKeysFallBackToIamRole() {
        assertThat(providerFor("only-access-key", ""))
                .as("반쪽짜리 키로 인증하려다 실패하는 것보다 Role을 찾는 편이 낫다")
                .isInstanceOf(DefaultCredentialsProvider.class);
        assertThat(providerFor("", "only-secret")).isInstanceOf(DefaultCredentialsProvider.class);
    }
}
