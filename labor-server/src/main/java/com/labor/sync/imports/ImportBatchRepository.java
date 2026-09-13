package com.labor.sync.imports;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, Long> {
    long countByStatus(ImportBatchStatus status);
    List<ImportBatch> findTop5ByOrderByCreatedAtDesc();
    Page<ImportBatch> findByProjectId(Long projectId, Pageable pageable);
    List<ImportBatch> findTop5ByProjectIdOrderByCreatedAtDesc(Long projectId);
    long countByProjectIdAndStatus(Long projectId, ImportBatchStatus status);
}
