import assert from 'node:assert/strict';
import { test } from 'node:test';
import { formatDateTime } from '../src/utils/formateDateTime.js';
import { loadComponent } from './componentHarness.mjs';

const textOf = (node) => Array.isArray(node) ? node.map(textOf).join(' ')
  : node && typeof node === 'object' ? textOf(node.props?.children) : String(node ?? '');

async function renderFlight(times) {
  const state = {
    flight: { flight: { id: 1, flightNumber: 'VN1001', ...times }, loading: false },
    cabinClass: { cabinClasses: [] }, fare: { fares: [] },
    flightCabinAncillary: { cabinAncillaries: [] }, flightMeal: { flightMeals: [] },
  };
  const component = await loadComponent('pages/airline/Dashboard/FlightManagment/FlightDetail.jsx', {
    useParams: () => ({ id: '1' }), useNavigate: () => () => {},
    useDispatch: () => () => {}, useSelector: (select) => select(state),
    formatDateTime, formatCurrency: String,
  });
  return textOf(component.render());
}

test('flight definition without dates displays unavailable in all four timing fields', async () => {
  const text = await renderFlight({ departureTime: null, arrivalTime: null });
  assert.doesNotMatch(text, /1970|Invalid Date/);
  assert.equal(text.match(/Not available/g)?.length, 4);
});

test('flight details preserve supplied departure and arrival dates', async () => {
  const times = { departureTime: '2026-10-20T08:00:00', arrivalTime: '2026-10-20T10:00:00' };
  const text = await renderFlight(times);
  for (const value of Object.values(times)) {
    assert.equal(text.split(formatDateTime(value)).length - 1, 2);
  }
  assert.doesNotMatch(text, /Not available|1970|Invalid Date/);
});

test('date formatter handles absent, empty, and invalid values explicitly', () => {
  for (const value of [null, undefined, '', '   ', 'not-a-date']) {
    assert.equal(formatDateTime(value), 'Not available');
  }
});

test('date formatter preserves valid timestamps including the epoch', () => {
  for (const value of ['2026-10-20T08:00:00', 0]) {
    assert.equal(formatDateTime(value), new Date(value).toLocaleString('en-IN', {
      weekday: 'short', day: '2-digit', month: 'short', year: 'numeric',
      hour: '2-digit', minute: '2-digit',
    }));
  }
});
