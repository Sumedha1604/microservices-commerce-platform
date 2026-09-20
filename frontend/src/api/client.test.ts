import { api, configureAuth } from './client';

describe('API client', () => {
  it('normalizes backend error envelopes', async () => {
    configureAuth(null, null);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ errorCode: 'BAD_REQUEST', message: 'Invalid input', statusCode: 400 }), { status: 400, headers: { 'Content-Type': 'application/json' } })));
    await expect(api('/api/v1/products')).rejects.toEqual(expect.objectContaining({ status: 400, code: 'BAD_REQUEST', message: 'Invalid input' }));
  });
  it('unwraps successful API responses', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ success: true, message: 'ok', data: { id: 1 }, timestamp: '' }), { status: 200 })));
    await expect(api<{id:number}>('/test')).resolves.toEqual({ id: 1 });
  });
});
