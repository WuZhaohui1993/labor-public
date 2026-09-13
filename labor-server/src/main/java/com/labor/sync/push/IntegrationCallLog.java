package com.labor.sync.push;

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
@Table(name = "integration_call_log")
public class IntegrationCallLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private LaborProject project;
    @Column(name = "integration_type", nullable = false, length = 32)
    private String integrationType;
    @Column(name = "operation_type", nullable = false, length = 64)
    private String operationType;
    @Column(name = "task_id")
    private Long taskId;
    @Column(name = "replay_job_id")
    private Long replayJobId;
    @Column(name = "batch_size", nullable = false)
    private int batchSize = 1;
    @Column(name = "request_path", nullable = false, length = 500)
    private String requestPath;
    @Column(name = "request_summary_json", columnDefinition = "json")
    private String requestSummaryJson;
    @Column(name = "http_status")
    private Integer httpStatus;
    @Column(name = "response_summary_json", columnDefinition = "json")
    private String responseSummaryJson;
    @Column(nullable = false)
    private boolean success;
    @Column(name = "duration_ms", nullable = false)
    private long durationMs;
    @Column(name = "error_code", length = 100)
    private String errorCode;
    @Column(name = "error_message", length = 1000)
    private String errorMessage;
    @Column(name = "trace_id", length = 100)
    private String traceId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
