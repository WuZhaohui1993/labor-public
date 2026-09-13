package com.labor.sync.integration;

import com.labor.sync.common.BaseEntity;
import com.labor.sync.masterdata.LaborProject;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "integration_config")
public class IntegrationConfig extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private LaborProject project;
    @Column(name = "integration_type", nullable = false, length = 32)
    private String integrationType;
    @Column(name = "base_url", length = 500)
    private String baseUrl;
    @Column(name = "app_key", length = 200)
    private String appKey;
    @Column(name = "app_secret_encrypted", columnDefinition = "text")
    private String appSecretEncrypted;
    @Column(name = "user_id", length = 200)
    private String userId;
    @Column(nullable = false)
    private boolean enabled;
    @Column(name = "config_version", nullable = false)
    private int configVersion = 1;
    @Column(name = "last_test_status", length = 32)
    private String lastTestStatus;
    @Column(name = "last_test_message", length = 500)
    private String lastTestMessage;
    @Column(name = "last_test_at")
    private Instant lastTestAt;
}
