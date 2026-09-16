import assert from 'node:assert/strict';
import { after, test } from 'node:test';
import { createServer } from 'vite';
import { loadComponent, findAll, deferred } from './componentHarness.mjs';

globalThis.localStorage = { getItem: () => 'test-token' };
const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom', optimizeDeps: { noDiscovery: true, include: [] } });
after(() => server.close());
const { createAppStore } = await server.ssrLoadModule('/src/Redux/globleState.js');
const { getAllUsers } = await server.ssrLoadModule('/src/Redux/user/userThunks.js');
const { default: api } = await server.ssrLoadModule('/src/utils/api.js');
const response = (id) => ({ content: [{ id, fullName: 'Fixture', role: 'ROLE_USER' }], totalElements: 50024, totalPages: 2001 });

test('user requests send server filters and preserve only the latest response', async () => {
  const store = createAppStore();
  const old = deferred();
  const latest = deferred();
  const calls = [];
  api.get = (path, options) => { calls.push({ path, ...options }); return calls.length === 1 ? old.promise : latest.promise; };
  const first = store.dispatch(getAllUsers({ page: 0, size: 25 }));
  const second = store.dispatch(getAllUsers({ page: 1, size: 25, search: 'needle', role: 'ROLE_USER', sortBy: 'email', direction: 'desc' }));
  assert.deepEqual(calls[1].params, { page: 1, size: 25, search: 'needle', role: 'ROLE_USER', sortBy: 'email', direction: 'desc' });
  assert.ok(calls[1].signal);
  latest.resolve({ data: response(26) }); await second;
  old.resolve({ data: response(1) }); await first;
  assert.deepEqual(store.getState().user.users.map(u => u.id), [26]);
  assert.equal(store.getState().user.usersTotalElements, 50024);
});

test('legacy unbounded response fails safely and retry clears errors', async () => {
  const store = createAppStore();
  api.get = async () => ({ data: Array.from({ length: 50024 }, (_, id) => ({ id })) });
  await store.dispatch(getAllUsers());
  assert.equal(store.getState().user.users.length, 0);
  assert.match(store.getState().user.error, /Unable to load users/);
  api.get = async () => ({ data: response(1) });
  await store.dispatch(getAllUsers());
  assert.equal(store.getState().user.error, null);
  assert.equal(store.getState().user.users.length, 1);
});

test('table renders only a page, changes page on Next, and resets filters to page zero', async () => {
  const calls = [];
  const dispatch = (action) => { calls.push(action); return { abort() {} }; };
  const state = { user: { users: Array.from({ length: 25 }, (_, id) => ({ id, fullName: 'Fixture', role: 'ROLE_USER' })), usersLoading: false, usersTotalElements: 50024, usersTotalPages: 2001 } };
  const component = await loadComponent('pages/super-admin/Dashboard/UserManagement.jsx', {
    useDispatch: () => dispatch, useSelector: (select) => select(state), useMemo: (callback) => callback(),
    getAllUsers: (params) => params,
  });
  try {
    let tree = component.render(); component.flushEffects();
    assert.equal(calls.at(-1).size, 25);
    assert.equal(findAll(tree, n => n.type === 'tr').length, 26);
    const button = (name) => findAll(tree, n => n.type === 'button' && n.props.children.flat(Infinity).includes(name))[0];
    button('Next').props.onClick(); tree = component.render(); component.flushEffects();
    assert.equal(calls.at(-1).page, 1);
    const role = findAll(tree, n => n.type === 'select' && n.props['aria-label'] === 'Filter by role')[0];
    role.props.onChange({ target: { value: 'ROLE_AIRLINE_OWNER' } }); tree = component.render(); component.flushEffects();
    assert.equal(calls.at(-1).page, 0); assert.equal(calls.at(-1).role, 'ROLE_AIRLINE_OWNER');
    const search = findAll(tree, n => n.type === 'input')[0];
    search.props.onChange({ target: { value: 'needle' } }); component.render(); component.flushEffects();
    await new Promise(resolve => setTimeout(resolve, 350)); component.render(); component.flushEffects();
    assert.equal(calls.at(-1).search, 'needle'); assert.equal(calls.at(-1).page, 0);
  } finally { component.unmount(); }
});
