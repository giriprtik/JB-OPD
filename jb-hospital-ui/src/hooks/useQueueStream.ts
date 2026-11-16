import { useEffect, useRef } from 'react';
import { fetchEventSource } from '@microsoft/fetch-event-source';
import { getAccessToken, refreshAccessToken } from '../lib/api';

const STREAM_URL = `${import.meta.env.VITE_API_BASE_URL ?? ''}/api/v1/opd/stream`;
const DEBOUNCE_MS = 300;
const RETRY_MS = 5000;

class RetriableError extends Error {}

/**
 * Calls `onChange` (debounced) whenever the OPD service reports a visit change relevant to this user.
 * Uses fetch-based SSE because native EventSource can't send an Authorization header, and the access
 * token only lives in memory. Keep usePolling running at a slow interval as a fallback.
 */
export function useQueueStream(onChange: () => void, enabled = true) {
  const callback = useRef(onChange);
  useEffect(() => { callback.current = onChange; });

  useEffect(() => {
    if (!enabled) return;
    const ctrl = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    const fire = () => {
      clearTimeout(timer);
      timer = setTimeout(() => callback.current(), DEBOUNCE_MS);
    };

    fetchEventSource(STREAM_URL, {
      signal: ctrl.signal,
      openWhenHidden: false,   // closes while the tab is hidden, reconnects when it is shown again
      // Read the token on every (re)connect: it is replaced every 15 minutes.
      fetch: (input, init) => fetch(input, {
        ...init,
        credentials: 'same-origin',
        headers: { ...(init?.headers as Record<string, string>), Authorization: `Bearer ${getAccessToken() ?? ''}` },
      }),
      async onopen(res) {
        if (res.ok) return;
        if (res.status === 401) {
          await refreshAccessToken();   // expired token: refresh, then the library reconnects
          throw new RetriableError('token refreshed');
        }
        throw new RetriableError(`stream refused: HTTP ${res.status}`);
      },
      onmessage(msg) {
        if (msg.event === 'visit') fire();
      },
      onerror() {
        return RETRY_MS;   // keep retrying quietly; polling covers the gap
      },
    }).catch(() => { /* aborted on unmount */ });

    return () => {
      clearTimeout(timer);
      ctrl.abort();
    };
  }, [enabled]);
}
