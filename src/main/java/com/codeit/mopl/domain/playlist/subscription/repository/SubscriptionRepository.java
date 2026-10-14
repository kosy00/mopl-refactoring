package com.codeit.mopl.domain.playlist.subscription.repository;

import com.codeit.mopl.domain.playlist.subscription.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    boolean existsBySubscriberIdAndPlaylistId(UUID subscriberId, UUID playlistId);

    Optional<Subscription> findBySubscriberIdAndPlaylistId(UUID subscriberId, UUID playlistId);

    List<Subscription> findByPlaylistId(UUID playlistId);

    /**
     * 구독을 단건 DELETE 쿼리로 삭제하고 삭제된 행 수를 반환한다.
     * 동시에 같은 구독을 취소하면 한 요청만 1을 받고 나머지는 0을 받으므로,
     * 반환값으로 성공 여부를 판단할 수 있다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Subscription s where s.subscriber.id = :subscriberId and s.playlist.id = :playlistId")
    int deleteBySubscriberAndPlaylist(@Param("subscriberId") UUID subscriberId,
                                      @Param("playlistId") UUID playlistId);

    @Transactional
    @Modifying
    void deleteByPlaylistId(UUID playlistId);
}
