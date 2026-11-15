import { vi } from 'vitest';
import { AxiosError, AxiosHeaders, type AxiosAdapter, type InternalAxiosRequestConfig } from 'axios';
import api from '../lib/api';

type Reply = { status: number; data?: unknown };
type Handler = (config: InternalAxiosRequestConfig) => Reply | Promise<Reply>;

const originalAdapter = api.defaults.adapter;

/** Answers every request made through the shared axios instance; non-2xx replies reject like a real server. */
export function mockApi(handler: Handler) {
  const adapter = vi.fn<AxiosAdapter>(async (config) => {
    const { status, data = {} } = await handler(config);
    const response = { data, status, statusText: '', headers: {}, config };
    if (status >= 200 && status < 300) return response;
    throw new AxiosError(`HTTP ${status}`, 'ERR_BAD_RESPONSE', config, {}, response);
  });
  api.defaults.adapter = adapter;
  return adapter;
}

/** Plain AxiosError for components that call a mocked service directly. */
export function httpError(status: number, data: unknown, url = '/api/v1/opd/visits/today') {
  const config = { url, headers: new AxiosHeaders() } as InternalAxiosRequestConfig;
  return new AxiosError('Request failed', 'ERR_BAD_RESPONSE', config, {}, {
    status, statusText: '', data, headers: {}, config,
  });
}

/** By default the browser has no refresh cookie: the auth bootstrap ends signed out. */
export const signedOut: Handler = (config) =>
  config.url?.includes('/auth/refresh') ? { status: 401, data: { detail: 'Session expired.' } } : { status: 404 };

export function restoreApi() {
  api.defaults.adapter = originalAdapter;
}

export const session = (role: 'DOCTOR' | 'NURSE' | 'LABTECH' | 'ADMIN', accessToken = 'access-' + role) => ({
  accessToken, tokenType: 'Bearer', expiresInMs: 900000,
  username: role.toLowerCase(), fullName: 'Test User', role,
});
