package com.labor.sync.workspace;

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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "project_sync_setting")
public class ProjectSyncSetting extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, unique = true)
    private LaborProject project;
    @Column(name = "collection_mode", nullable = false, length = 16)
    private String collectionMode = "DIRECT";
    @Column(name = "hik_collection_enabled", nullable = false)
    private boolean hikCollectionEnabled = true;
    @Column(name = "push_enabled", nullable = false)
    private boolean pushEnabled = true;
    @Column(name = "sync_started", nullable = false)
    private boolean syncStarted;
    @Column(name = "push_time", nullable = false)
    private String pushTime = "00:00";
    @Column(name = "zone_id", nullable = false, length = 64)
    private String zoneId = "Asia/Shanghai";
    @Column(name = "last_push_date")
    private LocalDate lastPushDate;
    @Column(name = "last_push_slot_at")
    private Instant lastPushSlotAt;
    @Column(name = "attendance_completion_enabled", nullable = false)
    private boolean attendanceCompletionEnabled;
    @Column(name = "attendance_completeness_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal attendanceCompletenessRate = new BigDecimal("95.00");
}
