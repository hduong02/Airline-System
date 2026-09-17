import assert from 'node:assert/strict';
import { test } from 'node:test';
import { loadComponent, findAll } from './componentHarness.mjs';
import { createServer } from 'vite';

const textOf = node => Array.isArray(node) ? node.map(textOf).join(' ')
  : node && typeof node === 'object' ? textOf(node.props?.children) : String(node ?? '');

async function dashboard(overrides = {}, pathname = '/airline/dashboard') {
  const state = {
    flight: { flights: [{ status: 'SCHEDULED' }, ...Array(31).fill({ status: 'COMPLETED' })],
      flightPage: { totalElements: 32 }, loading: false },
    aircraft: { aircrafts: Array(32).fill({}), fleetLoaded: true },
    flightSchedule: { flightSchedules: Array(32).fill({}), listLoaded: true },
    booking: { ownerBookings: [{ id: 1 }], ownerBookingsStatus: 'succeeded',
      statistics: { revenueThisMonth: 139 }, statisticsStatus: 'succeeded' },
    ...overrides,
  };
  const actions = [];
  const destinations = [];
  const component = await loadComponent('pages/airline/Dashboard/AirlineDashboard.jsx', {
    React: { createElement: (type, props, ...children) => ({ type, props: { ...props, children } }),
      useMemo: callback => callback() },
    useLocation: () => ({ pathname }), useNavigate: () => path => destinations.push(path),
    useDispatch: () => action => actions.push(action), useSelector: select => select(state),
    getFlightsByAirline: () => 'flights', listAllAircrafts: () => 'aircraft',
    getAllFlightSchedules: () => 'schedules', getBookingsByAirline: () => 'bookings',
    getBookingStatisticsForAirline: () => 'statistics', formatCurrency: value => `$${value}`,
  });
  const tree = component.render();
  component.flushEffects();
  return { tree, actions, destinations };
}

test('Profile and Transactions have their own headers without overview statistics', async () => {
  for (const [path, section, title, description, item] of [
    ['/airline/profile', 'profile', 'Airline Profile', 'Manage your airline profile and contact information', 'airline-profile'],
    ['/airline/transactions', 'transactions', 'Transactions', 'View and manage your airline payment transactions', 'transactions'],
  ]) {
    for (const pathname of [path, `${path}/`]) {
      const { tree, actions, destinations } = await dashboard({}, pathname);
      assert.equal(textOf(findAll(tree, node => node.type === 'h1')[0]), title);
      assert.ok(textOf(tree).includes(description));
      assert.doesNotMatch(textOf(tree), /Dashboard Overview|Total Flights|Total Bookings|Avg Occupancy|Monthly Revenue/);
      assert.ok(!actions.includes('bookings') && !actions.includes('statistics'));
      const sidebar = findAll(tree, node => node.type === 'AirlineSidebar')[0];
      assert.equal(sidebar.props.activeSection, section);
      sidebar.props.onSectionChange(item);
      assert.deepEqual(destinations, [path]);
    }
  }
});

const metric = (tree, label) => findAll(tree, node => node.type === 'div'
  && Array.isArray(node.props.children) && node.props.children.length === 2
  && textOf(node.props.children[1]) === label).map(node => textOf(node.props.children[0]))[0];

test('dashboard and sidebar share actual owner counts and booking revenue', async () => {
  const { tree, actions } = await dashboard();
  assert.equal(metric(tree, 'Total Flights'), '32');
  assert.equal(metric(tree, 'Scheduled Flights'), '1');
  assert.equal(metric(tree, 'Total Bookings'), '1');
  assert.equal(metric(tree, 'Monthly Revenue'), '$139');
  assert.equal(metric(tree, 'Avg Occupancy'), 'Unavailable');
  const sidebar = findAll(tree, node => node.type === 'AirlineSidebar')[0];
  assert.deepEqual(sidebar.props.counts, { aircraft: 32, flights: 32, schedules: 32, 'bookings-list': 1 });
  assert.deepEqual(new Set(actions), new Set(['flights', 'aircraft', 'schedules', 'bookings', 'statistics']));
});

test('unloaded or failed booking data is unavailable rather than zero', async () => {
  for (const status of ['idle', 'loading', 'failed']) {
    const { tree } = await dashboard({ booking: { ownerBookings: [], ownerBookingsStatus: status,
      statistics: { revenueThisMonth: 999 }, statisticsStatus: status } });
    assert.equal(metric(tree, 'Total Bookings'), 'Unavailable');
    assert.equal(metric(tree, 'Monthly Revenue'), 'Unavailable');
    assert.equal(findAll(tree, node => node.type === 'AirlineSidebar')[0].props.counts['bookings-list'], null);
  }
});

test('successfully loaded empty owner data displays zero', async () => {
  const { tree } = await dashboard({ booking: { ownerBookings: [], ownerBookingsStatus: 'succeeded',
    statistics: { revenueThisMonth: 0 }, statisticsStatus: 'succeeded' } });
  assert.equal(metric(tree, 'Total Bookings'), '0');
  assert.equal(metric(tree, 'Monthly Revenue'), '$0');
});

test('sidebar renders supplied counts including zero and hides unknown counts', async () => {
  const sections = [{ id: 'flights', title: 'Flights', icon: 'Plane', items: [
    { id: 'flights', label: 'All Flights', count: 45 },
    { id: 'schedules', label: 'All Schedules', count: 45 },
    { id: 'aircraft', label: 'All Aircraft', count: 4 },
  ] }];
  const component = await loadComponent('pages/airline/Sidebar/AirlineSidebar.jsx', {
    sidebarSections: sections, useDispatch: () => () => {}, useNavigate: () => () => {},
    useLocation: () => ({ pathname: '/airline/dashboard' }),
  });
  for (const isCollapsed of [false, true]) {
    const tree = component.render({ isCollapsed, counts: { flights: 32, schedules: 0 } });
    const badges = findAll(tree, node => node.type === 'Badge').map(textOf);
    assert.ok(badges.includes('0'));
    assert.ok(badges.includes('32'));
    assert.ok(!badges.includes('45') && !badges.includes('4'));
  }
});

