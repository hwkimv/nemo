package com.nemo.backend.domain.storage;

import com.nemo.backend.domain.map.util.NaverApiClient;
import com.nemo.backend.domain.photo.entity.Photo;
import com.nemo.backend.domain.photo.repository.PhotoRepository;
import com.nemo.backend.domain.photo.service.PhotoService;
import com.nemo.backend.domain.photo.service.PhotoStorage;
import com.nemo.backend.domain.storage.exception.PhotoLimitExceededException;
import com.nemo.backend.domain.user.entity.User;
import com.nemo.backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 로컬 PostgreSQL에서 사진 한도 잠금의 보장 범위와 DB 동작을 측정한다.
 *
 * <p>기본 테스트에서는 제외된다. Docker의 전용 DB를 켠 뒤
 * {@code ./gradlew postgresConcurrencyMeasurement}로만 실행한다.</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:55433/nemo_concurrency_measurement",
        "spring.datasource.username=nemo_concurrency",
        "spring.datasource.password=nemo_concurrency_local_only",
        "spring.datasource.driver-class-name=org.postgresql.Driver"
})
@ActiveProfiles("benchmark")
@Tag("postgres-concurrency")
@DisplayName("PostgreSQL 사진 저장 한도 동시성 측정")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresPhotoQuotaConcurrencyMeasurementTest {

    private static final int MAX_PHOTOS = 20;
    private static final int CONCURRENT_UPLOADS = 8;

    @MockitoBean
    private NaverApiClient naverApiClient;
    @MockitoBean
    private PhotoStorage photoStorage;

    @Autowired
    private PhotoService photoService;
    @Autowired
    private PhotoRepository photoRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DataSource dataSource;

    @BeforeAll
    void verifyDedicatedPostgresTarget() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT current_setting('server_version')")) {
            assertThat(connection.getCatalog()).isEqualTo("nemo_concurrency_measurement");
            assertThat(connection.getMetaData().getURL())
                    .isEqualTo("jdbc:postgresql://127.0.0.1:55433/nemo_concurrency_measurement");
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("17.10");
        }
    }

    @Test
    @DisplayName("한 자리에서 동시 8건이면 1건만 저장하고 최종 20장을 지킨다")
    void quotaIsPreservedUnderEightConcurrentRequests() throws Exception {
        User user = createUser("quota");
        for (int i = 0; i < MAX_PHOTOS - 1; i++) {
            savePhoto(user.getId());
        }

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_UPLOADS);
        CountDownLatch startTogether = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENT_UPLOADS);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejectedByQuota = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
        CountDownLatch allRequestsStoredFile = new CountDownLatch(CONCURRENT_UPLOADS);
        CountDownLatch releaseFileStorage = new CountDownLatch(1);

        when(photoStorage.store(any())).thenAnswer(invocation -> {
            allRequestsStoredFile.countDown();
            if (!releaseFileStorage.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 요청이 파일 저장 지점에 모이지 못했다");
            }
            return "measurement/" + UUID.randomUUID() + ".jpg";
        });

        try {
            for (int i = 0; i < CONCURRENT_UPLOADS; i++) {
                pool.submit(() -> {
                    try {
                        startTogether.await();
                        photoService.uploadHybrid(
                                user.getId(),
                                null,
                                new MockMultipartFile(
                                        "image",
                                        "measurement.jpg",
                                        "image/jpeg",
                                        new byte[]{1, 2, 3}
                                ),
                                "인생네컷",
                                "측정 위치",
                                null,
                                null,
                                null,
                                null
                        );
                        accepted.incrementAndGet();
                    } catch (Throwable error) {
                        if (hasCause(error, PhotoLimitExceededException.class)) {
                            rejectedByQuota.incrementAndGet();
                        } else {
                            unexpected.add(error);
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }

            long startedAt = System.nanoTime();
            startTogether.countDown();
            boolean allReachedStorage = allRequestsStoredFile.await(10, TimeUnit.SECONDS);
            releaseFileStorage.countDown();
            assertThat(allReachedStorage)
                    .as("8개 요청 모두 사전 한도 확인을 통과하고 파일 저장 지점에 도달해야 한다")
                    .isTrue();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            long elapsedMs = elapsedMillis(startedAt);
            int finalCount = photoRepository.countByUserIdAndDeletedIsFalse(user.getId());

            System.out.printf(
                    "POSTGRES_QUOTA accepted=%d rejected=%d finalCount=%d elapsedMs=%d%n",
                    accepted.get(), rejectedByQuota.get(), finalCount, elapsedMs
            );

            assertThat(unexpected).isEmpty();
            assertThat(accepted.get()).isEqualTo(1);
            assertThat(rejectedByQuota.get()).isEqualTo(CONCURRENT_UPLOADS - 1);
            assertThat(finalCount).isEqualTo(MAX_PHOTOS);
        } finally {
            releaseFileStorage.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("잠긴 사용자 행을 기다린 뒤 잠금 해제 후 진행한다")
    void waitsForLockedUserRow() throws Exception {
        User user = createUser("wait");
        ExecutorService pool = Executors.newSingleThreadExecutor();

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lockUser(holder, user.getId());

            CountDownLatch contenderReady = new CountDownLatch(1);
            AtomicInteger contenderPid = new AtomicInteger();
            Future<DatabaseAttempt> future = pool.submit(() ->
                    attemptLock(user.getId(), null, contenderReady, contenderPid)
            );

            assertThat(contenderReady.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(waitUntilLockWait(contenderPid.get(), 5_000)).isTrue();
            Thread.sleep(300);
            holder.commit();

            DatabaseAttempt result = future.get(5, TimeUnit.SECONDS);
            System.out.printf("POSTGRES_LOCK_WAIT elapsedMs=%d sqlState=%s%n",
                    result.elapsedMs(), result.sqlState());

            assertThat(result.sqlState()).isNull();
            assertThat(result.elapsedMs()).isBetween(250L, 6_500L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("lock_timeout이 지나면 PostgreSQL 55P03으로 중단한다")
    void stopsWaitingAtConfiguredLockTimeout() throws Exception {
        User user = createUser("timeout");
        ExecutorService pool = Executors.newSingleThreadExecutor();

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lockUser(holder, user.getId());

            CountDownLatch contenderReady = new CountDownLatch(1);
            AtomicInteger contenderPid = new AtomicInteger();
            Future<DatabaseAttempt> future = pool.submit(() ->
                    attemptLock(user.getId(), "250ms", contenderReady, contenderPid)
            );

            assertThat(contenderReady.await(5, TimeUnit.SECONDS)).isTrue();
            DatabaseAttempt result = future.get(5, TimeUnit.SECONDS);
            holder.rollback();

            System.out.printf("POSTGRES_LOCK_TIMEOUT elapsedMs=%d sqlState=%s%n",
                    result.elapsedMs(), result.sqlState());

            assertThat(result.sqlState()).isEqualTo("55P03");
            assertThat(result.elapsedMs()).isBetween(150L, 2_000L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("의도적으로 만든 교차 잠금에서 한 트랜잭션을 40P01로 중단한다")
    void detectsDeliberatelyCreatedDeadlock() throws Exception {
        User firstUser = createUser("deadlock-a");
        User secondUser = createUser("deadlock-b");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Connection first = dataSource.getConnection();
        Connection second = dataSource.getConnection();

        try {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            setLocal(first, "lock_timeout", "5s");
            setLocal(second, "lock_timeout", "5s");
            lockUser(first, firstUser.getId());
            lockUser(second, secondUser.getId());

            CountDownLatch startTogether = new CountDownLatch(1);
            Future<DatabaseAttempt> firstAttempt = pool.submit(() ->
                    attemptSecondLock(first, secondUser.getId(), startTogether)
            );
            Future<DatabaseAttempt> secondAttempt = pool.submit(() ->
                    attemptSecondLock(second, firstUser.getId(), startTogether)
            );

            startTogether.countDown();
            List<DatabaseAttempt> results = List.of(
                    firstAttempt.get(10, TimeUnit.SECONDS),
                    secondAttempt.get(10, TimeUnit.SECONDS)
            );

            DatabaseAttempt aborted = results.stream()
                    .filter(result -> "40P01".equals(result.sqlState()))
                    .findFirst()
                    .orElseThrow();

            System.out.printf(
                    "POSTGRES_DEADLOCK_DETECTION abortedElapsedMs=%d results=%s%n",
                    aborted.elapsedMs(), results
            );

            assertThat(results).extracting(DatabaseAttempt::sqlState)
                    .containsExactlyInAnyOrder(null, "40P01");
            assertThat(aborted.elapsedMs()).isLessThan(5_000L);
        } finally {
            rollbackQuietly(first);
            rollbackQuietly(second);
            closeQuietly(first);
            closeQuietly(second);
            pool.shutdownNow();
        }
    }

    private User createUser(String prefix) {
        User user = new User();
        user.setEmail(prefix + "-" + UUID.randomUUID() + "@nemo.test");
        user.setPassword("{noop}irrelevant");
        user.setNickname(prefix);
        user.setProvider("local");
        user.setMaxPhotoCount(MAX_PHOTOS);
        return userRepository.save(user);
    }

    private void savePhoto(Long userId) {
        Photo photo = new Photo();
        photo.setUserId(userId);
        photo.setImageUrl("https://example.test/" + UUID.randomUUID() + ".jpg");
        photo.setDeleted(false);
        photoRepository.save(photo);
    }

    private DatabaseAttempt attemptLock(
            Long userId,
            String lockTimeout,
            CountDownLatch ready,
            AtomicInteger backendPid
    ) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            if (lockTimeout != null) {
                setLocal(connection, "lock_timeout", lockTimeout);
            }
            backendPid.set(backendPid(connection));
            ready.countDown();
            long startedAt = System.nanoTime();
            try {
                lockUser(connection, userId);
                connection.commit();
                return new DatabaseAttempt(elapsedMillis(startedAt), null);
            } catch (SQLException error) {
                connection.rollback();
                return new DatabaseAttempt(elapsedMillis(startedAt), error.getSQLState());
            }
        }
    }

    private DatabaseAttempt attemptSecondLock(
            Connection connection,
            Long userId,
            CountDownLatch startTogether
    ) throws Exception {
        startTogether.await();
        long startedAt = System.nanoTime();
        try {
            lockUser(connection, userId);
            connection.commit();
            return new DatabaseAttempt(elapsedMillis(startedAt), null);
        } catch (SQLException error) {
            connection.rollback();
            return new DatabaseAttempt(elapsedMillis(startedAt), error.getSQLState());
        }
    }

    private void lockUser(Connection connection, Long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM users WHERE id = ? FOR UPDATE"
        )) {
            statement.setLong(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
            }
        }
    }

    private int backendPid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT pg_backend_pid()")) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }

    private boolean waitUntilLockWait(int backendPid, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            try (Connection observer = dataSource.getConnection();
                 PreparedStatement statement = observer.prepareStatement(
                         "SELECT wait_event_type FROM pg_stat_activity WHERE pid = ?"
                 )) {
                statement.setInt(1, backendPid);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next() && "Lock".equals(resultSet.getString(1))) {
                        return true;
                    }
                }
            }
            Thread.sleep(10);
        }
        return false;
    }

    private void setLocal(Connection connection, String setting, String value) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL " + setting + " = '" + value + "'");
        }
    }

    private boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 측정 결과를 덮지 않도록 정리 예외는 무시한다.
        }
    }

    private void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // 측정 결과를 덮지 않도록 정리 예외는 무시한다.
        }
    }

    private record DatabaseAttempt(long elapsedMs, String sqlState) {
    }
}
