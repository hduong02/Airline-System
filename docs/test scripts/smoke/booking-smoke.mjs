import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { setTimeout as sleep } from 'node:timers/promises';

const HELP = `Usage: node "docs/test scripts/smoke/booking-smoke.mjs" <fixture.json>

Required environment variables:
  STRIPE_API_KEY      sk_test_... key from the payment-service's Stripe account

Optional environment variables:
  SMOKE_AUTH_MODE     register (default) or login (existing account)
  SMOKE_EMAIL         Existing customer email (login mode only)
  SMOKE_PASSWORD      Existing customer password (login mode only)
  SMOKE_API_URL       Default http://localhost:5000 (loopback only)
  SMOKE_MAILHOG_URL   Default http://localhost:8025 (loopback only)

The fixture specifies departureAirportId, arrivalAirportId, departureDate,
cabinClass, and optionally flightInstanceId. It uses one available seat.
Complete the printed Stripe test checkout URL in your browser within 10 minutes.
The script then verifies the payment, booking, ticket, seat, history, and email.
Registration always generates a unique username/email and uses password "password".
SMOKE_EMAIL and SMOKE_PASSWORD are ignored in registration mode; signup failures stop the test.
It creates persistent accounts and test data and leaves the booking confirmed on success.
Exit code: 0 = all checks passed; 1 = failed or timed out.
`;

function localUrl(value, label) {
  const url = new URL(value);
  assert(['localhost', '127.0.0.1', '[::1]'].includes(url.hostname), `${label} must use localhost`);
  assert(['http:', 'https:'].includes(url.protocol) && !url.username && !url.password,
    `${label} must be an HTTP URL without credentials`);
  assert(url.pathname === '/' && !url.search && !url.hash, `${label} must be an origin URL`);
  return url.origin;
}

function decodeSubject(value) {
  // JavaMail encodes subjects containing the route arrow as RFC 2047 words.
  return value.replace(/(\?=)\s+(=\?)/g, '$1$2')
    .replace(/=\?([^?]+)\?([bq])\?([^?]*)\?=/gi, (_, charset, encoding, text) => {
      const bytes = encoding.toLowerCase() === 'b' ? Buffer.from(text, 'base64')
        : Buffer.from(text.replace(/_/g, ' ').replace(/=([\da-f]{2})/gi,
          (match, hex) => String.fromCharCode(Number.parseInt(hex, 16))), 'latin1');
      return new TextDecoder(charset).decode(bytes);
    });
}

