import { BrowserRouter, Route, Routes } from 'react-router-dom';
import { AuthProvider } from './features/auth/AuthContext';
import { StoreLayout } from './layouts/StoreLayout';
import { ProtectedRoute } from './routes/ProtectedRoute';
import { CataloguePage } from './pages/CataloguePage';
import { SearchPage } from './pages/SearchPage';
import { ProductPage } from './pages/ProductPage';
import { LoginPage, RegisterPage } from './pages/AuthPages';
import { CartPage } from './pages/CartPage';
import { CheckoutPage } from './pages/CheckoutPage';
import { OrderPage, OrdersPage } from './pages/OrdersPage';
import { NotificationsPage } from './pages/NotificationsPage';
import { AccountPage } from './pages/AccountPage';
import { AdminDltDetailPage, AdminDltPage, AdminLayout, AdminProductsPage } from './pages/AdminPages';
import { NotFoundPage } from './pages/NotFoundPage';

export default function App() { return <BrowserRouter><AuthProvider><Routes><Route element={<StoreLayout />}><Route index element={<CataloguePage />} /><Route path="search" element={<SearchPage />} /><Route path="products/:id" element={<ProductPage />} /><Route element={<ProtectedRoute />}><Route path="cart" element={<CartPage />} /><Route path="checkout" element={<CheckoutPage />} /><Route path="orders" element={<OrdersPage />} /><Route path="orders/:id" element={<OrderPage />} /><Route path="notifications" element={<NotificationsPage />} /><Route path="account" element={<AccountPage />} /></Route><Route element={<ProtectedRoute roles={['ADMIN']} />}><Route path="admin" element={<AdminLayout />}><Route path="products" element={<AdminProductsPage />} /><Route path="dlt" element={<AdminDltPage />} /><Route path="dlt/:id" element={<AdminDltDetailPage />} /></Route></Route><Route path="*" element={<NotFoundPage />} /></Route><Route path="login" element={<LoginPage />} /><Route path="register" element={<RegisterPage />} /></Routes></AuthProvider></BrowserRouter>; }
