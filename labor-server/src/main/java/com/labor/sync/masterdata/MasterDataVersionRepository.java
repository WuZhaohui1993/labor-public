package com.labor.sync.masterdata;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MasterDataVersionRepository extends JpaRepository<MasterDataVersion, Long> {
    List<MasterDataVersion> findByEntityTypeAndEntityIdOrderByVersionNoDesc(String entityType, Long entityId);
}

