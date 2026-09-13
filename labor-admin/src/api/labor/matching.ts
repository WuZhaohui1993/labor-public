import { http } from "@/utils/http";
import type { ApiResponse, PageResponse } from "./types";
import type { MatchMethod, MatchStatus } from "./hik";

export interface MatchItem {
  id: number;
  eventId: string;
  proCode: string;
  hikPersonName?: string;
  hikPersonId?: string;
  idcardType?: string;
  idcardNumber?: string;
  eventTime: string;
  direction: string;
  matchStatus: MatchStatus;
  matchMethod?: MatchMethod;
  matchedPersonId?: number;
  matchReason?: string;
}

export interface MatchHistory {
  id: number;
  previousPersonId?: number;
  matchedPersonId?: number;
  previousStatus?: MatchStatus;
  matchStatus: MatchStatus;
  matchMethod?: MatchMethod;
  actionType: string;
  reason?: string;
  operatedBy: string;
  createdAt: string;
}

export interface BatchMatchResult {
  total: number;
  matched: number;
  unmatched: number;
  conflicts: number;
  released: number;
}

export const getMatches = (params: {
  page: number;
  size: number;
  proCode?: string;
  status?: MatchStatus | "";
  search?: string;
}) =>
  http.request<ApiResponse<PageResponse<MatchItem>>>("get", "/matches", {
    params
  });

export const getMatchHistory = (eventId: number) =>
  http.request<ApiResponse<MatchHistory[]>>(
    "get",
    `/matches/${eventId}/history`
  );

export const confirmMatch = (
  eventId: number,
  data: { personId: number; reason: string }
) =>
  http.request<ApiResponse<MatchItem>>("post", `/matches/${eventId}/confirm`, {
    data
  });

export const releaseMatch = (eventId: number, reason: string) =>
  http.request<ApiResponse<MatchItem>>("post", `/matches/${eventId}/release`, {
    data: { reason }
  });

export const rematch = (eventId: number, reason: string) =>
  http.request<ApiResponse<MatchItem>>("post", `/matches/${eventId}/rematch`, {
    data: { reason }
  });

export const batchRematch = (eventIds: number[], reason: string) =>
  http.request<ApiResponse<BatchMatchResult>>(
    "post",
    "/matches/batch/rematch",
    {
      data: { eventIds, reason }
    }
  );

export const batchConfirm = (
  eventIds: number[],
  personId: number,
  reason: string
) =>
  http.request<ApiResponse<BatchMatchResult>>(
    "post",
    "/matches/batch/confirm",
    {
      data: { eventIds, personId, reason }
    }
  );
