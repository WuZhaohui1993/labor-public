import { http } from "@/utils/http";
import type { ApiResponse, PageResponse } from "./types";

export type PushTaskType =
  | "PROJECT"
  | "COMPANY"
  | "TEAM"
  | "PERSON"
  | "ATTENDANCE";
export type PushTaskStatus =
  | "WAITING_CONFIRM"
  | "PENDING"
  | "RUNNING"
  | "SUCCESS"
  | "FAILED"
  | "PAUSED"
  | "IGNORED";

export interface PushTask {
  id: number;
  taskType: PushTaskType;
  aggregateType: string;
  aggregateId: string;
  proCode: string;
  businessKey: string;
  dataVersionNo: number;
  idempotencyKey: string;
  dependencyKey?: string;
  status: PushTaskStatus;
  retryCount: number;
  maxRetries: number;
  nextRetryAt?: string;
  lastErrorCode?: string;
  lastErrorMessage?: string;
  remoteCode?: string;
  remoteMessage?: string;
  manualReason?: string;
  dependencyReady: boolean;
  startedAt?: string;
  completedAt?: string;
  createdAt: string;
  updatedAt: string;
}

export interface PushTaskDetail extends PushTask {
  payloadJson?: string;
  payloadSnapshot: boolean;
  payloadMessage?: string;
}

export interface PushTaskGroupSummary {
  taskType: PushTaskType;
  totalCount: number;
  waitingConfirmCount: number;
  pendingCount: number;
  runningCount: number;
  successCount: number;
  failedCount: number;
  pausedCount: number;
  ignoredCount: number;
  estimatedBatchCount: number;
}

export interface PushTaskSummary {
  totalCount: number;
  batchSize: number;
  groups: PushTaskGroupSummary[];
}

export interface BatchExecutionResult {
  requested: number;
  claimed: number;
  succeeded: number;
  failed: number;
  deferred: number;
  httpCalls: number;
}

export interface ProjectExecutionResult {
  proCode: string;
  total: number;
  succeeded: number;
  failed: number;
  paused: number;
  pending: number;
  waitingConfirm: number;
}

export interface IntegrationCallLog {
  id: number;
  integrationType: string;
  operationType: string;
  taskId?: number;
  replayJobId?: number;
  batchSize: number;
  requestPath: string;
  requestSummaryJson?: string;
  httpStatus?: number;
  responseSummaryJson?: string;
  success: boolean;
  durationMs: number;
  errorCode?: string;
  errorMessage?: string;
  traceId?: string;
  createdAt: string;
}

export interface ReplayPreview {
  projectCode: string;
  projectName: string;
  taskType?: PushTaskType;
  companyCode?: string;
  companyName?: string;
  startDate?: string;
  endDate?: string;
  total: number;
  byType: Partial<Record<PushTaskType, number>>;
  batchCount: number;
  attendanceCount: number;
  refreshSources: boolean;
  warning?: string;
}

export type ReplayJobStatus =
  | "CREATED"
  | "RUNNING"
  | "SUCCESS"
  | "PARTIAL_SUCCESS"
  | "FAILED";

export interface ReplayJob {
  id: number;
  jobNo: string;
  scopeType: string;
  scopeDescription: string;
  projectCode: string;
  projectName: string;
  taskType?: PushTaskType;
  companyCode?: string;
  companyName?: string;
  startDate?: string;
  endDate?: string;
  reason: string;
  requestedBy: string;
  status: ReplayJobStatus;
  totalCount: number;
  successCount: number;
  failedCount: number;
  pendingCount: number;
  completedCount: number;
  progressPercent: number;
  totalBatchCount: number;
  byType?: Partial<Record<PushTaskType, number>>;
  warning?: string;
  startedAt?: string;
  completedAt?: string;
  lastError?: string;
  createdAt: string;
}

export const getPushTasks = (params: {
  page: number;
  size: number;
  proCode?: string;
  taskType?: PushTaskType | "";
  status?: PushTaskStatus | "";
  search?: string;
}) =>
  http.request<ApiResponse<PageResponse<PushTask>>>("get", "/push/tasks", {
    params
  });

export const getPushTask = (id: number) =>
  http.request<ApiResponse<PushTaskDetail>>("get", `/push/tasks/${id}`);

export const getPushTaskSummary = () =>
  http.request<ApiResponse<PushTaskSummary>>("get", "/push/tasks/summary");

export const executePushTask = (id: number, reason = "人工立即执行") =>
  http.request<ApiResponse<BatchExecutionResult>>(
    "post",
    `/push/tasks/${id}/execute`,
    { data: { reason } }
  );

export const executePushTasks = (taskIds: number[], reason: string) =>
  http.request<ApiResponse<BatchExecutionResult>>(
    "post",
    "/push/tasks/batch/execute",
    { data: { taskIds, reason } }
  );

export const retryPushTask = (id: number, reason = "人工补推") =>
  http.request<ApiResponse<PushTask>>("post", `/push/tasks/${id}/retry`, {
    data: { reason }
  });

export const pausePushTask = (id: number, reason: string) =>
  http.request<ApiResponse<PushTask>>("post", `/push/tasks/${id}/pause`, {
    data: { reason }
  });

export const ignorePushTask = (id: number, reason: string) =>
  http.request<ApiResponse<PushTask>>("post", `/push/tasks/${id}/ignore`, {
    data: { reason }
  });

export const rebuildPushTasks = (proCode: string) =>
  http.request<
    ApiResponse<{ proCode: string; createdTasks: number; totalTasks: number }>
  >("post", "/push/rebuild", { params: { proCode } });

export const startProjectPush = (proCode: string) =>
  http.request<
    ApiResponse<{
      proCode: string;
      createdTasks: number;
      totalTasks: number;
      activatedTasks: number;
    }>
  >("post", "/push/start", { params: { proCode }, timeout: 30000 });

export const executeProjectPush = (proCode: string) =>
  http.request<ApiResponse<ProjectExecutionResult>>(
    "post",
    "/push/execute-project",
    { params: { proCode }, timeout: 300000 }
  );

export const previewReplay = (selection: {
  projectWide: boolean;
  projectCode?: string;
  taskIds?: number[];
  taskType?: PushTaskType;
  companyCode?: string;
  startDate?: string;
  endDate?: string;
  refreshSources?: boolean;
}) =>
  http.request<ApiResponse<ReplayPreview>>("post", "/push/replays/preview", {
    data: selection
  });

export const createReplay = (
  selection: {
    projectWide: boolean;
    projectCode?: string;
    taskIds?: number[];
    taskType?: PushTaskType;
    companyCode?: string;
    startDate?: string;
    endDate?: string;
    refreshSources?: boolean;
  },
  reason: string
) =>
  http.request<ApiResponse<ReplayJob>>("post", "/push/replays", {
    data: { ...selection, reason },
    timeout: 30000
  });

export const getReplayJob = (id: number) =>
  http.request<ApiResponse<ReplayJob>>("get", `/push/replays/${id}`);

export const getReplayJobs = () =>
  http.request<ApiResponse<ReplayJob[]>>("get", "/push/replays");

export const retryReplayFailed = (id: number, reason: string) =>
  http.request<ApiResponse<ReplayJob>>(
    "post",
    `/push/replays/${id}/retry-failed`,
    {
      data: { reason }
    }
  );

export const getIntegrationCallLogs = (params: {
  page: number;
  size: number;
  integrationType?: string;
  operationType?: string;
  success?: boolean | "";
  taskId?: number;
}) =>
  http.request<ApiResponse<PageResponse<IntegrationCallLog>>>(
    "get",
    "/push/call-logs",
    { params }
  );
