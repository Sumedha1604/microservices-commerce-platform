import { Check, Image as ImageIcon, ShoppingBag } from 'lucide-react';
import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { cartApi, productApi, recommendationApi } from '../api/services';
import { ApiError } from '../api/client';
import { useAuth } from '../features/auth/AuthContext';
import { useAsync } from '../hooks/useAsync';
import { ErrorState, Loader, Status } from '../components/Feedback';
import { ProductCard } from '../components/ProductCard';
import { money } from '../utils/format';

export function ProductPage() {
  const { id = '' } = useParams(); const { user } = useAuth(); const navigate = useNavigate(); const product = useAsync(() => productApi.get(id), [id]); const recs = useAsync(() => recommendationApi.get(id), [id]); const [adding, setAdding] = useState(false); const [added, setAdded] = useState(false); const [actionError, setActionError] = useState('');
  const add = async () => { if (!user) { navigate('/login', { state: { from: `/products/${id}` } }); return; } setAdding(true); setActionError(''); try { let cart; try { cart = await cartApi.getForUser(user.userId); } catch (error) { if (error instanceof ApiError && error.status === 404) cart = await cartApi.create(user.userId); else throw error; } await cartApi.add(cart.id, id); setAdded(true); } catch (error) { setActionError(error instanceof Error ? error.message : 'Could not add item'); } finally { setAdding(false); } };
  if (product.loading) return <Loader label="Loading product" />; if (product.error || !product.data) return <ErrorState message={product.error || 'Product not found'} retry={() => void product.reload()} />;
  const p = product.data; const primary = p.images.find(image => image.primaryImage) || p.images[0];
  return <section className="page-section"><div className="product-detail"><div className="detail-image">{primary ? <img src={primary.url} alt={primary.altText || p.name} /> : <><ImageIcon /><span>Product image unavailable</span></>}</div><div className="detail-copy"><p className="eyebrow">{p.category.name}{p.brand ? ` · ${p.brand.name}` : ''}</p><h1>{p.name}</h1><p className="sku">SKU {p.sku}</p><strong className="detail-price">{money(p.price, p.currency)}</strong><p className="lead">{p.shortDescription || p.description || 'No description is available for this product.'}</p><div className="detail-status"><Status value={p.status} />{p.active && <span><Check size={16} /> Active catalogue item</span>}</div><button className="button wide" onClick={() => void add()} disabled={adding || !p.active || p.status !== 'ACTIVE'}><ShoppingBag size={18} />{adding ? 'Adding…' : added ? 'Added to cart' : 'Add to cart'}</button>{actionError && <p className="form-error" role="alert">{actionError}</p>}{p.attributes.length > 0 && <dl className="attributes">{p.attributes.map(a => <div key={a.attributeId}><dt>{a.name}</dt><dd>{a.value}</dd></div>)}</dl>}</div></div><section className="recommendations"><div className="section-heading"><div><p className="eyebrow">Content-based recommendations</p><h2>You may also like</h2></div></div>{recs.loading ? <Loader label="Finding related products" /> : recs.error ? <p className="muted">Recommendations are temporarily unavailable.</p> : recs.data?.items.length ? <div className="product-grid compact-grid">{recs.data.items.map(item => <ProductCard product={{...item, sku: ''}} key={item.productId} />)}</div> : <p className="muted">No related products are available.</p>}</section></section>;
}
