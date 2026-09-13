import { defineStore } from "pinia";
import { store } from "../utils";
import { getWorkspaces, type WorkspaceItem } from "@/api/labor/workspaces";
import { getActiveProjectCode, setActiveProjectCode } from "@/utils/workspace";

export const useWorkspaceStore = defineStore("labor-workspace", {
  state: () => ({
    workspaces: [] as WorkspaceItem[],
    activeProCode: getActiveProjectCode(),
    loaded: false,
    loading: false
  }),
  getters: {
    current(state): WorkspaceItem | undefined {
      return state.workspaces.find(
        workspace => workspace.proCode === state.activeProCode
      );
    }
  },
  actions: {
    async load(force = false) {
      if ((this.loaded && !force) || this.loading) return this.current;
      this.loading = true;
      try {
        const result = await getWorkspaces();
        this.workspaces = result.data;
        const selected = this.workspaces.find(
          workspace => workspace.proCode === this.activeProCode
        );
        const next = selected || this.workspaces[0];
        this.activeProCode = next?.proCode || "";
        setActiveProjectCode(this.activeProCode);
        this.loaded = true;
        return next;
      } finally {
        this.loading = false;
      }
    },
    select(proCode: string) {
      const workspace = this.workspaces.find(item => item.proCode === proCode);
      if (!workspace) return;
      this.activeProCode = workspace.proCode;
      setActiveProjectCode(workspace.proCode);
      return workspace;
    },
    reset() {
      this.workspaces = [];
      this.activeProCode = "";
      this.loaded = false;
      setActiveProjectCode("");
    }
  }
});

export function useWorkspaceStoreHook() {
  return useWorkspaceStore(store);
}
