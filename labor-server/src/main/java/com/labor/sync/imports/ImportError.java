package com.labor.sync.imports;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "data_import_error")
public class ImportError {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private ImportBatch batch;
    @ManyToOne
    @JoinColumn(name = "import_row_id")
    private ImportRow importRow;
    @Column(name = "sheet_name", nullable = false, length = 64)
    private String sheetName;
    @Column(name = "excel_row_number", nullable = false)
    private int rowNumber;
    @Column(name = "field_name", nullable = false, length = 100)
    private String fieldName;
    @Column(name = "error_code", nullable = false, length = 64)
    private String errorCode;
    @Column(nullable = false, length = 500)
    private String message;
    @Column(name = "raw_value", length = 1000)
    private String rawValue;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
