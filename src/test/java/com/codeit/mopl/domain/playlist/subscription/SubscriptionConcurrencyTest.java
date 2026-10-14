package com.codeit.mopl.domain.playlist.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeit.mopl.domain.notification.service.NotificationService;
import com.codeit.mopl.domain.playlist.entity.Playlist;
import com.codeit.mopl.domain.playlist.repository.PlaylistRepository;
import com.codeit.mopl.domain.playlist.service.PlaylistDetailCache;
import com.codeit.mopl.domain.playlist.subscription.repository.SubscriptionRepository;
import com.codeit.mopl.domain.playlist.subscription.service.SubscriptionService;
import com.codeit.mopl.domain.user.entity.User;
import com.codeit.mopl.domain.user.repository.UserRepository;
import com.codeit.mopl.exception.playlist.subscription.SubscriptionDuplicateException;
import com.codeit.mopl.exception.playlist.subscription.SubscriptionNotFoundException;
import com.codeit.mopl.util.QueryDslConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 구독/구독 취소 동시성 테스트.
 *
 * <p>여러 스레드가 동시에 요청을 보내야 하므로 테스트 메서드를 트랜잭션으로 감싸지 않는다(NOT_SUPPORTED).
 * 각 서비스 호출이 자기 트랜잭션에서 실제로 커밋되고, 데이터 정리는 @AfterEach에서 직접 한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties =
    "spring.datasource.url=jdbc:h2:mem:subscription_concurrency;MODE=PostgreSQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1")
