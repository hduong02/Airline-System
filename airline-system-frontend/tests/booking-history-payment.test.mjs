import assert from 'node:assert/strict';
import { test } from 'node:test';
import { loadComponent, findAll, deferred } from './componentHarness.mjs';

const textOf = (node) => Array.isArray(node) ? node.map(textOf).join(' ')
  : node && typeof node === 'object' ? textOf(node.props?.children) : String(node ?? '');
const paymentButtons = (tree) => findAll(tree,
  (node) => node.type === 'Button' && textOf(node).includes('Complete Payment'));
const fixture = { id: 7, status: 'PENDING', paymentStatus: 'PENDING' };

test('pending booking resumes its existing Checkout and prevents duplicate clicks', async () => {
  const request = deferred();
  const calls = [];
  const destinations = [];
  const component = await loadComponent('pages/traveler/BookingHistory/TravellerBookingCard.jsx', {
    resumeBookingPayment: (id) => ({ type: 'resume', id }),
    useDispatch: () => (action) => { calls.push(action); return { unwrap: () => request.promise }; },
    window: { location: { assign: (url) => destinations.push(url) } },
  });
  const button = paymentButtons(component.render({ booking: fixture }))[0];
  assert.equal(typeof button.props.onClick, 'function');
  const pending = button.props.onClick();
  button.props.onClick();
  const loading = findAll(component.render({ booking: fixture }), (node) => node.type === 'Button' && node.props.disabled);
  assert.equal(loading.length, 1);
  request.resolve({ success: true, checkoutUrl: 'https://checkout.stripe.com/c/pay/existing' });
  await pending;
  assert.deepEqual(calls, [{ type: 'resume', id: 7 }]);
  assert.deepEqual(destinations, ['https://checkout.stripe.com/c/pay/existing']);
});

for (const status of ['CANCELLED', 'CONFIRMED', 'COMPLETED', undefined]) {
  test(`payment action is absent for ${status} booking with stale pending payment`, async () => {
    const component = await loadComponent('pages/traveler/BookingHistory/TravellerBookingCard.jsx', {
      useDispatch: () => () => {},
    });
    assert.equal(paymentButtons(component.render({ booking: { ...fixture, status } })).length, 0);
  });
}

test('checkout errors are visible and allow retry', async () => {
  const component = await loadComponent('pages/traveler/BookingHistory/TravellerBookingCard.jsx', {
    resumeBookingPayment: (id) => id,
    useDispatch: () => () => ({ unwrap: async () => { throw 'Booking hold has expired'; } }),
  });
  await paymentButtons(component.render({ booking: fixture }))[0].props.onClick();
  const tree = component.render({ booking: fixture });
  assert.ok(textOf(tree).includes('Booking hold has expired'));
  assert.equal(paymentButtons(tree)[0].props.disabled, false);
});
