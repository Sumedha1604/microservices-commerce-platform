import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ProductCard } from './ProductCard';

it('renders only real product summary fields and a details link', () => {
  render(<MemoryRouter><ProductCard product={{ productId: 'p1', sku: 'SKU-1', name: 'Desk Lamp', slug: 'desk-lamp', price: 49.5, currency: 'USD' }} /></MemoryRouter>);
  expect(screen.getByRole('heading', { name: 'Desk Lamp' })).toBeInTheDocument();
  expect(screen.getByText('$49.50')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: /view desk lamp/i })).toHaveAttribute('href', '/products/p1');
  expect(screen.queryByText(/rating/i)).not.toBeInTheDocument();
});
