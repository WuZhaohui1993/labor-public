package com.labor.sync.security;

import com.labor.sync.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/labor/admin/workspaces")
public class WorkspaceController {
    private final WorkspaceAccessService accessService;

    @GetMapping
    public ApiResponse<List<WorkspaceAccessService.WorkspaceView>> list(Authentication authentication) {
        return ApiResponse.ok(accessService.available(authentication.getName()));
    }
}
