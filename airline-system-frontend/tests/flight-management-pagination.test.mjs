import assert from 'node:assert/strict';
import { after, test } from 'node:test';
import { createServer } from 'vite';
import { loadComponent, findAll, deferred } from './componentHarness.mjs';

globalThis.localStorage = { getItem: () => 'test-token' };
const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom', optimizeDeps: { noDiscovery: true, include: [] } });
after(() => server.close());
const { createAppStore } = await server.ssrLoadModule('/src/Redux/globleState.js');
const { getFlightManagementPage, getFlightsByAirline } = await server.ssrLoadModule('/src/Redux/flight/flightThunk.js');
const { default: api } = await server.ssrLoadModule('/src/utils/api.js');
const response = (id, number = 0) => ({ content: [{ id }], number, size: 20, totalElements: 105, totalPages: 6 });

test('management fetches exactly one page; latest response wins without replacing shared flights', async () => {
  const store = createAppStore();
  const old = deferred(), latest = deferred();
  api.get = async () => ({ data: { ...response(999), totalPages: 1 } });
  await store.dispatch(getFlightsByAirline());
  const calls = [];
  api.get = (path, options) => { calls.push({ path, ...options }); return calls.length === 1 ? old.promise : latest.promise; };
  const first = store.dispatch(getFlightManagementPage());
  const second = store.dispatch(getFlightManagementPage({ page: 5, size: 20 }));
  assert.equal(calls.length, 2);
  assert.equal(calls[1].path, '/api/flights/airline');
  assert.deepEqual(calls[1].params, { page: 5, size: 20, sort: 'id,asc' });
  assert.ok(calls[1].signal);
  latest.resolve({ data: response(101, 5) }); await second;
  old.resolve({ data: response(1) }); await first;
  assert.equal(store.getState().flight.managementPage.content[0].id, 101);
  assert.equal(store.getState().flight.managementPage.totalElements, 105);
  assert.equal(store.getState().flight.flights[0].id, 999);
  assert.equal(store.getState().flight.flightPage.totalElements, 105);
});

test('stale failures, invalid responses, retry and cancellation', async () => {
  const store = createAppStore();
  api.get = async () => ({ data: [] });
  await store.dispatch(getFlightManagementPage());
  assert.match(store.getState().flight.managementPageError, /Invalid flight page/);
  const old = deferred(); api.get = () => old.promise;
  const first = store.dispatch(getFlightManagementPage());
  api.get = async () => ({ data: response(2) });
  await store.dispatch(getFlightManagementPage());
  old.reject(new Error('obsolete')); await first;
  assert.equal(store.getState().flight.managementPageError, null);
  assert.equal(store.getState().flight.managementPage.content[0].id, 2);
  api.get = () => new Promise(() => {});
  const request = store.dispatch(getFlightManagementPage()); request.abort(); await request;
  assert.equal(store.getState().flight.managementPageLoading, false);
  assert.equal(store.getState().flight.managementPageError, null);
});

test('shared dropdown loader still fetches every page without overwriting management data', async () => {
  const store = createAppStore();
  api.get = async () => ({ data: response(42) }); await store.dispatch(getFlightManagementPage());
  const pages = [];
  api.get = async (_, options) => { pages.push(options.params.page); return { data: { content: [{ id: options.params.page + 1 }], totalPages: 3, totalElements: 3 } }; };
  await store.dispatch(getFlightsByAirline());
  assert.deepEqual(pages, [0, 1, 2]);
  assert.deepEqual(store.getState().flight.flights.map(f => f.id), [1, 2, 3]);
  assert.equal(store.getState().flight.managementPage.content[0].id, 42);
});

test('controls page, reset size, retry, refresh after delete, clamp final page, and preserve navigation', async () => {
  const calls = [], destinations = [], notifications = [];
  const state = { flight: { managementPage: response(1), managementPageLoading: false, managementPageError: null } };
  let failDelete = false;
  let aborted = 0;
  const dispatch = action => { calls.push(action); return { abort() { aborted++; }, unwrap: async () => { if (failDelete) throw 'Delete failed'; } }; };
  const component = await loadComponent('pages/airline/Dashboard/FlightManagment/FlightManagement.jsx', {
    useDispatch: () => dispatch, useSelector: select => select(state), useNavigate: () => path => destinations.push(path),
    getFlightManagementPage: params => ({ kind: 'page', ...params }), deleteFlight: id => ({ kind: 'delete', id }),
    toast: { success: text => notifications.push(text), error: text => notifications.push(text) },
  });
  let tree;
  const render = () => { tree = component.render(); component.flushEffects(); };
  const button = label => findAll(tree, node => node.type === 'Button' && node.props.children.includes(label))[0];
  const last = () => calls.filter(call => call.kind === 'page').at(-1);
  const card = () => findAll(tree, node => node.type === 'FlightCard')[0];
  try {
    render(); assert.equal(last().size, 20); assert.equal(last().page, 0);
    assert.equal(findAll(tree, node => node.type === 'FlightCard').length, 1);
    button('Next').props.onClick(); render(); assert.equal(last().page, 1); assert.ok(aborted > 0);
    button('Previous').props.onClick(); render(); assert.equal(last().page, 0);
    button('Next').props.onClick(); render();
    findAll(tree, node => node.type === 'Select')[0].props.onValueChange('50'); render();
    assert.equal(last().page, 0); assert.equal(last().size, 50);
    const expected = last(); button('Refresh').props.onClick(); render(); assert.deepEqual(last(), expected);
    card().props.onView({ id: 7 }); card().props.onEdit({ id: 7 }); button('Add New Flight').props.onClick();
    assert.deepEqual(destinations, ['/airline/flights/7', '/airline/flights/7/edit', '/airline/flights/new']);
    const deletion = card().props.onDelete(1); render(); assert.equal(card().props.disabled, true);
    await deletion; render(); assert.equal(last().size, 50); assert.ok(notifications.includes('Flight deleted successfully'));
    const count = calls.filter(call => call.kind === 'page').length;
    failDelete = true; await card().props.onDelete(1); render();
    assert.ok(notifications.includes('Delete failed')); assert.equal(calls.filter(call => call.kind === 'page').length, count);
    state.flight.managementPageLoading = true; render(); assert.equal(button('Next').props.disabled, true);
    assert.equal(findAll(tree, node => node.type === 'FlightCard').length, 0);
    state.flight.managementPageLoading = false; state.flight.managementPageError = 'Network error'; render();
    button('Retry').props.onClick(); render(); assert.deepEqual(last(), expected);
    state.flight.managementPageError = null; render(); button('Next').props.onClick(); render();
    state.flight.managementPage = { content: [], number: 1, totalElements: 0, totalPages: 0 }; render(); render();
    assert.equal(last().page, 0); assert.equal(button('Next').props.disabled, true); assert.equal(button('Previous').props.disabled, true);
  } finally { component.unmount(); }
});
