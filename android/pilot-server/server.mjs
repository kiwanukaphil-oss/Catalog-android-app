import http from 'node:http';
import { createHash, randomUUID } from 'node:crypto';
import { mkdir, readFile, writeFile, rename } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const BRANCH = '10000000-0000-4000-8000-000000000001';
const OTHER_BRANCH = '10000000-0000-4000-8000-000000000002';
const category = (index, name, parent = null) => ({ id: `20000000-0000-4000-8000-${String(index).padStart(12, '0')}`, name, parent_id: parent });
const clothing = category(1, 'Clothing');
const shirts = category(2, 'Shirts', clothing.id);
const trousers = category(3, 'Trousers', clothing.id);
const footwear = category(6, 'Footware');
const categories = [clothing, shirts, trousers, category(4, 'Formal', shirts.id), category(5, 'Formal', trousers.id), footwear,
  category(7, 'Formal Shoes', footwear.id), category(8, 'Casual Shoes', footwear.id), category(9, 'Sandals', footwear.id),
  category(10, 'Underwear & Basics', clothing.id)];
for (const [index, name] of ['Boxers', 'Briefs', 'Socks', 'Vests'].entries()) categories.push(category(11 + index, name, categories[9].id));

const reply = (res, status, body) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body)); };
const fail = (status, message) => Object.assign(new Error(message), { status });
const uuid = value => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);

/** Bound fixture input and parse multipart without changing binary image bytes. This is a loopback test service, not a production host. */
async function readPayload(req) {
  const chunks = []; let bytes = 0;
  for await (const chunk of req) {
    bytes += chunk.length;
    if (bytes > 6 * 1024 * 1024) throw fail(413, 'Upload exceeds the test service limit.');
    chunks.push(chunk);
  }
  const buffer = Buffer.concat(chunks);
  if (!req.headers['content-type']?.startsWith('multipart/form-data')) return buffer.length ? JSON.parse(buffer) : {};
  const boundary = req.headers['content-type'].match(/boundary=(?:"([^"]+)"|([^;]+))/);
  if (!boundary) throw fail(400, 'Missing multipart boundary.');
  const parts = buffer.toString('latin1').split(`--${boundary[1] || boundary[2]}`);
  const result = {};
  for (const part of parts.slice(1, -1)) {
    const split = part.indexOf('\r\n\r\n');
    const header = part.slice(0, split);
    const name = header.match(/name="([^"]+)"/)?.[1];
    const content = part.slice(split + 4, -2);
    if (name === 'image') {
      result.image = Buffer.from(content, 'latin1');
      result.filename = header.match(/filename="([^"]+)"/)?.[1];
      result.mime = header.match(/Content-Type: ([^\r]+)/i)?.[1];
    } else if (name) result[name] = Buffer.from(content, 'latin1').toString('utf8');
  }
  return result;
}

