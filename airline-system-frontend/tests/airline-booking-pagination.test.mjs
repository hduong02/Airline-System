import assert from 'node:assert/strict';
import { after, test } from 'node:test';
import { createServer } from 'vite';
import { loadComponent, findAll, deferred } from './componentHarness.mjs';

globalThis.localStorage = { getItem: () => 'test-token' };
const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom', optimizeDeps: { noDiscovery: true, include: [] } });
after(() => server.close());
const { createAppStore } = await server.ssrLoadModule('/src/Redux/globleState.js');
const { getAirlineBookingPage } = await server.ssrLoadModule('/src/Redux/booking/bookingThunk.js');
const { default: api } = await server.ssrLoadModule('/src/utils/api.js');
const response = id => ({ content: [{ id }], number: 0, totalElements: 63, totalPages: 4 });

test('page requests send filters, ignore stale responses, and leave dashboard/history lists intact', async () => {
  const store = createAppStore();
  const old = deferred(), latest = deferred();
  const calls = [];
  api.get = (path, options) => { calls.push({ path, ...options }); return calls.length === 1 ? old.promise : latest.promise; };
  const first = store.dispatch(getAirlineBookingPage());
  const params = { page: 1, size: 20, search: 'needle', status: 'CONFIRMED', flightInstanceId: '7', sortDirection: 'ASC' };
  const second = store.dispatch(getAirlineBookingPage(params));
  assert.equal(calls[1].path, '/api/bookings/airline/page');
  assert.deepEqual(calls[1].params, params);
  assert.ok(calls[1].signal);
  latest.resolve({ data: response(21) }); await second;
  old.resolve({ data: response(1) }); await first;
  assert.equal(store.getState().booking.airlineBookingPage.content[0].id, 21);
  assert.equal(store.getState().booking.airlineBookingPage.totalElements, 63);
  assert.deepEqual(store.getState().booking.bookings, []);
  assert.deepEqual(store.getState().booking.ownerBookings, []);
  assert.equal(store.getState().booking.ownerBookingsStatus, 'idle');
});

test('failed pages can be retried and obsolete failures cannot replace success', async () => {
  const store = createAppStore();
  api.get = async () => ({ data: [] });
  await store.dispatch(getAirlineBookingPage());
  assert.match(store.getState().booking.airlineBookingPageError, /invalid page response/);
  const old = deferred();
  api.get = () => old.promise;
  const first = store.dispatch(getAirlineBookingPage());
  api.get = async () => ({ data: response(2) });
  await store.dispatch(getAirlineBookingPage());
  old.reject(new Error('obsolete')); await first;
  assert.equal(store.getState().booking.airlineBookingPageError, null);
  assert.equal(store.getState().booking.airlineBookingPage.content[0].id, 2);
});

test('table pages, resets filters, debounces search, and preserves query on refresh/retry', async () => {
  const calls = [];
  const state = { booking: { airlineBookingPage: { ...response(1), content: [{ id: 1, bookingDate: '2026-01-01', departureTime: '2026-02-01' }] } }, flightInstance: {} };
  const component = await loadComponent('pages/airline/Dashboard/Bookings/BookingManagement.jsx', {
    useDispatch: () => action => { calls.push(action); return { abort() {} }; },
    useSelector: select => select(state), getAirlineBookingPage: params => ({ kind: 'page', ...params }),
    getAllFlightInstances: () => ({ kind: 'flights' }),
  });
  const last = () => calls.filter(c => c.kind === 'page').at(-1);
  let tree;
  const render = () => { tree = component.render(); component.flushEffects(); };
  const button = label => findAll(tree, n => n.type === 'Button' && n.props.children.includes(label))[0];
  try {
    render();
    assert.equal(last().size, 20);
    assert.equal(findAll(tree, n => n.type === 'TableRow').length, 2);
    button('Next').props.onClick(); render();
    assert.equal(last().page, 1);
    const selects = () => findAll(tree, n => n.type === 'Select');
    selects()[0].props.onValueChange('confirmed'); render();
    assert.equal(last().status, 'CONFIRMED'); assert.equal(last().page, 0);
    selects()[1].props.onValueChange('7'); render();
    assert.equal(last().flightInstanceId, '7');
    selects()[2].props.onValueChange('ASC'); render();
    assert.equal(last().sortDirection, 'ASC');
    selects()[3].props.onValueChange('50'); render();
    assert.equal(last().size, 50);
    findAll(tree, n => n.type === 'Input')[0].props.onChange({ target: { value: 'needle' } }); render();
    assert.equal(last().search, undefined);
    await new Promise(resolve => setTimeout(resolve, 350)); render();
    assert.equal(last().search, 'needle');
    const expected = last();
    button('Refresh').props.onClick(); render();
    assert.deepEqual(last(), expected);
    state.booking.airlineBookingPageError = 'Network error'; render();
    button('Retry').props.onClick(); render();
    assert.deepEqual(last(), expected);
    state.booking.airlineBookingPageError = null;
    state.booking.airlineBookingPage = { content: [], number: 0, totalElements: 0, totalPages: 0 }; render();
    assert.equal(button('Previous').props.disabled, true);
    assert.equal(button('Next').props.disabled, true);
  } finally { component.unmount(); }
});