export async function runSmoke(fixture, env = process.env, options = {}) {
  const fetcher = options.fetch ?? fetch;
  const pause = options.sleep ?? sleep;
  const log = options.log ?? console.log;
  const now = options.now ?? Date.now;
  const pollMs = options.pollMs ?? 2000;
  const checkoutTimeoutMs = options.checkoutTimeoutMs ?? 600_000;
  const eventTimeoutMs = options.eventTimeoutMs ?? 120_000;
  const authMode = env.SMOKE_AUTH_MODE ?? 'register';
  assert(['register', 'login'].includes(authMode), 'SMOKE_AUTH_MODE must be register or login');
  assert(authMode !== 'login' || (env.SMOKE_EMAIL && env.SMOKE_PASSWORD),
    'Login mode requires SMOKE_EMAIL and SMOKE_PASSWORD');
  const username = `smoke-${randomUUID()}`;
  const email = authMode === 'register' ? `${username}@example.test` : env.SMOKE_EMAIL;
  const password = authMode === 'register' ? 'password' : env.SMOKE_PASSWORD;
  assert(/^sk_test_\S+$/.test(env.STRIPE_API_KEY ?? ''), 'STRIPE_API_KEY must be a Stripe test secret key (sk_test_...)');
  const api = localUrl(env.SMOKE_API_URL ?? 'http://localhost:5000', 'SMOKE_API_URL');
  const mailhog = localUrl(env.SMOKE_MAILHOG_URL ?? 'http://localhost:8025', 'SMOKE_MAILHOG_URL');
  for (const field of ['departureAirportId', 'arrivalAirportId']) {
    assert(Number.isSafeInteger(fixture[field]) && fixture[field] > 0, `${field} must be a positive integer`);
  }
  assert(['ECONOMY', 'PREMIUM_ECONOMY', 'BUSINESS', 'FIRST'].includes(fixture.cabinClass), 'Invalid cabinClass');
  const date = new Date(`${fixture.departureDate}T00:00:00Z`);
  assert(/^\d{4}-\d{2}-\d{2}$/.test(fixture.departureDate ?? '') && Number.isFinite(date.getTime())
    && date.toISOString().slice(0, 10) === fixture.departureDate && date.getTime() > now(),
  'departureDate must be a valid future date');
  if (fixture.flightInstanceId !== undefined) {
    assert(Number.isSafeInteger(fixture.flightInstanceId) && fixture.flightInstanceId > 0, 'Invalid flightInstanceId');
  }

  let token;
  let bookingId;
  async function request(origin, path, { method = 'GET', body, stripe = false, auth = false } = {}) {
    const headers = { Accept: 'application/json' };
    if (auth) headers.Authorization = `Bearer ${token}`;
    if (stripe) headers.Authorization = `Bearer ${env.STRIPE_API_KEY}`;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    let response;
    try {
      response = await fetcher(`${origin}${path}`, {
        method, headers, body: body === undefined ? undefined : JSON.stringify(body),
        signal: AbortSignal.timeout(10_000), redirect: 'error',
      });
    } catch {
      throw new Error(`${method} ${new URL(path, origin).pathname}: connection failed or request timed out`);
    }
    // Do not print response bodies, credentials, or authorization headers.
    assert(response.ok, `${method} ${new URL(path, origin).pathname}: HTTP ${response.status}`);
    return response.json();
  }
  const get = path => request(api, path, { auth: true });
  const post = (path, body) => request(api, path, { method: 'POST', body, auth: true });
  async function until(label, check, timeoutMs = eventTimeoutMs) {
    const deadline = now() + timeoutMs;
    do {
      const result = await check();
      if (result) {
        log(`PASS ${label}`);
        return result;
      }
      if (now() >= deadline) break;
      await pause(Math.min(pollMs, deadline - now()));
    } while (now() < deadline);
    throw new Error(`Timed out waiting for ${label}`);
  }

  try {
    await request(mailhog, '/api/v2/messages?limit=1');
    if (authMode === 'register') {
      const signup = await request(api, '/auth/signup', {
        method: 'POST', body: { username, fullName: username, email, password,
          phone: '+15555550123', role: 'ROLE_CUSTOMER' },
      });
      assert(signup.jwt && signup.user?.role === 'ROLE_CUSTOMER',
        'Signup must return a customer account and JWT');
      log('PASS customer registration (account persists after testing)');
    }
    const login = await request(api, '/auth/login', {
      method: 'POST', body: { email, password },
    });
    assert(login.jwt && login.user?.role === 'ROLE_CUSTOMER', 'Login must return a customer account and JWT');
    token = login.jwt;
    log('PASS customer login and MailHog availability');

    let selected;
    // Walk pages because cabin/price post-filtering can leave sparse pages.
    for (let page = 0; page < 100; page++) {
      const query = new URLSearchParams({
        departureAirportId: String(fixture.departureAirportId), arrivalAirportId: String(fixture.arrivalAirportId),
        departureDate: fixture.departureDate, cabinClass: fixture.cabinClass,
        passengers: '1', sortBy: 'departure', sortOrder: 'asc', page: String(page), size: '20',
      });
      const result = await get(`/api/flights/search?${query}`);
      assert(Array.isArray(result.content), 'Search must return a page with content');
      for (const flight of result.content) {
        if (fixture.flightInstanceId !== undefined && flight.id !== fixture.flightInstanceId) continue;
        if (!flight.fare?.id) continue;
        const path = `/api/flight-instance-cabins/flight-instance/${flight.id}/cabin-class-type/${fixture.cabinClass}`;
        const cabin = await get(path);
        const seat = cabin.seats?.find(item => item.status === 'AVAILABLE'
          && item.flightId === flight.flightId && item.flightInstanceId === flight.id);
        if (seat && cabin.canBook !== false && cabin.isActive !== false) {
          selected = { flight, cabinPath: path, seat };
          break;
        }
      }
      if (selected || result.last === true || page + 1 >= result.totalPages) break;
      // Empty post-filtered pages can report zero totals even when later DB pages exist.
      if (!result.content.length && !result.totalPages) break;
    }
    assert(selected, 'No bookable flight/seat found; check fixture IDs, date, fare and seeded cabin data');
    const { flight, cabinPath, seat } = selected;
    log(`PASS flight search: instance ${flight.id}, fare ${flight.fare.id}, seat ${seat.id}`);

    const payment = await post('/api/bookings', {
      flightId: flight.flightId, flightInstanceId: flight.id, fareId: flight.fare.id,
      cabinClass: fixture.cabinClass, ancillaryIds: [], mealIds: [],
      contactInfo: { email, phone: '+15555550123' },
      passengers: [{ firstName: 'Smoke', lastName: 'Test', email,
        phone: '+15555550123', dateOfBirth: '1990-01-01', gender: 'MALE',
        seatInstanceId: seat.id, nationality: 'Test' }],
    });
    assert(payment.success === true && payment.gateway === 'STRIPE' && payment.paymentId, 'Booking must initiate Stripe payment');
    // Despite its name, stripeCheckoutUrl contains the Checkout Session ID in this backend.
    const sessionId = payment.stripeCheckoutUrl;
    assert(/^cs_test_[\w]+$/.test(sessionId ?? ''), 'Backend must create a Stripe TEST Checkout Session');
    const checkout = new URL(payment.checkoutUrl);
    assert(checkout.protocol === 'https:' && checkout.hostname === 'checkout.stripe.com', 'Unexpected Stripe checkout URL');
    const sessionPath = `/v1/checkout/sessions/${sessionId}`;
    const readSession = () => request('https://api.stripe.com', sessionPath, { stripe: true });
    const initial = await readSession();
    assert(initial.livemode === false && initial.metadata?.payment_id === String(payment.paymentId)
      && initial.metadata?.user_id === String(login.user.id), 'Stripe session does not match this test payment/user');
    bookingId = Number(initial.metadata.booking_id);
    assert(Number.isSafeInteger(bookingId) && bookingId > 0, 'Missing Stripe booking metadata');
    const pending = await get(`/api/bookings/${bookingId}`);
    assert(pending.status === 'PENDING' && pending.id === bookingId, 'New booking must be pending');
    const held = (await get(cabinPath)).seats?.find(item => item.id === seat.id);
    assert(held?.status === 'RESERVED', 'Selected seat must be held before payment');
    log(`PASS pending booking ${bookingId} and RESERVED seat ${seat.id}`);
    log(`Open this Stripe TEST checkout URL in your browser:\n${checkout.href}`);
    log('Use card 4242 4242 4242 4242, any future expiry, and any 3-digit CVC. Waiting up to 10 minutes...');
    const paid = await until('Stripe test checkout paid', async () => {
      const session = await readSession();
      assert(session.livemode === false, 'Stripe session must remain in test mode');
      assert(session.status !== 'expired', 'Stripe checkout expired');
      return session.payment_status === 'paid' && session.payment_intent ? session : false;
    }, checkoutTimeoutMs);
    const intentId = typeof paid.payment_intent === 'string' ? paid.payment_intent : paid.payment_intent.id;
    const verified = await post('/api/payments/verify', { stripePaymentIntentId: intentId });
    assert(verified.status === 'SUCCESS' && verified.id === payment.paymentId && verified.bookingId === bookingId,
      'Verified payment must succeed for this booking');
    log('PASS backend payment verification');
    const confirmed = await until('CONFIRMED booking and passenger ticket', async () => {
      const booking = await get(`/api/bookings/${bookingId}`);
      assert(booking.status !== 'CANCELLED', 'Booking was cancelled while awaiting confirmation');
      return booking.status === 'CONFIRMED' && booking.paymentStatus === 'SUCCESS'
        && booking.passengers?.length === 1 && booking.tickets?.length === 1
        && booking.tickets[0].ticketNumber && booking.tickets[0].status === 'BOOKED' ? booking : false;
    });
    await until('BOOKED seat', async () => {
      const cabin = await get(cabinPath);
      return cabin.seats?.find(item => item.id === seat.id)?.status === 'BOOKED';
    });
    const history = await get('/api/bookings/user/history');
    assert(Array.isArray(history) && history.some(item => item.id === bookingId && item.status === 'CONFIRMED'),
      'Confirmed booking must appear in customer history');
    log('PASS customer booking history');
    await until('booking confirmation email in MailHog', async () => {
      const query = new URLSearchParams({ kind: 'containing', query: confirmed.bookingReference, limit: '100' });
      const messages = await request(mailhog, `/api/v2/search?${query}`);
      return messages.items?.some(message => {
        const headers = message.Content?.Headers ?? {};
        const recipients = message.To ?? [];
        const addressed = recipients.some(to => `${to.Mailbox}@${to.Domain}`.toLowerCase() === email.toLowerCase());
        return addressed && (headers.Subject ?? []).some(subject => {
          const decoded = decodeSubject(subject);
          return decoded.includes(confirmed.bookingReference) && decoded.includes('Booking Confirmed');
        });
      });
    });
    log(`SMOKE TEST PASSED — booking ${bookingId} (${confirmed.bookingReference}), seat ${seat.id}`);
    return { bookingId, bookingReference: confirmed.bookingReference, seatId: seat.id };
  } catch (error) {
    if (bookingId) log(`Test booking ${bookingId} remains in the local database; inspect its state before rerunning.`);
    throw error;
  }
}

async function main() {
  const args = process.argv.slice(2);
  if (args[0] === '--help' || args[0] === '-h') return console.log(HELP);
  assert(args.length === 1, HELP);
  const fixture = JSON.parse(await readFile(resolve(args[0]), 'utf8'));
  await runSmoke(fixture);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch(error => {
    console.error(`SMOKE TEST FAILED: ${error.message}`);
    process.exitCode = 1;
  });
}
