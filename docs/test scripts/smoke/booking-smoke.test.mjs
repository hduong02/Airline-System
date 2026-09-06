import assert from 'node:assert/strict';
import { test } from 'node:test';
import { runSmoke } from './booking-smoke.mjs';

const fixture = { departureAirportId: 1, arrivalAirportId: 2, departureDate: '2027-03-15', cabinClass: 'ECONOMY' };
const env = { SMOKE_EMAIL: 'smoke@example.test', SMOKE_PASSWORD: 'password', STRIPE_API_KEY: 'sk_test_fixture' };

function harness(change = {}) {
  const calls = [];
  const logs = [];
  let created = false;
  let verified = false;
  let sessionReads = 0;
  let bookingReads = 0;
  let customerEmail = env.SMOKE_EMAIL;
  let clock = Date.parse('2026-10-02T00:00:00Z');
  const confirmed = { id: 42, status: 'CONFIRMED', paymentStatus: 'SUCCESS', bookingReference: 'SMOKE42',
    passengers: [{ id: 1 }], tickets: [{ ticketNumber: 'TICKET42', status: 'BOOKED' }] };
  const options = {
    now: () => clock, sleep: async ms => { clock += ms; }, pollMs: 1,
    checkoutTimeoutMs: 5, eventTimeoutMs: 5, log: value => logs.push(value),
    fetch: async (value, init) => {
      const url = new URL(value);
      const path = url.pathname;
      calls.push({ url, init });
      if (url.hostname === 'api.stripe.com') {
        assert.equal(init.headers.Authorization, `Bearer ${env.STRIPE_API_KEY}`);
      } else if (path.startsWith('/api/') && url.port === '5000') {
        assert.equal(init.headers.Authorization, 'Bearer customer-jwt');
      }
      let data;
      if (path === '/api/v2/messages') data = { items: [] };
      else if (path === '/auth/signup' || path === '/auth/login') {
        assert.equal(init.method, 'POST');
        assert.equal(init.headers.Authorization, undefined);
        const payload = JSON.parse(init.body);
        customerEmail = payload.email;
        if (path === '/auth/signup') assert.equal(payload.role, 'ROLE_CUSTOMER');
        data = { jwt: 'customer-jwt', user: { id: 7, role: 'ROLE_CUSTOMER' } };
      }
      else if (path === '/api/flights/search') {
        data = { content: [{ id: 101, flightId: 10, fare: { id: 5 } }], totalPages: 1 };
      } else if (path.includes('/cabin-class-type/')) {
        data = { seats: [{ id: 201, flightId: 10, flightInstanceId: 101,
          status: !created ? 'AVAILABLE' : verified ? 'BOOKED' : 'RESERVED' }] };
      } else if (path === '/api/bookings' && init.method === 'POST') {
        created = true;
        const payload = JSON.parse(init.body);
        assert.equal(payload.contactInfo.email, customerEmail);
        assert.equal(payload.passengers[0].email, customerEmail);
        assert.equal(payload.fareId, 5);
        assert.equal(payload.passengers[0].seatInstanceId, 201);
        data = { success: true, gateway: 'STRIPE', paymentId: 9,
          stripeCheckoutUrl: 'cs_test_smoke', checkoutUrl: 'https://checkout.stripe.com/c/pay/cs_test_smoke' };
      } else if (path === '/v1/checkout/sessions/cs_test_smoke') {
        sessionReads++;
        data = { livemode: false, status: sessionReads < 3 ? 'open' : 'complete',
          payment_status: sessionReads < 3 ? 'unpaid' : 'paid', payment_intent: sessionReads < 3 ? null : 'pi_smoke',
          metadata: { booking_id: '42', payment_id: '9', user_id: '7' } };
      } else if (path === '/api/bookings/42') {
        bookingReads++;
        data = verified && bookingReads > 2 ? confirmed : { id: 42, status: 'PENDING' };
      } else if (path === '/api/payments/verify') {
        assert.deepEqual(JSON.parse(init.body), { stripePaymentIntentId: 'pi_smoke' });
        verified = true;
        data = { id: 9, bookingId: 42, status: 'SUCCESS' };
      } else if (path === '/api/bookings/user/history') data = [confirmed];
      else if (path === '/api/v2/search') {
        assert.equal(url.searchParams.get('query'), 'SMOKE42');
        const [Mailbox, Domain] = customerEmail.split('@');
        data = { items: [{ To: [{ Mailbox, Domain }],
          Content: { Headers: { Subject: [`=?UTF-8?B?${Buffer.from('Booking Confirmed | SMOKE42 | AAA→BBB').toString('base64')}?=`] } } }] };
      } else throw new Error(`Unexpected request ${path}`);
      const replacement = change.response?.(path, data);
      const status = change.status?.(path) ?? 200;
      return { ok: status < 400, status, json: async () => replacement ?? data };
    },
  };
  return { calls, logs, options };
}

