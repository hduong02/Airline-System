import assert from 'node:assert/strict';
import { test } from 'node:test';
import { loadComponent, findAll } from './componentHarness.mjs';

const textOf = (node) => Array.isArray(node) ? node.map(textOf).join(' ')
  : node && typeof node === 'object' ? textOf(node.props?.children) : String(node ?? '');
const render = async (state) => (await loadComponent(
  'pages/airline/Dashboard/BookingStatistics/BookingStatisticsOverview.jsx', {
    useDispatch: () => () => {}, useSelector: () => state,
    formatCurrency: (value) => `$${value}`,
  })).render();

test('booking statistics show zero totals and all four charts for an empty airline', async () => {
  const tree = await render({ statistics: {
    totalBookingsToday: 0, revenueToday: 0, totalBookingsThisMonth: 0, revenueThisMonth: 0,
    dailyTrend: [], monthlyData: [],
  }, loading: false, error: null });
  assert.match(textOf(tree), /Booking Statistics/);
  assert.match(textOf(tree), /\$0/);
  for (const chart of ['DailyBookingChart', 'DailyRevenueChart', 'MonthlyBookingChart', 'MonthlyRevenueChart']) {
    assert.equal(findAll(tree, node => node.type === chart).length, 1);
  }
});

test('booking statistics display request errors and a visible fallback for absent data', async () => {
  assert.match(textOf(await render({ error: 'Service unavailable' })), /Error loading statistics:.*Service unavailable/);
  assert.match(textOf(await render({ statistics: null })), /Booking statistics are not available/);
});
