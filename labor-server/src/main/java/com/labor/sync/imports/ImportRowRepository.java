package com.labor.sync.imports;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ImportRowRepository extends JpaRepository<ImportRow, Long> {
    Page<ImportRow> findByBatchId(Long batchId, Pageable pageable);
    List<ImportRow> findByBatchIdOrderByIdAsc(Long batchId);
    Optional<ImportRow> findFirstByBatchIdAndEntityTypeAndBusinessKey(Long batchId, String entityType, String businessKey);
}
