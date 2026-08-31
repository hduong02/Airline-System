import assert from 'node:assert/strict';
import { after, test } from 'node:test';
import { createServer } from 'vite';
import * as Yup from 'yup';
import { loadComponent, findAll, deferred, settle } from './componentHarness.mjs';

const server = await createServer({
  server: { middlewareMode: true }, appType: 'custom', optimizeDeps: { noDiscovery: true, include: [] },
});
after(() => server.close());
const { default: authReducer } = await server.ssrLoadModule('/src/Redux/auth/authSlice.js');
const { default: bookingReducer } = await server.ssrLoadModule('/src/Redux/booking/bookingSlice.js');
const { default: cabinReducer } = await server.ssrLoadModule('/src/Redux/flightInstanceCabin/flightInstanceCabinSlice.js');
const { getFlightInstanceCabinsByFlightInstanceAndCabinClass: getCabin } = await server.ssrLoadModule('/src/Redux/flightInstanceCabin/flightInstanceCabinThunk.js');
const checkout = await server.ssrLoadModule('/src/utils/bookingCheckout.js');
const { buildBookingPayload } = await server.ssrLoadModule('/src/services/Booking/bookingService.js');

test('signup keeps the user from the auth response', () => {
  const user = { id: 1, role: 'ROLE_USER', fullName: 'Test Passenger' };
  const state = authReducer(undefined, { type: 'auth/signup/fulfilled', payload: { user } });
  assert.deepEqual(state.user, user);
  assert.equal(state.isAuthenticated, true);
});

for (const operation of ['create', 'update', 'getById', 'cancel', 'delete', 'getByAirline', 'getByUser', 'getByFlight', 'getCountByFlight', 'getStatisticsForAirline']) {
  test(`${operation} uses booking loading and settles on failure or success`, () => {
    const state = bookingReducer(undefined, { type: `booking/${operation}/pending` });
    assert.equal(state.loading, true);
    assert.equal(state.routePerformanceLoading, false);
    for (const status of ['fulfilled', 'rejected']) {
      const settled = bookingReducer(state, {
        type: `booking/${operation}/${status}`,
        payload: status === 'rejected' ? 'Failed' : { id: 1 },
      });
      assert.equal(settled.loading, false);
    }
  });
}

test('loading a ticket clears the previously displayed booking', () => {
  const state = bookingReducer({ ...bookingReducer(undefined, {}), booking: { id: 1 } },
    { type: 'booking/getById/pending' });
  assert.equal(state.booking, null);
});

test('seat cabin loading clears stale seats and ignores an earlier response', () => {
  const initial = { ...cabinReducer(undefined, {}), cabin: { id: 1 } };
  const first = cabinReducer(initial, getCabin.pending('first', {}));
  assert.equal(first.cabin, null);
  assert.equal(first.loading, true);
  const second = cabinReducer(first, getCabin.pending('second', {}));
  assert.equal(cabinReducer(second, getCabin.fulfilled({ id: 1 }, 'first', {})).cabin, null);
  const final = cabinReducer(second, getCabin.fulfilled({ id: 2 }, 'second', {}));
  assert.equal(final.cabin.id, 2);
  assert.equal(final.loading, false);
  assert.equal(cabinReducer(second, getCabin.rejected(null, 'second', {}, 'Failed')).loading, false);
});

test('fare and add-on totals use backend prices for every passenger', () => {
  const totals = checkout.calculateBookingTotals({
    fare: { baseFare: '100', taxesAndFees: '20', airlineFees: '10', currentPrice: 500 },
    passengerCount: 3,
    selectedSeats: [{ price: '25', seatType: 'WINDOW' }, null, { price: 0, seatType: 'EMERGENCY_EXIT' }],
    selectedMeals: [null, { price: '5' }],
    selectedBaggage: [{ price: '7', quantity: 2 }],
    travelProtection: { price: '6' },
  });
  assert.equal(totals.subtotal, 390);
  assert.equal(totals.seatCharges, 25);
  assert.equal(totals.grandTotal, 440);
  assert.equal(checkout.calculateBookingTotals().grandTotal, 0);
});

test('passenger counts default safely and respect the search limit', () => {
  for (const invalid of [null, '', 'abc', '-2', '0', '1.5', '3x']) {
    assert.equal(checkout.getPassengerCount(invalid), 1);
  }
  assert.equal(checkout.getPassengerCount('3'), 3);
  assert.equal(checkout.getPassengerCount('999'), 9);
});

