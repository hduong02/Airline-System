import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFile } from 'node:fs/promises';
import { loadComponent, findAll, settle, deferred } from './componentHarness.mjs';
import { getPaymentVerificationRequest, getPaymentResult } from '../src/utils/paymentResult.js';

const textOf = (node) => {
  if (Array.isArray(node)) return node.map(textOf).join(' ');
  if (node && typeof node === 'object') return textOf(node.props?.children);
  return node == null ? '' : String(node);
};
const fixture = (status, paymentStatus) => ({ id: 7, status, paymentStatus, bookingReference: 'REAL-7' });
async function resultComponent(booking, query = '', verify = async () => ({ bookingId: 7, status: 'SUCCESS' })) {
  const calls = [];
  const dispatch = (action) => {
    calls.push(action);
    return { unwrap: () => action.type === 'verify' ? verify(action.payload) : Promise.resolve(booking) };
  };
  const component = await loadComponent('pages/traveler/BookingSuccess/BookingSuccess.jsx', {
    useParams: () => ({ bookingId: '7' }), useSearchParams: () => [new URLSearchParams(query)],
    useNavigate: () => () => {}, useDispatch: () => dispatch,
    useSelector: (selector) => selector({ booking: { booking, loading: false }, payment: { loading: false } }),
    getBookingById: (id) => ({ type: 'booking', id }), verifyPayment: (payload) => ({ type: 'verify', payload }),
    getPaymentVerificationRequest, getPaymentResult,
  });
  component.render(); component.flushEffects(); await settle();
  return { component, calls, tree: component.render() };
}

for (const [status, paymentStatus, title] of [
  ['PENDING', 'PENDING', 'Booking pending'], ['CANCELLED', 'SUCCESS', 'Booking cancelled'],
  ['PENDING', 'FAILED', 'Payment failed'], ['PENDING', 'CANCELLED', 'Payment cancelled'],
  ['CONFIRMED', 'REFUNDED', 'Payment refunded'], ['CONFIRMED', 'SUCCESS', 'Booking Confirmed!'],
]) {
  test(`result displays ${status}/${paymentStatus} honestly`, async () => {
    const { component, tree } = await resultComponent(fixture(status, paymentStatus));
    try {
      assert.ok(textOf(tree).includes(title), textOf(tree).slice(0, 250));
      if (title !== 'Booking Confirmed!') assert.ok(!textOf(tree).includes('Download E-Ticket'));
    } finally { component.unmount(); }
  });
}

test('Razorpay callback maps provider ID to verify contract and refreshes from PaymentDto', async () => {
  const { component, calls } = await resultComponent(fixture('CONFIRMED', 'SUCCESS'), 'razorpay_payment_id=pay_123');
  try {
    assert.deepEqual(calls[0], { type: 'verify', payload: { razorpayPaymentId: 'pay_123' } });
    assert.ok(calls.some((call) => call.type === 'booking'));
  } finally { component.unmount(); }
});

test('verification error is visible even if stale booking is confirmed', async () => {
  const { component, tree } = await resultComponent(fixture('CONFIRMED', 'SUCCESS'),
    'payment_id=pay_123&payment_link_id=link&signature=sig', async () => { throw new Error('Verification unavailable'); });
  try {
    assert.ok(textOf(tree).includes('Verification unavailable'));
    assert.ok(!textOf(tree).includes('Booking Confirmed!'));
  } finally { component.unmount(); }
});

test('ticket loading renders its spinner', async () => {
  const component = await loadComponent('pages/traveler/Ticket/Ticket.jsx', {
    useParams: () => ({ bookingId: '7' }), useNavigate: () => () => {}, useDispatch: () => () => {},
    useSelector: () => ({ loading: true }),
  });
  assert.ok(findAll(component.render(), (node) => node.type === 'Loader2').length);
});

test('legacy ticket page never invents passenger details', async () => {
  const component = await loadComponent('pages/traveler/Ticket/ETicket.jsx', {
    useParams: () => ({ pnr: 'ANY-PNR' }), useNavigate: () => () => {},
  });
  const text = textOf(component.render());
  assert.ok(!text.includes('John Doe'));
  assert.ok(text.includes('Bookings'));
});

test('supported Stripe intent maps correctly; session callback never verifies a session ID', async () => {
  assert.deepEqual(getPaymentVerificationRequest(new URLSearchParams('payment_intent=pi_real')),
    { stripePaymentIntentId: 'pi_real' });
  const { component, calls, tree } = await resultComponent(fixture('PENDING', 'PENDING'), 'session_id=cs_real');
  try {
    assert.deepEqual(calls, [{ type: 'booking', id: '7' }]);
    assert.ok(textOf(tree).includes('Booking pending'));
  } finally { component.unmount(); }
});

