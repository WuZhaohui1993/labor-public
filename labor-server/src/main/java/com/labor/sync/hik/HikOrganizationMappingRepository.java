package com.labor.sync.hik;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface HikOrganizationMappingRepository extends JpaRepository<HikOrganizationMapping, Long>, JpaSpecificationExecutor<HikOrganizationMapping> {
    List<HikOrganizationMapping> findByEnabledTrueOrderByProCodeAscOrgIndexCodeAsc();
    List<HikOrganizationMapping> findByProCodeOrderByOrgIndexCodeAsc(String proCode);
    Optional<HikOrganizationMapping> findByProCodeAndOrgIndexCode(String proCode, String orgIndexCode);
}
