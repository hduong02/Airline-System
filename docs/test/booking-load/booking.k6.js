import http from 'k6/http';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import crypto from 'k6/crypto';
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';

const seats = new SharedArray('seats', () => JSON.parse(open(__ENV.SEATS_FILE)));
const accounts = new SharedArray('accounts', () => JSON.parse(open(__ENV.ACCOUNTS_FILE)));
const payments = new Counter('successful_payments');
export const options = {
  scenarios: { bookings: { executor: 'externally-controlled', vus: 0,
    maxVUs: Number(__ENV.MAX_VUS || 160), duration: __ENV.MAX_DURATION || '20m' } },
  summaryTrendStats: ['min', 'med', 'p(95)', 'p(99)', 'max'],
  // IDs belong in the event journal, never in high-cardinality metric tags.
  systemTags: ['status', 'method', 'name', 'scenario', 'error_code'],
};

function emit(type, value) { console.log(JSON.stringify({ type, ...value })); }
function uuid() {
  const bytes = new Uint8Array(crypto.randomBytes(16));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  const h = Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0,8)}-${h.slice(8,12)}-${h.slice(12,16)}-${h.slice(16,20)}-${h.slice(20)}`;
}
function request(path, body, token, key) {
  const started = Date.now();
  const response = http.post(__ENV.BASE_URL + path, JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`,
      ...(key ? { 'Idempotency-Key': key } : {}) },
    timeout: '30s', redirects: 0, tags: { name: path },
  });
  let value = {};
  let error = response.status >= 200 && response.status < 300 ? null : `HTTP ${response.status}`;
  try { value = response.json(); } catch (_) { error = error || 'Invalid JSON'; }
  if (path === '/api/payments/verify' && value?.status !== 'SUCCESS') {
    error = error || `Payment status: ${value?.status}`;
  }
  emit('request', { time: Date.now()/1000, started: started/1000, endpoint: path,
    method: 'POST', status: response.status, latency_ms: Date.now()-started, error,
    error_code: response.error_code || null });
  return { value, error, status: response.status };
}

export default function () {
  const index = exec.scenario.iterationInTest;
  if (index >= seats.length) {
    emit('fatal', { reason: 'Unique eligible seat inventory exhausted', time: Date.now()/1000 });
    // Supervisor pauses the executor and drains the other in-flight iterations.
    sleep(65);
    return;
  }
  const seat = seats[index];
  const account = accounts[(exec.vu.idInTest-1) % accounts.length];
  const flow = { attempt: uuid(), started: Date.now()/1000, seat_id: seat.seatInstanceId,
    measured: true, paid: false };
  // Journal before sending, so timed-out or interrupted requests can be reconciled.
  emit('attempt', flow);
  try {
    const person = { firstName: 'Load', lastName: 'Traveler', email: account.email,
      phone: '+66000000000', dateOfBirth: '1990-01-01', gender: 'MALE', nationality: 'TH',
      seatInstanceId: seat.seatInstanceId };
    const booking = request('/api/bookings', { flightId: seat.flightId,
      flightInstanceId: seat.flightInstanceId, fareId: seat.fareId, cabinClass: 'ECONOMY',
      ancillaryIds: [], mealIds: [], contactInfo: { email: account.email, phone: person.phone },
      passengers: [person] }, account.jwt, flow.attempt);
    if (booking.error) throw new Error(booking.error);
    flow.booking_id = booking.value.bookingId;
    const reference = /booking reference:\s*(\S+)/i.exec(booking.value.description || '');
    if (!flow.booking_id || !reference) throw new Error('Missing booking ID/reference');
    flow.reference = reference[1];
    emit('booking', flow);
    const payment = request('/api/payments/verify', { bookingId: flow.booking_id }, account.jwt);
    if (payment.error) throw new Error(payment.error);
    flow.paid = true;
    flow.paid_at = Date.now()/1000;
    payments.add(1);
  } catch (error) { flow.error = String(error); }
  flow.ended = Date.now()/1000;
  emit('flow', flow);
}
