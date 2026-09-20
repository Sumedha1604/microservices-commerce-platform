import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AuthProvider, useAuth } from './AuthContext';
import { ProtectedRoute } from '../../routes/ProtectedRoute';

const auth = { userId: 'user-1', email: 'a@example.test', role: 'CUSTOMER', verified: true, tokens: { accessToken: 'access', refreshToken: 'refresh', tokenType: 'Bearer' } };
function Identity() { const { user } = useAuth(); return <span>{user?.email || 'anonymous'}</span>; }
describe('authentication state and protected routes', () => {
  it('restores the authenticated user from session storage', () => { sessionStorage.setItem('commerce.auth', JSON.stringify(auth)); render(<AuthProvider><Identity /></AuthProvider>); expect(screen.getByText('a@example.test')).toBeInTheDocument(); });
  it('redirects anonymous users to login', () => { render(<MemoryRouter initialEntries={['/cart']}><AuthProvider><Routes><Route element={<ProtectedRoute />}><Route path="/cart" element={<p>Cart</p>} /></Route><Route path="/login" element={<p>Login page</p>} /></Routes></AuthProvider></MemoryRouter>); expect(screen.getByText('Login page')).toBeInTheDocument(); });
  it('rejects a customer from an admin route', () => { sessionStorage.setItem('commerce.auth', JSON.stringify(auth)); render(<MemoryRouter initialEntries={['/admin']}><AuthProvider><Routes><Route path="/" element={<p>Home</p>} /><Route element={<ProtectedRoute roles={['ADMIN']} />}><Route path="/admin" element={<p>Admin</p>} /></Route></Routes></AuthProvider></MemoryRouter>); expect(screen.getByText('Home')).toBeInTheDocument(); });
});
