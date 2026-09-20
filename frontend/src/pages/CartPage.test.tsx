import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { CartPage } from './CartPage';

const { update } = vi.hoisted(() => ({ update: vi.fn().mockResolvedValue({}) }));
vi.mock('../features/auth/AuthContext', () => ({ useAuth: () => ({ user: { userId: 'u1' } }) }));
vi.mock('../api/services', () => ({
  cartApi: { getForUser: vi.fn().mockResolvedValue({ id: 'c1', userId: 'u1', items: [{ id: 'i1', productId: 'p1', quantity: 2, createdAt: '', updatedAt: '' }], createdAt: '', updatedAt: '' }), update, remove: vi.fn(), clear: vi.fn() },
  productApi: { get: vi.fn().mockResolvedValue({ productId: 'p1', sku: 'S1', name: 'Lamp', slug: 'lamp', price: 10, currency: 'USD' }) },
}));
it('renders cart totals and sends quantity changes to the cart service', async () => {
  const user = userEvent.setup(); render(<MemoryRouter><CartPage /></MemoryRouter>);
  expect(await screen.findAllByText('$20.00')).toHaveLength(2);
  await user.click(screen.getByRole('button', { name: 'Increase Lamp quantity' }));
  expect(update).toHaveBeenCalledWith('c1', 'p1', 3);
});
