import { http } from "@/utils/http";
import type { ApiResponse, PageResponse } from "./types";

export interface AttendanceDailySummary {
  date: string;
  expectedCount: number;
  attendedCount: number;
  capturedCount: number;
  autoCompletedCount: number;
  missingCount: number;
  completenessRate: number;
  eventCount: number;
  autoCompletedEventCount: number;
  outsideBaselineCount: number;
}

export interface PersonAttendanceDetail {
  personId: number;
  personName: string;
  workType?: string;
  companyCode: string;
  companyName: string;
  teamId: string;
  teamName: string;
  attendanceStatus: "ATTENDED" | "MISSING";
  attendanceSource: "HIKVISION" | "AUTO_COMPLETED" | "MIXED" | "NONE";
  firstEventTime?: string;
  lastEventTime?: string;
  eventCount: number;
  capturedEventCount: number;
  autoCompletedEventCount: number;
  entryCount: number;
  exitCount: number;
  locations: string[];
  pushStatus: string;
}

export interface AttendanceDailyDetails {
  summary: AttendanceDailySummary;
  people: PageResponse<PersonAttendanceDetail>;
}

export interface AttendanceCompletionWindowPreview {
  label: string;
  windowStart: string;
  windowEnd: string;
  closed: boolean;
  beforePushCutoff: boolean;
  alreadyCompleted: boolean;
  expectedCount: number;
  capturedCount: number;
  autoCompletedCount: number;
  coveredCount: number;
  targetCount: number;
  toGenerateCount: number;
}

export interface AttendanceCompletionPreview {
  date: string;
  targetRate: number;
  executable: boolean;
  closedWindowCount: number;
  message: string;
  windows: AttendanceCompletionWindowPreview[];
}

export interface AttendanceCompletionWindowExecution {
  label: string;
  windowStart: string;
  windowEnd: string;
  expectedCount: number;
  capturedCount: number;
  autoCompletedCount: number;
  newlyGeneratedCount: number;
  targetCount: number;
  succeededTaskCount: number;
  pausedTaskCount: number;
  remainingTaskCount: number;
  completed: boolean;
}

export interface AttendanceCompletionExecution {
  date: string;
  targetRate: number;
  newlyGeneratedCount: number;
  collection: {
    queried: number;
    inserted: number;
    enriched: number;
    duplicates: number;
  };
  matching: {
    total: number;
    matched: number;
    unmatched: number;
    conflict: number;
    released: number;
  };
  windows: AttendanceCompletionWindowExecution[];
}

export const getAttendanceDailySummaries = (params: {
  startDate: string;
  endDate: string;
}) =>
  http.request<ApiResponse<AttendanceDailySummary[]>>(
    "get",
    "/attendance-statistics/daily",
    { params }
  );

export const getAttendanceDailyDetails = (params: {
  date: string;
  attendanceStatus?: string;
  search?: string;
  page: number;
  size: number;
}) =>
  http.request<ApiResponse<AttendanceDailyDetails>>(
    "get",
    "/attendance-statistics/details",
    { params }
  );

export const getAttendanceCompletionPreview = (params: {
  date: string;
  targetRate?: number;
}) =>
  http.request<ApiResponse<AttendanceCompletionPreview>>(
    "get",
    "/attendance-statistics/completion-preview",
    { params }
  );

export const completeAndPushAttendanceDay = (data: {
  date: string;
  targetRate: number;
}) =>
  http.request<ApiResponse<AttendanceCompletionExecution>>(
    "post",
    "/attendance-statistics/complete-and-push",
    { data, timeout: 300000 }
  );
