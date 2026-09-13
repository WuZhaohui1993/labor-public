package com.labor.sync.security;

import com.labor.sync.integration.IntegrationConfig;
import com.labor.sync.integration.IntegrationConfigRepository;
import com.labor.sync.masterdata.LaborCompany;
import com.labor.sync.masterdata.LaborProject;
import com.labor.sync.masterdata.MasterDataWriteRequest;
import com.labor.sync.masterdata.MasterDataWriteService;
import com.labor.sync.masterdata.MasterDataStatus;
import com.labor.sync.masterdata.ProjectRepository;
import com.labor.sync.push.PushTask;
import com.labor.sync.push.PushTaskRepository;
import com.labor.sync.push.PushTaskStatus;
import com.labor.sync.push.PushTaskType;
import com.labor.sync.workspace.ProjectSyncSetting;
import com.labor.sync.workspace.ProjectSyncSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WorkspaceIsolationIntegrationTest {
    private static final String PROJECT_A = "P-WORKSPACE-A";
    private static final String PROJECT_B = "P-WORKSPACE-B";

    @Autowired MockMvc mvc;
    @Autowired MasterDataWriteService writeService;
    @Autowired IntegrationConfigRepository configRepository;
    @Autowired ProjectSyncSettingRepository settingRepository;
    @Autowired PushTaskRepository taskRepository;
    @Autowired AppUserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired ProjectRoleAssignmentRepository assignmentRepository;
    @Autowired ProjectRepository projectRepository;

    private LaborProject projectA;
    private LaborProject projectB;
    private LaborCompany companyA;
    private LaborCompany companyB;
    private PushTask taskA;

    @BeforeEach
    void prepareIndependentWorkspaces() {
        projectA = writeService.createProject(new MasterDataWriteRequest.Project(PROJECT_A, "工作区甲", null));
        projectB = writeService.createProject(new MasterDataWriteRequest.Project(PROJECT_B, "工作区乙", null));
        companyA = writeService.createCompany(company(PROJECT_A, "91110000000000001A", "甲项目企业"));
        companyB = writeService.createCompany(company(PROJECT_B, "91110000000000002A", "乙项目企业"));

        configure(PROJECT_A, "HIKVISION", "https://hik-a.example.test");
        configure(PROJECT_B, "HIKVISION", "https://hik-b.example.test");
        schedule(PROJECT_A, "20:00");
        schedule(PROJECT_B, "21:30");

        taskA = taskRepository.save(task(projectA, "A"));
        taskRepository.save(task(projectB, "B"));
        createProjectUser();
    }

    @Test
    @WithMockUser(username = "admin")
    void workspaceHeaderScopesMasterDataConfigsSettingsAndTasks() throws Exception {
        mvc.perform(get("/api/labor/admin/companies").header(WorkspaceContextFilter.HEADER, PROJECT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(companyA.getId()))
                .andExpect(jsonPath("$.data.items[0].proCode").value(PROJECT_A));
        mvc.perform(get("/api/labor/admin/companies").header(WorkspaceContextFilter.HEADER, PROJECT_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(companyB.getId()))
                .andExpect(jsonPath("$.data.items[0].proCode").value(PROJECT_B));

        mvc.perform(get("/api/labor/admin/integration-configs").header(WorkspaceContextFilter.HEADER, PROJECT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.integrationType == 'HIKVISION')].baseUrl")
                        .value("https://hik-a.example.test"));
        mvc.perform(get("/api/labor/admin/integration-configs").header(WorkspaceContextFilter.HEADER, PROJECT_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.integrationType == 'HIKVISION')].baseUrl")
                        .value("https://hik-b.example.test"));

        mvc.perform(get("/api/labor/admin/workspace-settings").header(WorkspaceContextFilter.HEADER, PROJECT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pushTime").value("00:00"));
        mvc.perform(get("/api/labor/admin/workspace-settings").header(WorkspaceContextFilter.HEADER, PROJECT_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pushTime").value("00:00"));

        mvc.perform(get("/api/labor/admin/attendance-statistics/daily")
                        .param("startDate", "2026-07-31")
                        .param("endDate", "2026-07-31")
                        .header(WorkspaceContextFilter.HEADER, PROJECT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].date").value("2026-07-31"))
                .andExpect(jsonPath("$.data[0].expectedCount").value(0));
        mvc.perform(get("/api/labor/admin/attendance-statistics/completion-preview")
                        .param("date", "2026-07-30")
                        .param("targetRate", "95.00")
                        .header(WorkspaceContextFilter.HEADER, PROJECT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.date").value("2026-07-30"))
                .andExpect(jsonPath("$.data.closedWindowCount").value(2))
                .andExpect(jsonPath("$.data.windows.length()").value(2));

        mvc.perform(get("/api/labor/admin/push/tasks").header(WorkspaceContextFilter.HEADER, PROJECT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].proCode").value(PROJECT_A));
        mvc.perform(get("/api/labor/admin/push/tasks/summary")
                        .header(WorkspaceContextFilter.HEADER, PROJECT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.groups[0].taskType").value("PROJECT"))
                .andExpect(jsonPath("$.data.groups[0].waitingConfirmCount").value(1));
        mvc.perform(get("/api/labor/admin/push/tasks/{id}", taskA.getId())
                        .header(WorkspaceContextFilter.HEADER, PROJECT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payloadSnapshot").value(false))
                .andExpect(jsonPath("$.data.payloadJson").value(org.hamcrest.Matchers.containsString(PROJECT_A)));
        mvc.perform(get("/api/labor/admin/push/tasks/{id}", taskA.getId())
                        .header(WorkspaceContextFilter.HEADER, PROJECT_B))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PUSH_TASK_NOT_FOUND"));
    }

    @Test
    @WithMockUser(username = "admin")
    void workspaceEndpointsRequireHeaderAndRejectCrossProjectRecords() throws Exception {
        mvc.perform(get("/api/labor/admin/companies"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WORKSPACE_REQUIRED"));
        mvc.perform(get("/api/labor/admin/attendance-statistics/daily")
                        .param("startDate", "2026-07-31")
                        .param("endDate", "2026-07-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WORKSPACE_REQUIRED"));
        mvc.perform(get("/api/labor/admin/attendance-statistics/completion-preview")
                        .param("date", "2026-07-30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WORKSPACE_REQUIRED"));
        mvc.perform(get("/api/labor/admin/companies/{id}", companyB.getId())
                        .header(WorkspaceContextFilter.HEADER, PROJECT_A))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_DATA_MISMATCH"));
    }

    @Test
    @WithMockUser(username = "workspace-a-user", authorities = "master:view")
    void projectMembershipLimitsWorkspaceListAndProjectVersionAccess() throws Exception {
        mvc.perform(get("/api/labor/admin/workspaces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].proCode").value(PROJECT_A));
        mvc.perform(get("/api/labor/admin/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].proCode").value(PROJECT_A));
        mvc.perform(get("/api/labor/admin/projects/{id}/versions", projectB.getId()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));
    }

    @Test
    @WithMockUser(username = "admin")
    void disabledProjectIsRemovedFromWorkspaceListAndCannotBeSelected() throws Exception {
        projectB.setStatus(MasterDataStatus.DISABLED);
        projectRepository.saveAndFlush(projectB);
        mvc.perform(get("/api/labor/admin/workspaces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.proCode == '" + PROJECT_B + "')]").isEmpty());
        mvc.perform(get("/api/labor/admin/companies").header(WorkspaceContextFilter.HEADER, PROJECT_B))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKSPACE_NOT_FOUND"));
    }

    private MasterDataWriteRequest.Company company(String proCode, String code, String name) {
        return new MasterDataWriteRequest.Company(proCode, code, name, "LAOWU_CANJIAN", "Y",
                null, null, null, null, null, null, "N", null);
    }

    private void configure(String proCode, String type, String baseUrl) {
        IntegrationConfig config = configRepository.findByProjectProCodeAndIntegrationType(proCode, type).orElseThrow();
        config.setBaseUrl(baseUrl);
        configRepository.save(config);
    }

    private void schedule(String proCode, String pushTime) {
        ProjectSyncSetting setting = settingRepository.findByProjectProCode(proCode).orElseThrow();
        setting.setPushTime(pushTime);
        settingRepository.save(setting);
    }

    private PushTask task(LaborProject project, String suffix) {
        PushTask task = new PushTask();
        task.setTaskType(PushTaskType.PROJECT);
        task.setAggregateType("PROJECT");
        task.setAggregateId(String.valueOf(project.getId()));
        task.setProCode(project.getProCode());
        task.setBusinessKey(project.getProCode());
        task.setBusinessAt(java.time.Instant.now());
        task.setIdempotencyKey("WORKSPACE-TEST-" + suffix);
        task.setStatus(PushTaskStatus.WAITING_CONFIRM);
        return task;
    }

    private void createProjectUser() {
        AppUser user = new AppUser();
        user.setUsername("workspace-a-user");
        user.setDisplayName("甲项目用户");
        user.setPasswordHash("not-used");
        user.setEnabled(true);
        userRepository.save(user);
        Role role = roleRepository.findByCode("DATA_ADMIN").orElseThrow();
        assignmentRepository.save(new ProjectRoleAssignment(user, projectA, role));
    }
}
