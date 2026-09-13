package com.labor.sync.imports;

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

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "data_import_batch")
public class ImportBatch extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private LaborProject project;
    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;
    @Column(name = "file_hash", nullable = false, length = 64)
    private String fileHash;
    @Column(name = "uploaded_by", nullable = false, length = 64)
    private String uploadedBy;
    @Enumerated(EnumType.STRING)
    @Column(name = "import_scope", nullable = false, length = 32)
    private ImportScope scope = ImportScope.ALL;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ImportBatchStatus status = ImportBatchStatus.UPLOADED;
    @Column(name = "total_count", nullable = false)
    private int totalCount;
    @Column(name = "valid_count", nullable = false)
    private int validCount;
    @Column(name = "error_count", nullable = false)
    private int errorCount;
    @Column(name = "new_count", nullable = false)
    private int newCount;
    @Column(name = "updated_count", nullable = false)
    private int updatedCount;
    @Column(name = "unchanged_count", nullable = false)
    private int unchangedCount;
    @Column(name = "review_comment", length = 1000)
    private String reviewComment;
    @Column(name = "reviewed_by", length = 64)
    private String reviewedBy;
    @Column(name = "reviewed_at")
    private Instant reviewedAt;
    @Column(name = "published_at")
    private Instant publishedAt;
}
