package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.rentoptima.entity.Tenant;

public interface TenantRepository extends JpaRepository<Tenant, Long> {
}
