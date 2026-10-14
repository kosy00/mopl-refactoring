package com.codeit.mopl.domain.playlist.repository;

import com.codeit.mopl.domain.playlist.entity.Playlist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PlaylistRepository extends JpaRepository<Playlist, UUID>,
CustomPlaylistRepository {

    /**
     * 구독자 수를 DB에서 원자적으로 1 증가시킨다.
     * 엔티티를 읽고 +1 해서 덮어쓰면 동시 요청 시 갱신 손실(Lost Update)이 발생하므로,
     * "현재 값 + 1"을 DB가 행 잠금 하에 계산하도록 한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Playlist p set p.subscriberCount = p.subscriberCount + 1 where p.id = :playlistId")
    int incrementSubscriberCount(@Param("playlistId") UUID playlistId);

    /**
     * 구독자 수를 DB에서 원자적으로 1 감소시킨다. 0 미만으로 내려가지 않는다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Playlist p set p.subscriberCount = p.subscriberCount - 1 "
        + "where p.id = :playlistId and p.subscriberCount > 0")
    int decrementSubscriberCount(@Param("playlistId") UUID playlistId);
}
