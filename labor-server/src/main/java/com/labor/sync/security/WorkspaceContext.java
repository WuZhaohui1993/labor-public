package com.labor.sync.security;

import com.labor.sync.common.BusinessException;

public final class WorkspaceContext {
    private static final ThreadLocal<WorkspaceRef> CURRENT = new ThreadLocal<>();

    private WorkspaceContext() {
    }

    public static void set(WorkspaceRef workspace) {
        CURRENT.set(workspace);
    }

    public static WorkspaceRef current() {
        return CURRENT.get();
    }

    public static WorkspaceRef required() {
        WorkspaceRef workspace = CURRENT.get();
        if (workspace == null) {
            throw new BusinessException("WORKSPACE_REQUIRED", "请先选择项目工作区");
        }
        return workspace;
    }

    public static Long projectId() {
        return required().projectId();
    }

    public static String proCode() {
        return required().proCode();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public record WorkspaceRef(Long projectId, String proCode, String projectName) {
    }
}
