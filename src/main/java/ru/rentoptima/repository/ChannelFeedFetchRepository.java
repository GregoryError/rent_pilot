package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import ru.rentoptima.entity.ChannelFeedFetch;

import java.time.LocalDateTime;
import java.util.List;

public interface ChannelFeedFetchRepository extends JpaRepository<ChannelFeedFetch, Long> {

    List<ChannelFeedFetch> findByChannelIdAndFetchedAtAfterOrderByFetchedAtAsc(
            Long channelId, LocalDateTime since);

    /** Чистка журнала, кросс-тенант. */
    @Modifying
    @Transactional
    @Query("DELETE FROM ChannelFeedFetch f WHERE f.fetchedAt < :before")
    int deleteOlderThan(@Param("before") LocalDateTime before);
}
