package com.labor.sync.push;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface IntegrationCallLogRepository extends JpaRepository<IntegrationCallLog, Long>, JpaSpecificationExecutor<IntegrationCallLog> {
    @Query("select log.id from IntegrationCallLog log where log.success = :success "
            + "and log.createdAt < :cutoff order by log.createdAt asc, log.id asc")
    List<Long> findRetentionIds(@Param("success") boolean success,
                                @Param("cutoff") Instant cutoff,
                                Pageable pageable);
}
