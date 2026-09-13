package com.labor.sync.security;

import com.labor.sync.masterdata.LaborProject;
import com.labor.sync.masterdata.MasterDataWriteRequest;
import com.labor.sync.masterdata.MasterDataWriteService;
import com.labor.sync.masterdata.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthControllerTest {
    @Autowired
    MockMvc mvc;
    @Autowired MasterDataWriteService writeService;
    @Autowired ProjectRepository projectRepository;
    @Autowired AppUserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired ProjectRoleAssignmentRepository assignmentRepository;
    @Autowired CaptchaService captchaService;

    private LaborProject project;

    @BeforeEach
    void prepareWorkspaceUsers() {
        project = projectRepository.findByProCode("P-AUTH-001").orElseGet(() ->
                writeService.createProject(new MasterDataWriteRequest.Project("P-AUTH-001", "权限测试项目", null)));
        createWorkspaceUser("data-user", "DATA_ADMIN");
        createWorkspaceUser("sync-user", "SYNC_OPERATOR");
    }

    private void createWorkspaceUser(String username, String roleCode) {
        AppUser user = userRepository.findByUsernameIgnoreCase(username).orElseGet(() -> {
            AppUser created = new AppUser();
            created.setUsername(username);
            created.setDisplayName(username);
            created.setPasswordHash("not-used");
            created.setEnabled(true);
            return userRepository.save(created);
        });
        Role role = roleRepository.findByCode(roleCode).orElseThrow();
        user.getRoles().add(role);
        userRepository.save(user);
        ProjectRoleAssignmentId id = new ProjectRoleAssignmentId(user.getId(), project.getId(), role.getId());
        if (!assignmentRepository.existsById(id)) {
            assignmentRepository.save(new ProjectRoleAssignment(user, project, role));
        }
    }

    @Test
    void captchaEndpointReturnsServerGeneratedImage() throws Exception {
        String response = mvc.perform(get("/api/labor/admin/auth/captcha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.captchaId").isNotEmpty())
                .andExpect(jsonPath("$.data.image").value(org.hamcrest.Matchers.startsWith("data:image/png;base64,")))
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String dataUrl = com.jayway.jsonpath.JsonPath.read(response, "$.data.image");
        byte[] png = Base64.getDecoder().decode(dataUrl.substring("data:image/png;base64,".length()));
        var image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image);
        assertEquals(144, image.getWidth());
        assertEquals(48, image.getHeight());
    }

    @Test
    void loginReturnsAccessTokenAndHttpOnlyRefreshCookie() throws Exception {
        mvc.perform(post("/api/labor/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("admin", "Test@12345")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.roles[0]").value("SYSTEM_ADMIN"))
                .andExpect(cookie().httpOnly("labor_refresh_token", true));
    }

    @Test
    void protectedEndpointRejectsAnonymousRequest() throws Exception {
        mvc.perform(get("/api/labor/admin/dashboard"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void invalidPasswordReturnsUnauthorizedInsteadOfServerError() throws Exception {
        mvc.perform(post("/api/labor/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("admin", "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void captchaIsRequiredAndCanOnlyBeUsedOnce() throws Exception {
        mvc.perform(post("/api/labor/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"Test@12345\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAPTCHA_REQUIRED"));

        String body = loginBody("admin", "wrong-password");
        mvc.perform(post("/api/labor/admin/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/labor/admin/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAPTCHA_EXPIRED"));
    }

    @Test
    void expiredCaptchaIsRejected() throws Exception {
        CaptchaService.CaptchaView captcha = captchaService.issueForTest("127.0.0.1", "ABCD");
        captchaService.expireForTest(captcha.captchaId());
        mvc.perform(post("/api/labor/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("admin", "Test@12345", captcha.captchaId(), "ABCD")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAPTCHA_EXPIRED"));
    }

    @Test
    void fivePasswordFailuresLockAccountUntilAdministratorUnlocksIt() throws Exception {
        for (int attempt = 1; attempt <= 4; attempt++) {
            mvc.perform(post("/api/labor/admin/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody("admin", "wrong-password")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
        mvc.perform(post("/api/labor/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("admin", "wrong-password")))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
        mvc.perform(post("/api/labor/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("admin", "Test@12345")))
                .andExpect(status().isLocked());

        Long adminId = userRepository.findByUsernameIgnoreCase("admin").orElseThrow().getId();
        mvc.perform(post("/api/labor/admin/system/users/{id}/unlock", adminId)
                        .with(user("security-admin").authorities(() -> "system:user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.failedLoginAttempts").value(0))
                .andExpect(jsonPath("$.data.lockedUntil").doesNotExist());
        mvc.perform(post("/api/labor/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("admin", "Test@12345")))
                .andExpect(status().isOk());
    }

    @Test
    void ordinaryUserCannotUnlockAccounts() throws Exception {
        Long adminId = userRepository.findByUsernameIgnoreCase("admin").orElseThrow().getId();
        mvc.perform(post("/api/labor/admin/system/users/{id}/unlock", adminId)
                        .with(user("ordinary-user").authorities(() -> "dashboard:view")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @WithMockUser(username = "data-user", authorities = "master:view")
    void pushEndpointRejectsAuthenticatedUserWithoutPushPermission() throws Exception {
        mvc.perform(get("/api/labor/admin/push/tasks").header(WorkspaceContextFilter.HEADER, project.getProCode()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @WithMockUser(username = "sync-user", authorities = "push:view")
    void pushEndpointAcceptsUserWithViewPermission() throws Exception {
        mvc.perform(get("/api/labor/admin/push/tasks").header(WorkspaceContextFilter.HEADER, project.getProCode()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
    }

    @Test
    @WithMockUser(username = "sync-user", authorities = "push:view")
    void workspaceEndpointRequiresProjectHeader() throws Exception {
        mvc.perform(get("/api/labor/admin/push/tasks"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WORKSPACE_REQUIRED"));
    }

    private String loginBody(String username, String password) {
        CaptchaService.CaptchaView captcha = captchaService.issueForTest("127.0.0.1", "ABCD");
        return loginBody(username, password, captcha.captchaId(), "ABCD");
    }

    private String loginBody(String username, String password, String captchaId, String captchaCode) {
        return """
                {"username":"%s","password":"%s","captchaId":"%s","captchaCode":"%s"}
                """.formatted(username, password, captchaId, captchaCode);
    }
}