/** Persist fixture acceptance before replying, allowing restart and lost-acknowledgement recovery to be tested. */
export async function createPilotServer({ directory, port = 5117 } = {}) {
  directory ||= fileURLToPath(new URL('./data/', import.meta.url));
  await mkdir(directory, { recursive: true });
  const statePath = path.join(directory, 'state.json');
  let state;
  try { state = JSON.parse(await readFile(statePath, 'utf8')); }
  catch (error) { if (error.code !== 'ENOENT') throw error; state = { deliveries: {}, items: {}, sessions: {}, fault: null, stockMutations: 0 }; }
  const persist = async () => { await writeFile(`${statePath}.tmp`, JSON.stringify(state)); await rename(`${statePath}.tmp`, statePath); };
  let requests = Promise.resolve();
  const server = http.createServer((req, res) => {
    // Serialize mutations so disk snapshots and idempotency checks remain deterministic across the two phones.
    requests = requests.then(() => routeRequest(req, res)).catch(error => {
      if (!res.headersSent && !res.destroyed) reply(res, error.status || 500, { message: error.status ? error.message : 'Test service error.' });
    });
  });
  /** Implement only the accepted upload contract and explicit fixture controls; stock receipt is intentionally absent. */
  async function routeRequest(req, res) {
    const url = new URL(req.url, 'http://127.0.0.1');
    const route = url.pathname;
    if (route === '/health') return reply(res, 200, { service: 'K-Line isolated pilot', production: false });
    if (route === '/__test/state' && req.method === 'GET') return reply(res, 200, {
      deliveries: state.deliveries, items: state.items, stockMutations: state.stockMutations });
    if (route === '/__test/fault' && req.method === 'POST') {
      const { kind, itemId } = await readPayload(req);
      if (!['drop-upload-ack', 'reject-upload', 'link-500', 'received', 'reconcile', 'conflict', 'expire', 'clear'].includes(kind)) throw fail(400, 'Unknown fault.');
      if (kind === 'expire') state.sessions = {};
      else state.fault = kind === 'clear' ? null : { kind, itemId };
      await persist(); return reply(res, 200, { ok: true });
    }
    if (route === '/api/auth/login' && req.method === 'POST') {
      const { username, password } = await readPayload(req);
      if (!['pilot', 'pilot2'].includes(username) || password !== 'pilot-only') throw fail(401, 'Use the fixture account shown on the sign-in screen.');
      const token = randomUUID(); state.sessions[token] = username; await persist();
      return reply(res, 200, { success: true, token, user: { username } });
    }
    const owner = state.sessions[req.headers.authorization?.replace(/^Bearer /, '')];
    if (!owner) throw fail(401, 'Session expired. Sign in again.');
    if (route === '/api/catalog/session') return reply(res, 200, { data: {
      id: owner === 'pilot' ? '30000000-0000-4000-8000-000000000001' : '30000000-0000-4000-8000-000000000002',
      full_name: owner === 'pilot' ? 'Pilot colleague' : 'Second colleague', username: owner, can_upload: true,
      default_branch_id: BRANCH, branches: [{ id: BRANCH, name: 'Pilot · Namugongo', can_switch_to: true },
        { id: OTHER_BRANCH, name: 'Pilot · Second branch', can_switch_to: true }] } });
    const branch = req.headers['x-branch-id'];
    if (![BRANCH, OTHER_BRANCH].includes(branch)) throw fail(403, 'Select an authorised test branch.');
    if (route === '/api/catalog/reference-data') return reply(res, 200, { data: { categories, fields: [] } });
    if (route === '/api/catalog-workspace/batches' && req.method === 'POST') {
      const { id, title } = await readPayload(req);
      if (!uuid(id) || typeof title !== 'string' || !title.trim() || title.length > 120) throw fail(400, 'Invalid delivery.');
      const existing = state.deliveries[id];
      if (existing && (existing.owner !== owner || existing.branch !== branch)) throw fail(409, 'Delivery identity is unavailable.');
      state.deliveries[id] ||= { id, title, owner, branch }; await persist();
      return reply(res, 200, state.deliveries[id]);
    }
    if (route === '/api/catalog/items' && req.method === 'POST') {
      const data = await readPayload(req);
      if (state.fault?.kind === 'reject-upload' && state.fault.itemId === data.id) {
        state.fault = null; await persist(); throw fail(413, 'Test: this photo exceeds the upload limit. Original retained.');
      }
      if (!uuid(data.id) || !categories.some(category => category.id === data.category_id)) throw fail(400, 'Invalid photo identity or category.');
      if (!data.image?.length || !['image/jpeg', 'image/png', 'image/webp'].includes(data.mime)) throw fail(400, 'Select a JPEG, PNG or WebP image.');
      if (data.image.length > 5 * 1024 * 1024) throw fail(413, 'Photo exceeds 5 MB.');
      const extension = { 'image/jpeg': 'jpg', 'image/png': 'png', 'image/webp': 'webp' }[data.mime];
      const existing = state.items[data.id];
      if (existing && (existing.branch !== branch || existing.category_id !== data.category_id || existing.extension !== extension)) throw fail(409, 'Existing intake identity does not match.');
      const created = !existing;
      state.items[data.id] ||= { id: data.id, branch, category_id: data.category_id, extension, bytes: data.image.length,
        sha256: createHash('sha256').update(data.image).digest('hex'), batch_id: null, pos_product_id: null, requires_pos_reconciliation: false };
      if (created) await writeFile(path.join(directory, `${data.id}.${extension}`), data.image);
      await persist();
      if (state.fault?.kind === 'drop-upload-ack' && (!state.fault.itemId || state.fault.itemId === data.id)) {
        state.fault = null; await persist(); req.socket.destroy(); return;
      }
      return reply(res, created ? 201 : 200, { success: true, data: state.items[data.id] });
    }
    const link = route.match(/^\/api\/catalog-workspace\/batches\/([^/]+)\/items\/([^/]+)$/);
    if (link && req.method === 'PUT') {
      const item = state.items[link[2]], delivery = state.deliveries[link[1]];
      if (!item || item.branch !== branch || !delivery || delivery.branch !== branch) throw fail(404, 'Item or delivery unavailable.');
      const fault = state.fault;
      if (fault && (!fault.itemId || fault.itemId === item.id)) {
        state.fault = null;
        if (['received', 'reconcile'].includes(fault.kind)) {
          item.pos_product_id = 'fixture-existing-product'; item.requires_pos_reconciliation = fault.kind === 'reconcile';
          await persist(); throw fail(409, 'Received items cannot change delivery.');
        }
        await persist();
        if (fault.kind === 'link-500') throw fail(503, 'Temporary test linking failure.');
        if (fault.kind === 'conflict') throw fail(409, 'This lot belongs to another delivery.');
      }
      if (item.pos_product_id) throw fail(409, 'Received items cannot change delivery.');
      if (item.batch_id && item.batch_id !== delivery.id) throw fail(409, 'This lot belongs to another delivery.');
      item.batch_id = delivery.id; await persist(); return reply(res, 200, { added: true });
    }
    const detail = route.match(/^\/api\/catalog-workspace\/items\/([^/]+)$/);
    if (detail && req.method === 'GET') {
      const item = state.items[detail[1]];
      if (!item || item.branch !== branch) throw fail(404, 'Item unavailable.');
      return reply(res, 200, { revision: 'fixture-edit', publication_revision: 'fixture-receive', fields: [], blockers: [],
        item: { ...item, is_published: !!item.pos_product_id, is_cancelled: false } });
    }
    throw fail(404, 'This operation is outside the capture pilot.');
  }
  await new Promise(resolve => server.listen(port, '127.0.0.1', resolve));
  return server;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const server = await createPilotServer();
  console.log(`K-Line isolated pilot listening on http://127.0.0.1:${server.address().port}`);
}
