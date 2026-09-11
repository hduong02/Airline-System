"""Seed the agreed Oct 15--Dec 31 2026 dataset on the Docker host.

Preview is read-only. --apply backs up empty databases and pauses application
writers; interrupted runs stay paused and may resume from the same directory.
--verify performs database checks; --smoke tests logins/search without bookings.
Requires Python 3.9+, Docker access, and bcrypt (only when applying/verifying).
"""
import argparse
import datetime as dt
import hashlib
import itertools
import json
import math
import os
from pathlib import Path
import secrets
import subprocess
import urllib.parse
import urllib.request

START = dt.date(2026, 10, 15)
END = dt.date(2026, 12, 31)
DAYS = (END - START).days + 1
VERSION = 1
# Country, airline name, IATA, ICAO, alliance, city/airport records.
# Airport coordinates are rounded reference coordinates, sufficient for estimates.
COUNTRIES = [
    ('VN', 'Vietnam', 'Vietnam Airlines', 'VN', 'HVN', 'SkyTeam', [
        ('Hanoi', 'HAN', 'Noi Bai International Airport', 'Asia/Ho_Chi_Minh', 21.2212, 105.807),
        ('Ho Chi Minh City', 'SGN', 'Tan Son Nhat International Airport', 'Asia/Ho_Chi_Minh', 10.8188, 106.652)]),
    ('KR', 'South Korea', 'Korean Air', 'KE', 'KAL', 'SkyTeam', [
        ('Seoul', 'ICN', 'Incheon International Airport', 'Asia/Seoul', 37.4691, 126.451),
        ('Busan', 'PUS', 'Gimhae International Airport', 'Asia/Seoul', 35.1795, 128.938)]),
    ('JP', 'Japan', 'Japan Airlines', 'JL', 'JAL', 'oneworld', [
        ('Tokyo', 'HND', 'Tokyo Haneda Airport', 'Asia/Tokyo', 35.5523, 139.78),
        ('Osaka', 'KIX', 'Kansai International Airport', 'Asia/Tokyo', 34.4273, 135.244)]),
    ('AU', 'Australia', 'Qantas', 'QF', 'QFA', 'oneworld', [
        ('Sydney', 'SYD', 'Sydney Kingsford Smith Airport', 'Australia/Sydney', -33.9461, 151.177),
        ('Melbourne', 'MEL', 'Melbourne Airport', 'Australia/Melbourne', -37.6733, 144.843)]),
    ('US', 'United States', 'American Airlines', 'AA', 'AAL', 'oneworld', [
        ('New York', 'JFK', 'John F. Kennedy International Airport', 'America/New_York', 40.6398, -73.7789),
        ('Los Angeles', 'LAX', 'Los Angeles International Airport', 'America/Los_Angeles', 33.9425, -118.408)]),
    ('GB', 'United Kingdom', 'British Airways', 'BA', 'BAW', 'oneworld', [
        ('London', 'LHR', 'London Heathrow Airport', 'Europe/London', 51.4706, -0.461941),
        ('Manchester', 'MAN', 'Manchester Airport', 'Europe/London', 53.3537, -2.27495)]),
    ('FR', 'France', 'Air France', 'AF', 'AFR', 'SkyTeam', [
        ('Paris', 'CDG', 'Charles de Gaulle Airport', 'Europe/Paris', 49.0128, 2.55),
        ('Lyon', 'LYS', 'Lyon Saint-Exupery Airport', 'Europe/Paris', 45.7256, 5.08111)]),
    ('AE', 'United Arab Emirates', 'Emirates', 'EK', 'UAE', None, [
        ('Dubai', 'DXB', 'Dubai International Airport', 'Asia/Dubai', 25.2528, 55.3644),
        ('Abu Dhabi', 'AUH', 'Zayed International Airport', 'Asia/Dubai', 24.433, 54.6511)]),
    ('SG', 'Singapore', 'Singapore Airlines', 'SQ', 'SIA', 'Star Alliance', [
        ('Singapore', 'SIN', 'Singapore Changi Airport', 'Asia/Singapore', 1.35019, 103.994)]),
]
PUBLISHED = {
    ('HAN', 'SGN'): (120, 'https://www.vietnamairlines.com/en-vn/flights-from-hanoi-to-ho-chi-minh-city'),
    ('SYD', 'MEL'): (95, 'https://www.qantas.com/en-au/book/flight-deals/from-sydney-to-melbourne'),
    ('SIN', 'JFK'): (1105, 'https://www.singaporeair.com/sg/en/plan-travel/destinations/flights-from-singapore-to-new-york/'),
}
TABLES = {
    'locationdb': {'city': 17, 'airport': 17},
    'userdb': {'users': 50010},
    'airlinedb': {'airline': 9, 'aircraft': 272},
    'seatdb': {'cabin_class': 544, 'seat_map': 544, 'seats': 32640,
               'flight_instance_cabin': 42432, 'seat_instance': 2545920},
    'flightdb': {'flight': 272, 'flight_schedule': 272,
                 'flight_schedule_operating_days': 1904, 'flight_instance': 21216},
    'pricingdb': {'fare': 544, 'fare_rules': 544, 'baggage_policy': 544},
}
ORDER = [(c, t) for c, tables in TABLES.items() for t in tables]
# Inventory needs physical seats first; instances precede their inventory.
ORDER.remove(('seatdb', 'flight_instance_cabin'))
ORDER.remove(('seatdb', 'seat_instance'))
ORDER += [('seatdb', 'flight_instance_cabin'), ('seatdb', 'seat_instance')]
ALL_DBS = list(TABLES) + ['bookingdb', 'paymentdb', 'ancillarydb']


