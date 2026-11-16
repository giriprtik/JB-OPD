import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { refreshAccessToken, registerSessionListener, setAccessToken, type SessionResponse } from '../lib/api';
import { AuthApi } from '../lib/services';
import type { Role, SessionUser } from '../lib/types';
import { Spinner } from '../components/ui';

interface AuthState {
  user: SessionUser | null;
  login: (username: string, password: string) => Promise<SessionUser>;
  logout: () => Promise<void>;
  hasRole: (...roles: Role[]) => boolean;
}

const AuthContext = createContext<AuthState | null>(null);

const toUser = (s: SessionResponse): SessionUser => ({ username: s.username, fullName: s.fullName, role: s.role });

/**
 * The access token lives only in memory (lib/api). On page load we have none, so we ask the auth
 * service to swap the HttpOnly refresh cookie for one; until that answers, nothing route-guarded renders.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<SessionUser | null>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    let active = true;
    registerSessionListener((session) => { if (active) setUser(session ? toUser(session) : null); });
    refreshAccessToken().finally(() => { if (active) setReady(true); });
    return () => {
      active = false;
      registerSessionListener(null);
    };
  }, []);

  const login = async (username: string, password: string) => {
    const res = await AuthApi.login(username, password);
    setAccessToken(res.accessToken);
    const u = toUser(res);
    setUser(u);
    return u;
  };

  const logout = async () => {
    try {
      await AuthApi.logout();   // revokes the refresh token server-side and clears the cookie
    } catch {
      /* offline or already expired: signing out locally is still right */
    }
    setAccessToken(null);
    setUser(null);
  };

  const hasRole = (...roles: Role[]) => !!user && roles.includes(user.role);

  if (!ready) {
    return (
      <div className="grid min-h-screen place-items-center">
        <Spinner label="Restoring your session…" />
      </div>
    );
  }

  return <AuthContext.Provider value={{ user, login, logout, hasRole }}>{children}</AuthContext.Provider>;
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}

/** Where each role lands after sign-in. */
// eslint-disable-next-line react-refresh/only-export-components
export const HOME: Record<Role, string> = {
  ADMIN: '/registration',
  NURSE: '/registration',
  DOCTOR: '/doctor',
  LABTECH: '/lab',
};
