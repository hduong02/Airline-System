import assert from 'node:assert/strict';
import { after, test } from 'node:test';
import { createServer } from 'vite';
import { loadComponent, findAll, deferred } from './componentHarness.mjs';

globalThis.localStorage = { getItem: () => 'test-token' };
const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom', optimizeDeps: { noDiscovery: true, include: [] } });
after(() => server.close());
const { createAppStore } = await server.ssrLoadModule('/src/Redux/globleState.js');
const { getFlightSchedulePage, getAllFlightSchedules } = await server.ssrLoadModule('/src/Redux/flightSchedule/flightScheduleThunk.js');
const { default: api } = await server.ssrLoadModule('/src/utils/api.js');
const response = (id, number = 0) => ({ content: [{ id }], number, size: 20, totalElements: 105, totalPages: 6 });

test('management fetches exactly one page; latest response wins without replacing shared flights', async () => {
  const store = createAppStore();
  const old = deferred(), latest = deferred();
  api.get = async () => ({ data: [{ id: 999 }] });
  await store.dispatch(getAllFlightSchedules());
  const calls = [];
  api.get = (path, options) => { calls.push({ path, ...options }); return calls.length === 1 ? old.promise : latest.promise; };
  const first = store.dispatch(getFlightSchedulePage());
  const second = store.dispatch(getFlightSchedulePage({ page: 5, size: 20 }));
  assert.equal(calls.length, 2);
  assert.equal(calls[1].path, '/api/flight-schedules/page');
  assert.deepEqual(calls[1].params, { page: 5, size: 20 });
  assert.ok(calls[1].signal);
  latest.resolve({ data: response(101, 5) }); await second;
  old.resolve({ data: response(1) }); await first;
  assert.equal(store.getState().flightSchedule.managementPage.content[0].id, 101);
  assert.equal(store.getState().flightSchedule.managementPage.totalElements, 105);
  assert.equal(store.getState().flightSchedule.flightSchedules[0].id, 999);
  assert.equal(store.getState().flightSchedule.paginatedFlightSchedules.totalElements, 1);
});

test('stale failures, invalid responses, retry and cancellation', async () => {
  const store = createAppStore();
  api.get = async () => ({ data: [] });
  await store.dispatch(getFlightSchedulePage());
  assert.match(store.getState().flightSchedule.managementPageError, /Invalid schedule page/);
  const old = deferred(); api.get = () => old.promise;
  const first = store.dispatch(getFlightSchedulePage());
  api.get = async () => ({ data: response(2) });
  await store.dispatch(getFlightSchedulePage());
  old.reject(new Error('obsolete')); await first;
  assert.equal(store.getState().flightSchedule.managementPageError, null);
  assert.equal(store.getState().flightSchedule.managementPage.content[0].id, 2);
  api.get = () => new Promise(() => {});
  const request = store.dispatch(getFlightSchedulePage()); request.abort(); await request;
  assert.equal(store.getState().flightSchedule.managementPageLoading, false);
  assert.equal(store.getState().flightSchedule.managementPageError, null);
});


test('page rejection does not corrupt shared list errors; legacy list still supports mutations', async () => {
  const store = createAppStore();
  api.get = async () => ({ data: [{ id: 1 }, { id: 2 }] });
  await store.dispatch(getAllFlightSchedules());
  api.get = async () => { throw new Error('Offline'); };
  await store.dispatch(getFlightSchedulePage());
  assert.equal(store.getState().flightSchedule.error, null);
  store.dispatch({ type: 'flightSchedule/delete/fulfilled', payload: 1 });
  assert.deepEqual(store.getState().flightSchedule.flightSchedules.map(s => s.id), [2]);
});

test('schedule controls, deletion failures, final-page recovery and navigation', async () => {
  const calls = [], destinations = [], notifications = [];
  const state = { flightSchedule: { managementPage: { ...response(1), content: [{ id: 1, departureTime: '09:00', arrivalTime: '11:00' }] },
    managementPageLoading: false, managementPageError: null } };
  let failDelete = false, aborted = 0;
  const dispatch = action => { calls.push(action); return { abort() { aborted++; }, unwrap: async () => { if (failDelete) throw 'Delete failed'; } }; };
  const component = await loadComponent('pages/airline/Dashboard/FlightSchedules/FlightScheduleTable.jsx', {
    useDispatch: () => dispatch, useSelector: select => select(state), useNavigate: () => path => destinations.push(path),
    getFlightSchedulePage: params => ({ kind: 'page', ...params }), deleteFlightSchedule: id => ({ kind: 'delete', id }),
    toast: { success: text => notifications.push(text), error: text => notifications.push(text) }, window: { confirm: () => true },
  });
  let tree;
  const render = () => { tree = component.render(); component.flushEffects(); };
  const button = label => findAll(tree, node => node.type === 'Button' && node.props.children.includes(label))[0];
  const last = () => calls.filter(call => call.kind === 'page').at(-1);
  try {
    render(); assert.deepEqual(last(), { kind: 'page', page: 0, size: 20 });
    button('Next').props.onClick(); render(); assert.equal(last().page, 1); assert.ok(aborted > 0);
    button('Previous').props.onClick(); render(); assert.equal(last().page, 0);
    button('Next').props.onClick(); render();
    findAll(tree, node => node.type === 'Select')[0].props.onValueChange('50'); render();
    assert.equal(last().page, 0); assert.equal(last().size, 50);
    const expected = last(); button('Refresh').props.onClick(); render(); assert.deepEqual(last(), expected);
    button('View').props.onClick(); button('Edit').props.onClick(); button('Create Schedule').props.onClick();
    assert.deepEqual(destinations, ['/airline/schedules/1', '/airline/schedules/1/edit', '/airline/schedules/new']);
    const deletion = button('Delete').props.onClick(); render(); assert.equal(button('Delete').props.disabled, true);
    await deletion; render(); assert.ok(notifications.includes('Flight schedule deleted successfully'));
    const count = calls.filter(call => call.kind === 'page').length;
    failDelete = true; await button('Delete').props.onClick(); render();
    assert.ok(notifications.includes('Delete failed')); assert.equal(calls.filter(call => call.kind === 'page').length, count);
    state.flightSchedule.managementPageLoading = true; render(); assert.equal(button('Next').props.disabled, true);
    assert.equal(button('Delete'), undefined);
    state.flightSchedule.managementPageLoading = false; state.flightSchedule.managementPageError = 'Offline'; render();
    button('Retry').props.onClick(); render(); assert.deepEqual(last(), expected);
    state.flightSchedule.managementPageError = null; render(); button('Next').props.onClick(); render();
    state.flightSchedule.managementPage = { content: [], number: 1, totalElements: 0, totalPages: 0 }; render(); render();
    assert.equal(last().page, 0); assert.equal(button('Next').props.disabled, true); assert.equal(button('Previous').props.disabled, true);
  } finally { component.unmount(); }
});
