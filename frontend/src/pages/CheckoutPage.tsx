import { CheckCircle2, LoaderCircle } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { cartApi, checkoutApi } from '../api/services';
import { useAuth } from '../features/auth/AuthContext';
import { ErrorState, Loader, Status } from '../components/Feedback';
import { useAsync } from '../hooks/useAsync';
import { money } from '../utils/format';
import type { Checkout } from '../types';

export function CheckoutPage() {
  const { user } = useAuth(); const cart = useAsync(() => cartApi.getForUser(user!.userId), [user!.userId]); const [result, setResult] = useState<Checkout | null>(null); const [busy, setBusy] = useState(false); const [error, setError] = useState('');
  const submit = async () => { if (!cart.data) return; setBusy(true); setError(''); try { setResult(await checkoutApi.create(cart.data.id)); } catch (cause) { setError(cause instanceof Error ? cause.message : 'Checkout failed'); } finally { setBusy(false); } };
  if (cart.loading) return <Loader label="Preparing checkout" />; if (cart.error || !cart.data) return <ErrorState message={cart.error || 'No cart found'} />;
  if (result) return <section className="result-card"><CheckCircle2 /><p className="eyebrow">Checkout complete</p><h1>Order created</h1><p>Your checkout was accepted by the orchestration service.</p><dl><div><dt>Order</dt><dd>{result.orderId}</dd></div><div><dt>Payment</dt><dd>{result.paymentId}</dd></div><div><dt>Total</dt><dd>{money(result.total, result.currency)}</dd></div><div><dt>Status</dt><dd><Status value={result.orderStatus} /> <Status value={result.paymentStatus} /></dd></div></dl><Link className="button" to={`/orders/${result.orderId}`}>View order</Link></section>;
  return <section className="page-section narrow"><div className="page-title"><p className="eyebrow">Secure gateway checkout</p><h1>Review and place order</h1><p>The Checkout Service validates products and inventory, then creates your order and payment.</p></div>{error && <div className="checkout-error" role="alert"><h2>Checkout could not be completed</h2><p>{error}</p></div>}<div className="checkout-card"><div><h2>{cart.data.items.length} cart items</h2><p className="muted">Your cart remains unchanged after checkout.</p></div><button className="button" onClick={() => void submit()} disabled={busy || cart.data.items.length === 0}>{busy ? <><LoaderCircle className="spin" size={18} /> Processing…</> : 'Place order'}</button></div></section>;
}
