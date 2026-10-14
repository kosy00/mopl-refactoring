package com.codeit.mopl.domain.message.conversation.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
  @DisplayName("Redis 장애 시 join/leave/touch는 예외를 전파하지 않는다")
  void writeOperations_doNotPropagateRedisError() {
    when(stringRedisTemplate.opsForSet()).thenThrow(new RedisConnectionFailureException("down"));
    when(stringRedisTemplate.expire(anyString(), any(java.time.Duration.class)))
        .thenThrow(new RedisConnectionFailureException("down"));

    assertThatCode(() -> registry.join(userId, conversationId, "s:1")).doesNotThrowAnyException();
    assertThatCode(() -> registry.leave(userId, conversationId, "s:1")).doesNotThrowAnyException();
    assertThatCode(() -> registry.touch(userId, conversationId)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("touch는 활성 채팅방 키의 TTL을 연장한다")
  void touch_extendsTtl() {
    registry.touch(userId, conversationId);

    verify(stringRedisTemplate).expire(key, ConversationSubscriptionRegistry.TTL);
  }
}
