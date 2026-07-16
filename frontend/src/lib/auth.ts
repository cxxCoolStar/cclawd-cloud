// Cookie-based session is the source of truth. These helpers exist so
// older callers that still imported isLoggedIn / logout keep working
// while the rest of the UI moves to /api/me + /api/login + /api/logout.
import { setAuthToken, getAuthToken, logout as apiLogout } from "./api";

// isLoggedIn returns true when there's a bearer token in localStorage. The
// authoritative login state for the UI is the cookie session, but several
// older components still gate UI on this — they'll render correctly even
// when isLoggedIn is false, because apiFetch sends the cookie regardless.
export function isLoggedIn(): boolean {
  return !!getAuthToken();
}

export function login(token: string) {
  // Programmatic clients can still drop a bearer apikey here; the cookie
  // session takes precedence on the server when both are present.
  setAuthToken(token);
}

export function logout() {
  void apiLogout();
}
