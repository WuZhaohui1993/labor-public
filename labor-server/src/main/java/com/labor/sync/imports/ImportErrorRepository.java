package com.labor.sync.imports;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ImportErrorRepository extends JpaRepository<ImportError, Long> {
    Page<ImportError> findByBatchId(Long batchId, Pageable pageable);
    List<ImportError> findByBatchIdOrderBySheetNameAscRowNumberAsc(Long batchId);
}

