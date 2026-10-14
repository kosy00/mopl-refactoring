package com.codeit.mopl.domain.playlist.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.codeit.mopl.domain.playlist.dto.PlaylistCachedDto;
import com.codeit.mopl.domain.playlist.entity.Playlist;
import com.codeit.mopl.domain.playlist.mapper.PlaylistMapper;
import com.codeit.mopl.domain.playlist.repository.PlaylistRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 실제 Spring 캐시 프록시를 띄워서 검증한다.
 * (Redis 대신 메모리 캐시를 쓰지만, @Cacheable이 프록시를 통해 동작하는지는 동일하게 확인된다)
 */
@SpringJUnitConfig
class PlaylistDetailCacheTest {

  @Configuration
  @EnableCaching
  static class Config {

    @Bean
    CacheManager cacheManager() {
      return new ConcurrentMapCacheManager(PlaylistDetailCache.CACHE_NAME);
    }

    @Bean
    PlaylistRepository playlistRepository() {
      return mock(PlaylistRepository.class);
    }

    @Bean
    PlaylistMapper playlistMapper() {
      return mock(PlaylistMapper.class);
    }

    @Bean
    PlaylistDetailCache playlistDetailCache(PlaylistRepository playlistRepository,
        PlaylistMapper playlistMapper, CacheManager cacheManager) {
      return new PlaylistDetailCache(playlistRepository, playlistMapper, cacheManager);
    }
  }

  @Autowired private PlaylistDetailCache playlistDetailCache;
  @Autowired private PlaylistRepository playlistRepository;
  @Autowired private PlaylistMapper playlistMapper;
  @Autowired private CacheManager cacheManager;

  private UUID playlistId;
  private PlaylistCachedDto cachedDto;

  @BeforeEach
  void setUp() {
    reset(playlistRepository, playlistMapper);
    cache().clear();

    playlistId = UUID.randomUUID();
    Playlist playlist = Playlist.builder().title("제목").description("설명").build();
    cachedDto = new PlaylistCachedDto(playlistId, null, "제목", "설명", null, 0, null);

    given(playlistRepository.findById(playlistId)).willReturn(Optional.of(playlist));
    given(playlistMapper.toCachedDto(playlist)).willReturn(cachedDto);
  }

  @AfterEach
  void tearDown() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  @DisplayName("같은 플레이리스트를 두 번 조회하면 두 번째는 캐시에서 반환한다 (DB 조회 1회)")
  void get_secondCallHitsCache() {
    PlaylistCachedDto first = playlistDetailCache.get(playlistId);
    PlaylistCachedDto second = playlistDetailCache.get(playlistId);

    assertThat(first).isEqualTo(second);
    verify(playlistRepository, times(1)).findById(playlistId);
  }

  @Test
  @DisplayName("트랜잭션 안에서 무효화를 요청하면 커밋 전까지는 캐시가 유지되고, 커밋 후에 지워진다")
  void evictAfterCommit_evictsOnlyAfterCommit() {
    playlistDetailCache.get(playlistId);
    TransactionSynchronizationManager.initSynchronization();

    playlistDetailCache.evictAfterCommit(playlistId);
    assertThat(cache().get(playlistId)).isNotNull();   // 커밋 전: 유지

    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);
    assertThat(cache().get(playlistId)).isNull();      // 커밋 후: 무효화
  }

  @Test
  @DisplayName("트랜잭션이 롤백되면 캐시를 지우지 않는다")
  void evictAfterCommit_keepsCacheOnRollback() {
    playlistDetailCache.get(playlistId);
    TransactionSynchronizationManager.initSynchronization();

    playlistDetailCache.evictAfterCommit(playlistId);
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

    assertThat(cache().get(playlistId)).isNotNull();
  }

  @Test
  @DisplayName("트랜잭션 밖에서 무효화를 요청하면 즉시 지운다")
  void evictAfterCommit_withoutTransaction_evictsImmediately() {
    playlistDetailCache.get(playlistId);

    playlistDetailCache.evictAfterCommit(playlistId);

    assertThat(cache().get(playlistId)).isNull();
  }

  @Test
  @DisplayName("무효화 후 다시 조회하면 DB에서 최신 데이터를 가져온다")
  void get_afterEvict_reloadsFromDb() {
    playlistDetailCache.get(playlistId);
    playlistDetailCache.evictAfterCommit(playlistId);

    playlistDetailCache.get(playlistId);

    verify(playlistRepository, times(2)).findById(playlistId);
  }

  private Cache cache() {
    return cacheManager.getCache(PlaylistDetailCache.CACHE_NAME);
  }
}
