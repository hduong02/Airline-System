import assert from 'node:assert/strict';
import { test } from 'node:test';
import { loadComponent, settle } from './componentHarness.mjs';

const textOf = (node) => Array.isArray(node) ? node.map(textOf).join(' ')
  : node && typeof node === 'object' ? textOf(node.props?.children) : String(node ?? '');
const airport = {
  iataCode: 'HAN', name: 'Noi Bai', detailedName: null, address: null,
  city: { cityCode: 'HAN', name: 'Hanoi', countryName: 'Vietnam' },
};
const schedule = {
  id: 1, flightId: 1, flightNumber: 'VN1001', isActive: true,
  departureTime: '08:00:00', arrivalTime: '10:00:00', operatingDays: [],
  departureAirport: airport, arrivalAirport: { ...airport, iataCode: 'SGN' },
};

async function renderSchedule(payload) {
  const component = await loadComponent('pages/airline/Dashboard/FlightSchedules/FlightScheduleDetail.jsx', {
    useParams: () => ({ id: '1' }), useNavigate: () => () => {},
    useSelector: () => ({ loading: false }),
    getFlightScheduleById: (id) => id,
    useDispatch: () => () => Promise.resolve({ payload }),
  });
  component.render();
  component.flushEffects();
  await settle();
  return component.render();
}

test('schedule details render the route when both airport addresses are null', async () => {
  const text = textOf(await renderSchedule(schedule));
  for (const value of ['VN1001', 'HAN', 'SGN', 'Airport Details', 'Address not available']) {
    assert.ok(text.includes(value), `Missing ${value}`);
  }
});

test('schedule details preserve supplied airport address information', async () => {
  const address = { street: 'Airport Road', cityName: 'Hanoi', regionCode: 'ASIA' };
  const text = textOf(await renderSchedule({ ...schedule,
    departureAirport: { ...airport, address }, arrivalAirport: { ...airport, address },
  }));
  assert.ok(text.includes('Airport Road, Hanoi'));
  assert.ok(text.includes('ASIA'));
});

test('schedule details tolerate missing airport and city objects', async () => {
  const text = textOf(await renderSchedule({ ...schedule,
    departureAirport: null, arrivalAirport: { ...airport, iataCode: 'SGN', city: null },
  }));
  for (const value of ['VN1001', 'SGN', 'Airport not available', 'City not available']) {
    assert.ok(text.includes(value), `Missing ${value}`);
  }
});

test('partial airport addresses display without dangling separators', async () => {
  const text = textOf(await renderSchedule({ ...schedule,
    departureAirport: { ...airport, address: { street: 'Airport Road' } },
  }));
  assert.ok(text.includes('Airport Road'));
  assert.ok(!text.includes('Airport Road,'));
  assert.ok(!text.includes('undefined'));
});
