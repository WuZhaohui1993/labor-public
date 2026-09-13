import { http } from "@/utils/http";
import type { ApiResponse, PageResponse } from "./types";

export type MatchStatus = "UNMATCHED" | "MATCHED" | "CONFLICT" | "RELEASED";
export type MatchMethod =
  | "CERTIFICATE"
  | "HIK_PERSON_ID"
  | "MANUAL"
  | "AUTO_COMPLETED";
export type AttendanceSource = "HIKVISION" | "AUTO_COMPLETED";

export interface HikMapping {
  id: number;
  proCode: string;
  orgIndexCode: string;
  orgName?: string;
  orgPath?: string;
  mappingSource: "HIKVISION" | "MANUAL";
  includeChildren: boolean;
  enabled: boolean;
  lastEventTime?: string;
  lastSuccessAt?: string;
  lastError?: string;
}

export interface HikOrganizationOption {
  orgIndexCode: string;
  orgName: string;
  parentOrgIndexCode?: string;
  orgPath?: string;
  sortOrder: number;
  mapped: boolean;
}

export interface HikEvent {
  id: number;
  eventId: string;
  proCode: string;
  orgIndexCode?: string;
  hikPersonId?: string;
  personName?: string;
  idcardType?: string;
  idcardNumber?: string;
  eventTime: string;
  direction: string;
  checkType: string;
  checkWay: string;
  checkLocation?: string;
  attendanceSource: AttendanceSource;
  completionRunId?: number;
  matchStatus: MatchStatus;
  matchMethod?: MatchMethod;
  matchedPersonId?: number;
  matchReason?: string;
  receivedAt: string;
}

export const getHikMappings = () =>
  http.request<ApiResponse<HikMapping[]>>("get", "/hik/organizations");

export const getHikOrganizationOptions = () =>
  http.request<ApiResponse<HikOrganizationOption[]>>(
    "get",
    "/hik/organization-options"
  );

export const saveHikMapping = (
  id: number | undefined,
  data: Pick<
    HikMapping,
    | "proCode"
    | "orgIndexCode"
    | "orgName"
    | "orgPath"
    | "mappingSource"
    | "includeChildren"
    | "enabled"
  >
) =>
  http.request<ApiResponse<HikMapping>>(
    id ? "put" : "post",
    id ? `/hik/organizations/${id}` : "/hik/organizations",
    { data }
  );

export const disableHikMapping = (id: number) =>
  http.request<ApiResponse<void>>("delete", `/hik/organizations/${id}`);

export const collectHikEvents = (data: {
  mappingId: number;
  startTime?: string;
  endTime?: string;
  advanceCursor: boolean;
}) =>
  http.request<
    ApiResponse<{
      mappingId: number;
      startTime: string;
      endTime: string;
      queried: number;
      inserted: number;
      enriched: number;
      duplicates: number;
      cursorTime?: string;
    }>
  >("post", "/hik/collections", { data });

export const getHikEvents = (params: {
  page: number;
  size: number;
  proCode?: string;
  matchStatus?: MatchStatus | "";
  search?: string;
}) =>
  http.request<ApiResponse<PageResponse<HikEvent>>>("get", "/hik/events", {
    params
  });

export const updateHikDirection = (id: number, direction: string) =>
  http.request<ApiResponse<HikEvent>>("put", `/hik/events/${id}/direction`, {
    data: { direction }
  });
