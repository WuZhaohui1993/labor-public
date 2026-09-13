const ACTIVE_WORKSPACE_KEY = "labor-active-project";

export function getActiveProjectCode(): string {
  return localStorage.getItem(ACTIVE_WORKSPACE_KEY) || "";
}

export function setActiveProjectCode(proCode: string) {
  if (proCode) localStorage.setItem(ACTIVE_WORKSPACE_KEY, proCode);
  else localStorage.removeItem(ACTIVE_WORKSPACE_KEY);
}

export function clearActiveProjectCode() {
  localStorage.removeItem(ACTIVE_WORKSPACE_KEY);
}
