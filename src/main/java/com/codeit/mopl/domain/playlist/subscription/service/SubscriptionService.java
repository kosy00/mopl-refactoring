package com.codeit.mopl.domain.playlist.subscription.service;

import com.codeit.mopl.domain.notification.entity.Level;
import com.codeit.mopl.domain.notification.service.NotificationService;
import com.codeit.mopl.domain.notification.template.NotificationMessage;
import com.codeit.mopl.domain.notification.template.NotificationTemplate;
import com.codeit.mopl.domain.notification.template.context.PlaylistSubscribedContext;
import com.codeit.mopl.domain.playlist.entity.Playlist;
import com.codeit.mopl.domain.playlist.repository.PlaylistRepository;
import com.codeit.mopl.domain.playlist.service.PlaylistDetailCache;
import com.codeit.mopl.domain.playlist.subscription.entity.Subscription;
import com.codeit.mopl.domain.playlist.subscription.repository.SubscriptionRepository;
import com.codeit.mopl.domain.user.entity.User;
import com.codeit.mopl.domain.user.repository.UserRepository;
import com.codeit.mopl.exception.playlist.PlaylistNotFoundException;
import com.codeit.mopl.exception.playlist.subscription.SubscriptionDuplicateException;
import com.codeit.mopl.exception.playlist.subscription.SubscriptionNotFoundException;
import com.codeit.mopl.exception.playlist.subscription.SubscriptionSelfProhibitedException;
import com.codeit.mopl.exception.user.UserErrorCode;
import com.codeit.mopl.exception.user.UserNotFoundException;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final PlaylistRepository playlistRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final PlaylistDetailCache playlistDetailCache;

    public void subscribe(UUID playlistId, UUID subscriberId) {
        Instant subscribedAt = Instant.now();
        log.info("[플레이리스트] 플레이리스트 구독 처리 시작 - playlistId = {}", playlistId);

        Playlist playlist = playlistRepository.findById(playlistId)
                .orElseThrow(() -> {
                    log.warn("[플레이리스트] 플레이리스트 구독 처리 실패 - 플레이리스트가 존재하지 않음 - playlistId = {}",playlistId);
                    return PlaylistNotFoundException.withId(playlistId);
                });

        User subscriber = userRepository.findById(subscriberId)
                .orElseThrow(() -> {
                    log.warn("[플레이리스트] 플레이리스트 구독 처리 실패 - 유저가 존재하지 않음 userId = {}", subscriberId);
                    return new UserNotFoundException(UserErrorCode.USER_NOT_FOUND, Map.of("userId", subscriberId));
                });

        UUID ownerId = playlist.getUser().getId();

        if (ownerId.equals(subscriberId)) {
            log.warn("[플레이리스트] 플레이리스트 구독 처리 실패 - 본인의 플레이리스트는 구독할 수 없음 - playlistId = {}", playlistId);
            throw SubscriptionSelfProhibitedException.withId(playlistId,subscriberId);
        }

        if (subscriptionRepository.existsBySubscriberIdAndPlaylistId(subscriberId,playlistId)) {
            log.warn("[플레이리스트] 플레이리스트 구독 처리 실패 - 이미 구독 내역이 존재함 - playlistId = {}, subscriberId = {}", playlistId, subscriberId);
            throw SubscriptionDuplicateException.withId(playlistId,subscriberId);
        }

        Subscription subscription = new Subscription(playlist, subscriber, subscribedAt);
        try {
            // 즉시 INSERT해서 UNIQUE 제약 위반을 이 시점에 감지한다.
            // (위의 존재 확인과 저장 사이에 동시 요청이 끼어들 수 있으므로 DB 제약을 최종 방어선으로 사용)
            subscriptionRepository.saveAndFlush(subscription);
        } catch (DataIntegrityViolationException e) {
            log.warn("[플레이리스트] 플레이리스트 구독 처리 실패 - 동시 요청으로 인한 중복 구독 - playlistId = {}, subscriberId = {}", playlistId, subscriberId);
            throw SubscriptionDuplicateException.withId(playlistId, subscriberId);
        }

        // 구독자 증가 (DB 원자적 UPDATE로 갱신 손실 방지)
        playlistRepository.incrementSubscriberCount(playlistId);
        // 캐시된 상세 정보의 구독자 수가 바뀌므로 커밋 후 무효화
        playlistDetailCache.evictAfterCommit(playlistId);

        log.info("[플레이리스트] 플레이리스트 구독 처리 완료 - playlistId = {}, subscriberId = {}", playlistId, subscriberId);

        // 플레이리스트 소유자에게 알림을 생성함(동기 처리)
        PlaylistSubscribedContext ctx =
            new PlaylistSubscribedContext(subscriber.getName(), playlist.getTitle());

        NotificationTemplate template = NotificationTemplate.PLAYLIST_SUBSCRIBED;
        NotificationMessage message = template.build(ctx);
        notificationService.createNotification(
            ownerId,
            message.title(),
            message.content(),
            Level.INFO
        );
        log.info("[플레이리스트] 플레이리스트 구독 알림 생성 - playlistId = {}, subscriberId = {}", playlistId, subscriberId);
    }

    public void unsubscribe(UUID playlistId, UUID subscriberId) {
        log.info("[플레이리스트] 플레이리스트 구독 취소 처리 시작 - playlistId = {}, subscriberId = {}", playlistId, subscriberId);

        // 조회 후 삭제하면 동시 요청이 같은 구독을 함께 삭제하려다 충돌하므로, DELETE 결과(삭제된 행 수)로 판단한다.
        int deleted = subscriptionRepository.deleteBySubscriberAndPlaylist(subscriberId, playlistId);
        if (deleted == 0) {
            log.warn("[플레이리스트] 플레이리스트 구독 취소 처리 실패 - 구독 내역이 존재하지 않음 - playlistId = {}", playlistId);
            throw SubscriptionNotFoundException.withId(subscriberId, playlistId);
        }

        // 구독자 감소 (실제로 삭제된 경우에만, DB 원자적 UPDATE)
        playlistRepository.decrementSubscriberCount(playlistId);
        playlistDetailCache.evictAfterCommit(playlistId);

        log.info("[플레이리스트] 플레이리스트 구독 취소 처리 완료 - playlistId = {}, subscriberId = {}", playlistId, subscriberId);

    }
}
