"""Run on the VPS; reuse seeded travelers, measure gateway APIs, simulate three payments."""
import datetime as dt
import importlib.util
import json
from pathlib import Path
import statistics
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def main():
    spec = importlib.util.spec_from_file_location('remote', sys.argv[1])
    remote = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(remote)
    env = dict(e.split('=', 1) for e in remote.inspect('payment-service')['Config']['Env'])
    mail_env = dict(e.split('=', 1) for e in remote.inspect('notification-service')['Config']['Env'])
    assert env.get('SPRING_PROFILES_ACTIVE') == 'load-test'
    assert env.get('STRIPE_API_BASE_URL') == 'http://stripe-simulator:8080'
    assert env.get('STRIPE_API_KEY') == 'sk_test_simulator'
    assert mail_env.get('MAIL_HOST') == 'mailhog'
    fixtures = remote.fixtures()
    assert len(fixtures) >= 3, 'Need three available fixture seats'
    accounts = remote.accounts()
    report = {'started_utc': dt.datetime.now(dt.timezone.utc).isoformat(),
              'base': 'http://127.0.0.1:5000', 'payment': 'stripe-simulator',
              'requests': [], 'flows': []}
    token = None

    def request(label, method, path, body=None, predicate=lambda v: True, codes=(200,), measured=True):
        headers = {'Accept': 'application/json', 'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        if method == 'POST' and path == '/api/bookings':
            headers['Idempotency-Key'] = str(uuid.uuid4())
        started = time.perf_counter()
        req = urllib.request.Request(report['base'] + path, method=method, headers=headers,
                                     data=json.dumps(body).encode() if body is not None else None)
        try:
            with urllib.request.urlopen(req, timeout=30) as response:
                code, raw = response.status, response.read()
        except urllib.error.HTTPError as error:
            code, raw = error.code, error.read()
        elapsed = round((time.perf_counter() - started) * 1000, 2)
        value = json.loads(raw) if raw else {}
        passed = code in codes and bool(predicate(value))
        record = {'api': label, 'method': method, 'path': path, 'http': code,
                  'latency_ms': elapsed, 'pass': passed, 'primary_sample': measured}
        report['requests'].append(record)
        print(json.dumps(record), flush=True)
        assert passed, label + ': HTTP ' + str(code) + '; failed contract'
        return value

    try:
        for index, fixture in enumerate(fixtures[:3]):
            started = time.perf_counter()
            email = accounts['emails'][index]
            token = None
            auth = request('Login', 'POST', '/auth/login',
                           {'email': email, 'password': accounts['password']}, lambda v: bool(v.get('jwt')))
            token = auth['jwt']
            request('Profile', 'GET', '/api/users/profile', predicate=lambda v: v.get('email') == email)
            request('Airports', 'GET', '/api/airports?size=200', predicate=lambda v: bool(v))
            instance = request('Flight instance', 'GET', '/api/flight-instances/' + str(fixture['flightInstanceId']))
            query = urllib.parse.urlencode({'departureAirportId': instance['departureAirport']['id'],
                'arrivalAirportId': instance['arrivalAirport']['id'], 'departureDate': instance['departureDateTime'][:10],
                'passengers': 1, 'cabinClass': 'ECONOMY', 'size': 200})
            request('Flight search', 'GET', '/api/flights/search?' + query,
                    predicate=lambda v: any(x['id'] == fixture['flightInstanceId'] for x in v.get('content', [])))
            request('Flight detail', 'GET', '/api/flights/' + str(fixture['flightId']))
            fare = request('Fare detail', 'GET', '/api/fares/' + str(fixture['fareId']))
            seat_path = '/api/flight-instance-cabins/flight-instance/%s/cabin-class-type/ECONOMY' % fixture['flightInstanceId']
            request('Seat map', 'GET', seat_path, predicate=lambda v: any(
                x['id'] == fixture['seatInstanceId'] and x['status'] == 'AVAILABLE' for x in v.get('seats', [])))
            cabin_id = fare['cabinClassId']
            request('Ancillary catalogue', 'GET', '/api/flight-cabin-ancillaries/flight/%s/cabin/%s' % (fixture['flightId'], cabin_id))
            body = {k: fixture[k] for k in ['flightId', 'flightInstanceId', 'fareId']}
            body.update({'cabinClass': 'ECONOMY', 'ancillaryIds': [], 'mealIds': [],
                'contactInfo': {'email': email, 'phone': '+66000000000'},
                'passengers': [{'firstName': 'Smoke', 'lastName': 'Latency', 'email': email,
                    'phone': '+66000000000', 'dateOfBirth': '1990-01-01', 'gender': 'MALE',
                    'nationality': 'TH', 'seatInstanceId': fixture['seatInstanceId']}]})
            checkout_started = time.perf_counter()
            booking = request('Create booking / checkout', 'POST', '/api/bookings', body,
                              lambda v: bool(v.get('bookingId')) and bool(v.get('checkoutUrl') or v.get('stripeCheckoutUrl')), (201,))
            bid = booking['bookingId']
            flow = {'booking_id': bid, 'seat_id': fixture['seatInstanceId']}
            report['flows'].append(flow)
            request('Verify payment', 'POST', '/api/payments/verify', {'bookingId': bid}, lambda v: v.get('status') == 'SUCCESS')
            booking_path = '/api/bookings/' + str(bid)
            # Event consumers can finish after payment verification returns.
            for attempt in range(31):
                detail = request('Booking detail', 'GET', booking_path, measured=attempt == 0)
                if detail.get('status') == 'CONFIRMED' and detail.get('paymentStatus') == 'SUCCESS' and detail.get('tickets'):
                    break
                time.sleep(0.5)
            else:
                raise AssertionError('Booking confirmation/ticket timeout')
            flow['checkout_to_confirmed_ms'] = round((time.perf_counter() - checkout_started) * 1000, 2)
            request('Seat map after payment', 'GET', seat_path, predicate=lambda v: any(
                x['id'] == fixture['seatInstanceId'] and x['status'] == 'BOOKED' for x in v.get('seats', [])))
            request('Booking history', 'GET', '/api/bookings/user/history', predicate=lambda v: any(
                x['id'] == bid and x.get('status') == 'CONFIRMED' for x in v))
            request('Payment lookup', 'POST', '/api/payments/batch/bookings', [bid],
                    lambda v: v.get(str(bid), {}).get('status') == 'SUCCESS')
            for _ in range(20):
                with urllib.request.urlopen('http://127.0.0.1:8025/api/v2/messages?limit=100', timeout=30) as response:
                    mail = json.load(response)
                matches = [m for m in mail.get('items', []) if email in json.dumps(m) and detail['bookingReference'] in json.dumps(m)]
                if matches:
                    break
                time.sleep(0.5)
            assert len(matches) == 1, 'Expected one confirmation email'
            flow.update({'pass': True, 'tickets': len(detail['tickets']), 'confirmation_emails': len(matches),
                         'total_flow_ms': round((time.perf_counter() - started) * 1000, 2)})
            print(json.dumps({'flow': flow}), flush=True)
    except Exception as error:
        report['error'] = type(error).__name__ + ': ' + str(error)
        raise
    finally:
        report['summary'] = []
        for label in dict.fromkeys(r['api'] for r in report['requests']):
            records = [r for r in report['requests'] if r['api'] == label and r['primary_sample']]
            values = [r['latency_ms'] for r in records]
            report['summary'].append({'api': label, 'samples': len(values), 'median_ms': round(statistics.median(values), 2),
                'min_ms': min(values), 'max_ms': max(values), 'pass': all(r['pass'] for r in records)})
        output = Path(sys.argv[2])
        output.write_text(json.dumps(report, indent=2))
        print('REPORT ' + json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
