import { createContext, useCallback, useContext, useEffect, useMemo, useState, type PropsWithChildren } from 'react';
import { authApi } from '../../api/services';
import { configureAuth } from '../../api/client';
import type { AuthResponse, AuthUser } from '../../types';

interface AuthContextValue { user: AuthUser | null; loading: boolean; login(email: string, password: string): Promise<void>; register(email: string, password: string): Promise<void>; logout(): Promise<void> }
const AuthContext = createContext<AuthContextValue | null>(null);
const storageKey = 'commerce.auth';

function loadSession(): AuthResponse | null { try { const value = sessionStorage.getItem(storageKey); return value ? JSON.parse(value) as AuthResponse : null; } catch { return null; } }

export function AuthProvider({ children }: PropsWithChildren) {
  const [session, setSession] = useState<AuthResponse | null>(() => loadSession());
  const clear = useCallback(() => { sessionStorage.removeItem(storageKey); setSession(null); configureAuth(null, null); }, []);
  useEffect(() => { configureAuth(session?.tokens.accessToken ?? null, session?.tokens.refreshToken ?? null, clear); }, [session, clear]);
  const accept = (value: AuthResponse) => { sessionStorage.setItem(storageKey, JSON.stringify(value)); setSession(value); };
  const login = async (email: string, password: string) => accept(await authApi.login(email, password));
  const register = async (email: string, password: string) => accept(await authApi.register(email, password));
  const logout = async () => { const refresh = session?.tokens.refreshToken; clear(); if (refresh) try { await authApi.logout(refresh); } catch { /* local logout still succeeds */ } };
  const user = session ? { userId: session.userId, email: session.email, role: session.role, verified: session.verified } : null;
  const value = useMemo(() => ({ user, loading: false, login, register, logout }), [user]); // eslint-disable-line react-hooks/exhaustive-deps
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
// eslint-disable-next-line react-refresh/only-export-components
export function useAuth() { const value = useContext(AuthContext); if (!value) throw new Error('useAuth must be used within AuthProvider'); return value; }
