package com.labor.sync.imports;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
@Table(name = "data_import_row")
public class ImportRow {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private ImportBatch batch;
    @Column(name = "sheet_name", nullable = false, length = 64)
    private String sheetName;
    @Column(name = "excel_row_number", nullable = false)
    private int rowNumber;
    @Column(name = "entity_type", nullable = false, length = 32)
    private String entityType;
    @Column(name = "business_key", length = 300)
    private String businessKey;
    @Column(name = "raw_json", nullable = false, columnDefinition = "json")
    private String rawJson;
    @Column(name = "normalized_json", columnDefinition = "json")
    private String normalizedJson;
    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 32)
    private ChangeType changeType;
    @Enumerated(EnumType.STRING)
    @Column(name = "validation_status", nullable = false, length = 32)
    private RowValidationStatus validationStatus;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
