import { http } from "@/utils/http";

export type UserResult = {
  code: string;
  message: string;
  data: {
    /** 头像 */
    avatar: string;
    /** 用户名 */
    username: string;
    /** 昵称 */
    nickname: string;
    /** 当前登录用户的角色 */
    roles: Array<string>;
    /** 按钮级别权限 */
    permissions: Array<string>;
    /** `token` */
    accessToken: string;
    /** 用于调用刷新`accessToken`的接口时所需的`token` */
    refreshToken?: string;
    /** `accessToken`的过期时间（格式'xxxx/xx/xx xx:xx:xx'） */
    expires: Date;
  };
};

export type CaptchaResult = {
  code: string;
  message: string;
  data: {
    captchaId: string;
    image: string;
    expiresAt: string;
  };
};

export type RefreshTokenResult = {
  code: string;
  message: string;
  data: {
    /** `token` */
    accessToken: string;
    /** 用于调用刷新`accessToken`的接口时所需的`token` */
    refreshToken?: string;
    /** `accessToken`的过期时间（格式'xxxx/xx/xx xx:xx:xx'） */
    expires: Date;
  };
};

export type UserInfo = {
  /** 头像 */
  avatar: string;
  /** 用户名 */
  username: string;
  /** 昵称 */
  nickname: string;
  /** 邮箱 */
  email: string;
  /** 联系电话 */
  phone: string;
  /** 简介 */
  description: string;
  /** 是否已设置密保问题 */
  securityQuestionSet: boolean;
};

export type UserInfoResult = {
  code: string;
  message: string;
  data: UserInfo;
};

type ResultTable = {
  code: string;
  message: string;
  data?: {
    /** 列表数据 */
    list: Array<any>;
    /** 总条目数 */
    total?: number;
    /** 每页显示条目个数 */
    pageSize?: number;
    /** 当前页数 */
    currentPage?: number;
  };
};

/** 登录 */
export const getLogin = (data?: object) => {
  return http.request<UserResult>("post", "/auth/login", { data });
};

/** 服务端图片验证码 */
export const getCaptcha = () =>
  http.request<CaptchaResult>("get", "/auth/captcha");

/** 刷新`token` */
export const refreshTokenApi = () => {
  return http.request<RefreshTokenResult>("post", "/auth/refresh");
};

export const logoutApi = () => http.request("post", "/auth/logout");

export const getCurrentUser = () => http.request<UserResult>("get", "/auth/me");

/** 账户设置-个人信息 */
export const getMine = (data?: object) => {
  return http.request<UserInfoResult>("get", "/mine", { data });
};

/** 更新账户资料 */
export const updateMineProfile = (
  data: Pick<UserInfo, "nickname" | "email" | "phone" | "description">
) => {
  return http.request<UserInfoResult>("put", "/mine/profile", { data });
};

/** 更新账户头像 */
export const updateMineAvatar = (file: File) => {
  const formData = new FormData();
  formData.append("file", file);
  return http.request<UserInfoResult>("post", "/mine/avatar", {
    data: formData,
    headers: { "Content-Type": "multipart/form-data" }
  });
};

export type PasswordUpdate = {
  currentPassword: string;
  newPassword: string;
  confirmPassword: string;
};

/** 修改登录密码 */
export const updateMinePassword = (data: PasswordUpdate) => {
  return http.request<{ code: string; message: string }>(
    "put",
    "/mine/password",
    { data }
  );
};

export type SecurityQuestionUpdate = {
  question: string;
  answer: string;
  currentPassword: string;
};

/** 设置密保问题 */
export const updateMineSecurityQuestion = (data: SecurityQuestionUpdate) => {
  return http.request<UserInfoResult>("put", "/mine/security-question", {
    data
  });
};

export type MinePreferences = Record<string, boolean>;

export const getMinePreferences = () =>
  http.request<{ code: string; message: string; data: MinePreferences }>(
    "get",
    "/mine/preferences"
  );

export const updateMinePreferences = (data: MinePreferences) =>
  http.request<{ code: string; message: string; data: MinePreferences }>(
    "put",
    "/mine/preferences",
    { data }
  );

/** 账户设置-个人安全日志 */
export const getMineLogs = (data?: object) => {
  return http.request<ResultTable>("get", "/mine-logs", { data });
};
