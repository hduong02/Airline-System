import assert from 'node:assert/strict';
import { after, test } from 'node:test';
import { createServer } from 'vite';
import { loadComponent, findAll, deferred } from './componentHarness.mjs';

globalThis.localStorage = { getItem: () => 'test-token' };
const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom', optimizeDeps: { noDiscovery: true, include: [] } });
after(() => server.close());
const { createAppStore } = await server.ssrLoadModule('/src/Redux/globleState.js');
const { getTravelerBookingPage } = await server.ssrLoadModule('/src/Redux/booking/bookingThunk.js');
const { default: api } = await server.ssrLoadModule('/src/utils/api.js');
const response = id => ({ content: [{ id }], number: 0, totalElements: 35, totalPages: 4 });

test('history requests send tab/page, ignore stale success/failure, and keep airline lists separate', async () => {
  const store = createAppStore();
  const old = deferred(), latest = deferred();
  const calls = [];
  api.get = (path, options) => { calls.push({ path, ...options }); return calls.length === 1 ? old.promise : latest.promise; };
  const first = store.dispatch(getTravelerBookingPage());
  const params = { tab: 'upcoming', page: 1, size: 10 };
  const second = store.dispatch(getTravelerBookingPage(params));
  assert.equal(calls[1].path, '/api/bookings/user/history/page');
  assert.deepEqual(calls[1].params, params);
  assert.ok(calls[1].signal);
  latest.resolve({ data: response(11) }); await second;
  old.resolve({ data: response(1) }); await first;
  assert.equal(store.getState().booking.travelerBookingPage.content[0].id, 11);
  assert.equal(store.getState().booking.travelerBookingPage.totalElements, 35);
  assert.deepEqual(store.getState().booking.ownerBookings, []);
  assert.deepEqual(store.getState().booking.airlineBookingPage.content, []);
  const failure = deferred();
  api.get = () => failure.promise;
  const stale = store.dispatch(getTravelerBookingPage());
  api.get = async () => ({ data: response(21) });
  await store.dispatch(getTravelerBookingPage({ tab: 'past' }));
  failure.reject(new Error('obsolete')); await stale;
  assert.equal(store.getState().booking.travelerBookingPageError, null);
  assert.equal(store.getState().booking.travelerBookingPage.content[0].id, 21);
});

test('invalid responses fail safely and retry clears the error', async () => {
  const store = createAppStore();
  api.get = async () => ({ data: [] });
  await store.dispatch(getTravelerBookingPage());
  assert.match(store.getState().booking.travelerBookingPageError, /invalid page response/);
  api.get = async () => ({ data: response(1) });
  await store.dispatch(getTravelerBookingPage());
  assert.equal(store.getState().booking.travelerBookingPageError, null);
});

test('history renders one page, resets on tab/size changes, and preserves filters on retry', async () => {
  const calls = [];
  const state = { booking: { travelerBookingPage: response(1) } };
  const component = await loadComponent('pages/traveler/BookingHistory/BookingHistory.jsx', {
    useDispatch: () => action => { calls.push(action); return { abort() {} }; },
    useSelector: select => select(state), useNavigate: () => () => {}, getTravelerBookingPage: params => params,
  });
  let tree;
  const render = () => { tree = component.render(); component.flushEffects(); };
  const button = label => findAll(tree, n => ['Button', 'button'].includes(n.type) && n.props.children.includes(label))[0];
  try {
    render();
    assert.deepEqual(calls.at(-1), { tab: 'all', page: 0, size: 10 });
    assert.equal(findAll(tree, n => n.type === 'TravellerBookingCard').length, 1);
    button('Next').props.onClick(); render();
    assert.equal(calls.at(-1).page, 1);
    for (const [label, tab] of [['Upcoming', 'upcoming'], ['Past', 'past'], ['Cancelled', 'cancelled'], ['All Bookings', 'all']]) {
      button(label).props.onClick(); render();
      assert.equal(calls.at(-1).tab, tab); assert.equal(calls.at(-1).page, 0);
      assert.equal(button(label).props['aria-pressed'], true);
    }
    button('Next').props.onClick(); render();
    findAll(tree, n => n.type === 'Select')[0].props.onValueChange('20'); render();
    assert.equal(calls.at(-1).size, 20); assert.equal(calls.at(-1).page, 0);
    const expected = calls.at(-1);
    button('Refresh').props.onClick(); render(); assert.deepEqual(calls.at(-1), expected);
    state.booking.travelerBookingPageLoading = true; render();
    assert.equal(findAll(tree, n => n.type === 'TravellerBookingCard').length, 0);
    assert.equal(button('Next').props.disabled, true);
    state.booking.travelerBookingPageLoading = false;
    state.booking.travelerBookingPageError = 'Network error'; render();
    button('Retry').props.onClick(); render(); assert.deepEqual(calls.at(-1), expected);
    state.booking.travelerBookingPageError = null;
    state.booking.travelerBookingPage = { content: [], number: 0, totalElements: 0, totalPages: 0 }; render();
    assert.equal(button('Previous').props.disabled, true); assert.equal(button('Next').props.disabled, true);
    assert.equal(findAll(tree, n => n.type === 'TravellerBookingCard').length, 0);
  } finally { component.unmount(); }
});
