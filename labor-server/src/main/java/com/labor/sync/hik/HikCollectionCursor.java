package com.labor.sync.hik;

import com.labor.sync.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "hik_collection_cursor")
public class HikCollectionCursor extends BaseEntity {
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mapping_id", nullable = false, unique = true)
    private HikOrganizationMapping mapping;
    @Column(name = "last_event_time")
    private Instant lastEventTime;
    @Column(name = "last_event_id", length = 150)
    private String lastEventId;
    @Column(name = "overlap_seconds", nullable = false)
    private int overlapSeconds = 120;
    @Column(name = "last_success_at")
    private Instant lastSuccessAt;
    @Column(name = "last_error", length = 1000)
    private String lastError;
}
