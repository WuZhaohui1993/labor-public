import { http } from "@/utils/http";
import type { ApiResponse } from "./types";

export interface IntegrationConfig {
  id: number;
  integrationType: string;
  baseUrl?: string;
  appKey?: string;
  appSecret?: string;
  userId?: string;
  enabled: boolean;
  configVersion: number;
  lastTestStatus?: string;
  lastTestMessage?: string;
  lastTestAt?: string;
}

export const getIntegrationConfigs = () =>
  http.request<ApiResponse<IntegrationConfig[]>>("get", "/integration-configs");
export const saveIntegrationConfig = (
  type: string,
  data: Partial<IntegrationConfig> & { appSecret?: string }
) =>
  http.request<ApiResponse<IntegrationConfig>>(
    "put",
    `/integration-configs/${type}`,
    { data }
  );
export const testIntegrationConfig = (type: string) =>
  http.request<ApiResponse<{ success: boolean; message: string }>>(
    "post",
    `/integration-configs/${type}/test`
  );
