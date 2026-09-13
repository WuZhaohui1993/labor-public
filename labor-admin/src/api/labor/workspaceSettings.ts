import { http } from "@/utils/http";
import type { ApiResponse } from "./types";

export interface WorkspaceSyncSetting {
  id: number;
  hikCollectionEnabled: boolean;
  pushEnabled: boolean;
  syncStarted: boolean;
  pushTime: string;
  zoneId: string;
  attendanceCompletionEnabled: boolean;
  attendanceCompletenessRate: number;
}

export const getWorkspaceSyncSetting = () =>
  http.request<ApiResponse<WorkspaceSyncSetting>>("get", "/workspace-settings");

export const saveWorkspaceSyncSetting = (data: WorkspaceSyncSetting) =>
  http.request<ApiResponse<WorkspaceSyncSetting>>(
    "put",
    "/workspace-settings",
    {
      data: {
        hikCollectionEnabled: data.hikCollectionEnabled,
        pushEnabled: data.pushEnabled,
        attendanceCompletionEnabled: data.attendanceCompletionEnabled,
        attendanceCompletenessRate: data.attendanceCompletenessRate,
        pushTime: data.pushTime,
        zoneId: data.zoneId
      }
    }
  );
