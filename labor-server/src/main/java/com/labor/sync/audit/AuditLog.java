package com.labor.sync.audit;

import com.labor.sync.masterdata.LaborProject;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
@Table(name = "audit_log")
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private LaborProject project;
    @Column(nullable = false, length = 64)
    private String actor;
    @Column(nullable = false, length = 64)
    private String action;
    @Column(name = "resource_type", nullable = false, length = 64)
    private String resourceType;
    @Column(name = "resource_id", length = 100)
    private String resourceId;
    @Column(length = 500)
    private String reason;
    @Column(nullable = false, length = 32)
    private String result;
    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;
    @Column(name = "client_ip", length = 64)
    private String clientIp;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
