package com.labor.sync.masterdata;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface PersonRepository extends JpaRepository<LaborPerson, Long>, JpaSpecificationExecutor<LaborPerson> {
    Optional<LaborPerson> findByProCodeAndIdcardHash(String proCode, String idcardHash);
    List<LaborPerson> findByProCodeAndNameAndStatusNot(String proCode, String name, MasterDataStatus status);
    List<LaborPerson> findByProCodeAndHikPersonIdAndStatusNot(String proCode, String hikPersonId, MasterDataStatus status);
    List<LaborPerson> findByProCodeAndStatusNotOrderByNameAsc(String proCode, MasterDataStatus status);
    boolean existsByProCodeAndStatusNot(String proCode, MasterDataStatus status);
    boolean existsByProCodeAndTeamIdAndStatusNot(String proCode, String teamId, MasterDataStatus status);
    long countByProCodeAndStatusNot(String proCode, MasterDataStatus status);
}
