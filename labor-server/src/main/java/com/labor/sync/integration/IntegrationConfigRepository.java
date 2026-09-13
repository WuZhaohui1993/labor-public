package com.labor.sync.integration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IntegrationConfigRepository extends JpaRepository<IntegrationConfig, Long> {
    Optional<IntegrationConfig> findByProjectProCodeAndIntegrationType(String proCode, String integrationType);
    Optional<IntegrationConfig> findByProjectIdAndIntegrationType(Long projectId, String integrationType);
    List<IntegrationConfig> findByProjectProCodeOrderByIntegrationType(String proCode);
    boolean existsByProjectIdAndIntegrationType(Long projectId, String integrationType);
}
