import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios';
import type { Role } from './types';

/** What /auth/login and /auth/refresh return. The refresh token itself only ever lives in an HttpOnly cookie. */
export interface SessionResponse {
  accessToken: string;
  tokenType: string;
  expiresInMs: number;
  username: string;
  fullName: string;
  role: Role;
}

export const REFRESH_URL = '/api/v1/auth/refresh';
export const LOGOUT_URL = '/api/v1/auth/logout';
const AUTH_ENDPOINTS = ['/api/v1/auth/login', REFRESH_URL, LOGOUT_URL];

// Older builds kept an 8-hour token in localStorage. Wipe it so it can't be lifted later.
try {
  localStorage.removeItem('jb.token');
  localStorage.removeItem('jb.user');
} catch {
  /* storage blocked: nothing to clean */
}

// ---------------------------------------------------------------- in-memory access token

let accessToken: string | null = null;

export const getAccessToken = () => accessToken;
export const setAccessToken = (token: string | null) => { accessToken = token; };

/** AuthContext subscribes so a refresh (or a failed one) updates who is signed in. */
type SessionListener = (session: SessionResponse | null) => void;
let sessionListener: SessionListener | null = null;
export function registerSessionListener(listener: SessionListener | null) {
  sessionListener = listener;
}

// ---------------------------------------------------------------- client

/**
 * Same-origin calls: nginx (Docker) and the Vite dev server both proxy /api to the gateway,
 * so the browser never needs CORS. Override with VITE_API_BASE_URL if the UI is hosted elsewhere.
 */
const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '',
  timeout: 15000,
  withCredentials: true, // the refresh cookie must travel if the UI is ever served from another origin
  headers: { 'Content-Type': 'application/json' },
});

api.interceptors.request.use((config) => {
  if (accessToken) config.headers.Authorization = `Bearer ${accessToken}`;
  return config;
});

// ---------------------------------------------------------------- refresh (single flight)

let inFlight: Promise<string | null> | null = null;

/**
 * Exchanges the refresh cookie for a new access token. Concurrent callers share one request,
 * so ten 401s arriving together trigger a single refresh. Resolves null when the session is over.
 */
export function refreshAccessToken(): Promise<string | null> {
  inFlight ??= api
    .post<SessionResponse>(REFRESH_URL, null, { headers: { 'X-JB-Refresh': '1' } })
    .then((res) => {
      setAccessToken(res.data.accessToken);
      sessionListener?.(res.data);
      return res.data.accessToken;
    })
    .catch(() => {
      setAccessToken(null);
      sessionListener?.(null);
      return null;
    })
    .finally(() => { inFlight = null; });
  return inFlight;
}

type RetriableConfig = InternalAxiosRequestConfig & { _retried?: boolean };

const isAuthEndpoint = (url?: string) => !!url && AUTH_ENDPOINTS.some((path) => url.includes(path));

// A 401 on a normal call means the access token expired: refresh once and replay the call.
// If the refresh fails too, the session is over. A 403 is shown to the user as a normal error.
api.interceptors.response.use(
  (res) => res,
  async (error: AxiosError) => {
    const config = error.config as RetriableConfig | undefined;
    if (error.response?.status === 401 && config && !isAuthEndpoint(config.url) && !config._retried) {
      config._retried = true;
      const token = await refreshAccessToken();
      if (token) return api(config);
      if (window.location.pathname !== '/login') window.location.assign('/login?expired=1');
    }
    return Promise.reject(error);
  },
);

/** Human-readable message from a problem+json (or legacy {message}) error body. */
export function apiError(err: unknown, fallback = 'Something went wrong.'): string {
  if (axios.isAxiosError(err)) {
    if (!err.response) return 'Cannot reach the server. Check that the services are running.';
    const data = err.response.data as { detail?: string; message?: string } | undefined;
    return data?.detail || data?.message || `${fallback} (HTTP ${err.response.status})`;
  }
  return fallback;
}

export default api;
