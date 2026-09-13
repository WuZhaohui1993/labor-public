package com.labor.sync.integration;

import com.labor.sync.masterdata.LaborProject;
import com.labor.sync.workspace.ProjectSyncSetting;
import com.labor.sync.workspace.ProjectSyncSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProjectIntegrationProvisioner {
    private static final List<String> TYPES = List.of("HIKVISION", "LABOR_PLATFORM");
    private final IntegrationConfigRepository repository;
    private final ProjectSyncSettingRepository settingRepository;

    public void provision(LaborProject project) {
        TYPES.forEach(type -> {
            if (!repository.existsByProjectIdAndIntegrationType(project.getId(), type)) {
                IntegrationConfig config = new IntegrationConfig();
                config.setProject(project);
                config.setIntegrationType(type);
                config.setEnabled(false);
                repository.save(config);
            }
        });
        if (!settingRepository.existsByProjectId(project.getId())) {
            ProjectSyncSetting setting = new ProjectSyncSetting();
            setting.setProject(project);
            settingRepository.save(setting);
        }
    }
}