test('runs the booking flow, waits for checkout and Kafka, and checks the correlated email', async () => {
  const { options, calls, logs } = harness();
  assert.deepEqual(await runSmoke(fixture, env, options), { bookingId: 42, bookingReference: 'SMOKE42', seatId: 201 });
  assert.equal(calls.filter(call => call.url.pathname === '/api/bookings' && call.init.method === 'POST').length, 1);
  assert(calls.filter(call => call.url.hostname === 'api.stripe.com').length >= 3);
  assert(logs.at(-1).startsWith('SMOKE TEST PASSED'));
  assert(!logs.join('\n').includes(env.STRIPE_API_KEY));
  assert(!logs.join('\n').includes(env.SMOKE_PASSWORD));
  assert(!logs.join('\n').includes('customer-jwt'));
  const authCalls = calls.filter(call => call.url.pathname.startsWith('/auth/'));
  assert.deepEqual(authCalls.map(call => call.url.pathname), ['/auth/signup', '/auth/login']);
  assert.deepEqual(JSON.parse(authCalls[1].init.body), {
    email: JSON.parse(authCalls[0].init.body).email, password: 'password',
  });
});

test('generates unique usernames with fixed password and reuses credentials throughout the flow', async () => {
  const settings = { ...env, SMOKE_EMAIL: 'stale@example.test', SMOKE_PASSWORD: 'ignored-password' };
  const credentials = [];
  for (let run = 0; run < 2; run++) {
    const { options, calls, logs } = harness();
    await runSmoke(fixture, settings, options);
    const signup = JSON.parse(calls.find(call => call.url.pathname === '/auth/signup').init.body);
    const login = JSON.parse(calls.find(call => call.url.pathname === '/auth/login').init.body);
    assert.match(signup.email, /^smoke-[\da-f-]+@example\.test$/);
    assert.equal(signup.email, `${signup.username}@example.test`);
    assert.equal(signup.fullName, signup.username);
    assert.equal(signup.password, 'password');
    assert.deepEqual(login, { email: signup.email, password: signup.password });
    assert(!logs.join('\n').includes(signup.password));
    assert(!logs.join('\n').includes('customer-jwt'));
    credentials.push(signup);
  }
  assert.notEqual(credentials[0].email, credentials[1].email);
  assert.notEqual(credentials[0].username, credentials[1].username);
});

test('login mode uses an existing account without signup', async () => {
  const { options, calls } = harness();
  await runSmoke(fixture, { ...env, SMOKE_AUTH_MODE: 'login' }, options);
  assert(!calls.some(call => call.url.pathname === '/auth/signup'));
  assert(calls.some(call => call.url.pathname === '/auth/login'));
});

test('rejects invalid auth modes and incomplete credentials before HTTP requests', async () => {
  for (const settings of [
    { ...env, SMOKE_AUTH_MODE: 'guest' },
    { ...env, SMOKE_AUTH_MODE: 'login', SMOKE_PASSWORD: undefined },
    { ...env, SMOKE_AUTH_MODE: 'login', SMOKE_EMAIL: undefined },
    { STRIPE_API_KEY: env.STRIPE_API_KEY, SMOKE_AUTH_MODE: 'login' },
  ]) {
    const { options, calls } = harness();
    await assert.rejects(runSmoke(fixture, settings, options));
    assert.equal(calls.length, 0);
  }
});

test('signup failures stop the flow without falling back to login', async () => {
  for (const status of [400, 409, 500]) {
    const { options, calls, logs } = harness({ status: path => path === '/auth/signup' ? status : 200 });
    await assert.rejects(runSmoke(fixture, env, options), /POST \/auth\/signup: HTTP/);
    assert(!calls.some(call => call.url.pathname === '/auth/login' || call.url.pathname === '/api/bookings'));
    assert(!logs.join('\n').includes(env.SMOKE_PASSWORD));
  }
});

