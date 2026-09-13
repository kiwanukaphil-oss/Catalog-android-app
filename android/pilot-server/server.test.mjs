import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { createPilotServer } from './server.mjs';

/** Exercise persistent acceptance and branch isolation over HTTP with the same stable identifiers a phone uses. */
test('pilot persists uploads, reconciles lost acknowledgements, isolates branches, and never receives stock', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'kline-pilot-test-'));
  let server = await createPilotServer({ directory, port: 0 });
  let root = `http://127.0.0.1:${server.address().port}`;
  const request = (route, options = {}) => fetch(root + route, options);
  try {
    const login = await (await request('/api/auth/login', { method: 'POST', body: JSON.stringify({ username: 'pilot', password: 'pilot-only' }) })).json();
    const headers = { Authorization: `Bearer ${login.token}`, 'X-Branch-Id': '10000000-0000-4000-8000-000000000001' };
    const batchId = randomUUID(), itemId = randomUUID();
    const delivery = await request('/api/catalog-workspace/batches', { method: 'POST', headers, body: JSON.stringify({ id: batchId, title: 'Recovery test' }) });
    assert.equal(delivery.status, 200);
    const upload = () => {
      const body = new FormData(); body.set('id', itemId); body.set('category_id', '20000000-0000-4000-8000-000000000004');
      body.set('image', new Blob([Buffer.from([0xff, 0xd8, 0xff, 0xd9])], { type: 'image/jpeg' }), `${itemId}.jpg`);
      return request('/api/catalog/items', { method: 'POST', headers, body });
    };
    await request('/__test/fault', { method: 'POST', body: JSON.stringify({ kind: 'drop-upload-ack' }) });
    await assert.rejects(upload());
    assert.equal((await upload()).status, 200);
    await new Promise(resolve => server.close(resolve));
    server = await createPilotServer({ directory, port: 0 }); root = `http://127.0.0.1:${server.address().port}`;
    assert.equal((await upload()).status, 200);
    await request('/__test/fault', { method: 'POST', body: JSON.stringify({ kind: 'received' }) });
    assert.equal((await request(`/api/catalog-workspace/batches/${batchId}/items/${itemId}`, { method: 'PUT', headers })).status, 409);
    const detail = await (await request(`/api/catalog-workspace/items/${itemId}`, { headers })).json();
    assert.equal(detail.item.is_published, true);
    assert.equal((await request(`/api/catalog-workspace/items/${itemId}`, { headers: { ...headers, 'X-Branch-Id': '10000000-0000-4000-8000-000000000002' } })).status, 404);
    const state = await (await request('/__test/state')).json();
    assert.equal(Object.keys(state.items).length, 1); assert.equal(state.stockMutations, 0);
    assert.equal(state.items[itemId].batch_id, null);
  } finally { await new Promise(resolve => server.close(resolve)); }
});
