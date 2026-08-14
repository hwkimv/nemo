package com.nemo.backend.domain.photo.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "S3가 없어도 애플리케이션은 기동해야 한다"를 고정한다.
 *
 * 예전에는 기동 시 headBucket()이 연결에 실패하면 예외를 던져 컨텍스트 생성이 중단됐다.
 * 그러면 S3와 아무 관계 없는 조회 API(앨범 목록·타임라인·지도)까지 함께 죽는다.
 * 스토리지 장애의 영향 범위는 "파일을 다루는 요청"으로 좁혀져야 한다.
 *
 * 이 문제는 성능 측정 중 앱이 뜨지 않아 발견했고, CI에서 컨테이너 기동을 검증하려 할 때
 * 다시 걸림돌이 됐다. (CI 러너에는 S3가 없다)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("S3 기동 결합도")
class S3PhotoStorageStartupTest {

    private S3PhotoStorage newStorage(S3Client s3Client, boolean createBucketIfMissing) {
        return new S3PhotoStorage(
                s3Client,
                "nemo-test-bucket",
                createBucketIfMissing,
                "ap-northeast-2"
        );
    }

    @Test
    @DisplayName("S3에 연결되지 않아도 기동한다 (조회 API를 함께 죽이지 않는다)")
    void startsEvenWhenS3IsUnreachable() {
        S3Client s3Client = mock(S3Client.class);
        when(s3Client.headBucket(any(Consumer.class)))
                .thenThrow(SdkClientException.create(
                        "Unable to execute HTTP request: Connect to localhost:4566 failed"));

        assertThatCode(() -> newStorage(s3Client, false))
                .as("S3 연결 실패가 애플리케이션 기동을 막으면 안 된다")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("버킷이 없고 생성도 요청하지 않았으면 그대로 기동한다")
    void startsWhenBucketMissingAndCreationNotRequested() {
        S3Client s3Client = mock(S3Client.class);
        when(s3Client.headBucket(any(Consumer.class)))
                .thenThrow(NoSuchBucketException.builder().message("no such bucket").build());

        assertThatCode(() -> newStorage(s3Client, false))
                .doesNotThrowAnyException();
    }
}
