import { http } from "@/utils/http";
import type { ApiResponse, PageQuery, PageResponse } from "./types";

export interface UserItem {
  id: number;
  username: string;
  displayName: string;
  enabled: boolean;
  failedLoginAttempts: number;
  lockedUntil?: string | null;
  lastFailedLoginAt?: string | null;
  roles: string[];
  projectRoles: Array<{
    projectId: number;
    proCode: string;
    projectName: string;
    roles: string[];
  }>;
}

export interface RoleItem {
  id: number;
  code: string;
  name: string;
  builtin: boolean;
  permissions: string[];
}

export interface PermissionItem {
  id: number;
  code: string;
  name: string;
  group: string;
}

export interface AuditItem {
  id: number;
  actor: string;
  action: string;
  resourceType: string;
  resourceId?: string;
  reason?: string;
  result: string;
  traceId: string;
  clientIp?: string;
  createdAt: string;
}

export const getUsers = () =>
  http.request<ApiResponse<UserItem[]>>("get", "/system/users");
export const saveUser = (user: Partial<UserItem> & { password?: string }) =>
  http.request<ApiResponse<UserItem>>(
    user.id ? "put" : "post",
    user.id ? `/system/users/${user.id}` : "/system/users",
    {
      data: {
        username: user.username,
        displayName: user.displayName,
        password: user.password,
        enabled: user.enabled,
        roleCodes: user.roles,
        projectRoles: user.projectRoles?.map(item => ({
          proCode: item.proCode,
          roleCodes: item.roles
        }))
      }
    }
  );
export const unlockUser = (id: number) =>
  http.request<ApiResponse<UserItem>>("post", `/system/users/${id}/unlock`);
export const getRoles = () =>
  http.request<ApiResponse<RoleItem[]>>("get", "/system/roles");
export const getPermissions = () =>
  http.request<ApiResponse<PermissionItem[]>>("get", "/system/permissions");
export const saveRolePermissions = (id: number, permissions: string[]) =>
  http.request<ApiResponse<RoleItem>>(
    "put",
    `/system/roles/${id}/permissions`,
    { data: permissions }
  );
export const getAuditLogs = (params: PageQuery) =>
  http.request<ApiResponse<PageResponse<AuditItem>>>("get", "/audit-logs", {
    params
  });
