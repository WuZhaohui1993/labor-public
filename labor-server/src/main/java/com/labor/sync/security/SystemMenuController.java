package com.labor.sync.security;

import com.labor.sync.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/labor/admin/system/menus")
public class SystemMenuController {
    private final SystemMenuService service;
    private final WorkspaceAccessService workspaceAccessService;

    @GetMapping
    @PreAuthorize("hasAuthority('system:menu')")
    public ApiResponse<List<SystemMenuService.MenuView>> list() {
        return ApiResponse.ok(service.list());
    }

    @GetMapping("/navigation")
    public ApiResponse<List<SystemMenuService.NavigationView>> navigation(
            Authentication authentication,
            @RequestHeader(value = WorkspaceContextFilter.HEADER, required = false) String proCode) {
        Set<String> permissions;
        boolean unrestricted;
        if (proCode != null && !proCode.isBlank()) {
            WorkspaceAccessService.WorkspaceAccess access = workspaceAccessService.resolve(
                    authentication.getName(), proCode.trim());
            permissions = access.permissions();
            unrestricted = access.roles().contains("SYSTEM_ADMIN");
        } else {
            permissions = authentication.getAuthorities().stream()
                    .map(authority -> authority.getAuthority())
                    .filter(authority -> !authority.startsWith("ROLE_"))
                    .collect(Collectors.toSet());
            unrestricted = authentication.getAuthorities().stream()
                    .anyMatch(authority -> "ROLE_SYSTEM_ADMIN".equals(authority.getAuthority()));
        }
        return ApiResponse.ok(service.navigation(permissions, unrestricted));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('system:menu')")
    public ApiResponse<SystemMenuService.MenuView> create(@Valid @RequestBody MenuRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('system:menu')")
    public ApiResponse<SystemMenuService.MenuView> update(@PathVariable Long id,
                                                           @Valid @RequestBody MenuRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:menu')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    public record MenuRequest(Long parentId,
                              @NotNull MenuType menuType,
                              @NotBlank @Size(max = 100) String title,
                              @NotBlank @Size(max = 100) String routeName,
                              @NotBlank @Size(max = 200) String routePath,
                              @Size(max = 200) String componentPath,
                              @Size(max = 200) String redirectPath,
                              @Size(max = 100) String permissionCode,
                              @Size(max = 100) String icon,
                              @Min(1) @Max(9999) int sortNo,
                              boolean visible,
                              boolean enabled,
                              boolean keepAlive) implements SystemMenuService.MenuWrite {
    }
}
