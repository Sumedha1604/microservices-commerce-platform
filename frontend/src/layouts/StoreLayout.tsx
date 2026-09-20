import { Menu, Search, ShoppingBag, UserRound, Bell, X } from 'lucide-react';
import { FormEvent, useState } from 'react';
import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../features/auth/AuthContext';

export function StoreLayout() {
  const { user, logout } = useAuth(); const navigate = useNavigate(); const [q, setQ] = useState(''); const [open, setOpen] = useState(false);
  const search = (event: FormEvent) => { event.preventDefault(); navigate(`/search${q.trim() ? `?q=${encodeURIComponent(q.trim())}` : ''}`); setOpen(false); };
  return <div className="app-shell"><a href="#main" className="skip-link">Skip to content</a><header className="site-header"><div className="header-inner">
    <Link className="brand" to="/" onClick={() => setOpen(false)}><span className="brand-mark">N</span><span>Northstar</span></Link>
    <form className="header-search" role="search" onSubmit={search}><Search size={18} /><label className="sr-only" htmlFor="site-search">Search products</label><input id="site-search" value={q} onChange={e => setQ(e.target.value)} placeholder="Search the catalogue" /></form>
    <button className="icon-button menu-button" aria-label="Toggle navigation" aria-expanded={open} onClick={() => setOpen(!open)}>{open ? <X /> : <Menu />}</button>
    <nav className={open ? 'nav open' : 'nav'} aria-label="Main navigation"><NavLink to="/" onClick={() => setOpen(false)}>Catalogue</NavLink>{user && <><NavLink to="/orders" onClick={() => setOpen(false)}>Orders</NavLink><NavLink title="Notifications" to="/notifications" onClick={() => setOpen(false)}><Bell size={19} /><span className="mobile-label">Notifications</span></NavLink><NavLink title="Cart" to="/cart" onClick={() => setOpen(false)}><ShoppingBag size={19} /><span className="mobile-label">Cart</span></NavLink><NavLink title="Account" to="/account" onClick={() => setOpen(false)}><UserRound size={19} /><span className="mobile-label">Account</span></NavLink>{user.role === 'ADMIN' && <NavLink to="/admin/products" onClick={() => setOpen(false)}>Admin</NavLink>}<button className="nav-logout" onClick={() => void logout()}>Log out</button></>}{!user && <><NavLink to="/login">Log in</NavLink><Link className="button compact" to="/register">Create account</Link></>}</nav>
  </div></header><main id="main"><Outlet /></main><footer><div><strong>Northstar Commerce</strong><p>A portfolio storefront powered by independent Java microservices.</p></div><nav aria-label="Footer navigation"><Link to="/">Catalogue</Link><Link to="/search">Search</Link>{user && <Link to="/orders">Orders</Link>}</nav></footer></div>;
}
