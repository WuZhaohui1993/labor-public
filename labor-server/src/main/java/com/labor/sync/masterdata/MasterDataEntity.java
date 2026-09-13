package com.labor.sync.masterdata;

import com.labor.sync.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@MappedSuperclass
public abstract class MasterDataEntity extends BaseEntity {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MasterDataStatus status = MasterDataStatus.PUBLISHED;
    @Column(name = "data_version_no", nullable = false)
    private int dataVersionNo = 1;
    @Column(name = "source_batch_id")
    private Long sourceBatchId;
    @Column(name = "published_at")
    private Instant publishedAt;
}

