package com.labor.sync.masterdata;

import com.labor.sync.security.WorkspaceContextFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@Transactional
class PersonFilterIntegrationTest {
    private static final String PROJECT_A = "P-PERSON-FILTER-A";
    private static final String PROJECT_B = "P-PERSON-FILTER-B";
    private static final String COMPANY_A1 = "PERSON-FILTER-COMPANY-A1";
    private static final String COMPANY_A2 = "PERSON-FILTER-COMPANY-A2";
    private static final String COMPANY_B1 = "PERSON-FILTER-COMPANY-B1";
    private static final String TEAM_A1 = "PERSON-FILTER-TEAM-A1";
    private static final String TEAM_A2 = "PERSON-FILTER-TEAM-A2";
    private static final String TEAM_B1 = "PERSON-FILTER-TEAM-B1";

    @Autowired MockMvc mvc;
    @Autowired MasterDataWriteService writeService;

    @BeforeEach
    void preparePersonHierarchy() {
        writeService.createProject(new MasterDataWriteRequest.Project(PROJECT_A, "人员筛选项目甲", null));
        writeService.createProject(new MasterDataWriteRequest.Project(PROJECT_B, "人员筛选项目乙", null));
        writeService.createCompany(company(PROJECT_A, COMPANY_A1, "A企业一"));
        writeService.createCompany(company(PROJECT_A, COMPANY_A2, "A企业二"));
        writeService.createCompany(company(PROJECT_B, COMPANY_B1, "B企业一"));
        writeService.createTeam(team(PROJECT_A, COMPANY_A1, TEAM_A1, "A施工队一"));
        writeService.createTeam(team(PROJECT_A, COMPANY_A2, TEAM_A2, "A施工队二"));
        writeService.createTeam(team(PROJECT_B, COMPANY_B1, TEAM_B1, "B施工队一"));
        writeService.createPerson(person(PROJECT_A, TEAM_A1, "人员甲一", "110101199101010011"));
        writeService.createPerson(person(PROJECT_A, TEAM_A2, "人员甲二", "110101199101010012"));
        writeService.createPerson(person(PROJECT_B, TEAM_B1, "人员乙一", "110101199101010013"));
    }

    @Test
    @WithMockUser(username = "admin")
    void filterTreeUsesCurrentWorkspaceHierarchy() throws Exception {
        mvc.perform(get("/api/labor/admin/persons/filter-tree")
                        .header(WorkspaceContextFilter.HEADER, PROJECT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.key").value("project:" + PROJECT_A))
                .andExpect(jsonPath("$.data.label").value("人员筛选项目甲"))
                .andExpect(jsonPath("$.data.children.length()").value(2))
                .andExpect(jsonPath("$.data.children[0].collCropCode").value(COMPANY_A1))
                .andExpect(jsonPath("$.data.children[0].children[0].teamId").value(TEAM_A1))
                .andExpect(jsonPath("$.data.children[1].collCropCode").value(COMPANY_A2))
                .andExpect(jsonPath("$.data.children[1].children[0].teamId").value(TEAM_A2));

        mvc.perform(get("/api/labor/admin/persons/filter-tree")
                        .header(WorkspaceContextFilter.HEADER, PROJECT_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.proCode").value(PROJECT_B))
                .andExpect(jsonPath("$.data.children.length()").value(1))
                .andExpect(jsonPath("$.data.children[0].collCropCode").value(COMPANY_B1))
                .andExpect(jsonPath("$.data.children[0].children[0].teamId").value(TEAM_B1));
    }

    @Test
    @WithMockUser(username = "admin")
    void projectCompanyAndTeamSelectionsFilterPersonsWithoutCrossWorkspaceLeakage() throws Exception {
        mvc.perform(get("/api/labor/admin/persons")
                        .param("size", "100")
                        .header(WorkspaceContextFilter.HEADER, PROJECT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));

        mvc.perform(get("/api/labor/admin/persons")
                        .param("collCropCode", COMPANY_A1)
                        .header(WorkspaceContextFilter.HEADER, PROJECT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("人员甲一"))
                .andExpect(jsonPath("$.data.items[0].collCropCode").value(COMPANY_A1));

        mvc.perform(get("/api/labor/admin/persons")
                        .param("teamId", TEAM_A2)
                        .header(WorkspaceContextFilter.HEADER, PROJECT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("人员甲二"))
                .andExpect(jsonPath("$.data.items[0].teamId").value(TEAM_A2));

        mvc.perform(get("/api/labor/admin/persons")
                        .param("collCropCode", COMPANY_B1)
                        .param("teamId", TEAM_B1)
                        .header(WorkspaceContextFilter.HEADER, PROJECT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    private MasterDataWriteRequest.Company company(String proCode, String companyCode, String name) {
        return new MasterDataWriteRequest.Company(proCode, companyCode, name, "LAOWU_CANJIAN", "Y",
                null, null, null, null, null, null, "N", null);
    }

    private MasterDataWriteRequest.Team team(String proCode, String companyCode, String teamId, String name) {
        return new MasterDataWriteRequest.Team(teamId, proCode, companyCode, "CANJIAN_TEAM", name,
                null, null, null, null, null, null, null);
    }

    private MasterDataWriteRequest.Person person(String proCode, String teamId, String name, String idcard) {
        return new MasterDataWriteRequest.Person(name, "SHENFEN_ZHENGJIAN", idcard,
                null, null, "Y", proCode, teamId, "LAB_USER_BULIDER", "WORK_TYPE_OTHER",
                null, null, null, null, null, "N", null, null);
    }
}
