package com.labor.sync.masterdata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "master_data_version")
public class MasterDataVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "entity_type", nullable = false, length = 32)
    private String entityType;
    @Column(name = "entity_id", nullable = false)
    private Long entityId;
    @Column(name = "business_key", nullable = false, length = 300)
    private String businessKey;
    @Column(name = "version_no", nullable = false)
    private int versionNo;
    @Column(name = "snapshot_json", nullable = false, columnDefinition = "json")
    private String snapshotJson;
    @Column(name = "changed_fields_json", columnDefinition = "json")
    private String changedFieldsJson;
    @Column(name = "source_batch_id")
    private Long sourceBatchId;
    @Column(name = "changed_by", nullable = false, length = 64)
    private String changedBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}

