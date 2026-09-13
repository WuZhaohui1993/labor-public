package com.labor.sync.masterdata;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface TeamRepository extends JpaRepository<LaborTeam, Long>, JpaSpecificationExecutor<LaborTeam> {
    Optional<LaborTeam> findByProCodeAndTeamId(String proCode, String teamId);
    List<LaborTeam> findByProCodeAndStatusNotOrderByTeamNameAsc(String proCode, MasterDataStatus status);
    boolean existsByProCodeAndTeamId(String proCode, String teamId);
    boolean existsByProCodeAndStatusNot(String proCode, MasterDataStatus status);
    boolean existsByProCodeAndCollCropCodeAndStatusNot(String proCode, String collCropCode, MasterDataStatus status);
    long countByProCodeAndStatusNot(String proCode, MasterDataStatus status);
}