test('owner booking totals survive filtered lists, failed requests, and unrelated booking reads', async () => {
  const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom',
    optimizeDeps: { noDiscovery: true, include: [] } });
  try {
    const { default: reducer } = await server.ssrLoadModule('/src/Redux/booking/bookingSlice.js');
    let state = reducer(undefined, { type: '@@init' });
    const action = (stage, requestId, arg, payload) => ({
      type: `booking/getByAirline/${stage}`, meta: { requestId, arg }, payload,
    });
    state = reducer(state, action('pending', 'owner', {}));
    state = reducer(state, action('fulfilled', 'owner', {}, [{ id: 1 }, { id: 2 }]));
    for (const arg of [{ status: 'CONFIRMED' }, { search: 'absent' }, { flightInstanceId: 7 }]) {
      state = reducer(state, action('pending', 'filter', arg));
      state = reducer(state, action('fulfilled', 'filter', arg, []));
      assert.equal(state.ownerBookings.length, 2);
      assert.equal(state.ownerBookingsStatus, 'succeeded');
    }
    state = reducer(state, { type: 'booking/getById/fulfilled', payload: { id: 1 } });
    assert.equal(state.ownerBookings.length, 2);
    state = reducer(state, { type: 'booking/delete/fulfilled', payload: 1 });
    assert.equal(state.ownerBookings.length, 1);
    state = reducer(state, action('pending', 'old', {}));
    state = reducer(state, action('pending', 'new', {}));
    state = reducer(state, action('fulfilled', 'old', {}, []));
    assert.equal(state.ownerBookingsStatus, 'loading');
    state = reducer(state, action('rejected', 'new', {}, 'Unavailable'));
    assert.equal(state.ownerBookingsStatus, 'failed');
    state = reducer(state, action('pending', 'retry', {}));
    state = reducer(state, action('fulfilled', 'retry', {}, []));
    assert.equal(state.ownerBookingsStatus, 'succeeded');
    assert.equal(state.ownerBookings.length, 0);

    const stats = (stage, payload) => ({ type: `booking/getStatisticsForAirline/${stage}`,
      meta: { requestId: 'stats' }, payload });
    // Use the thunk's actual action names to exercise the production reducer.
    const { getBookingStatisticsForAirline: thunk } = await server.ssrLoadModule('/src/Redux/booking/bookingThunk.js');
    state = reducer(state, { ...stats('pending'), type: thunk.pending.type });
    assert.equal(state.statisticsStatus, 'loading');
    state = reducer(state, { ...stats('rejected', 'Unavailable'), type: thunk.rejected.type });
    assert.equal(state.statisticsStatus, 'failed');
    state = reducer(state, { ...stats('pending'), type: thunk.pending.type });
    state = reducer(state, { ...stats('fulfilled', { revenueThisMonth: 139 }), type: thunk.fulfilled.type });
    assert.equal(state.statisticsStatus, 'succeeded');
  } finally { await server.close(); }
});


test('flight management skips bulk flight preload and sidebar uses its server total', async () => {
  for (const path of ['/airline/flights', '/airline/flights/']) {
    const { tree, actions } = await dashboard({ flight: { flights: [{ id: 999 }],
      flightPage: { totalElements: 999 }, managementPage: { totalElements: 105 },
      managementPageLoading: false, managementPageError: null } }, path);
    assert.ok(!actions.includes('flights'));
    assert.equal(findAll(tree, node => node.type === 'AirlineSidebar')[0].props.counts.flights, 105);
  }
});


test('schedule management skips bulk schedule preload and uses its server total', async () => {
  for (const path of ['/airline/schedules', '/airline/schedules/']) {
    const { tree, actions } = await dashboard({ flightSchedule: { flightSchedules: [{ id: 999 }],
      listLoaded: true, managementPage: { totalElements: 105 },
      managementPageLoading: false, managementPageError: null } }, path);
    assert.ok(!actions.includes('schedules'));
    assert.equal(findAll(tree, node => node.type === 'AirlineSidebar')[0].props.counts.schedules, 105);
  }
});


test('entering schedules cancels the dashboard full-list request', async () => {
  let pathname = '/airline/dashboard';
  const calls = [], aborted = [];
  const state = { flight: { flights: [] }, aircraft: {}, flightSchedule: {}, booking: {} };
  const component = await loadComponent('pages/airline/Dashboard/AirlineDashboard.jsx', {
    useLocation: () => ({ pathname }), useNavigate: () => () => {},
    useDispatch: () => action => { calls.push(action); return { abort: () => aborted.push(action) }; },
    useSelector: select => select(state),
    getFlightsByAirline: () => 'flights', listAllAircrafts: () => 'aircraft',
    getAllFlightSchedules: () => 'schedules', getBookingsByAirline: () => 'bookings',
    getBookingStatisticsForAirline: () => 'statistics', formatCurrency: value => `$${value}`,
  });
  try {
    component.render(); component.flushEffects();
    assert.equal(calls.filter(action => action === 'schedules').length, 1);
    pathname = '/airline/schedules'; component.render(); component.flushEffects();
    assert.ok(aborted.includes('schedules'));
    assert.equal(calls.filter(action => action === 'schedules').length, 1);
    pathname = '/airline/schedules/new'; component.render(); component.flushEffects();
    assert.equal(calls.filter(action => action === 'schedules').length, 2);
  } finally { component.unmount(); }
});
