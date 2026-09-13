import { http } from "@/utils/http";
import type { ApiResponse, PageResponse, PageQuery } from "./types";

export type ImportScope = "ALL" | "PROJECT" | "COMPANY" | "TEAM" | "PERSON";

export interface ImportBatch {
  id: number;
  fileName: string;
  fileHash: string;
  uploadedBy: string;
  scope: ImportScope;
  status: string;
  totalCount: number;
  validCount: number;
  errorCount: number;
  newCount: number;
  updatedCount: number;
  unchangedCount: number;
  reviewComment?: string;
  reviewedBy?: string;
  reviewedAt?: string;
  publishedAt?: string;
  createdAt: string;
}

export interface ImportRow {
  id: number;
  sheet: string;
  row: number;
  entityType: string;
  businessKey: string;
  raw: Record<string, string>;
  normalized: Record<string, string>;
  changeType: string;
  validationStatus: string;
}

export interface ImportError {
  id: number;
  sheet: string;
  row: number;
  field: string;
  errorCode: string;
  message: string;
  rawValue?: string;
}

export const getImportBatches = (params: PageQuery) =>
  http.request<ApiResponse<PageResponse<ImportBatch>>>("get", "/imports", {
    params
  });

export const getImportBatch = (id: number) =>
  http.request<ApiResponse<ImportBatch>>("get", `/imports/${id}`);

export const uploadImport = (file: File, scope: ImportScope = "ALL") => {
  const data = new FormData();
  data.append("file", file);
  return http.request<ApiResponse<ImportBatch>>("post", "/imports", {
    data,
    params: { scope },
    headers: { "Content-Type": "multipart/form-data" },
    timeout: 60000
  });
};

export const getImportRows = (id: number, params: PageQuery) =>
  http.request<ApiResponse<PageResponse<ImportRow>>>(
    "get",
    `/imports/${id}/rows`,
    { params }
  );

export const getImportErrors = (id: number, params: PageQuery) =>
  http.request<ApiResponse<PageResponse<ImportError>>>(
    "get",
    `/imports/${id}/errors`,
    { params }
  );

export const submitImport = (id: number) =>
  http.request<ApiResponse<ImportBatch>>("post", `/imports/${id}/submit`);

export const approveImport = (id: number, comment = "") =>
  http.request<ApiResponse<ImportBatch>>("post", `/imports/${id}/approve`, {
    data: { comment }
  });

export const rejectImport = (id: number, comment: string) =>
  http.request<ApiResponse<ImportBatch>>("post", `/imports/${id}/reject`, {
    data: { comment }
  });

export const publishImport = (id: number) =>
  http.request<ApiResponse<ImportBatch>>("post", `/imports/${id}/publish`);

export const downloadTemplate = (scope: ImportScope = "ALL") =>
  http.request<Blob>("get", "/imports/template", {
    params: { scope },
    responseType: "blob"
  });

export const downloadImportErrors = (id: number) =>
  http.request<Blob>("get", `/imports/${id}/errors/export`, {
    responseType: "blob"
  });
