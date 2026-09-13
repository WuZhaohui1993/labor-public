package com.labor.sync.matching;

import com.labor.sync.hik.MatchMethod;
import com.labor.sync.hik.MatchStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "person_match_history")
public class PersonMatchHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "attendance_event_id", nullable = false)
    private Long attendanceEventId;
    @Column(name = "previous_person_id")
    private Long previousPersonId;
    @Column(name = "matched_person_id")
    private Long matchedPersonId;
    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 32)
    private MatchStatus previousStatus;
    @Enumerated(EnumType.STRING)
    @Column(name = "match_status", nullable = false, length = 32)
    private MatchStatus matchStatus;
    @Enumerated(EnumType.STRING)
    @Column(name = "match_method", length = 32)
    private MatchMethod matchMethod;
    @Column(name = "action_type", nullable = false, length = 32)
    private String actionType;
    @Column(length = 500)
    private String reason;
    @Column(name = "operated_by", nullable = false, length = 64)
    private String operatedBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
