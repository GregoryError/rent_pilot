package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.rentoptima.entity.UnitType;

import java.util.List;

public interface UnitTypeRepository extends JpaRepository<UnitType, Long> {

    List<UnitType> findByTenantIdAndActiveTrue(Long tenantId);

    List<UnitType> findByPropertyIdAndActiveTrue(Long propertyId);
}