const passenger = { title: 'Mr', firstName: ' Test ', lastName: ' Passenger ', gender: 'Male' };
const contactInfo = { email: ' test@example.com ', phone: '1234567890', countryCode: '+91' };
test('untouched, whitespace-only and invalid contact data cannot be submitted', () => {
  for (const untouched of [undefined, [], {}, { passengers: [] }]) {
    assert.ok(checkout.validateTravellerData(untouched, 1));
  }
  assert.ok(checkout.validateTravellerData({ passengers: [{ ...passenger, firstName: ' ' }], contactInfo }, 1));
  for (const contact of [{}, { ...contactInfo, email: 'bad' }, { ...contactInfo, phone: 'abc' }]) {
    assert.ok(checkout.validateTravellerData({ passengers: [passenger], contactInfo: contact }, 1));
  }
  assert.equal(checkout.validateTravellerData({ passengers: [passenger], contactInfo }, 1), null);
  assert.deepEqual(checkout.normalizeContactInfo(contactInfo), { email: 'test@example.com', phone: '+911234567890' });
  assert.equal(checkout.normalizeContactInfo({ ...contactInfo, phone: '+911234567890' }).phone, '+911234567890');
});

const cabins = [{ id: 1, code: 'Y', name: 'Economy' }, { id: 2, code: 'W', name: 'Premium Economy' }];
const flight = { id: 5, flightId: 10, aircraftId: 20, departureAirportCode: 'A', arrivalAirportCode: 'B' };
const fare = { id: 30, flightId: 10, cabinClassId: 2, cabinClass: 'PREMIUM_ECONOMY', baseFare: 100, taxesAndFees: 20, airlineFees: 10 };
test('requested cabin resolves by id, code or type and waits for available cabins', () => {
  assert.equal(checkout.selectInitialCabin([], 'ECONOMY'), null);
  for (const requested of ['PREMIUM_ECONOMY', 'Premium Economy', 'W', 2, { id: 2 }]) {
    assert.equal(checkout.selectInitialCabin(cabins, requested), cabins[1]);
  }
});

test('booking URL uses the fare cabin enum and rejects mixed flight/cabin selections', () => {
  const payload = buildBookingPayload({ flight, selectedFare: fare, selectedCabinClass: cabins[1], numberOfTravellers: 3 });
  assert.equal(payload.queryParams.cabinClass, 'PREMIUM_ECONOMY');
  assert.equal(payload.queryParams.numberOfTravellers, '3');
  assert.equal(JSON.parse(atob(payload.queryParams.xflt)).CabinClass, 'PREMIUM_ECONOMY');
  for (const selectedFare of [{ ...fare, cabinClassId: 1 }, { ...fare, flightId: 99 }, { ...fare, cabinClass: 'Premium Economy' }]) {
    assert.throws(() => buildBookingPayload({ flight, selectedFare, selectedCabinClass: cabins[1] }));
  }
});

test('registration dispatches the user contract and handles unwrapped success', async () => {
  let payload, destination, submitting;
  const component = await loadComponent('pages/auth/RegisterForm.jsx', {
    Yup, useDispatch: () => (action) => ({ unwrap: async () => ({ user: { role: 'ROLE_USER' }, action }) }),
    useSelector: () => ({ loading: false }), useNavigate: () => (path) => { destination = path; },
    signup: (data) => { payload = data; return data; },
  });
  const form = component.render();
  const values = { fullName: ' Test Passenger ', email: ' test@example.com ', mobile: '1234567890', password: 'abc123' };
  await form.props.onSubmit(values, { setSubmitting: (value) => { submitting = value; } });
  assert.deepEqual(payload, { fullName: 'Test Passenger', email: 'test@example.com', phone: '1234567890', password: 'abc123', role: 'ROLE_USER' });
  assert.equal(values.role, undefined);
  assert.equal(destination, '/traveler');
  assert.equal(submitting, false);
});

test('traveller form publishes initial and profile-prefilled contact values without an invented phone', async () => {
  const updates = [];
  const form = await loadComponent('pages/traveler/BookingReview/TravellerDetailsForm.jsx', {
    useSelector: () => ({ userProfile: { email: 'test@example.com' } }),
  });
  const props = { passengerCount: 3, onTravellerDataChange: (data) => updates.push(data) };
  form.render(props); form.flushEffects();
  form.render(props); form.flushEffects();
  assert.equal(updates.at(-1).passengers.length, 3);
  assert.equal(updates.at(-1).contactInfo.email, 'test@example.com');
  assert.equal(updates.at(-1).contactInfo.phone, '');
  form.unmount();
});

