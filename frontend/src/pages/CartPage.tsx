import { Minus, Plus, Trash2 } from 'lucide-react';
import { Link } from 'react-router-dom';
import { cartApi, productApi } from '../api/services';
import { useAuth } from '../features/auth/AuthContext';
import { useAsync } from '../hooks/useAsync';
import { EmptyState, ErrorState, Loader } from '../components/Feedback';
import { money } from '../utils/format';
import type { Cart, Product } from '../types';
import { ApiError } from '../api/client';

interface CartView { cart: Cart | null; products: Record<string, Product> }
export function CartPage() {
  const { user } = useAuth(); const result = useAsync<CartView>(async () => { try { const cart = await cartApi.getForUser(user!.userId); const products = await Promise.all(cart.items.map(item => productApi.get(item.productId))); return { cart, products: Object.fromEntries(products.map(p => [p.productId, p])) }; } catch (error) { if (error instanceof ApiError && error.status === 404) return { cart: null, products: {} }; throw error; } }, [user!.userId]);
  const mutate = async (action: () => Promise<unknown>) => { await action(); await result.reload(); };
  if (result.loading) return <Loader label="Loading cart" />; if (result.error) return <ErrorState message={result.error} retry={() => void result.reload()} />; const cart = result.data?.cart;
  if (!cart?.items.length) return <section className="page-section"><EmptyState title="Your cart is empty" text="Browse the catalogue and add something considered." /><div className="center"><Link className="button" to="/">Browse products</Link></div></section>;
  const currencies = new Set(cart.items.map(item => result.data?.products[item.productId]?.currency).filter(Boolean)); const currency = currencies.size === 1 ? [...currencies][0]! : null; const total = cart.items.reduce((sum, item) => sum + (result.data?.products[item.productId]?.price || 0) * item.quantity, 0);
  return <section className="page-section narrow"><div className="page-title"><p className="eyebrow">Your selection</p><h1>Shopping cart</h1></div><div className="cart-layout"><div className="cart-list">{cart.items.map(item => { const product = result.data?.products[item.productId]; return <article className="cart-row" key={item.id}><div className="mini-art" aria-hidden="true" /><div><h2>{product?.name || item.productId}</h2><p className="muted">{product ? money(product.price, product.currency) : 'Product details unavailable'}</p><div className="quantity"><button aria-label={`Decrease ${product?.name || 'item'} quantity`} disabled={item.quantity === 1} onClick={() => void mutate(() => cartApi.update(cart.id, item.productId, item.quantity - 1))}><Minus size={16} /></button><span>{item.quantity}</span><button aria-label={`Increase ${product?.name || 'item'} quantity`} onClick={() => void mutate(() => cartApi.update(cart.id, item.productId, item.quantity + 1))}><Plus size={16} /></button></div></div><div className="cart-row-end"><strong>{product ? money(product.price * item.quantity, product.currency) : '—'}</strong><button className="icon-button" aria-label={`Remove ${product?.name || 'item'}`} onClick={() => void mutate(() => cartApi.remove(cart.id, item.productId))}><Trash2 size={18} /></button></div></article>; })}<button className="text-button danger" onClick={() => void mutate(() => cartApi.clear(cart.id))}>Clear cart</button></div><aside className="summary"><h2>Summary</h2><div><span>{cart.items.reduce((n, item) => n + item.quantity, 0)} items</span><strong>{currency ? money(total, currency) : 'Calculated at checkout'}</strong></div><p className="muted small">Product prices shown are current catalogue values. Checkout calculates the authoritative total.</p><Link className="button wide" to="/checkout">Continue to checkout</Link></aside></div></section>;
}
