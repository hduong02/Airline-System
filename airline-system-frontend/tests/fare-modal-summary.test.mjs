import assert from 'node:assert/strict';
import { test } from 'node:test';
import { loadComponent } from './componentHarness.mjs';

function textContent(node) {
  if (Array.isArray(node)) return node.map(textContent).join(' ');
  if (node && typeof node === 'object') return textContent(node.props?.children);
  return node == null || typeof node === 'boolean' ? '' : String(node);
}

async function loadModal() {
  return loadComponent('pages/traveler/FlightList/FlightPricing/FlightPricingModal.jsx', {
    useDispatch: () => () => {},
    useNavigate: () => () => {},
    useSearchParams: () => [new URLSearchParams()],
    getPassengerCount: () => 1,
  });
}

const flight = {
  id: 2801, flightId: 2801, aircraftId: 501,
  departureAirport: { iataCode: 'AUH' },
  arrivalAirport: { iataCode: 'AAN' },
  departureDateTime: '2026-10-21T10:00:00',
  arrivalDateTime: '2026-10-21T12:00:00',
};

test('fare modal shows the selected search result route, times and non-stop status', async () => {
  const modal = await loadModal();
  const text = textContent(modal.render({ isOpen: true, flight }));
  assert.match(text, /AUH/);
  assert.match(text, /AAN/);
  assert.match(text, /10:00/);
  assert.match(text, /12:00/);
  assert.equal((text.match(/Oct 21, 2026/g) || []).length, 2);
  assert.match(text, /Non-stop/);
  assert.doesNotMatch(text, /DEL|SGN|Invalid Date|undefined stop/);
});

test('fare modal updates its summary for another flight and shows overnight arrival dates', async () => {
  const modal = await loadModal();
  modal.render({ isOpen: true, flight });
  const text = textContent(modal.render({ isOpen: true, flight: {
    ...flight,
    departureAirport: { iataCode: 'HAN' },
    arrivalAirport: { iataCode: 'DAD' },
    departureDateTime: '2026-10-22T23:00:00',
    arrivalDateTime: '2026-10-23T01:00:00',
    totalStops: 1,
  } }));
  for (const value of ['HAN', 'DAD', '23:00', '01:00', 'Oct 22, 2026', 'Oct 23, 2026', '1 stop(s)']) {
    assert.ok(text.includes(value), value);
  }
  assert.doesNotMatch(text, /AUH|AAN|Oct 21/);
});

test('fare modal handles absent or invalid details without invented airports or invalid dates', async () => {
  const modal = await loadModal();
  for (const incomplete of [undefined, {}, { departureDateTime: 'bad', arrivalDateTime: 'bad', stops: 2 }]) {
    const text = textContent(modal.render({ isOpen: true, flight: incomplete }));
    assert.doesNotMatch(text, /DEL|SGN|Invalid Date|undefined stop/);
    assert.ok(text.includes('—'));
    assert.ok(text.includes(incomplete?.stops ? '2 stop(s)' : 'Non-stop'));
  }
});
