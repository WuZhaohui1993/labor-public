package com.labor.sync.masterdata;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface DomainOutboxRepository extends JpaRepository<DomainOutbox, Long> {
    List<DomainOutbox> findTop50ByStatusAndNextAttemptAtBeforeOrderByCreatedAtAsc(String status, Instant nextAttemptAt);
    List<DomainOutbox> findTop50ByStatusAndNextAttemptAtIsNullOrderByCreatedAtAsc(String status);
}
