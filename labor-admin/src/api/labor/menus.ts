import { http } from "@/utils/http";
import type { ApiResponse } from "./types";

export type MenuType = "DIRECTORY" | "PAGE";

export interface MenuItem {
  id: number;
  parentId?: number;
  menuType: MenuType;
  title: string;
  routeName: string;
  routePath: string;
  componentPath?: string;
  redirectPath?: string;
  permissionCode?: string;
  icon?: string;
  sortNo: number;
  visible: boolean;
  enabled: boolean;
  keepAlive: boolean;
  builtin: boolean;
}

export interface NavigationMenu {
  id: number;
  path: string;
  name: string;
  component?: string;
  redirect?: string;
  meta: {
    title: string;
    icon?: string;
    rank: number;
    showLink: boolean;
    keepAlive: boolean;
    permission?: string;
  };
  children?: NavigationMenu[];
}

export type MenuWrite = Omit<MenuItem, "id" | "builtin">;

export const getMenus = () =>
  http.request<ApiResponse<MenuItem[]>>("get", "/system/menus");

export const getNavigationMenus = () =>
  http.request<ApiResponse<NavigationMenu[]>>(
    "get",
    "/system/menus/navigation"
  );

export const saveMenu = (menu: Partial<MenuItem>) =>
  http.request<ApiResponse<MenuItem>>(
    menu.id ? "put" : "post",
    menu.id ? `/system/menus/${menu.id}` : "/system/menus",
    {
      data: {
        parentId: menu.parentId || null,
        menuType: menu.menuType,
        title: menu.title,
        routeName: menu.routeName,
        routePath: menu.routePath,
        componentPath: menu.componentPath || null,
        redirectPath: menu.redirectPath || null,
        permissionCode: menu.permissionCode || null,
        icon: menu.icon || null,
        sortNo: menu.sortNo,
        visible: menu.visible,
        enabled: menu.enabled,
        keepAlive: menu.keepAlive
      }
    }
  );

export const removeMenu = (id: number) =>
  http.request<ApiResponse<void>>("delete", `/system/menus/${id}`);
