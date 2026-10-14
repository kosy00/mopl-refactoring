package com.codeit.mopl.domain.message.conversation.repository;

import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 사용자가 현재 열어둔(STOMP 구독 중인) DM 채팅방을 Redis에 기록한다.
 *
 * <p>key   : dm:active:{userId}:{conversationId}
 * <p>value : Set of "{sessionId}:{subscriptionId}" — 탭/기기(구독) 단위로 기록
 *
 * <ul>
 *   <li>다중 탭: 구독 하나가 해제돼도 다른 구독이 남아 있으면 계속 활성 상태로 판단한다.</li>
 *   <li>TTL: 서버 비정상 종료로 UNSUBSCRIBE/DISCONNECT 이벤트가 유실돼도 키가 자동 만료된다.</li>
 *   <li>Fail-open: Redis 장애 시 예외를 전파하지 않는다. 조회 실패는 "비활성"으로 간주해
 *       알림을 보낸다(알림 누락보다 중복 알림이 나을 것이라고 판단).</li>
 * </ul>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ConversationSubscriptionRegistry {

  static final String KEY_PREFIX = "dm:active:";
  static final Duration TTL = Duration.ofHours(1);

  private final StringRedisTemplate stringRedisTemplate;

  public void join(UUID userId, UUID conversationId, String connectionId) {
    String key = key(userId, conversationId);
    try {
      stringRedisTemplate.opsForSet().add(key, connectionId);
      stringRedisTemplate.expire(key, TTL);
    } catch (Exception e) {
      log.warn("[DM 활성 채팅방] join 실패 - userId={}, conversationId={}", userId, conversationId, e);
    }
  }

  public void leave(UUID userId, UUID conversationId, String connectionId) {
    try {
      stringRedisTemplate.opsForSet().remove(key(userId, conversationId), connectionId);
    } catch (Exception e) {
      // 실패해도 TTL로 정리되므로 예외를 전파하지 않는다.
      log.warn("[DM 활성 채팅방] leave 실패 - userId={}, conversationId={}", userId, conversationId, e);
    }
  }

  /**
   * 채팅방에서 활동(메시지 전송)이 있으면 TTL을 연장한다. 키가 없으면 아무 일도 일어나지 않는다.
   */
  public void touch(UUID userId, UUID conversationId) {
    try {
      stringRedisTemplate.expire(key(userId, conversationId), TTL);
    } catch (Exception e) {
      log.warn("[DM 활성 채팅방] TTL 갱신 실패 - userId={}, conversationId={}", userId, conversationId, e);
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

  static String key(UUID userId, UUID conversationId) {
    return KEY_PREFIX + userId + ":" + conversationId;
  }
}
