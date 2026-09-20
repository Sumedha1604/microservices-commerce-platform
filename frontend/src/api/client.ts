import type { ApiErrorBody, ApiResponse, AuthResponse } from '../types';

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) { super(message); this.name = 'ApiError'; }
}

const baseUrl = (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
let accessToken: string | null = null;
let refreshToken: string | null = null;
let refreshing: Promise<boolean> | null = null;
let unauthorizedHandler: (() => void) | null = null;

export function configureAuth(access: string | null, refresh: string | null, onUnauthorized?: () => void) { accessToken = access; refreshToken = refresh; unauthorizedHandler = onUnauthorized ?? null; }

async function rotateToken(): Promise<boolean> {
  if (!refreshToken) return false;
  try {
    const response = await fetch(`${baseUrl}/api/v1/auth/refresh`, { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, body: JSON.stringify({ refreshToken }) });
    if (!response.ok) return false;
    const envelope = await response.json() as ApiResponse<AuthResponse>;
    accessToken = envelope.data.tokens.accessToken;
    refreshToken = envelope.data.tokens.refreshToken;
    sessionStorage.setItem('commerce.auth', JSON.stringify(envelope.data));
    return true;
  } catch { return false; }
}

export async function api<T>(path: string, init: RequestInit = {}, retry = true): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');
  if (init.body) headers.set('Content-Type', 'application/json');
  if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`);
  let response: Response;
  try { response = await fetch(`${baseUrl}${path}`, { ...init, headers }); }
  catch { throw new ApiError(0, 'NETWORK_ERROR', 'Unable to reach the API Gateway.'); }
  if (response.status === 401 && retry && refreshToken && !path.includes('/auth/')) {
    refreshing ??= rotateToken().finally(() => { refreshing = null; });
    if (await refreshing) return api<T>(path, init, false);
    unauthorizedHandler?.();
  }
  if (!response.ok) {
    let error: Partial<ApiErrorBody> = {};
    try { error = await response.json() as ApiErrorBody; } catch { /* non-JSON error */ }
    throw new ApiError(response.status, error.errorCode || 'REQUEST_FAILED', error.message || `Request failed (${response.status})`);
  }
  if (response.status === 204) return undefined as T;
  const envelope = await response.json() as ApiResponse<T>;
  if (!envelope.success) throw new ApiError(response.status, 'REQUEST_FAILED', envelope.message);
  return envelope.data;
}

export const query = (values: Record<string, string | number | undefined | null>) => {
  const params = new URLSearchParams();
  Object.entries(values).forEach(([key, value]) => { if (value !== undefined && value !== null && value !== '') params.set(key, String(value)); });
  const text = params.toString();
  return text ? `?${text}` : '';
};