test('PaymentDto failure overrides confirmation and unrelated verified payment is rejected', async () => {
  for (const [response, title] of [
    [{ bookingId: 7, status: 'FAILED' }, 'Payment failed'],
    [{ bookingId: 8, status: 'SUCCESS' }, 'different booking'],
  ]) {
    const { component, tree } = await resultComponent(fixture('CONFIRMED', 'SUCCESS'), 'payment_intent=pi_real', async () => response);
    try {
      assert.ok(textOf(tree).includes(title));
      assert.ok(!textOf(tree).includes('Download E-Ticket'));
    } finally { component.unmount(); }
  }
});

test('pending Stripe callbacks refresh until the authoritative booking confirms', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const dispatch = () => ({ unwrap: async () => ++fetches === 1
    ? fixture('PENDING', 'PENDING') : fixture('CONFIRMED', 'SUCCESS') });
  let fetches = 0;
  const component = await loadComponent('pages/traveler/BookingSuccess/BookingSuccess.jsx', {
    useParams: () => ({ bookingId: '7' }), useSearchParams: () => [new URLSearchParams('session_id=cs_real')],
    useNavigate: () => () => {}, useDispatch: () => dispatch,
    getBookingById: (id) => id, getPaymentVerificationRequest, getPaymentResult,
  });
  try {
    component.render(); component.flushEffects(); await settle();
    assert.ok(textOf(component.render()).includes('Booking pending'));
    t.mock.timers.tick(2000); await settle();
    assert.ok(textOf(component.render()).includes('Booking Confirmed!'));
    assert.equal(fetches, 2);
  } finally { component.unmount(); }
});

test('route changes cannot display the previous booking or its late response', async () => {
  let bookingId = '7';
  const first = deferred();
  const second = deferred();
  const dispatch = (id) => ({ unwrap: () => id === '7' ? first.promise : second.promise });
  const component = await loadComponent('pages/traveler/BookingSuccess/BookingSuccess.jsx', {
    useParams: () => ({ bookingId }), useSearchParams: () => [new URLSearchParams()],
    useNavigate: () => () => {}, useDispatch: () => dispatch,
    getBookingById: (id) => id, getPaymentVerificationRequest, getPaymentResult,
  });
  try {
    component.render(); component.flushEffects();
    bookingId = '8'; component.render(); component.flushEffects();
    second.resolve({ ...fixture('CONFIRMED', 'SUCCESS'), id: 8, bookingReference: 'REAL-8' }); await settle();
    first.resolve(fixture('CONFIRMED', 'SUCCESS')); await settle();
    const text = textOf(component.render());
    assert.ok(text.includes('REAL-8'));
    assert.ok(!text.includes('REAL-7'));
  } finally { component.unmount(); }
});

test('history download invokes the imported ticket generator with its booking', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const booking = fixture('CONFIRMED', 'SUCCESS');
  let downloaded;
  const component = await loadComponent('pages/traveler/BookingHistory/TravellerBookingCard.jsx', {
    generateTicketPDF: async (value) => { downloaded = value; return { fileName: 'test.pdf' }; },
  });
  const button = findAll(component.render({ booking }), (node) => node.type === 'Button' &&
    textOf(node).includes('Download Ticket'))[0];
  assert.ok(button);
  const originalDocument = globalThis.document;
  globalThis.document = { createElement: () => ({ remove() {} }), body: { appendChild() {} } };
  try {
    await button.props.onClick();
    assert.equal(downloaded, booking);
  } finally { globalThis.document = originalDocument; }
});

test('payment and cancel screens use booking history and never collect card details', async () => {
  for (const cancelled of [false, true]) {
    const destinations = [];
    const component = await loadComponent('pages/traveler/Payment/PaymentPage.jsx', {
      useLocation: () => ({ state: { booking: { id: 7 } } }), useSearchParams: () => [new URLSearchParams()],
      useNavigate: () => (url) => destinations.push(url),
    });
    const tree = component.render({ cancelled });
    assert.ok(textOf(tree).includes(cancelled ? 'Checkout cancelled' : 'Check your payment status'));
    assert.equal(findAll(tree, (node) => node.type === 'Input').length, 0);
    findAll(tree, (node) => node.type === 'Button').forEach((button) => button.props.onClick());
    assert.deepEqual(destinations, ['/booking-success/7', '/bookings']);
  }
});

test('Stripe cancellation URL and legacy ticket routes have result pages', async () => {
  const source = await readFile(new URL('../src/App.jsx', import.meta.url), 'utf8');
  assert.match(source, /path="\/payment-cancelled\/:paymentId"\s+element=\{<PaymentPage cancelled \/>\}/);
  for (const route of ['/ticket', '/ticket/:pnr']) assert.ok(source.includes(`path="${route}"`));
});
