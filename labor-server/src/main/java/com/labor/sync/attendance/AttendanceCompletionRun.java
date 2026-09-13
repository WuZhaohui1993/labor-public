package com.labor.sync.attendance;

import com.labor.sync.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "attendance_completion_run")
public class AttendanceCompletionRun extends BaseEntity {
    @Column(name = "pro_code", nullable = false, length = 100)
    private String proCode;
    @Column(name = "window_start_at", nullable = false)
    private Instant windowStartAt;
    @Column(name = "window_end_at", nullable = false)
    private Instant windowEndAt;
    @Column(name = "target_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal targetRate;
    @Column(name = "expected_count", nullable = false)
    private int expectedCount;
    @Column(name = "captured_count", nullable = false)
    private int capturedCount;
    @Column(name = "existing_completed_count", nullable = false)
    private int existingCompletedCount;
    @Column(name = "target_count", nullable = false)
    private int targetCount;
    @Column(name = "generated_count", nullable = false)
    private int generatedCount;
    @Column(name = "generated_by", nullable = false, length = 64)
    private String generatedBy = "system";
}
