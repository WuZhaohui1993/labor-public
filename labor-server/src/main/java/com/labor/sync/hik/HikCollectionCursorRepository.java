package com.labor.sync.hik;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface HikCollectionCursorRepository extends JpaRepository<HikCollectionCursor, Long> {
    Optional<HikCollectionCursor> findByMappingId(Long mappingId);
}
