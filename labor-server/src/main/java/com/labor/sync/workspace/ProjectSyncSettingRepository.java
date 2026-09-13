package com.labor.sync.workspace;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectSyncSettingRepository extends JpaRepository<ProjectSyncSetting, Long> {
    @EntityGraph(attributePaths = "project")
    Optional<ProjectSyncSetting> findByProjectId(Long projectId);

    @EntityGraph(attributePaths = "project")
    Optional<ProjectSyncSetting> findByProjectProCode(String proCode);

    @EntityGraph(attributePaths = "project")
    List<ProjectSyncSetting> findByPushEnabledTrueOrderByProjectProCodeAsc();

    @EntityGraph(attributePaths = "project")
    List<ProjectSyncSetting> findByHikCollectionEnabledTrueOrderByProjectProCodeAsc();

    boolean existsByProjectId(Long projectId);
}
