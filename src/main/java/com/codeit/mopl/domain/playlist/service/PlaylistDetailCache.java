package com.codeit.mopl.domain.playlist.service;

import com.codeit.mopl.domain.playlist.dto.PlaylistCachedDto;
import com.codeit.mopl.domain.playlist.entity.Playlist;
import com.codeit.mopl.domain.playlist.mapper.PlaylistMapper;
import com.codeit.mopl.domain.playlist.repository.PlaylistRepository;
import com.codeit.mopl.exception.playlist.PlaylistNotFoundException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 플레이리스트 상세 조회 캐시.
 *
 * <p>모든 사용자가 공유하는 정보(PlaylistCachedDto)만 캐싱한다.
 * 사용자별 값(subscribedByMe)은 캐시하지 않고 조회 시점에 합친다.
 *
 * <ul>
 *   <li>별도 빈으로 분리: 같은 클래스 안에서 호출하면(자기 호출) Spring AOP 프록시를 거치지 않아
 *       {@code @Cacheable}이 동작하지 않기 때문</li>
 *   <li>커밋 후 무효화: 트랜잭션 안에서 바로 지우면 커밋 전에 다른 요청이 옛 데이터를
 *       다시 캐싱할 수 있으므로, 트랜잭션 커밋이 끝난 뒤에 지운다</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaylistDetailCache {

  public static final String CACHE_NAME = "playlist:detail";

  private final PlaylistRepository playlistRepository;
  private final PlaylistMapper playlistMapper;
  private final CacheManager cacheManager;

  @Transactional(readOnly = true)
  @Cacheable(cacheNames = CACHE_NAME, key = "#playlistId")
  public PlaylistCachedDto get(UUID playlistId) {
    log.debug("[플레이리스트 캐시] 캐시 미스 - DB 조회 - playlistId = {}", playlistId);
    Playlist playlist = playlistRepository.findById(playlistId)
        .orElseThrow(() -> {
          log.warn("[플레이리스트 캐시] 플레이리스트 조회 실패 - 플레이리스트가 존재하지 않음 - playlistId = {}", playlistId);
          return PlaylistNotFoundException.withId(playlistId);
        });
    return playlistMapper.toCachedDto(playlist);
  }

  /**
   * 진행 중인 트랜잭션이 있으면 커밋 이후에, 없으면 즉시 캐시를 무효화한다.
   * 롤백되면 데이터가 바뀌지 않았으므로 지우지 않는다.
   */
  public void evictAfterCommit(UUID playlistId) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          evict(playlistId);
        }
      });
      return;
    }
    evict(playlistId);
  }

  private void evict(UUID playlistId) {
    Cache cache = cacheManager.getCache(CACHE_NAME);
    if (cache == null) {
      return;
    }
    try {
      cache.evict(playlistId);
      log.debug("[플레이리스트 캐시] 무효화 완료 - playlistId = {}", playlistId);
    } catch (RuntimeException e) {
      // Redis 장애 시에도 비즈니스 흐름은 유지. 남은 캐시는 TTL(5분)로 만료된다.
      log.warn("[플레이리스트 캐시] 무효화 실패 - playlistId = {}", playlistId, e);
    }
  }
}
