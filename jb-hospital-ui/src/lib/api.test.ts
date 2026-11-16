import { AxiosError } from 'axios';
import { describe, expect, it, vi } from 'vitest';
import api, { apiError, getAccessToken, refreshAccessToken, registerSessionListener, setAccessToken } from './api';
import { httpError, mockApi, session } from '../test/apiMock';

describe('apiError', () => {
  it('prefers the problem+json detail', () => {
    expect(apiError(httpError(409, { title: 'Conflict', detail: 'Ram Bahadur already has an open visit today.' })))
      .toBe('Ram Bahadur already has an open visit today.');
  });

  it('falls back to a legacy message field, then to the status code', () => {
    expect(apiError(httpError(400, { message: 'Bad thing' }))).toBe('Bad thing');
    expect(apiError(httpError(502, ''), 'Could not save')).toBe('Could not save (HTTP 502)');
  });

  it('explains a network failure', () => {
    const offline = new AxiosError('Network Error', 'ERR_NETWORK');
    expect(apiError(offline)).toMatch(/Cannot reach the server/);
  });

  it('uses the fallback for anything that is not an HTTP error', () => {
    expect(apiError(new Error('boom'), 'Sign-in failed')).toBe('Sign-in failed');
  });
});

describe('api client', () => {
  const stubLocation = (pathname: string) => {
    const assign = vi.fn();
    vi.spyOn(window, 'location', 'get').mockReturnValue({ ...window.location, pathname, assign });
    return assign;
  };

  it('sends the in-memory token as a Bearer header and never touches localStorage', async () => {
    setAccessToken('abc.def.ghi');
    const adapter = mockApi(() => ({ status: 200, data: [] }));

    await api.get('/api/v1/opd/visits/today');

    expect(adapter.mock.calls[0][0].headers.Authorization).toBe('Bearer abc.def.ghi');
    expect(localStorage.length).toBe(0);
  });

  it('on a 401, refreshes once and replays the request with the new token', async () => {
    setAccessToken('expired');
    const adapter = mockApi((config) => {
      if (config.url?.includes('/auth/refresh')) return { status: 200, data: session('NURSE', 'fresh-token') };
      return config.headers.Authorization === 'Bearer fresh-token'
        ? { status: 200, data: ['ok'] }
        : { status: 401 };
    });

    const res = await api.get('/api/v1/opd/visits/today');

    expect(res.data).toEqual(['ok']);
    expect(getAccessToken()).toBe('fresh-token');
    const refreshCall = adapter.mock.calls.find(([c]) => c.url?.includes('/auth/refresh'))![0];
    expect(refreshCall.headers['X-JB-Refresh']).toBe('1');
  });

  it('parallel 401s share a single refresh call', async () => {
    setAccessToken('expired');
    const adapter = mockApi((config) => {
      if (config.url?.includes('/auth/refresh')) return { status: 200, data: session('DOCTOR', 'fresh') };
      return config.headers.Authorization === 'Bearer fresh' ? { status: 200, data: 1 } : { status: 401 };
    });

    await Promise.all([api.get('/a'), api.get('/b'), api.get('/c')]);

    expect(adapter.mock.calls.filter(([c]) => c.url?.includes('/auth/refresh'))).toHaveLength(1);
  });

  it('signs the user out when the refresh fails too', async () => {
    setAccessToken('expired');
    const listener = vi.fn();
    registerSessionListener(listener);
    const assign = stubLocation('/triage');
    mockApi(() => ({ status: 401 }));

    await expect(api.get('/api/v1/opd/visits/today')).rejects.toBeInstanceOf(AxiosError);

    expect(getAccessToken()).toBeNull();
    expect(listener).toHaveBeenCalledWith(null);
    expect(assign).toHaveBeenCalledWith('/login?expired=1');
    registerSessionListener(null);
  });

  it('does not retry a request that already failed after a refresh', async () => {
    setAccessToken('expired');
    const adapter = mockApi((config) =>
      config.url?.includes('/auth/refresh') ? { status: 200, data: session('NURSE') } : { status: 401 });
    stubLocation('/triage');

    await expect(api.get('/api/v1/opd/visits/today')).rejects.toBeInstanceOf(AxiosError);

    // original + one replay, never a loop
    expect(adapter.mock.calls.filter(([c]) => c.url === '/api/v1/opd/visits/today')).toHaveLength(2);
  });

  it('does not treat a failed login (401) as an expired session', async () => {
    const assign = stubLocation('/login');
    const adapter = mockApi(() => ({ status: 401, data: { detail: 'Invalid username or password.' } }));

    await expect(api.post('/api/v1/auth/login', {})).rejects.toBeInstanceOf(AxiosError);

    expect(assign).not.toHaveBeenCalled();
    expect(adapter).toHaveBeenCalledTimes(1);   // no refresh attempt
  });

  it('keeps the session on a 403', async () => {
    setAccessToken('still-valid');
    mockApi(() => ({ status: 403, data: { detail: 'Your role is not allowed to do this.' } }));

    await expect(api.get('/api/v1/opd/patients')).rejects.toBeInstanceOf(AxiosError);

    expect(getAccessToken()).toBe('still-valid');
  });

  it('refreshAccessToken resolves null without a refresh cookie', async () => {
    mockApi(() => ({ status: 401 }));

    await expect(refreshAccessToken()).resolves.toBeNull();
  });
});
