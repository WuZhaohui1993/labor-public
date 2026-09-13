import { http } from "@/utils/http";
import type { ApiResponse, PageQuery, PageResponse } from "./types";

export interface ProjectItem {
  id: number;
  proCode: string;
  projectName: string;
  internalRemark?: string;
  status: string;
  versionNo: number;
  publishedAt?: string;
  updatedAt: string;
}

export interface CompanyItem {
  id: number;
  proCode: string;
  collCropCode: string;
  companyName: string;
  personPushEnabled: boolean;
  collCropType: string;
  chinaFlag: string;
  entryDate?: string;
  exitDate?: string;
  contactName?: string;
  contactIdType?: string;
  contactIdNumber?: string;
  contactMobile?: string;
  blacklistFlag?: string;
  internalRemark?: string;
  status: string;
  versionNo: number;
}

export interface TeamItem {
  id: number;
  teamId: string;
  proCode: string;
  collCropCode: string;
  teamType: string;
  teamName: string;
  companyName?: string;
  entryDate?: string;
  exitDate?: string;
  leaderName?: string;
  leaderIdType?: string;
  leaderIdNumber?: string;
  leaderMobile?: string;
  internalRemark?: string;
  status: string;
  versionNo: number;
}

export interface PersonItem {
  id: number;
  name: string;
  idcardType: string;
  idcardNumber: string;
  idcardStartDate?: string;
  idcardEndDate?: string;
  idcardForever: string;
  proCode: string;
  teamId: string;
  teamName?: string;
  collCropCode?: string;
  companyName?: string;
  userType: string;
  workType: string;
  entryDate?: string;
  exitDate?: string;
  politicsStatus?: string;
  eduLevel?: string;
  maritalStatus?: string;
  sex?: string;
  idcardAddress?: string;
  homeAddress?: string;
  birthday?: string;
  nation?: string;
  countryCode?: string;
  provinceCode?: string;
  positiveIdcardImagePresent: boolean;
  negativeIdcardImagePresent: boolean;
  headImagePresent: boolean;
  mobile?: string;
  teamLeaderFlag?: string;
  hikPersonId?: string;
  internalRemark?: string;
  status: string;
  versionNo: number;
}

export type PersonFilterNodeType = "PROJECT" | "COMPANY" | "TEAM";

export interface PersonFilterTreeNode {
  key: string;
  type: PersonFilterNodeType;
  label: string;
  proCode: string;
  collCropCode?: string;
  teamId?: string;
  children: PersonFilterTreeNode[];
}

export interface PersonPageQuery extends PageQuery {
  collCropCode?: string;
  teamId?: string;
}

export type MasterDataItem = ProjectItem | CompanyItem | TeamItem | PersonItem;

export const getProjects = (params: PageQuery) =>
  http.request<ApiResponse<PageResponse<ProjectItem>>>("get", "/projects", {
    params
  });
export const getCompanies = (params: PageQuery) =>
  http.request<ApiResponse<PageResponse<CompanyItem>>>("get", "/companies", {
    params
  });
export const getTeams = (params: PageQuery) =>
  http.request<ApiResponse<PageResponse<TeamItem>>>("get", "/teams", {
    params
  });
export const getPersons = (params: PersonPageQuery) =>
  http.request<ApiResponse<PageResponse<PersonItem>>>("get", "/persons", {
    params
  });

export const getPersonFilterTree = () =>
  http.request<ApiResponse<PersonFilterTreeNode>>(
    "get",
    "/persons/filter-tree"
  );

export const getVersions = (type: string, id: number) =>
  http.request<ApiResponse<any[]>>("get", `/${type}/${id}/versions`);

export const saveCompanyPersonPushSetting = (id: number, enabled: boolean) =>
  http.request<ApiResponse<CompanyItem>>(
    "put",
    `/companies/${id}/person-push-setting`,
    { data: { enabled } }
  );

export const saveMasterData = (
  type: string,
  id: number | undefined,
  payload: Record<string, unknown>
) =>
  http.request<ApiResponse<MasterDataItem>>(
    id ? "put" : "post",
    id ? `/${type}/${id}` : `/${type}`,
    { data: payload }
  );

export const removeMasterData = (type: string, id: number) =>
  http.request<ApiResponse<void>>("delete", `/${type}/${id}`);
