import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { CheckoutPage } from './CheckoutPage';

const { create } = vi.hoisted(() => ({ create: vi.fn().mockResolvedValue({ cartId: 'c1', orderId: 'order-123', paymentId: 'payment-456', orderStatus: 'PENDING', paymentStatus: 'PENDING', total: 25, currency: 'USD' }) }));
vi.mock('../features/auth/AuthContext', () => ({ useAuth: () => ({ user: { userId: 'u1' } }) }));
vi.mock('../api/services', () => ({ cartApi: { getForUser: vi.fn().mockResolvedValue({ id: 'c1', items: [{ id: 'i1' }] }) }, checkoutApi: { create } }));
it('shows the backend checkout result and identifiers', async () => {
  const user = userEvent.setup(); render(<MemoryRouter><CheckoutPage /></MemoryRouter>);
  await user.click(await screen.findByRole('button', { name: 'Place order' }));
  expect(await screen.findByText('Order created')).toBeInTheDocument();
  expect(screen.getByText('order-123')).toBeInTheDocument(); expect(screen.getByText('payment-456')).toBeInTheDocument(); expect(create).toHaveBeenCalledWith('c1');
});
