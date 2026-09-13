import { http } from "@/utils/http";
import type { ApiResponse } from "./types";

export interface RecentBatch {
  id: number;
  fileName: string;
  status: string;
  totalCount: number;
  errorCount: number;
  createdAt: string;
}

export interface DashboardData {
  projects: number;
  companies: number;
  teams: number;
  persons: number;
  waitingReview: number;
  waitingPublish: number;
  unmatchedAttendance: number;
  conflictMatches: number;
  pendingPush: number;
  failedPush: number;
  pausedPush: number;
  recentBatches: RecentBatch[];
}

export const getDashboard = () =>
  http.request<ApiResponse<DashboardData>>("get", "/dashboard");
