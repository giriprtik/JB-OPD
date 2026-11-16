import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, vi } from 'vitest';
import { cleanup } from '@testing-library/react';
import { setAccessToken } from '../lib/api';
import { mockApi, restoreApi, signedOut } from './apiMock';

// Every test starts signed out: no access token in memory and no refresh cookie on the "server".
beforeEach(() => {
  mockApi(signedOut);
});

afterEach(() => {
  cleanup();
  setAccessToken(null);
  restoreApi();
  vi.restoreAllMocks();
  localStorage.clear();
});