def mysql_command(container):
    return ['docker', 'exec', '-i', container, 'sh', '-c',
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 '
            '-uroot "$MYSQL_DATABASE" --batch --raw --skip-column-names']


def query(container, sql):
    p = subprocess.run(mysql_command(container), input=sql, text=True,
                       encoding='utf-8', capture_output=True, check=True)
    return [line.split('\t') for line in p.stdout.splitlines()]


def scalar(container, sql):
    return int(query(container, sql)[0][0])


def literal(value):
    if value is None:
        return 'NULL'
    if isinstance(value, bool):
        return '1' if value else '0'
    if isinstance(value, (int, float)):
        return str(value)
    if isinstance(value, (dt.date, dt.datetime, dt.time)):
        value = value.isoformat(sep=' ') if isinstance(value, dt.datetime) else value.isoformat()
    return "CONVERT(X'" + str(value).encode('utf-8').hex() + "' USING utf8mb4)"


def save(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False), encoding='utf-8')
    os.chmod(temporary, 0o600)
    temporary.replace(path)


def distance(a, b):
    lat1, lat2 = math.radians(a['lat']), math.radians(b['lat'])
    delta_lat = lat2 - lat1
    delta_lon = math.radians(b['lon'] - a['lon'])
    h = math.sin(delta_lat / 2)**2 + math.cos(lat1)*math.cos(lat2)*math.sin(delta_lon / 2)**2
    return 6371 * 2 * math.asin(math.sqrt(min(1, h)))


def dataset():
    airports, airlines, routes = [], [], []
    for ai, (code, country, name, iata, icao, alliance, cities) in enumerate(COUNTRIES, 1):
        home = []
        for city, airport, airport_name, zone, lat, lon in cities:
            entry = dict(id=len(airports)+1, city=city, code=airport, name=airport_name,
                         zone=zone, lat=lat, lon=lon, country_code=code, country=country)
            airports.append(entry)
            home.append(entry['id'])
        airlines.append(dict(id=ai, name=name, iata=iata, icao=icao, alliance=alliance,
                             country=country, homes=home, owner=50000+ai))
    for airline in airlines:
        for origin in airline['homes']:
            a = airports[origin-1]
            for b in airports:
                if origin == b['id']:
                    continue
                km = distance(a, b)
                published = PUBLISHED.get((a['code'], b['code']))
                minutes = published[0] if published else int(round((45+km/850*60)/5)*5)
                ri = len(routes)+1
                base = round(45+km*.055, 2)
                routes.append(dict(id=ri, airline=airline['id'], origin=origin,
                                   destination=b['id'], origin_code=a['code'], destination_code=b['code'],
                                   number=f"{airline['iata']}{1000+ri}", km=round(km, 6),
                                   minutes=minutes, source=published[1] if published else 'great-circle estimate: 45 + km/850*60; rounded to 5 minutes',
                                   hour=8+(ri-1)%12, economy=base, business=round(base*2.5, 2)))
    assert len(routes) == 272 and DAYS == 78
    return dict(version=VERSION, start=str(START), end=str(END), days=DAYS, time_basis='UTC',
                seats=120, economy_seats=108, business_seats=12, airports=airports,
                airlines=airlines, routes=routes, targets=TABLES)


