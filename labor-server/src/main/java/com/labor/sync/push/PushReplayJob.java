package com.labor.sync.push;

import com.labor.sync.common.BaseEntity;
import com.labor.sync.masterdata.LaborProject;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
@Table(name = "push_replay_job")
public class PushReplayJob extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private LaborProject project;
    @Column(name = "job_no", nullable = false, unique = true, length = 64)
    private String jobNo;
    @Column(name = "pro_code", nullable = false, length = 100)
    private String proCode;
    @Column(name = "scope_type", nullable = false, length = 32)
    private String scopeType;
    @Column(name = "scope_description", nullable = false, length = 500)
    private String scopeDescription;
    @Column(name = "selection_json", columnDefinition = "TEXT")
    private String selectionJson;
    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", length = 32)
    private PushTaskType taskType;
    @Column(name = "company_code", length = 64)
    private String companyCode;
    @Column(name = "company_name", length = 200)
    private String companyName;
    @Column(name = "start_date")
    private LocalDate startDate;
    @Column(name = "end_date")
    private LocalDate endDate;
    @Column(name = "total_batch_count", nullable = false)
    private int totalBatchCount;
    @Column(nullable = false, length = 500)
    private String reason;
    @Column(name = "requested_by", nullable = false, length = 100)
    private String requestedBy;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PushReplayJobStatus status = PushReplayJobStatus.CREATED;
    @Column(name = "total_count", nullable = false)
    private int totalCount;
    @Column(name = "success_count", nullable = false)
    private int successCount;
    @Column(name = "failed_count", nullable = false)
    private int failedCount;
    @Column(name = "pending_count", nullable = false)
    private int pendingCount;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "last_error", length = 1000)
    private String lastError;
}
