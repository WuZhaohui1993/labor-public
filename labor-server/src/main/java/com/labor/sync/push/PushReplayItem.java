package com.labor.sync.push;

import com.labor.sync.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "push_replay_item", uniqueConstraints = {
        @UniqueConstraint(name = "uk_replay_source", columnNames = {"job_id", "source_task_id"}),
        @UniqueConstraint(name = "uk_replay_task", columnNames = "replay_task_id")
})
public class PushReplayItem extends BaseEntity {
    @ManyToOne(optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private PushReplayJob job;
    @Column(name = "source_task_id", nullable = false)
    private Long sourceTaskId;
    @Column(name = "replay_task_id", nullable = false)
    private Long replayTaskId;
    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 32)
    private PushTaskType taskType;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PushReplayItemStatus status = PushReplayItemStatus.PENDING;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    @Column(name = "max_retries", nullable = false)
    private int maxRetries = 5;
    @Column(name = "last_error_code", length = 100)
    private String lastErrorCode;
    @Column(name = "last_error_message", length = 1000)
    private String lastErrorMessage;
    @Column(name = "completed_at")
    private Instant completedAt;
}
