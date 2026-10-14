package com.codeit.mopl.domain.message.conversation.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class ConversationSubscriptionRegistryTest {

  @Mock
  private StringRedisTemplate stringRedisTemplate;

  @Mock
  private SetOperations<String, String> setOperations;

  @InjectMocks
  private ConversationSubscriptionRegistry registry;

  private UUID userId;
  private UUID conversationId;
  private String key;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    conversationId = UUID.randomUUID();
    key = ConversationSubscriptionRegistry.key(userId, conversationId);
  }

  @Test
  @DisplayName("join 시 채팅방 키에 구독(connectionId)을 추가하고 TTL을 설정한다")
  void join_addsConnectionAndSetsTtl() {
    when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);

    registry.join(userId, conversationId, "session-1:sub-1");

    verify(setOperations).add(key, "session-1:sub-1");
    verify(stringRedisTemplate).expire(key, ConversationSubscriptionRegistry.TTL);
  }

  @Test
  @DisplayName("leave 시 키 전체가 아니라 해당 구독(connectionId)만 제거한다 - 다중 탭 대응")
  void leave_removesOnlyThatConnection() {
    when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);

    registry.leave(userId, conversationId, "session-1:sub-1");

    verify(setOperations).remove(key, "session-1:sub-1");
    verify(stringRedisTemplate, never()).delete(anyString());
  }

  @Test
  @DisplayName("다른 탭의 구독이 남아 있으면 활성 상태로 판단한다")
  void isActive_trueWhenAnyConnectionRemains() {
    when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
    when(setOperations.size(key)).thenReturn(1L);

    assertThat(registry.isActive(userId, conversationId)).isTrue();
  }

  @Test
  @DisplayName("남은 구독이 없으면 비활성 상태로 판단한다")
  void isActive_falseWhenNoConnection() {
    when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
    when(setOperations.size(key)).thenReturn(0L);

    assertThat(registry.isActive(userId, conversationId)).isFalse();
  }

  @Test
  @DisplayName("Redis 장애 시 isActive는 예외 없이 false를 반환한다 (fail-open: 알림 전송)")
  void isActive_failOpenOnRedisError() {
    when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
    when(setOperations.size(any())).thenThrow(new RedisConnectionFailureException("down"));

    assertThat(registry.isActive(userId, conversationId)).isFalse();
  }

  @Test
  @DisplayName("Redis 장애 시 join/leave는 예외를 전파하지 않는다")
  void writeOperations_doNotPropagateRedisError() {
    when(stringRedisTemplate.opsForSet()).thenThrow(new RedisConnectionFailureException("down"));

    assertThatCode(() -> registry.join(userId, conversationId, "s:1")).doesNotThrowAnyException();
    assertThatCode(() -> registry.leave(userId, conversationId, "s:1")).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("메시지를 보내지 않아도, 서버가 보유한 구독의 TTL은 주기적으로 갱신된다 (같은 방의 여러 탭은 키당 1회)")
  void refresh_extendsTtlOfLocalConnections() {
    when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
    UUID otherConversationId = UUID.randomUUID();
    String otherKey = ConversationSubscriptionRegistry.key(userId, otherConversationId);

    registry.join(userId, conversationId, "session-1:sub-1");
    registry.join(userId, conversationId, "session-2:sub-1");   // 같은 방, 다른 탭
    registry.join(userId, otherConversationId, "session-1:sub-2");
    clearInvocations(stringRedisTemplate);

    registry.refreshLocalConnections();

    verify(stringRedisTemplate, times(1)).expire(key, ConversationSubscriptionRegistry.TTL);
    verify(stringRedisTemplate, times(1)).expire(otherKey, ConversationSubscriptionRegistry.TTL);
  }

  @Test
  @DisplayName("구독이 해제된 채팅방은 더 이상 TTL을 갱신하지 않는다")
  void refresh_skipsLeftConnections() {
    when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
    registry.join(userId, conversationId, "session-1:sub-1");
    registry.leave(userId, conversationId, "session-1:sub-1");
    clearInvocations(stringRedisTemplate);

    registry.refreshLocalConnections();

    verify(stringRedisTemplate, never()).expire(anyString(), any(Duration.class));
  }

  @Test
  @DisplayName("Redis Set 삭제가 실패해도 해당 구독은 갱신 대상에서 빠진다 (TTL로 정리되도록)")
  void refresh_skipsConnectionEvenIfRedisLeaveFailed() {
    when(stringRedisTemplate.opsForSet())
        .thenReturn(setOperations)                                          // join
        .thenThrow(new RedisConnectionFailureException("down"));            // leave
    registry.join(userId, conversationId, "session-1:sub-1");
    registry.leave(userId, conversationId, "session-1:sub-1");
    clearInvocations(stringRedisTemplate);

    registry.refreshLocalConnections();

    verify(stringRedisTemplate, never()).expire(anyString(), any(Duration.class));
  }

  @Test
  @DisplayName("TTL 갱신 중 Redis 장애가 나도 예외를 전파하지 않는다")
  void refresh_doesNotPropagateRedisError() {
    when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
    registry.join(userId, conversationId, "session-1:sub-1");
    when(stringRedisTemplate.expire(anyString(), any(Duration.class)))
        .thenThrow(new RedisConnectionFailureException("down"));

    assertThatCode(() -> registry.refreshLocalConnections()).doesNotThrowAnyException();
  }
}
