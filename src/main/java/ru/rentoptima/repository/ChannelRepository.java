package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.rentoptima.entity.Channel;

import java.util.List;
import java.util.Optional;

public interface ChannelRepository extends JpaRepository<Channel, Long> {

    List<Channel> findByTenantIdAndActiveTrue(Long tenantId);

    List<Channel> findByTenantIdAndActiveTrueAndSyncEnabledTrue(Long tenantId);

    List<Channel> findByUnitTypeIdAndActiveTrue(Long unitTypeId);

    List<Channel> findByChannelTypeAndActiveTrue(Channel.ChannelType channelType);

    Optional<Channel> findByExportSecret(String exportSecret);
}
