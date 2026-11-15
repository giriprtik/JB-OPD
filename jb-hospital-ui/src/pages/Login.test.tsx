import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AxiosError, AxiosHeaders, type InternalAxiosRequestConfig } from 'axios';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../context/AuthContext';
import { getAccessToken } from '../lib/api';
import { AuthApi } from '../lib/services';
import { session } from '../test/apiMock';
import Login from './Login';

vi.mock('../lib/services', () => ({ AuthApi: { login: vi.fn(), logout: vi.fn() } }));
const login = vi.mocked(AuthApi.login);

/** Renders, then waits for the session bootstrap (no refresh cookie: signed out) to finish. */
async function renderLogin(path = '/login') {
  const view = render(
    <AuthProvider>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="/doctor" element={<p>doctor workspace</p>} />
          <Route path="/registration" element={<p>registration desk</p>} />
        </Routes>
      </MemoryRouter>
    </AuthProvider>,
  );
  await waitFor(() => expect(screen.queryByText(/restoring your session/i)).not.toBeInTheDocument());
  return view;
}

describe('Login', () => {
  beforeEach(() => { login.mockReset(); });

  it('signs in and lands each role on its own workspace', async () => {
    login.mockResolvedValue({ ...session('DOCTOR', 'doctor-access'), fullName: 'Dr. Ramesh Sharma' });
    await renderLogin();

    await userEvent.type(screen.getByLabelText('Username'), '  doctor ');
    await userEvent.type(screen.getByLabelText('Password'), 'Demo@1234');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    expect(await screen.findByText('doctor workspace')).toBeInTheDocument();
    expect(login).toHaveBeenCalledWith('doctor', 'Demo@1234');
    expect(getAccessToken()).toBe('doctor-access');   // in memory only
    expect(localStorage.length).toBe(0);
  });

  it('demo account buttons fill the form', async () => {
    await renderLogin();

    await userEvent.click(screen.getByRole('button', { name: /lab tech/i }));

    expect(screen.getByLabelText('Username')).toHaveValue('labtech');
    expect(screen.getByLabelText('Password')).toHaveValue('Demo@1234');
  });

  it('shows the server message when sign-in fails', async () => {
    const config = { headers: new AxiosHeaders() } as InternalAxiosRequestConfig;
    const rejected = new AxiosError('401', 'ERR_BAD_REQUEST', config, {}, {
      status: 401, statusText: '', headers: {}, config, data: { detail: 'Invalid username or password.' },
    });
    login.mockRejectedValue(rejected);
    await renderLogin();

    await userEvent.type(screen.getByLabelText('Username'), 'nurse');
    await userEvent.type(screen.getByLabelText('Password'), 'wrong');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    expect(await screen.findByText('Invalid username or password.')).toBeInTheDocument();
    expect(screen.getByLabelText('Username')).toBeEnabled();
  });

  it('explains why the user was signed out', async () => {
    await renderLogin('/login?expired=1');
    expect(screen.getByText(/your session expired/i)).toBeInTheDocument();
  });
});