class Seeder:
    def __init__(self, directory):
        self.directory = directory
        self.plan = dataset()
        self.schemas = {}
        for c in ALL_DBS:
            rows = query(c, 'SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE '
                         'FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() '
                         'ORDER BY TABLE_NAME,ORDINAL_POSITION;')
            for table, column, kind, nullable in rows:
                self.schemas.setdefault((c, table), []).append((column, kind, nullable))
        for key in ORDER:
            if key not in self.schemas:
                raise ValueError(f'Missing table: {key}')
        self.fingerprint = hashlib.sha256(json.dumps(list(self.schemas.items()), sort_keys=True).encode()).hexdigest()
        self.manifest_path = directory / 'manifest.json'
        self.manifest = json.loads(self.manifest_path.read_text()) if self.manifest_path.exists() else None
        if self.manifest:
            if self.manifest['dataset'] != self.plan or self.manifest['schema_hash'] != self.fingerprint:
                raise ValueError('Manifest or deployed schema differs; refusing resume')
        self.now = dt.datetime.fromisoformat(self.manifest['created_at']) if self.manifest else dt.datetime.now(dt.timezone.utc).replace(tzinfo=None, microsecond=0)
        self.credentials = None

    def counts(self):
        return {f'{c}.{t}': scalar(c, f'SELECT COUNT(*) FROM `{t}`;') for c, t in self.schemas if t not in ('hibernate_sequence',)}

    def load_credentials(self, create=False):
        import bcrypt
        path = self.directory / 'credentials.json'
        if not path.exists():
            if not create:
                raise ValueError('Missing credentials file')
            credentials = []
            for label, email in [('travelers', 'seed.traveler.00001@example.invalid')] + [
                    (f'airline_{a[0].lower()}', f'seed.owner.{a[0].lower()}@example.invalid') for a in COUNTRIES] + [
                    ('system_admin', 'admin@gmail.com')]:
                password = secrets.token_urlsafe(24)
                credentials.append(dict(label=label, email=email, password=password,
                                        password_hash=bcrypt.hashpw(password.encode(), bcrypt.gensalt(rounds=10, prefix=b'2a')).decode()))
            save(path, credentials)
        self.credentials = json.loads(path.read_text())
        for credential in self.credentials:
            if not bcrypt.checkpw(credential['password'].encode(), credential['password_hash'].encode()):
                raise ValueError('Invalid stored credentials')

    def rows(self, table):
        if table == 'city':
            for a in self.plan['airports']:
                yield dict(id=a['id'], name=a['city'], city_code=a['code'], country_code=a['country_code'],
                           country_name=a['country'], region_code='England' if a['country_code']=='GB' else None,
                           time_zone_id=a['zone'])
        elif table == 'airport':
            for a in self.plan['airports']:
                yield dict(id=a['id'], city_id=a['id'], iata_code=a['code'], name=a['name'],
                           latitude=a['lat'], longitude=a['lon'], time_zone=a['zone'])
        elif table == 'users':
            first = ['Alex', 'Minh', 'Yuna', 'Hana', 'Linh', 'Sam', 'Emma', 'Noah', 'Leo', 'Mia']
            last = ['Nguyen', 'Kim', 'Tanaka', 'Wilson', 'Smith', 'Brown', 'Martin', 'Ahmed', 'Lee', 'Taylor']
            for i in range(1, 50011):
                ci = 0 if i <= 50000 else i-50000
                cred = self.credentials[ci]
                yield dict(id=i, full_name=f'{first[(i-1)%10]} {last[((i-1)//10)%10]} {i:05d}' if i<=50000 else cred['label'].replace('_', ' ').title(),
                           email=f'seed.traveler.{i:05d}@example.invalid' if i<=50000 else cred['email'],
                           phone=None, password=cred['password_hash'], role=1 if i<=50000 else (2 if i<=50009 else 0),
                           created_at=self.now, updated_at=self.now, last_login=None)
        elif table == 'airline':
            for a in self.plan['airlines']:
                yield dict(id=a['id'], name=a['name'], iata_code=a['iata'], icao_code=a['icao'],
                           country=a['country'], alliance=a['alliance'], owner_id=a['owner'],
                           headquarters_city_id=a['homes'][0], status='ACTIVE', updated_by_id=50010,
                           email=self.credentials[a['id']]['email'])
        elif table == 'aircraft':
            for r in self.plan['routes']:
                yield dict(id=r['id'], airline_id=r['airline'], code=f"SEED-{r['id']:04d}",
                           model='Synthetic 120-seat aircraft', manufacturer='Seed Fleet', seating_capacity=120,
                           economy_seats=108, business_seats=12, premium_economy_seats=0, first_class_seats=0,
                           range_km=max(2000, math.ceil(r['km']/1000)*1000), cruising_speed_kmh=850,
                           status='ACTIVE', is_available=True, current_airport_id=r['origin'],
                           next_maintenance_date=dt.date(2027, 12, 31), registration_date=START)
        elif table in ('cabin_class', 'seat_map'):
            for r in self.plan['routes']:
                for cabin in (0, 1):
                    identifier=(r['id']-1)*2+cabin+1
                    if table == 'cabin_class':
                        yield dict(id=identifier, aircraft_id=r['id'], name='BUSINESS' if cabin else 'ECONOMY',
                                   code=f'SEED-{identifier:04d}', display_order=0 if cabin else 1,
                                   is_active=True, is_bookable=True, typical_seat_pitch=38 if cabin else 31,
                                   typical_seat_width=21 if cabin else 18, seat_type='RECLINER' if cabin else 'STANDARD')
                    else:
                        yield dict(id=identifier, cabin_class_id=identifier, airline_id=r['airline'],
                                   name=f'Seed {identifier} '+('Business' if cabin else 'Economy'),
                                   total_rows=3 if cabin else 18, left_seats_per_row=2 if cabin else 3,
                                   right_seats_per_row=2 if cabin else 3)
        elif table == 'seats':
            for r in self.plan['routes']:
                for position in range(120):
                    business=position<12
                    offset=position if business else position-12
                    width=4 if business else 6
                    letters='ACDF' if business else 'ABCDEF'
                    row=offset//width+(1 if business else 4)
                    col=letters[offset%width]
                    cabin=(r['id']-1)*2+int(business)+1
                    yield dict(id=(r['id']-1)*120+position+1, seat_number=f'{row}{col}', seat_row=row,
                               column_letter=col, seat_type='WINDOW' if col in 'AF' else ('AISLE' if col in 'CD' else 'MIDDLE'),
                               cabin_class_id=cabin, seat_map_id=cabin, base_price=0, premium_surcharge=0,
                               is_active=True, is_available=True, is_blocked=False,
                               seat_pitch=38 if business else 31, seat_width=21 if business else 18,
                               has_power_outlet=True, has_tv_screen=business, has_extra_width=business,
                               has_extra_legroom=business, version=0)
        elif table in ('flight', 'flight_schedule', 'flight_schedule_operating_days', 'flight_instance'):
            for r in self.plan['routes']:
                departure=dt.datetime.combine(START, dt.time(r['hour']))
                arrival=departure+dt.timedelta(minutes=r['minutes'])
                common=dict(departure_airport_id=r['origin'], arrival_airport_id=r['destination'])
                if table == 'flight':
                    yield dict(id=r['id'], flight_number=r['number'], airline_id=r['airline'],
                               aircraft_id=r['id'], status='SCHEDULED', **common)
                elif table == 'flight_schedule':
                    yield dict(id=r['id'], flight_id=r['id'], start_date=START, end_date=END,
                               departure_time=departure.time(), arrival_time=arrival.time(), is_active=True, **common)
                elif table == 'flight_schedule_operating_days':
                    for weekday in ('MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY'):
                        yield dict(flight_schedule_id=r['id'], operating_days=weekday)
                else:
                    for day in range(DAYS):
                        yield dict(id=(r['id']-1)*DAYS+day+1, flight_id=r['id'], schedule_id=r['id'],
                                   airline_id=r['airline'], departure_date_time=departure+dt.timedelta(days=day),
                                   arrival_date_time=arrival+dt.timedelta(days=day), total_seats=120, available_seats=120,
                                   status='SCHEDULED', is_active=True, min_advance_booking_days=0,
                                   max_advance_booking_days=365, **common)
        elif table in ('fare', 'fare_rules', 'baggage_policy'):
            for r in self.plan['routes']:
                for cabin in (0, 1):
                    identifier=(r['id']-1)*2+cabin+1
                    base=r['business'] if cabin else r['economy']
                    if table == 'fare':
                        yield dict(id=identifier, flight_id=r['id'], cabin_class_id=identifier,
                                   cabin_class='BUSINESS' if cabin else 'ECONOMY', name='Seed Business' if cabin else 'Seed Economy',
                                   rbd_code='J' if cabin else 'Y', base_fare=base, taxes_and_fees=25, airline_fees=5,
                                   current_price=round(base+30, 2), fare_label='Business' if cabin else 'Economy',
                                   advance_seat_selection=True, complimentary_meals=True, complimentary_beverages=True,
                                   priority_boarding=bool(cabin), priority_checkin=bool(cabin), fast_track_security=bool(cabin),
                                   lounge_access=bool(cabin), premium_meal_choice=bool(cabin), full_refund=bool(cabin),
                                   free_date_change=bool(cabin), extra_seat_space=bool(cabin), guaranteed_seat_together=False,
                                   preferred_seat_choice=True)
                    elif table == 'fare_rules':
                        yield dict(id=identifier, fare_id=identifier, airline_id=r['airline'], rule_name='Seed Standard',
                                   is_refundable=bool(cabin), is_changeable=True, change_fee=0 if cabin else 30,
                                   cancellation_fee=0 if cabin else 50, refund_deadline_days=1, change_deadline_hours=24)
                    else:
                        yield dict(id=identifier, fare_id=identifier, airline_id=r['airline'], name='Seed baggage policy',
                                   cabin_baggage_pieces=1, cabin_baggage_max_weight=7, cabin_baggage_weight_per_piece=7,
                                   check_in_baggage_pieces=2 if cabin else 1, check_in_baggage_weight_per_piece=23,
                                   check_in_baggage_max_weight=46 if cabin else 23, free_checked_bags_allowance=2 if cabin else 1,
                                   priority_baggage=bool(cabin), extra_baggage_allowance=bool(cabin))
        elif table in ('flight_instance_cabin', 'seat_instance'):
            for r in self.plan['routes']:
                for day in range(DAYS):
                    instance=(r['id']-1)*DAYS+day+1
                    if table == 'flight_instance_cabin':
                        for cabin in (0,1):
                            yield dict(id=(instance-1)*2+cabin+1, flight_instance_id=instance,
                                       cabin_class_id=(r['id']-1)*2+cabin+1, total_seats=12 if cabin else 108, booked_seats=0)
                    else:
                        for position in range(120):
                            business=position<12
                            base=r['business'] if business else r['economy']
                            yield dict(id=(instance-1)*120+position+1, flight_id=r['id'], flight_schedule_id=r['id'],
                                       flight_instance_id=instance, flight_instance_cabin_id=(instance-1)*2+int(business)+1,
                                       seat_id=(r['id']-1)*120+position+1, booking_id=None, status='AVAILABLE',
                                       is_booked=False, is_available=True, fare=base+30, premium_surcharge=0, version=0)
        else:
            raise ValueError(table)

    def fill(self, c, table, raw):
        schema=self.schemas[(c, table)]
        unknown=set(raw)-{col for col, _, _ in schema}
        if unknown:
            raise ValueError(f'Unknown fields {table}: {unknown}')
        row={}
        for column, kind, nullable in schema:
            if column in raw:
                value=raw[column]
            elif column in ('created_at', 'updated_at'):
                value=self.now
            elif kind.startswith('bit'):
                value=False
            elif column=='version':
                value=0
            elif nullable=='YES':
                value=None
            else:
                raise ValueError(f'Missing required field {table}.{column}')
            if value is None and nullable=='NO':
                raise ValueError(f'Null required field {table}.{column}')
            row[column]=value
        return row

    def preflight(self):
        for c, table in ORDER:
            raw=next(self.rows(table))
            self.fill(c, table, raw)

    def check_existing(self, c, table, expected, count):
        """Compare every existing record against the regenerated deterministic prefix."""
        schema=self.schemas[(c,table)]
        columns=[col for col, _, _ in schema]
        selects=[f'CAST(`{col}` AS UNSIGNED)' if kind.startswith('bit') else f'`{col}`'
                 for col, kind, _ in schema]
        order='id' if 'id' in columns else 'flight_schedule_id,operating_days'
        if 'id' not in columns:
            # Collection rows are inserted in one transaction, sorted for comparison.
            if count not in (0,expected):
                raise ValueError(f'Partial collection table {table}')
            if not count:
                return
            rows=sorted((self.fill(c,table,r) for r in self.rows(table)),
                        key=lambda r:(r['flight_schedule_id'],r['operating_days']))
            generator=iter(rows)
        else:
            generator=(self.fill(c,table,r) for r in self.rows(table))
            bounds=query(c,f'SELECT COALESCE(MIN(id),0),COALESCE(MAX(id),0) FROM `{table}`;')[0]
            if count and tuple(map(int,bounds))!=(1,count):
                raise ValueError(f'Noncontiguous IDs in {table}')
        for offset in range(0,count,10000):
            limit=min(10000,count-offset)
            predicate=f' WHERE id>{offset} AND id<={offset+limit}' if 'id' in columns else ''
            actual=query(c,f'SELECT {",".join(selects)} FROM `{table}`{predicate} ORDER BY {order}'+('' if 'id' in columns else f' LIMIT {limit} OFFSET {offset}')+';')
            if len(actual)!=limit:
                raise ValueError(f'Existing row count changed in {table}')
            for values in actual:
                row=next(generator)
                for (col,kind,_),value in zip(schema,values):
                    want=row[col]
                    if want is None:
                        good=value=='NULL'
                    elif kind.startswith(('double','float','decimal')):
                        good=math.isclose(float(value),float(want),rel_tol=1e-10,abs_tol=1e-8)
                    elif kind.startswith(('bit','tinyint','bigint','int')):
                        good=int(value)==int(want)
                    elif kind.startswith('datetime'):
                        good=dt.datetime.fromisoformat(value)==want
                    elif kind=='date':
                        good=dt.date.fromisoformat(value)==want
                    elif kind.startswith('time'):
                        good=dt.time.fromisoformat(value)==want
                    else:
                        good=value==str(want)
                    if not good:
                        raise ValueError(f'Existing data differs in {c}.{table}.{col}, row {row.get("id",offset)}')
            if count>100000 and (offset+limit)%100000==0:
                print(f'Resume validated: {table} {offset+limit}/{count}',flush=True)

    def insert(self,c,table,expected):
        count=scalar(c,f'SELECT COUNT(*) FROM `{table}`;')
        if count>expected:
            raise ValueError(f'Too many rows in {table}')
        if count:
            self.check_existing(c,table,expected,count)
        if count==expected:
            print('Already complete:',table,count,flush=True)
            return
        columns=[col for col,_,_ in self.schemas[(c,table)]]
        generator=itertools.islice(self.rows(table),count,None)
        with (self.directory/'mysql-errors.log').open('ab') as errors:
            process=subprocess.Popen(mysql_command(c),stdin=subprocess.PIPE,stdout=subprocess.DEVNULL,
                                     stderr=errors,text=True,encoding='utf-8')
            loaded=count
            collection='id' not in columns
            try:
                process.stdin.write("SET time_zone='+00:00';\n")
                if collection:
                    process.stdin.write('START TRANSACTION;\n')
                while True:
                    batch=list(itertools.islice(generator,1000))
                    if not batch:
                        break
                    values=[]
                    for raw in batch:
                        row=self.fill(c,table,raw)
                        values.append('('+','.join(literal(row[col]) for col in columns)+')')
                    sql='INSERT INTO `'+table+'` ('+','.join('`'+col+'`' for col in columns)+') VALUES\n'+',\n'.join(values)+';\n'
                    process.stdin.write(sql if collection else 'START TRANSACTION;\n'+sql+'COMMIT;\n')
                    process.stdin.flush()
                    loaded+=len(batch)
                    if loaded%25000==0:
                        print(f'{table}: {loaded}/{expected}',flush=True)
                if collection:
                    process.stdin.write('COMMIT;\n')
                process.stdin.close()
                if process.wait()!=0:
                    raise RuntimeError('MySQL failed; inspect mysql-errors.log')
            finally:
                if not process.stdin.closed:
                    process.stdin.close()
                process.wait()
        actual=scalar(c,f'SELECT COUNT(*) FROM `{table}`;')
        if actual!=expected or loaded!=expected:
            raise ValueError(f'{table}: loaded {actual}, expected {expected}')
        print('Loaded:',table,actual,flush=True)
        self.manifest.setdefault('completed_tables',[]).append(f'{c}.{table}')
        save(self.manifest_path,self.manifest)

    def backup(self):
        for c in ALL_DBS:
            path=self.directory/f'{c}-before.sql'
            if path.exists() and path.stat().st_size>0:
                continue
            temporary=path.with_suffix('.partial')
            with temporary.open('wb') as out:
                subprocess.run(['docker','exec',c,'sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --no-tablespaces "$MYSQL_DATABASE"'],
                               stdout=out,stderr=subprocess.PIPE,check=True)
            os.chmod(temporary,0o600)
            temporary.replace(path)
        print('Backups complete',flush=True)

    def initial_data_ok(self):
        """Allow only the exact known startup admin recreated by user-service."""
        import bcrypt
        counts=self.counts()
        if any(n for key,n in counts.items() if key!='userdb.users'):
            raise ValueError(f'Target databases must be empty: {counts}')
        if counts['userdb.users']==0:
            return False
        rows=query('userdb','SELECT id,email,role,full_name,phone,created_at,updated_at,last_login,password FROM users;')
        if len(rows)!=1 or rows[0][:8]!=['1','admin@gmail.com','0','admin','NULL','NULL','NULL','NULL']:
            raise ValueError('Unexpected existing user data; refusing seed')
        if not bcrypt.checkpw(b'admin',rows[0][8].encode()):
            raise ValueError('Startup admin was modified; refusing seed')
        return True

    def apply(self):
        if self.manifest and self.manifest.get('status')=='complete':
            self.load_credentials()
            self.verify()
            return
        if not self.manifest:
            self.initial_data_ok()
            self.directory.mkdir(parents=True,mode=0o700,exist_ok=True)
            os.chmod(self.directory,0o700)
            services=subprocess.check_output(['docker','ps','--format','{{.Names}}'],text=True).splitlines()
            services=[s for s in services if s.endswith('-service') or s=='api-gateway']
            self.manifest=dict(dataset=self.plan,schema_hash=self.fingerprint,created_at=self.now.isoformat(),
                               status='prepared',services=services,completed_tables=[])
            save(self.manifest_path,self.manifest)
        self.load_credentials(create=True)
        self.preflight()
        services=self.manifest['services']
        if services:
            subprocess.run(['docker','stop',*services],check=True)
        if self.manifest['status']=='prepared':
            bootstrap=self.initial_data_ok()
            self.backup()
            self.manifest['status']='loading'
            save(self.manifest_path,self.manifest)
            if bootstrap:
                query('userdb',"DELETE FROM users WHERE id=1 AND email='admin@gmail.com' AND role=0;")
        # On failure deliberately leave writers stopped, so deterministic resume is safe.
        for c,table in ORDER:
            self.insert(c,table,TABLES[c][table])
        self.verify()
        subprocess.run(['docker','exec','redis','redis-cli','FLUSHALL'],check=True)
        if services:
            subprocess.run(['docker','start',*services],check=True)
        self.manifest['status']='complete'
        self.manifest['completed_at']=dt.datetime.now(dt.timezone.utc).isoformat()
        save(self.manifest_path,self.manifest)
        print('Seed complete; credentials:',self.directory/'credentials.json',flush=True)

    def verify(self):
        self.load_credentials()
        results={}
        def check(name,c,sql,expected=0):
            actual=scalar(c,sql)
            results[name]=actual
            if actual!=expected:
                raise ValueError(f'{name}: {actual}, expected {expected}')
        for c,table in ORDER:
            check(f'{c}.{table}',c,f'SELECT COUNT(*) FROM `{table}`;',TABLES[c][table])
        for c,table in self.schemas:
            if table not in TABLES.get(c,{}):
                check(f'empty:{c}.{table}',c,f'SELECT COUNT(*) FROM `{table}`;')
        check('travelers','userdb','SELECT COUNT(*) FROM users WHERE role=1;',50000)
        check('owners','userdb','SELECT COUNT(*) FROM users WHERE role=2;',9)
        check('admins','userdb','SELECT COUNT(*) FROM users WHERE role=0;',1)
        check('unique_emails','userdb','SELECT COUNT(DISTINCT email) FROM users;',50010)
        check('instance_dates','flightdb',"SELECT COUNT(*) FROM flight_instance WHERE DATE(departure_date_time)<'2026-10-15' OR DATE(departure_date_time)>'2026-12-31' OR arrival_date_time<=departure_date_time OR total_seats<>120 OR available_seats<>120 OR is_active<>1 OR status<>'SCHEDULED';")
        check('daily_routes','flightdb','SELECT COUNT(*) FROM (SELECT flight_id FROM flight_instance GROUP BY flight_id HAVING COUNT(*)<>78 OR COUNT(DISTINCT DATE(departure_date_time))<>78) t;')
        check('duplicate_routes','flightdb','SELECT COUNT(*) FROM (SELECT departure_airport_id,arrival_airport_id FROM flight GROUP BY departure_airport_id,arrival_airport_id HAVING COUNT(*)<>1) t;')
        check('self_routes','flightdb','SELECT COUNT(*) FROM flight WHERE departure_airport_id=arrival_airport_id;')
        check('schedules','flightdb',"SELECT COUNT(*) FROM flight_schedule WHERE start_date<>'2026-10-15' OR end_date<>'2026-12-31' OR is_active<>1;")
        check('weekdays','flightdb','SELECT COUNT(*) FROM (SELECT flight_schedule_id FROM flight_schedule_operating_days GROUP BY flight_schedule_id HAVING COUNT(DISTINCT operating_days)<>7) t;')
        check('inventory','seatdb',"SELECT COUNT(*) FROM seat_instance WHERE status<>'AVAILABLE' OR is_booked<>0 OR is_available<>1 OR booking_id IS NOT NULL OR version<>0;")
        check('cabin_inventory','seatdb','SELECT COUNT(*) FROM flight_instance_cabin c LEFT JOIN (SELECT flight_instance_cabin_id,COUNT(*) n FROM seat_instance GROUP BY flight_instance_cabin_id) s ON s.flight_instance_cabin_id=c.id WHERE s.n IS NULL OR c.total_seats<>s.n OR c.booked_seats<>0;')
        check('instance_inventory','seatdb','SELECT COUNT(*) FROM (SELECT flight_instance_id FROM seat_instance GROUP BY flight_instance_id HAVING COUNT(*)<>120 OR COUNT(DISTINCT seat_id)<>120) t;')
        check('physical_links','seatdb','SELECT COUNT(*) FROM seat_instance si LEFT JOIN seats s ON s.id=si.seat_id LEFT JOIN flight_instance_cabin c ON c.id=si.flight_instance_cabin_id WHERE s.id IS NULL OR c.id IS NULL OR s.cabin_class_id<>c.cabin_class_id OR si.flight_instance_id<>c.flight_instance_id;')
        check('cabins','seatdb',"SELECT COUNT(*) FROM flight_instance_cabin i JOIN cabin_class c ON c.id=i.cabin_class_id WHERE i.total_seats<>IF(c.name='BUSINESS',12,108);")
        check('fares','pricingdb','SELECT COUNT(*) FROM fare WHERE ABS(current_price-base_fare-taxes_and_fees-airline_fees)>0.000001 OR advance_seat_selection IS NULL OR extra_seat_space IS NULL OR preferred_seat_choice IS NULL OR guaranteed_seat_together IS NULL OR free_date_change IS NULL;')
        check('fare_links','pricingdb','SELECT COUNT(*) FROM fare f LEFT JOIN fare_rules r ON r.fare_id=f.id LEFT JOIN baggage_policy b ON b.fare_id=f.id WHERE r.id IS NULL OR b.id IS NULL;')
        # Exact route metadata and elapsed duration for every instance.
        flights=query('flightdb','SELECT id,airline_id,aircraft_id,departure_airport_id,arrival_airport_id FROM flight ORDER BY id;')
        for row,r in zip(flights,self.plan['routes']):
            if list(map(int,row))!=[r['id'],r['airline'],r['id'],r['origin'],r['destination']]:
                raise ValueError('Route references differ from manifest')
        cases='CASE flight_id '+' '.join(f"WHEN {r['id']} THEN {r['minutes']}" for r in self.plan['routes'])+' END'
        check('durations','flightdb',f'SELECT COUNT(*) FROM flight_instance WHERE TIMESTAMPDIFF(MINUTE,departure_date_time,arrival_date_time)<>{cases};')
        check('instance_refs','flightdb','SELECT COUNT(*) FROM flight_instance i LEFT JOIN flight f ON f.id=i.flight_id LEFT JOIN flight_schedule s ON s.id=i.schedule_id WHERE f.id IS NULL OR s.id IS NULL OR s.flight_id<>i.flight_id OR i.airline_id<>f.airline_id OR i.departure_airport_id<>f.departure_airport_id OR i.arrival_airport_id<>f.arrival_airport_id;')
        for c,table,cols,expected_rows in [
                ('airlinedb','airline','id,owner_id,headquarters_city_id',[[a['id'],a['owner'],a['homes'][0]] for a in self.plan['airlines']]),
                ('airlinedb','aircraft','id,airline_id,seating_capacity',[[r['id'],r['airline'],120] for r in self.plan['routes']]),
                ('seatdb','cabin_class','id,aircraft_id',[[i,(i-1)//2+1] for i in range(1,545)]),
                ('pricingdb','fare','id,flight_id,cabin_class_id',[[i,(i-1)//2+1,i] for i in range(1,545)])]:
            if [list(map(int,row)) for row in query(c,f'SELECT {cols} FROM `{table}` ORDER BY id;')]!=expected_rows:
                raise ValueError(f'Cross-database references failed: {table}')
        check('seat_flight_refs','seatdb','SELECT COUNT(*) FROM seat_instance WHERE flight_instance_id NOT BETWEEN 1 AND 21216 OR flight_id<>FLOOR((flight_instance_id-1)/78)+1 OR flight_schedule_id<>flight_id OR seat_id NOT BETWEEN (flight_id-1)*120+1 AND flight_id*120;')
        for credential in self.credentials:
            stored=query('userdb',f'SELECT password FROM users WHERE email={literal(credential["email"])};')
            if not stored or stored[0][0]!=credential['password_hash']:
                raise ValueError('Credential/user mismatch')
        hashes={row[0] for row in query('userdb','SELECT DISTINCT password FROM users WHERE role=1;')}
        if hashes!={self.credentials[0]['password_hash']}:
            raise ValueError('Traveler password hashes mismatch')
        results['overnight_instances']=scalar('flightdb','SELECT COUNT(*) FROM flight_instance WHERE DATE(arrival_date_time)>DATE(departure_date_time);')
        results['arrivals_in_2027']=scalar('flightdb',"SELECT COUNT(*) FROM flight_instance WHERE arrival_date_time>='2027-01-01';")
        if not results['overnight_instances'] or not results['arrivals_in_2027']:
            raise ValueError('Expected overnight/final-day rollover examples')
        storage=[]
        for c,tables in TABLES.items():
            query(c,'ANALYZE TABLE '+','.join('`'+table+'`' for table in tables)+';')
        for c in ALL_DBS:
            storage += [[c,*row] for row in query(c,'SELECT TABLE_NAME,DATA_LENGTH,INDEX_LENGTH FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE();')]
        results['table_storage_bytes']=sum(int(r[2] or 0)+int(r[3] or 0) for r in storage)
        results['storage']=storage
        save(self.directory/'verification.json',results)
        print('Database verification passed; allocated table/index bytes:',results['table_storage_bytes'],flush=True)
        return results

    def smoke(self,base):
        self.load_credentials()
        results=[]
        def http(path,token=None,payload=None):
            headers={'Content-Type':'application/json'}
            if token:
                headers['Authorization']='Bearer '+token
            req=urllib.request.Request(base.rstrip('/')+path,
                                       data=json.dumps(payload).encode() if payload is not None else None,headers=headers)
            with urllib.request.urlopen(req,timeout=60) as response:
                return json.load(response)
        for index in range(11):
            role='ROLE_USER' if index==0 else ('ROLE_SYSTEM_ADMIN' if index==10 else 'ROLE_AIRLINE_OWNER')
            credential=self.credentials[index]
            response=http('/auth/login',payload={'email':credential['email'],'password':credential['password']})
            if not response.get('jwt') or response['user']['role']!=role:
                raise ValueError(f'Login failed for {role}')
            results.append({'login':role,'passed':True})
            if index==0:
                token=response['jwt']
            elif index<10:
                airline=http('/api/airlines/admin',response['jwt'])
                if airline['id']!=index or airline['ownerId']!=50000+index:
                    raise ValueError('Airline ownership API mismatch')
            else:
                airlines=http('/api/airlines?size=20',response['jwt'])
                if airlines['totalElements']!=9:
                    raise ValueError('Administrator airline listing mismatch')
        airports=http('/api/airports',token)
        if len(airports)!=17:
            raise ValueError('Airport API count mismatch')
        routes=self.plan['routes']
        examples=[('domestic',routes[0],START),('international',routes[1],START),
                  ('long_haul',next(r for r in routes if r['origin_code']=='SIN' and r['destination_code']=='JFK'),START),
                  ('last_day',routes[0],END),
                  ('rollover',next(r for r in routes if r['hour']*60+r['minutes']>=1440),END)]
        for label,r,date in examples:
            for cabin,capacity in [('ECONOMY',108),('BUSINESS',12)]:
                params=urllib.parse.urlencode(dict(departureAirportId=r['origin'],arrivalAirportId=r['destination'],
                                                  departureDate=str(date),passengers=1,cabinClass=cabin,size=10))
                page=http('/api/flights/search?'+params,token)
                matches=page.get('content',[])
                if len(matches)!=1:
                    raise ValueError(f'Search {label}/{cabin}: {len(matches)} matches')
                match=matches[0]
                if match['totalSeats']!=120 or match['availableSeats']!=capacity or not match.get('fare'):
                    raise ValueError(f'Search inventory/fare mismatch: {label}/{cabin}')
                inventory=http(f"/api/flight-instance-cabins/flight-instance/{match['id']}/cabin-class-type/{cabin}",token)
                if len(inventory['seats'])!=capacity or any(s['status']!='AVAILABLE' for s in inventory['seats']):
                    raise ValueError(f'Cabin API inventory mismatch: {label}/{cabin}')
                duration=int((dt.datetime.fromisoformat(match['arrivalDateTime'])-dt.datetime.fromisoformat(match['departureDateTime'])).total_seconds()/60)
                if duration!=r['minutes']:
                    raise ValueError('API duration mismatch')
                results.append(dict(scenario=label,cabin=cabin,instance=match['id'],passed=True))
        save(self.directory/'smoke.json',results)
        print('Application smoke checks passed:',len(results),flush=True)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    mode=parser.add_mutually_exclusive_group()
    mode.add_argument('--apply',action='store_true')
    mode.add_argument('--verify',action='store_true')
    mode.add_argument('--smoke',action='store_true')
    parser.add_argument('--directory',type=Path,default=Path('/root/future-seed-20261015'))
    parser.add_argument('--base-url',default='http://127.0.0.1:5000')
    args=parser.parse_args()
    os.umask(0o077)
    seeder=Seeder(args.directory)
    if args.apply:
        seeder.apply()
    elif args.verify:
        seeder.verify()
    elif args.smoke:
        seeder.smoke(args.base_url)
    else:
        print(json.dumps(dict(dataset=seeder.plan,existing_counts=seeder.counts(),schema_hash=seeder.fingerprint),indent=2))


if __name__=='__main__':
    main()
