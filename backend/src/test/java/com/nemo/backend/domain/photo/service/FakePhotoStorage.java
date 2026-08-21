package com.nemo.backend.domain.photo.service;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 테스트에서 S3 자리를 대신하는 저장소.
 *
 * <p>정합성 문제는 "S3가 실패하는 순간"에만 드러난다. 실제 S3나 LocalStack으로는
 * <b>원하는 지점에 정확히</b> 실패를 넣을 수 없다. 여기서 확인하려는 것은
 * AWS SDK의 동작이 아니라 <b>두 저장소를 건드리는 순서</b>이므로 이걸로 충분하다.
 *
 * <p>단순 Mockito mock 대신 이 클래스를 쓰는 이유는 <b>상태를 들고 있어야</b>
 * "지웠는데 아직 남아 있다" 같은 불일치를 눈으로 확인할 수 있기 때문이다.
 */
public class FakePhotoStorage implements PhotoStorage {

    /** 아주 작은 1x1 PNG. 실제 이미지여야 S3PhotoStorage 검증 로직과 같은 경로를 탄다. */
    public static final byte[] PNG_BYTES = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    /** 지금 저장소에 실제로 존재하는 키 */
    private final Set<String> existing = new LinkedHashSet<>();
    /** store()로 올린 키 (지워졌어도 기록은 남는다) */
    private final List<String> stored = new ArrayList<>();
    /** delete()가 호출된 키 */
    private final List<String> deleted = new ArrayList<>();

    private final AtomicReference<RuntimeException> deleteFailure = new AtomicReference<>();
    private final AtomicReference<RuntimeException> storeFailure = new AtomicReference<>();
    /** 앞으로 몇 번 더 delete를 실패시킬지. 재시도 성공 시나리오를 만들 때 쓴다. */
    private int deleteFailuresRemaining = 0;

    @Override
    public synchronized String store(MultipartFile file) {
        RuntimeException fail = storeFailure.get();
        if (fail != null) throw fail;
        String key = "albums/test/" + UUID.randomUUID() + ".webp";
        existing.add(key);
        stored.add(key);
        return key;
    }

    @Override
    public synchronized String storeBytes(byte[] data, String originalFilename, String contentType) {
        return store(null);
    }

    @Override
    public synchronized void delete(String key) {
        deleted.add(key);
        if (deleteFailuresRemaining > 0) {
            deleteFailuresRemaining--;
            throw new IllegalStateException("S3 delete 실패 (남은 실패 횟수 " + deleteFailuresRemaining + ")");
        }
        RuntimeException fail = deleteFailure.get();
        if (fail != null) throw fail;
        // 이미 없는 키를 지워도 조용히 성공한다. S3의 DeleteObject도 같은 성질이다.
        existing.remove(key);
    }

    // ─────────────────────── 테스트 조작 ───────────────────────

    public synchronized void reset() {
        existing.clear();
        stored.clear();
        deleted.clear();
        deleteFailure.set(null);
        storeFailure.set(null);
        deleteFailuresRemaining = 0;
    }

    /** 저장된 키는 그대로 두고 호출 기록만 비운다. */
    public synchronized void resetCallLog() {
        stored.clear();
        deleted.clear();
        deleteFailure.set(null);
        storeFailure.set(null);
        deleteFailuresRemaining = 0;
    }

    /** 업로드를 거치지 않고 저장소에 파일이 있는 상태를 만든다. */
    public synchronized void putForTest(String key) {
        existing.add(key);
    }

    public synchronized void failDelete(RuntimeException e) {
        deleteFailure.set(e);
    }

    /** 다음 n번의 delete만 실패시키고 그 뒤에는 성공한다. */
    public synchronized void failDeleteTimes(int times) {
        deleteFailuresRemaining = times;
    }

    public synchronized void failStore(RuntimeException e) {
        storeFailure.set(e);
    }

    public synchronized List<String> storedKeys() {
        return List.copyOf(stored);
    }

    public synchronized List<String> deletedKeys() {
        return List.copyOf(deleted);
    }

    public synchronized Set<String> existingKeys() {
        return Set.copyOf(existing);
    }

    public synchronized boolean exists(String key) {
        return existing.contains(key);
    }

    /**
     * 테스트에서 이 저장소를 실제 S3PhotoStorage 자리에 끼워 넣는다.
     *
     * <p>@Primary를 하나 더 붙이면 "primary 후보가 둘"이라 컨텍스트가 뜨지 않는다.
     * 그래서 <b>같은 빈 이름</b>으로 정의를 덮어쓴다.
     * 테스트에서만 쓰므로 {@code spring.main.allow-bean-definition-overriding=true}가 필요하다.
     */
    @TestConfiguration
    public static class Config {
        @Bean("s3PhotoStorage")
        public FakePhotoStorage fakePhotoStorage() {
            return new FakePhotoStorage();
        }
    }
}