test('fare modal waits for cabins, clears selections, shows every fare and ignores late responses', async () => {
  const requests = [];
  const dispatch = (action) => {
    const result = deferred();
    requests.push({ action, ...result });
    return { unwrap: () => result.promise };
  };
  const modal = await loadComponent('pages/traveler/FlightList/FlightPricing/FlightPricingModal.jsx', {
    ...checkout, buildBookingPayload,
    useDispatch: () => dispatch, useNavigate: () => () => {},
    useSearchParams: () => [new URLSearchParams('numberOfTravellers=3&cabinClass=PREMIUM_ECONOMY')],
    getCabinClassesByAircraft: (id) => ({ type: 'cabins', id }),
    getFlightFares: (data) => ({ type: 'fares', ...data }),
  });
  const props = { isOpen: true, flight };
  const render = () => modal.render(props);
  const continueButton = (tree) => findAll(tree, (node) => node.type === 'Button').at(-1);
  render(); modal.flushEffects();
  assert.equal(requests.length, 1);
  assert.equal(continueButton(render()).props.disabled, true);
  requests[0].resolve(cabins); await settle();
  let tree = render(); modal.flushEffects();
  assert.equal(requests[1].action.cabinId, 2);
  requests[1].resolve([fare, { ...fare, id: 31 }, { ...fare, id: 32 }, { ...fare, id: 33 }]); await settle();
  tree = render();
  const cards = findAll(tree, (node) => node.type === 'FareCard');
  assert.equal(cards.length, 4);
  assert.equal(cards[3].props.passengerCount, 3);
  cards[3].props.onSelect();
  assert.equal(continueButton(render()).props.disabled, false);
  findAll(render(), (node) => node.type === 'Tabs')[0].props.onValueChange('1');
  tree = render();
  assert.equal(continueButton(tree).props.disabled, true);
  assert.equal(findAll(tree, (node) => node.type === 'FareCard').length, 0);
  modal.flushEffects();
  findAll(render(), (node) => node.type === 'Tabs')[0].props.onValueChange('2');
  render(); modal.flushEffects();
  requests[3].resolve([fare]); await settle();
  requests[2].resolve([{ ...fare, id: 90, cabinClassId: 1, cabinClass: 'ECONOMY' }]); await settle();
  assert.equal(findAll(render(), (node) => node.type === 'FareCard')[0].props.fare.id, 30);
  assert.equal(continueButton(render()).props.disabled, true);
  props.flight = { ...flight, id: 6, flightId: 11 };
  assert.equal(findAll(render(), (node) => node.type === 'FareCard').length, 0);
  modal.unmount();
});

test('booking review blocks untouched/contact-invalid forms and duplicate submissions', async () => {
  const apiRequests = [];
  const errors = [];
  const dispatch = (action) => {
    if (action.type === 'fare') return { unwrap: async () => fare };
    if (action.type === 'booking') {
      const response = deferred();
      apiRequests.push({ action, ...response });
      return { unwrap: () => response.promise };
    }
    return {};
  };
  const component = await loadComponent('pages/traveler/BookingReview/BookingReview.jsx', {
    ...checkout, useDispatch: () => dispatch, useNavigate: () => () => {},
    useSearchParams: () => [new URLSearchParams('fareId=30&flightId=10&flightInstanceId=5&cabinClass=Premium+Economy')],
    useSelector: (selector) => selector({ flightInstance: {}, flightCabinAncillary: { ancillariesByType: {} }, booking: { loading: false } }),
    getFareById: (id) => ({ type: 'fare', id }), createBooking: (data) => ({ type: 'booking', data }),
    getBaggagePolicyByFare: () => ({}), getFareRuleByFare: () => ({}), getFlightInstanceById: () => ({}),
    getFlightInstanceCabinsByFlightInstanceAndCabinClass: () => ({}), fetchFlightMealsByFlightId: () => ({}),
    getFlightCabinAncillariesByType: () => ({}), getAllFlightCabinAncillariesByType: () => ({}),
    toast: { error: (error) => errors.push(error), loading() {}, success() {} },
  });
  component.render(); component.flushEffects(); await settle();
  const summary = () => findAll(component.render(), (node) => node.type === 'FareSummaryCard')[0];
  await summary().props.onProceedToPayment();
  assert.equal(apiRequests.length, 0);
  assert.match(errors.at(-1), /traveller/);
  const form = findAll(component.render(), (node) => node.type === 'TravellerDetailsForm')[0];
  form.props.onTravellerDataChange({ passengers: [passenger], contactInfo: {} });
  await summary().props.onProceedToPayment();
  assert.equal(apiRequests.length, 0);
  assert.match(errors.at(-1), /email/);
  form.props.onTravellerDataChange({ passengers: [passenger], contactInfo });
  findAll(component.render(), (node) => node.type === 'SeatSelection')[0].props.onSelectSeat(0, { id: 9, seatNumber: '1A', seatType: 'WINDOW', price: 25 });
  assert.equal(summary().props.totals.grandTotal, 155);
  const mobileTotal = findAll(component.render(), (node) => node.type === 'p' && node.props.className === 'text-lg font-bold text-gray-900')[0];
  assert.equal(mobileTotal.props.children.join(''), '$155');
  const submit = summary().props.onProceedToPayment;
  const first = submit();
  await submit();
  assert.equal(apiRequests.length, 1);
  assert.equal(apiRequests[0].action.data.cabinClass, 'PREMIUM_ECONOMY');
  assert.equal(apiRequests[0].action.data.passengers[0].firstName, 'Test');
  assert.equal(apiRequests[0].action.data.passengers[0].seatInstanceId, 9);
  assert.equal(apiRequests[0].action.data.contactInfo.phone, '+911234567890');
  apiRequests[0].reject('Network failed');
  await first;
  const retry = summary().props.onProceedToPayment();
  assert.equal(apiRequests.length, 2);
  apiRequests[1].resolve({ success: true }); await retry;
  component.unmount();
});
