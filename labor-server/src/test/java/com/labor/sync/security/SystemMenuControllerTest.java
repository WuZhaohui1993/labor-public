package com.labor.sync.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SystemMenuControllerTest {
    @Autowired MockMvc mvc;
    @Autowired SystemMenuRepository repository;

    private SystemMenu parent;

    @BeforeEach
    void prepareMenuTree() {
        parent = menu(null, MenuType.DIRECTORY, "测试目录", "TestDirectory", "/test-directory", null);
        parent.setBuiltin(true);
        repository.save(parent);
        SystemMenu page = menu(parent, MenuType.PAGE, "测试页面", "TestPage", "/test-page", "welcome/index");
        page.setPermissionCode("dashboard:view");
        repository.save(page);
        SystemMenu disabled = menu(parent, MenuType.PAGE, "停用页面", "DisabledPage", "/disabled-page", "welcome/index");
        disabled.setEnabled(false);
        repository.save(disabled);
    }

    @Test
    @WithMockUser(username = "admin", authorities = "dashboard:view")
    void navigationReturnsEnabledTreeAndIconConfiguration() throws Exception {
        mvc.perform(get("/api/labor/admin/system/menus/navigation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("TestDirectory"))
                .andExpect(jsonPath("$.data[0].children.length()").value(1))
                .andExpect(jsonPath("$.data[0].children[0].meta.icon").value("ri/menu-2-line"));
    }

    @Test
    @WithMockUser(username = "restricted-user", authorities = "audit:view")
    void navigationRemovesUnauthorizedPagesAndEmptyDirectories() throws Exception {
        mvc.perform(get("/api/labor/admin/system/menus/navigation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @WithMockUser(username = "admin", authorities = "system:menu")
    void menuCanBeCreatedAndUpdated() throws Exception {
        String created = mvc.perform(post("/api/labor/admin/system/menus")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "parentId": %d,
                                  "menuType": "PAGE",
                                  "title": "新增页面",
                                  "routeName": "CreatedPage",
                                  "routePath": "/created-page",
                                  "componentPath": "welcome/index",
                                  "permissionCode": "dashboard:view",
                                  "icon": "ri/dashboard-line",
                                  "sortNo": 20,
                                  "visible": true,
                                  "enabled": true,
                                  "keepAlive": false
                                }
                                """.formatted(parent.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("新增页面"))
                .andReturn().getResponse().getContentAsString();

        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(created).path("data").path("id").asLong();
        mvc.perform(put("/api/labor/admin/system/menus/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "parentId": %d,
                                  "menuType": "PAGE",
                                  "title": "已修改页面",
                                  "routeName": "CreatedPage",
                                  "routePath": "/created-page",
                                  "componentPath": "welcome/index",
                                  "permissionCode": "dashboard:view",
                                  "icon": "ri/settings-3-line",
                                  "sortNo": 21,
                                  "visible": true,
                                  "enabled": true,
                                  "keepAlive": true
                                }
                                """.formatted(parent.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("已修改页面"))
                .andExpect(jsonPath("$.data.icon").value("ri/settings-3-line"))
                .andExpect(jsonPath("$.data.keepAlive").value(true));
    }

    @Test
    @WithMockUser(username = "admin", authorities = "system:menu")
    void builtinMenuCannotBeDeleted() throws Exception {
        mvc.perform(delete("/api/labor/admin/system/menus/{id}", parent.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUILTIN_MENU_DELETE_FORBIDDEN"));
    }

    @Test
    @WithMockUser(username = "auditor", authorities = "audit:view")
    void managementEndpointRequiresMenuPermission() throws Exception {
        mvc.perform(get("/api/labor/admin/system/menus"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    private SystemMenu menu(SystemMenu parentMenu, MenuType type, String title, String name,
                            String path, String component) {
        SystemMenu menu = new SystemMenu();
        menu.setParent(parentMenu);
        menu.setMenuType(type);
        menu.setTitle(title);
        menu.setRouteName(name);
        menu.setRoutePath(path);
        menu.setComponentPath(component);
        menu.setIcon("ri/menu-2-line");
        menu.setSortNo(10);
        menu.setVisible(true);
        menu.setEnabled(true);
        menu.setBuiltin(false);
        return menu;
    }
}
