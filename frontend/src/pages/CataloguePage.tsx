import { useSearchParams } from 'react-router-dom';
import { ProductCard } from '../components/ProductCard';
import { EmptyState, ErrorState, Loader } from '../components/Feedback';
import { productApi } from '../api/services';
import { useAsync } from '../hooks/useAsync';

export function CataloguePage() {
  const [params, setParams] = useSearchParams(); const page = Math.max(0, Number(params.get('page')) || 0);
  const result = useAsync(() => productApi.list(page), [page]);
  return <><section className="hero"><div><p className="eyebrow">The considered collection</p><h1>Useful things,<br />chosen with intent.</h1><p>Browse the live product catalogue. No invented ratings, discounts, or stock claims—just what the platform actually knows.</p></div><div className="hero-accent" aria-hidden="true"><span>01</span><p>Independent services.<br />One storefront.</p></div></section><section className="page-section"><div className="section-heading"><div><p className="eyebrow">Catalogue</p><h2>All products</h2></div>{result.data && <p className="muted">{result.data.totalElements} items</p>}</div>{result.loading ? <div className="product-grid skeleton-grid">{Array.from({length: 8}, (_, i) => <div className="skeleton" key={i} />)}</div> : result.error ? <ErrorState message={result.error} retry={() => void result.reload()} /> : !result.data?.items.length ? <EmptyState title="No products yet" text="Active products will appear here when they are added to the catalogue." /> : <><div className="product-grid">{result.data.items.map(product => <ProductCard key={product.productId} product={product} />)}</div><Pagination page={page} hasNext={result.data.hasNext} hasPrevious={result.data.hasPrevious} onChange={next => setParams(next ? { page: String(next) } : {})} /></>}</section></>;
}
export function Pagination({ page, hasNext, hasPrevious, onChange }: { page: number; hasNext: boolean; hasPrevious: boolean; onChange: (page: number) => void }) { return <nav className="pagination" aria-label="Pagination"><button className="button secondary" disabled={!hasPrevious} onClick={() => onChange(page - 1)}>Previous</button><span>Page {page + 1}</span><button className="button secondary" disabled={!hasNext} onClick={() => onChange(page + 1)}>Next</button></nav>; }
void Loader;
