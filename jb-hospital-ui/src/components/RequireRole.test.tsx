import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { AuthProvider } from '../context/AuthContext';
import { getAccessToken } from '../lib/api';
import type { Role } from '../lib/types';
import { mockApi, session } from '../test/apiMock';
import { RequireRole } from './RequireRole';

/** The browser holds a valid refresh cookie for this role: the bootstrap refresh succeeds. */
function hasRefreshCookieFor(role: Role) {
  mockApi((config) => (config.url?.includes('/auth/refresh')
    ? { status: 200, data: session(role) }
    : { status: 404 }));
}

function renderAt(path: string) {
  return render(
    <AuthProvider>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/login" element={<p>login page</p>} />
          <Route path="/forbidden" element={<p>forbidden page</p>} />
          <Route element={<RequireRole roles={['DOCTOR']} />}>
            <Route path="/doctor" element={<p>doctor workspace</p>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </AuthProvider>,
  );
}

describe('RequireRole', () => {
  it('sends signed-out users to the login page', async () => {
    renderAt('/doctor');
    expect(await screen.findByText('login page')).toBeInTheDocument();
  });

  it('restores the session from the refresh cookie and lets the right role through', async () => {
    hasRefreshCookieFor('DOCTOR');
    renderAt('/doctor');
    expect(await screen.findByText('doctor workspace')).toBeInTheDocument();
    expect(getAccessToken()).toBe('access-DOCTOR');
  });

  it('shows the forbidden page to other roles', async () => {
    hasRefreshCookieFor('NURSE');
    renderAt('/doctor');
    expect(await screen.findByText('forbidden page')).toBeInTheDocument();
  });

  it('shows a loading state instead of bouncing to login while the session is being restored', async () => {
    let answer: (reply: { status: number }) => void = () => {};
    const adapter = mockApi(() => new Promise((resolve) => { answer = resolve; }));
    renderAt('/doctor');
    await waitFor(() => expect(adapter).toHaveBeenCalled());

    expect(screen.getByText(/restoring your session/i)).toBeInTheDocument();
    expect(screen.queryByText('login page')).not.toBeInTheDocument();

    answer({ status: 401 });   // let the refresh settle so it doesn't leak into the next test
    expect(await screen.findByText('login page')).toBeInTheDocument();
  });

  it('treats a server error during restore as signed out', async () => {
    mockApi(() => ({ status: 500 }));
    renderAt('/doctor');
    expect(await screen.findByText('login page')).toBeInTheDocument();
  });

  it('never stores tokens in localStorage', async () => {
    hasRefreshCookieFor('DOCTOR');
    renderAt('/doctor');
    await screen.findByText('doctor workspace');
    expect(localStorage.length).toBe(0);
  });
});
