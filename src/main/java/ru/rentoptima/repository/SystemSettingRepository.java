package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.rentoptima.entity.SystemSetting;
import java.util.List;
import java.util.Optional;

public interface SystemSettingRepository extends JpaRepository<SystemSetting, Long> {

    List<SystemSetting> findByTenantIdOrderByKey(Long tenantId);

    Optional<SystemSetting> findByTenantIdAndKey(Long tenantId, String key);
}