test('invalid signup or login responses stop the flow before booking', async () => {
  for (const path of ['/auth/signup', '/auth/login']) {
    for (const response of [{ user: { role: 'ROLE_CUSTOMER' } },
      { jwt: 'customer-jwt', user: { role: 'ROLE_AIRLINE_ADMIN' } }, { jwt: 'customer-jwt' }]) {
      const { options, calls } = harness({ response: current => current === path ? response : undefined });
      await assert.rejects(runSmoke(fixture, env, options), /must return a customer account and JWT/);
      assert(!calls.some(call => call.url.pathname === '/api/bookings'));
      if (path === '/auth/signup') assert(!calls.some(call => call.url.pathname === '/auth/login'));
    }
  }
});

test('rejects live keys, remote targets, and invalid fixture dates before any HTTP request', async () => {
  for (const [data, settings] of [
    [fixture, { ...env, STRIPE_API_KEY: 'sk_live_example' }],
    [fixture, { ...env, SMOKE_API_URL: 'https://production.example' }],
    [{ ...fixture, departureDate: '2027-02-30' }, env],
    [{ ...fixture, departureDate: '2020-01-01' }, env],
  ]) {
    const { options, calls } = harness();
    await assert.rejects(runSmoke(data, settings, options));
    assert.equal(calls.length, 0);
  }
});

test('does not book when the fixture flight is missing', async () => {
  const { options, calls } = harness();
  await assert.rejects(runSmoke({ ...fixture, flightInstanceId: 999 }, env, options), /No bookable flight/);
  assert(!calls.some(call => call.url.pathname === '/api/bookings'));
});

test('fails if the seat was not held before checkout', async () => {
  const { options, logs } = harness({ response: (path, data) => path.includes('/cabin-class-type/')
    ? { seats: data.seats.map(seat => ({ ...seat, status: 'AVAILABLE' })) } : data });
  await assert.rejects(runSmoke(fixture, env, options), /Selected seat must be held/);
  assert(!logs.some(line => line.includes('Open this Stripe')));
});

test('times out an unpaid checkout without calling payment verification', async () => {
  const { options, calls } = harness({ response: (path, data) => path.startsWith('/v1/')
    ? { ...data, payment_status: 'unpaid', payment_intent: null } : data });
  await assert.rejects(runSmoke(fixture, env, options), /Timed out waiting for Stripe test checkout paid/);
  assert(!calls.some(call => call.url.pathname === '/api/payments/verify'));
});

test('rejects a payment result belonging to a different booking', async () => {
  const { options } = harness({ response: (path, data) => path === '/api/payments/verify'
    ? { ...data, bookingId: 999 } : data });
  await assert.rejects(runSmoke(fixture, env, options), /Verified payment must succeed for this booking/);
});

test('does not accept another recipients email as confirmation', async () => {
  const { options } = harness({ response: (path, data) => path === '/api/v2/search'
    ? { items: [{ ...data.items[0], To: [{ Mailbox: 'other', Domain: 'example.test' }] }] } : data });
  await assert.rejects(runSmoke(fixture, env, options), /Timed out waiting for booking confirmation email/);
});

test('decodes folded quoted-printable email subjects from JavaMail', async () => {
  const { options } = harness({ response: (path, data) => path === '/api/v2/search'
    ? { items: [{ ...data.items[0], Content: { Headers: {
      Subject: ['=?UTF-8?Q?Booking_Confirmed_=7C_?=\r\n =?UTF-8?Q?SMOKE42_=7C_AAA=E2=86=92BBB?='],
    } } }] } : data });
  assert.equal((await runSmoke(fixture, env, options)).bookingId, 42);
});

test('missing tickets prevent success even when the booking is confirmed', async () => {
  const { options } = harness({ response: (path, data) => path === '/api/bookings/42'
    && data.status === 'CONFIRMED' ? { ...data, tickets: [] } : data });
  await assert.rejects(runSmoke(fixture, env, options), /Timed out waiting for CONFIRMED booking and passenger ticket/);
});
