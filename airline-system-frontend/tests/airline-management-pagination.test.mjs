import assert from 'node:assert/strict';
import { after, test } from 'node:test';
import { createServer } from 'vite';
import { loadComponent, findAll, deferred } from './componentHarness.mjs';

globalThis.localStorage = { getItem: () => 'test-token' };
const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom', optimizeDeps: { noDiscovery: true, include: [] } });
after(() => server.close());
const { createAppStore } = await server.ssrLoadModule('/src/Redux/globleState.js');
const { getAllAirlines } = await server.ssrLoadModule('/src/Redux/airline/airlineThunks.js');
const { default: api } = await server.ssrLoadModule('/src/utils/api.js');
const response = id => ({ content: [{ id, status: 'ACTIVE' }], number: 0, totalElements: 105, totalPages: 6 });

test('latest page wins; filters and abort signal reach API; stale errors are ignored', async () => {
  const store = createAppStore();
  const old = deferred(), latest = deferred();
  const calls = [];
  api.get = (path, options) => { calls.push({ path, ...options }); return calls.length === 1 ? old.promise : latest.promise; };
  const first = store.dispatch(getAllAirlines());
  const params = { page: 1, size: 20, search: 'needle', status: 'ACTIVE', sort: 'name,asc' };
  const second = store.dispatch(getAllAirlines(params));
  assert.equal(calls[1].path, '/api/airlines');
  assert.deepEqual(calls[1].params, params);
  assert.ok(calls[1].signal);
  latest.resolve({ data: response(101) }); await second;
  old.reject(new Error('obsolete')); await first;
  assert.equal(store.getState().airline.paginatedAirlines.content[0].id, 101);
  assert.equal(store.getState().airline.listError, null);
  assert.equal(store.getState().airline.error, null);
  assert.equal(store.getState().airline.listLoading, false);
});

test('invalid responses show retryable error; aborted requests do not show errors', async () => {
  const store = createAppStore();
  api.get = async () => ({ data: [] });
  await store.dispatch(getAllAirlines());
  assert.match(store.getState().airline.listError, /Invalid airline page/);
  api.get = async () => ({ data: response(2) });
  await store.dispatch(getAllAirlines());
  assert.equal(store.getState().airline.listError, null);
  api.get = () => new Promise(() => {});
  const request = store.dispatch(getAllAirlines()); request.abort(); await request;
  assert.equal(store.getState().airline.listLoading, false);
  assert.equal(store.getState().airline.listError, null);
});

test('page controls, debounced search, filter resets, retry, sections and page clamping', async () => {
  const calls = [];
  const state = { airline: { paginatedAirlines: response(1), listLoading: false, listError: null } };
  const dispatch = action => { calls.push(action); return { abort() {}, unwrap: async () => ({}) }; };
  const component = await loadComponent('pages/super-admin/airline/AirlineManagement.jsx', {
    useDispatch: () => dispatch, useSelector: select => select(state),
    getAllAirlines: params => ({ kind: 'page', ...params }), approveAirline: id => ({ kind: 'approve', id }),
    toast: { success() {}, error() {} },
  });
  let tree;
  let props = { activeSection: 'airlines-list' };
  const render = () => { tree = component.render(props); component.flushEffects(); };
  const button = label => findAll(tree, node => node.type === 'Button' && node.props.children.includes(label))[0];
  const last = () => calls.filter(call => call.kind === 'page').at(-1);
  try {
    render();
    assert.equal(last().size, 20);
    button('Next').props.onClick(); render(); assert.equal(last().page, 1);
    findAll(tree, node => node.type === 'Select')[1].props.onValueChange('active'); render();
    assert.equal(last().page, 0); assert.equal(last().status, 'ACTIVE');
    findAll(tree, node => node.type === 'Select')[0].props.onValueChange('50'); render();
    assert.equal(last().size, 50);
    findAll(tree, node => node.type === 'Input')[0].props.onChange({ target: { value: 'needle' } }); render();
    assert.equal(last().search, undefined);
    await new Promise(resolve => setTimeout(resolve, 350)); render();
    assert.equal(last().search, 'needle');
    const expected = last(); button('Refresh').props.onClick(); render(); assert.deepEqual(last(), expected);
    state.airline.listError = 'Network error'; render(); button('Retry').props.onClick(); render();
    assert.deepEqual(last(), expected);
    state.airline.listError = null;
    props = { activeSection: 'airlines-pending' }; render(); render(); assert.equal(last().status, 'INACTIVE'); assert.equal(last().page, 0);
    const card = findAll(tree, node => node.type === 'AirlineCard')[0];
    await card.props.onApprove(1); render();
    assert.equal(calls.at(-2).kind, 'approve'); assert.equal(last().status, 'INACTIVE');
    props = { activeSection: 'airlines-suspended' }; render(); render(); assert.equal(last().status, 'BANNED');
    props = { activeSection: 'airlines-commission' }; render(); render(); assert.equal(last().status, 'ACTIVE');
    button('Next').props.onClick(); render(); assert.equal(last().page, 1);
    state.airline.paginatedAirlines = { content: [], number: 1, totalElements: 0, totalPages: 0 }; render(); render();
    assert.equal(last().page, 0); assert.equal(button('Next').props.disabled, true); assert.equal(button('Previous').props.disabled, true);
  } finally { component.unmount(); }
});
