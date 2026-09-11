import assert from 'node:assert/strict';
import { test } from 'node:test';
import { loadComponent, findAll } from './componentHarness.mjs';

const textOf = (node) => Array.isArray(node) ? node.map(textOf).join(' ')
  : node && typeof node === 'object' ? textOf(node.props?.children) : String(node ?? '');
const booking = {
  id: 7, bookingReference: 'BOOK-7', status: 'CONFIRMED', flightNumber: 'VN123',
  departureAirport: 'Noi Bai (HAN)', arrivalAirport: 'Tan Son Nhat (SGN)',
  departureTime: '2026-10-20T09:30:00', arrivalTime: '2026-10-20T11:30:00',
  bookingDate: '2026-10-15T12:00:00Z',
  passengers: [
    { fullName: 'Alice Nguyen', seatInstanceId: 200 },
    { fullName: 'Bob Tran', seatInstanceId: 100 },
  ],
  seatInstances: [{ id: 100, seatNumber: '12A' }, { id: 200, seatNumber: '12B' }],
};

test('ticket displays itinerary and matches seats by passenger selection, not array order', async () => {
  const component = await loadComponent('pages/traveler/Ticket/Ticket.jsx', {
    useParams: () => ({ bookingId: '7' }), useNavigate: () => () => {}, useDispatch: () => () => {},
    useSelector: () => ({ booking, loading: false }),
  });
  const tree = component.render();
  const text = textOf(tree);
  for (const value of ['VN123', 'Noi Bai (HAN)', 'Tan Son Nhat (SGN)', '09:30', '11:30', '20 Oct 2026']) {
    assert.ok(text.includes(value), `Missing ${value}`);
  }
  const rows = findAll(tree, (node) => node.type === 'tr').map(textOf);
  assert.ok(rows.find((row) => row.includes('Alice Nguyen')).includes('12B'));
  assert.ok(rows.find((row) => row.includes('Bob Tran')).includes('12A'));
  assert.match(text, /Seat\s+12B/);
});

test('downloadable ticket includes itinerary and correct passenger seats', async () => {
  const component = await loadComponent('pages/traveler/Ticket/TicketPDF.jsx', {
    StyleSheet: { create: (styles) => styles },
  });
  const tree = component.render({ booking });
  const text = textOf(tree);
  for (const value of ['VN123', 'Noi Bai (HAN)', 'Tan Son Nhat (SGN)', '09:30', '11:30', '20 October 2026']) {
    assert.ok(text.includes(value), `Missing ${value}`);
  }
  for (const [name, seat] of [['Alice Nguyen', '12B'], ['Bob Tran', '12A']]) {
    assert.ok(findAll(tree, (node) => node.type === 'View')
      .some((node) => textOf(node).includes(name) && textOf(node).includes(seat)
        && !textOf(node).includes(name === 'Alice Nguyen' ? 'Bob Tran' : 'Alice Nguyen')));
  }
  assert.ok(text.includes('Seat 12B'));
});
