package com.labor.sync.push;

import com.labor.sync.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "push_task")
public class PushTask extends BaseEntity {
    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 32)
    private PushTaskType taskType;
    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;
    @Column(name = "aggregate_id", nullable = false, length = 100)
    private String aggregateId;
    @Column(name = "pro_code", nullable = false, length = 100)
    private String proCode;
    @Column(name = "business_key", nullable = false, length = 300)
    private String businessKey;
    @Column(name = "company_code", length = 64)
    private String companyCode;
    @Column(name = "business_date")
    private LocalDate businessDate;
    @Column(name = "business_at")
    private Instant businessAt;
    @Column(name = "data_version_no", nullable = false)
    private int dataVersionNo = 1;
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 200)
    private String idempotencyKey;
    @Column(name = "dependency_key", length = 200)
    private String dependencyKey;
    @Column(name = "payload_encrypted", columnDefinition = "longtext")
    private String payloadEncrypted;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PushTaskStatus status = PushTaskStatus.PENDING;
    @Column(name = "priority_no", nullable = false)
    private int priority = 100;
    @Column(name = "retry_count", nullable = false)
    private int retryCount;
    @Column(name = "max_retries", nullable = false)
    private int maxRetries = 5;
    @Column(name = "next_retry_at")
    private Instant nextRetryAt;
    @Column(name = "last_error_code", length = 100)
    private String lastErrorCode;
    @Column(name = "last_error_message", length = 1000)
    private String lastErrorMessage;
    @Column(name = "remote_code", length = 100)
    private String remoteCode;
    @Column(name = "remote_message", length = 1000)
    private String remoteMessage;
    @Column(name = "requested_at")
    private Instant requestedAt;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "manual_reason", length = 500)
    private String manualReason;
    @Column(name = "replay_job_id")
    private Long replayJobId;
    @Column(name = "replay_source_task_id")
    private Long replaySourceTaskId;
}
