import { http } from "@/utils/http";
import type { ApiResponse } from "./types";

export interface WorkspaceItem {
  id: number;
  proCode: string;
  projectName: string;
  status: string;
  roles: string[];
  permissions: string[];
}

export const getWorkspaces = () =>
  http.request<ApiResponse<WorkspaceItem[]>>("get", "/workspaces");
