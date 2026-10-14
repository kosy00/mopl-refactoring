package com.codeit.mopl.domain.message.conversation.repository;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

/**
 * 사용자가 현재 열어둔(STOMP 구독 중인) DM 채팅방을 Redis에 기록한다.
 *
 * <p>key   : dm:active:{userId}:{conversationId}
 * <p>value : Set of "{sessionId}:{subscriptionId}" — 탭/기기(구독) 단위로 기록
 *
 * <ul>
 *   <li>다중 탭: 구독 하나가 해제돼도 다른 구독이 남아 있으면 계속 활성 상태로 판단한다.</li>
 *   <li>TTL 갱신 기준 = WebSocket 연결 생존: 각 서버 인스턴스가 자신이 보유한 DM 구독을 메모리에 기록하고
 *       주기적으로 해당 키의 TTL을 갱신한다. 메시지를 보내지 않고 보기만 하는 사용자도 만료되지 않으며,
 *       서버가 비정상 종료되면 갱신이 멈춰 TTL 안에 키가 자동 만료된다.</li>
 *   <li>Fail-open: Redis 장애 시 예외를 전파하지 않는다. 조회 실패는 "비활성"으로 간주해
 *       알림을 보낸다(알림 누락보다 중복 알림이 낫다).</li>
 * </ul>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ConversationSubscriptionRegistry {

  static final String KEY_PREFIX = "dm:active:";
  static final Duration TTL = Duration.ofMinutes(5);
  static final long REFRESH_INTERVAL_MS = 120_000L; // 2분 (TTL보다 충분히 짧게)

  private final StringRedisTemplate stringRedisTemplate;

  /** 이 서버 인스턴스가 보유한 DM 구독: connectionId -> Redis key */
  private final Map<String, String> localConnections = new ConcurrentHashMap<>();

  public void join(UUID userId, UUID conversationId, String connectionId) {
    String key = key(userId, conversationId);
    localConnections.put(connectionId, key);
    try {
      stringRedisTemplate.opsForSet().add(key, connectionId);
      stringRedisTemplate.expire(key, TTL);
    } catch (Exception e) {
      log.warn("[DM 활성 채팅방] join 실패 - userId={}, conversationId={}", userId, conversationId, e);
    }
  }

  public void leave(UUID userId, UUID conversationId, String connectionId) {
    localConnections.remove(connectionId);
    try {
      stringRedisTemplate.opsForSet().remove(key(userId, conversationId), connectionId);
    } catch (Exception e) {
      // 실패해도 이 서버는 더 이상 갱신하지 않으므로, 다른 구독이 없으면 TTL로 정리된다.
      log.warn("[DM 활성 채팅방] leave 실패 - userId={}, conversationId={}", userId, conversationId, e);
    }
  }

  public boolean isActive(UUID userId, UUID conversationId) {
    try {
      Long count = stringRedisTemplate.opsForSet().size(key(userId, conversationId));
      return count != null && count > 0;
    } catch (Exception e) {
      log.warn("[DM 활성 채팅방] 조회 실패 - 알림을 보내도록 비활성으로 처리 - userId={}, conversationId={}",
          userId, conversationId, e);
      return false;
    }
  }

  /**
   * 이 서버가 아직 WebSocket 연결을 보유하고 있는 DM 구독의 TTL을 주기적으로 연장한다.
   */
  @Scheduled(fixedDelay = REFRESH_INTERVAL_MS)
  public void refreshLocalConnections() {
    Set<String> keys = new HashSet<>(localConnections.values()); // 같은 방을 연 탭이 여러 개여도 키당 1번
    for (String key : keys) {
      try {
        stringRedisTemplate.expire(key, TTL);
      } catch (Exception e) {
        log.warn("[DM 활성 채팅방] TTL 갱신 실패 - key={}", key, e);
      }
    }
  }

  static String key(UUID userId, UUID conversationId) {
    return KEY_PREFIX + userId + ":" + conversationId;
  }
}
