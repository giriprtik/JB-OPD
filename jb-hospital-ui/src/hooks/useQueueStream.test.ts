import { renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setAccessToken } from '../lib/api';
import { useQueueStream } from './useQueueStream';

type Options = {
  onmessage: (m: { event: string; data: string }) => void;
  onopen: (r: Response) => Promise<void>;
  onerror: (e: unknown) => number | void;
  fetch: (input: RequestInfo, init?: RequestInit) => Promise<Response>;
  signal: AbortSignal;
};
let captured: Options | undefined;

vi.mock('@microsoft/fetch-event-source', () => ({
  fetchEventSource: vi.fn((_url: string, options: Options) => {
    captured = options;
    return new Promise<void>(() => {});
  }),
}));

describe('useQueueStream', () => {
  beforeEach(() => { vi.useFakeTimers(); captured = undefined; });
  afterEach(() => { vi.useRealTimers(); });

  it('debounces a burst of visit events into one reload', () => {
    const onChange = vi.fn();
    renderHook(() => useQueueStream(onChange));

    captured!.onmessage({ event: 'visit', data: '{}' });
    captured!.onmessage({ event: 'visit', data: '{}' });
    captured!.onmessage({ event: 'visit', data: '{}' });
    vi.advanceTimersByTime(400);

    expect(onChange).toHaveBeenCalledTimes(1);
  });

  it('ignores the ready handshake and heartbeats', () => {
    const onChange = vi.fn();
    renderHook(() => useQueueStream(onChange));

    captured!.onmessage({ event: 'ready', data: 'ok' });
    vi.advanceTimersByTime(400);

    expect(onChange).not.toHaveBeenCalled();
  });

  it('sends the current in-memory token on every (re)connect', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null));
    renderHook(() => useQueueStream(vi.fn()));

    setAccessToken('first');
    await captured!.fetch('/api/v1/opd/stream', { headers: {} });
    setAccessToken('second');
    await captured!.fetch('/api/v1/opd/stream', { headers: {} });

    const auth = fetchSpy.mock.calls.map(([, init]) => (init!.headers as Record<string, string>).Authorization);
    expect(auth).toEqual(['Bearer first', 'Bearer second']);
  });

  it('keeps retrying quietly after an error', () => {
    renderHook(() => useQueueStream(vi.fn()));
    expect(captured!.onerror(new Error('network'))).toBe(5000);
  });

  it('closes the stream on unmount', () => {
    const { unmount } = renderHook(() => useQueueStream(vi.fn()));
    const signal = captured!.signal;
    unmount();
    expect(signal.aborted).toBe(true);
  });

  it('does not connect when disabled', () => {
    renderHook(() => useQueueStream(vi.fn(), false));
    expect(captured).toBeUndefined();
  });
});
