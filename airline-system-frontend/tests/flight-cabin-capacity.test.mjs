import assert from 'node:assert/strict';
import { test } from 'node:test';
import { findAll, loadComponent } from './componentHarness.mjs';

async function capacities(cabinClasses, aircraft = {}) {
  const state = {
    flight: { flight: { id: 1, flightNumber: 'VN1001', aircraft }, loading: false },
    cabinClass: { cabinClasses }, fare: { fares: [] },
    flightCabinAncillary: { cabinAncillaries: [] }, flightMeal: { flightMeals: [] },
  };
  const component = await loadComponent('pages/airline/Dashboard/FlightManagment/FlightDetail.jsx', {
    useParams: () => ({ id: '1' }), useNavigate: () => () => {},
    useDispatch: () => () => {}, useSelector: select => select(state),
    formatDateTime: () => 'Not available', formatCurrency: String,
  });
  return findAll(component.render(), node => node.type === 'div'
    && node.props.children[0] === 'Capacity: ')
    .map(node => node.props.children.join(''));
}

test('VN1001 cabin summary shows seat-map totals rather than zero or available seats', async () => {
  assert.deepEqual(await capacities([
    { id: 1, code: 'ECONOMY', name: 'Economy', seatMap: { totalSeats: 108, availableSeats: 100 } },
    { id: 2, code: 'BUSINESS', name: 'Business', seatMap: { totalSeats: 12, availableSeats: 10 } },
  ]), ['Capacity: 108 seats', 'Capacity: 12 seats']);
});

test('an empty seat map retains its zero capacity', async () => {
  assert.deepEqual(await capacities([
    { id: 1, code: 'ECONOMY', seatMap: { totalSeats: 0 } },
  ], { economySeats: 108 }), ['Capacity: 0 seats']);
});

test('missing seat-map totals display unavailable rather than an invented zero', async () => {
  assert.deepEqual(await capacities([
    { id: 1, seatMap: null },
    { id: 2 },
    { id: 3, seatMap: {} },
    { id: 4, seatMap: { totalSeats: null } },
  ]), Array(4).fill('Capacity: Not available'));
});
