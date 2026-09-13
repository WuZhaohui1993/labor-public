package com.labor.sync.masterdata;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProjectRepository extends JpaRepository<LaborProject, Long>, JpaSpecificationExecutor<LaborProject> {
    Optional<LaborProject> findByProCode(String proCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from LaborProject p where p.proCode = :proCode")
    Optional<LaborProject> findByProCodeForUpdate(@Param("proCode") String proCode);

    boolean existsByProCode(String proCode);
}