@Import({QueryDslConfig.class, SubscriptionService.class})
@EnableJpaRepositories(basePackages = {
    "com.codeit.mopl.domain.playlist.repository",
    "com.codeit.mopl.domain.playlist.subscription.repository",
    "com.codeit.mopl.domain.user.repository"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SubscriptionConcurrencyTest {

  private static final int USER_COUNT = 100;
  private static final int THREAD_POOL_SIZE = 32;

  @Autowired private SubscriptionService subscriptionService;
  @Autowired private SubscriptionRepository subscriptionRepository;
  @Autowired private PlaylistRepository playlistRepository;
  @Autowired private UserRepository userRepository;

  @MockitoBean private NotificationService notificationService;
  @MockitoBean private PlaylistDetailCache playlistDetailCache;

  private UUID playlistId;
  private List<UUID> subscriberIds;

  @BeforeEach
  void setUp() {
    User owner = userRepository.save(new User("owner@test.com", "password", "owner"));

    Playlist playlist = new Playlist();
    playlist.setUser(owner);
    playlist.setTitle("동시성 테스트 플레이리스트");
    playlist.setDescription("설명");
    playlistId = playlistRepository.save(playlist).getId();

    subscriberIds = new ArrayList<>();
    for (int i = 0; i < USER_COUNT; i++) {
      User user = userRepository.save(new User("user" + i + "@test.com", "password", "user" + i));
      subscriberIds.add(user.getId());
    }
  }

  @AfterEach
  void tearDown() {
    subscriptionRepository.deleteAllInBatch();
    playlistRepository.deleteAll();
    userRepository.deleteAllInBatch();
  }

  @Test
  @DisplayName("서로 다른 사용자 100명이 동시에 구독하면 구독자 수는 정확히 100이어야 한다")
  void concurrentSubscribe_differentUsers_countMatches() throws InterruptedException {
    Map<String, AtomicInteger> results = runConcurrently(USER_COUNT,
        i -> subscriptionService.subscribe(playlistId, subscriberIds.get(i)));

    long subscriptionRows = subscriptionRepository.findByPlaylistId(playlistId).size();
    long subscriberCount = playlistRepository.findById(playlistId).orElseThrow().getSubscriberCount();
    print("서로 다른 사용자 100명 동시 구독", results, subscriptionRows, subscriberCount);

    assertThat(results.getOrDefault("SUCCESS", new AtomicInteger()).get()).isEqualTo(USER_COUNT);
    assertThat(subscriptionRows).isEqualTo(USER_COUNT);
    assertThat(subscriberCount).isEqualTo(USER_COUNT);
  }

  @Test
  @DisplayName("같은 사용자가 동시에 10번 구독하면 1건만 성공하고 나머지는 중복 구독 예외(409)여야 한다")
  void concurrentSubscribe_sameUser_onlyOneSucceeds() throws InterruptedException {
    UUID subscriberId = subscriberIds.get(0);
    int requestCount = 10;

    Map<String, AtomicInteger> results = runConcurrently(requestCount,
        i -> subscriptionService.subscribe(playlistId, subscriberId));

    long subscriptionRows = subscriptionRepository.findByPlaylistId(playlistId).size();
    long subscriberCount = playlistRepository.findById(playlistId).orElseThrow().getSubscriberCount();
    print("같은 사용자 10회 동시 구독", results, subscriptionRows, subscriberCount);

    assertThat(results.getOrDefault("SUCCESS", new AtomicInteger()).get()).isEqualTo(1);
    assertThat(results.getOrDefault(SubscriptionDuplicateException.class.getSimpleName(), new AtomicInteger()).get())
        .isEqualTo(requestCount - 1);
    assertThat(subscriptionRows).isEqualTo(1);
    assertThat(subscriberCount).isEqualTo(1);
  }

  @Test
  @DisplayName("구독자 100명이 동시에 구독 취소하면 구독자 수는 정확히 0이어야 한다")
  void concurrentUnsubscribe_differentUsers_countMatches() throws InterruptedException {
    for (UUID subscriberId : subscriberIds) {             // 순차 구독으로 준비 (100명)
      subscriptionService.subscribe(playlistId, subscriberId);
    }

    Map<String, AtomicInteger> results = runConcurrently(USER_COUNT,
        i -> subscriptionService.unsubscribe(playlistId, subscriberIds.get(i)));

    long subscriptionRows = subscriptionRepository.findByPlaylistId(playlistId).size();
    long subscriberCount = playlistRepository.findById(playlistId).orElseThrow().getSubscriberCount();
    print("구독자 100명 동시 구독 취소", results, subscriptionRows, subscriberCount);

    assertThat(results.getOrDefault("SUCCESS", new AtomicInteger()).get()).isEqualTo(USER_COUNT);
    assertThat(subscriptionRows).isZero();
    assertThat(subscriberCount).isZero();
  }

  @Test
  @DisplayName("같은 사용자가 동시에 5번 구독 취소하면 1건만 성공하고 나머지는 구독 없음 예외(404)여야 한다")
  void concurrentUnsubscribe_sameUser_onlyOneSucceeds() throws InterruptedException {
    UUID subscriberId = subscriberIds.get(0);
    subscriptionService.subscribe(playlistId, subscriberId);
    int requestCount = 5;

    Map<String, AtomicInteger> results = runConcurrently(requestCount,
        i -> subscriptionService.unsubscribe(playlistId, subscriberId));

    long subscriptionRows = subscriptionRepository.findByPlaylistId(playlistId).size();
    long subscriberCount = playlistRepository.findById(playlistId).orElseThrow().getSubscriberCount();
    print("같은 사용자 5회 동시 구독 취소", results, subscriptionRows, subscriberCount);

    assertThat(results.getOrDefault("SUCCESS", new AtomicInteger()).get()).isEqualTo(1);
    assertThat(results.getOrDefault(SubscriptionNotFoundException.class.getSimpleName(), new AtomicInteger()).get())
        .isEqualTo(requestCount - 1);
    assertThat(subscriptionRows).isZero();
    assertThat(subscriberCount).isZero();
  }

  // ===================== 헬퍼 =====================

  /** taskCount개의 작업을 최대한 동시에 시작시키고, 결과(성공/예외 종류별 건수)를 모은다. */
  private Map<String, AtomicInteger> runConcurrently(int taskCount, Consumer<Integer> task)
      throws InterruptedException {
    ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    CountDownLatch ready = new CountDownLatch(Math.min(taskCount, THREAD_POOL_SIZE));
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(taskCount);
    Map<String, AtomicInteger> results = new ConcurrentHashMap<>();

    for (int i = 0; i < taskCount; i++) {
      final int index = i;
      executor.submit(() -> {
        ready.countDown();
        try {
          start.await();
          task.accept(index);
          results.computeIfAbsent("SUCCESS", k -> new AtomicInteger()).incrementAndGet();
        } catch (Exception e) {
          results.computeIfAbsent(e.getClass().getSimpleName(), k -> new AtomicInteger()).incrementAndGet();
        } finally {
          done.countDown();
        }
      });
    }

    ready.await(10, TimeUnit.SECONDS);
    start.countDown();                       // 출발 신호: 대기 중인 스레드가 동시에 시작
    done.await(60, TimeUnit.SECONDS);
    executor.shutdown();
    return results;
  }

  private void print(String scenario, Map<String, AtomicInteger> results, long rows, long count) {
    System.out.printf("%n[동시성 테스트] %s%n  결과: %s%n  구독 행 수: %d%n  subscriberCount: %d%n%n",
        scenario, results, rows, count);
  }
}
