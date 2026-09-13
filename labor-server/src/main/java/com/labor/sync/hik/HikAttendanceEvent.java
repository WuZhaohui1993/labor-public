package com.labor.sync.hik;

import com.labor.sync.attendance.AttendanceCompletionRun;
import com.labor.sync.common.BaseEntity;
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

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "hik_attendance_event")
public class HikAttendanceEvent extends BaseEntity {
    @Column(name = "event_id", nullable = false, length = 150)
    private String eventId;
    @Column(name = "pro_code", nullable = false, length = 100)
    private String proCode;
    @Column(name = "org_index_code", length = 100)
    private String orgIndexCode;
    @Column(name = "hik_person_id", length = 100)
    private String hikPersonId;
    @Column(name = "person_name", length = 100)
    private String personName;
    @Column(name = "idcard_type", length = 64)
    private String idcardType;
    @Column(name = "idcard_encrypted", columnDefinition = "text")
    private String idcardEncrypted;
    @Column(name = "idcard_hash", length = 64)
    private String idcardHash;
    @Column(name = "event_time", nullable = false)
    private Instant eventTime;
    @Column(nullable = false, length = 64)
    private String direction;
    @Column(name = "check_type", nullable = false, length = 64)
    private String checkType;
    @Column(name = "check_way", nullable = false, length = 64)
    private String checkWay;
    @Column(name = "check_location", length = 300)
    private String checkLocation;
    @Column(name = "longitude_value", length = 64)
    private String longitude;
    @Column(name = "latitude_value", length = 64)
    private String latitude;
    @Column(name = "door_index_code", length = 100)
    private String doorIndexCode;
    @Column(name = "device_index_code", length = 100)
    private String deviceIndexCode;
    @Column(name = "raw_payload_encrypted", nullable = false, columnDefinition = "longtext")
    private String rawPayloadEncrypted;
    @Column(name = "raw_payload_archived", nullable = false)
    private boolean rawPayloadArchived;
    @Enumerated(EnumType.STRING)
    @Column(name = "attendance_source", nullable = false, length = 32)
    private AttendanceSource attendanceSource = AttendanceSource.HIKVISION;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "completion_run_id")
    private AttendanceCompletionRun completionRun;
    @Enumerated(EnumType.STRING)
    @Column(name = "match_status", nullable = false, length = 32)
    private MatchStatus matchStatus = MatchStatus.UNMATCHED;
    @Enumerated(EnumType.STRING)
    @Column(name = "match_method", length = 32)
    private MatchMethod matchMethod;
    @Column(name = "matched_person_id")
    private Long matchedPersonId;
    @Column(name = "match_reason", length = 500)
    private String matchReason;
    @Column(name = "match_version", nullable = false)
    private int matchVersion;
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();
}
