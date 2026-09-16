import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createServer } from 'vite';
import { loadComponent, findAll } from './componentHarness.mjs';

const textOf = node => Array.isArray(node) ? node.map(textOf).join(' ')
  : node && typeof node === 'object' ? textOf(node.props?.children) : String(node ?? '');
const performance = {
  startDate: '2025-11-01', endDate: '2026-10-16', totalRoutes: 12, totalBookings: 1234,
  totalRevenue: 54321, averageRevenuePerRoute: 4526.75,
  topRoutesByBookings: [{ route: 'HAN → SGN', totalBookings: 10, totalRevenue: 100 }],
  topRoutesByRevenue: [{ route: 'SGN → HAN', totalBookings: 1, totalRevenue: 200 }],
  topRoutesByAverageRevenue: [{ route: 'SGN → HAN', totalBookings: 1, totalRevenue: 200 }],
};
async function page(state) {
  return loadComponent('pages/airline/Dashboard/RoutePerformance/RoutePerformancePage.jsx', {
    useSelector: () => state, useDispatch: () => () => {},
  });
}

test('route analytics use all-route server totals rather than sums of top-ten lists', async () => {
  const component = await page({ routePerformance: performance });
  const tree = component.render();
  const text = textOf(tree);
  for (const value of ['1,234', '$54,321', '12', '2025-11-01', '2026-10-16']) {
    assert.ok(text.includes(value), `Missing ${value}`);
  }
  const charts = findAll(tree, node => node.type === 'BarChart');
  assert.equal(charts[0].props.data[0].route, 'HAN → SGN');
  assert.equal(charts[1].props.data[0].route, 'SGN → HAN');
});

test('metric selector changes the active ranking tab, including average revenue', async () => {
  const component = await page({ routePerformance: performance });
  let tree = component.render();
  const select = findAll(tree, node => node.type === 'Select')[0];
  select.props.onValueChange('average');
  tree = component.render();
  assert.equal(findAll(tree, node => node.type === 'Tabs')[0].props.value, 'average');
  assert.ok(findAll(tree, node => node.type === 'TabsContent' && node.props.value === 'average').length);
  findAll(tree, node => node.type === 'Select')[0].props.onValueChange('revenue');
  assert.equal(findAll(component.render(), node => node.type === 'Tabs')[0].props.value, 'revenue');
});

test('loading, request failures, and empty results have distinct visible states', async () => {
  assert.match(textOf((await page({ routePerformanceLoading: true })).render()), /Loading route performance/);
  assert.match(textOf((await page({ routePerformanceError: 'Service unavailable' })).render()), /Error loading route analytics:.*Service unavailable/);
  assert.match(textOf((await page({ routePerformance: { ...performance, totalRoutes: 0 } })).render()), /No route performance data/);
});

test('real route reducer can repeatedly reject and recover without changing booking errors', async () => {
  const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom',
    optimizeDeps: { noDiscovery: true, include: [] } });
  try {
    const { default: reducer } = await server.ssrLoadModule('/src/Redux/booking/bookingSlice.js');
    let state = reducer(undefined, { type: '@@init' });
    state = { ...state, error: 'Booking error' };
    for (let attempt = 0; attempt < 2; attempt++) {
      state = reducer(state, { type: 'booking/getRoutePerformanceForAirline/pending' });
      assert.equal(state.routePerformanceLoading, true);
      assert.equal(state.routePerformanceError, null);
      state = reducer(state, { type: 'booking/getRoutePerformanceForAirline/rejected', payload: 'Unavailable' });
      assert.equal(state.routePerformanceLoading, false);
      assert.equal(state.routePerformanceError, 'Unavailable');
      assert.equal(state.error, 'Booking error');
    }
    state = reducer(state, { type: 'booking/getRoutePerformanceForAirline/fulfilled', payload: performance });
    assert.equal(state.routePerformanceError, null);
    assert.deepEqual(state.routePerformance, performance);
    state = reducer(state, { type: 'booking/getAirportPerformanceForAirline/pending' });
    assert.equal(state.airportPerformanceLoading, true);
  } finally {
    await server.close();
  }
});
