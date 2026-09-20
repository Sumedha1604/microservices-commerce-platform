import { ArrowRight, Image as ImageIcon } from 'lucide-react';
import { Link } from 'react-router-dom';
import type { ProductSummary, SearchProduct } from '../types';
import { money } from '../utils/format';

export function ProductCard({ product }: { product: ProductSummary | SearchProduct }) {
  const description = 'shortDescription' in product ? product.shortDescription : null;
  return <article className="product-card">
    <Link className="product-art" to={`/products/${product.productId}`} aria-label={`View ${product.name}`}><ImageIcon aria-hidden="true" /><span>Product image unavailable</span></Link>
    <div className="product-card-body"><p className="eyebrow">{product.sku}</p><h3><Link to={`/products/${product.productId}`}>{product.name}</Link></h3>{description && <p className="muted clamp">{description}</p>}<div className="product-card-bottom"><strong>{money(product.price, product.currency)}</strong><Link className="text-link" to={`/products/${product.productId}`}>View <ArrowRight size={16} /></Link></div></div>
  </article>;
}
