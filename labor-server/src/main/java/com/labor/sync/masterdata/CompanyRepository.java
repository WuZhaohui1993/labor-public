package com.labor.sync.masterdata;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface CompanyRepository extends JpaRepository<LaborCompany, Long>, JpaSpecificationExecutor<LaborCompany> {
    Optional<LaborCompany> findByProCodeAndCollCropCode(String proCode, String collCropCode);
    List<LaborCompany> findByProCodeAndStatusNotOrderByCompanyNameAsc(String proCode, MasterDataStatus status);
    boolean existsByProCodeAndCollCropCode(String proCode, String collCropCode);
    boolean existsByProCodeAndStatusNot(String proCode, MasterDataStatus status);
    long countByProCodeAndStatusNot(String proCode, MasterDataStatus status);
}
