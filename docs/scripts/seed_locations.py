"""Seed the Docker location database; preview by default, write with --apply.

Run on the server with Python 3. Downloads public reference data, archives inputs,
backs up the database, inserts missing records in one transaction, and evicts only
location caches. Existing city/airport records are never updated or deleted.
City codes use a representative airport IATA code (not metropolitan IATA codes).
"""
import argparse
import csv
import hashlib
import io
import json
import re
import subprocess
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

SOURCES = {
    'airports': 'https://raw.githubusercontent.com/davidmegginson/ourairports-data/main/airports.csv',
    'countries': 'https://raw.githubusercontent.com/davidmegginson/ourairports-data/main/countries.csv',
    'timezones': 'https://raw.githubusercontent.com/mwgg/Airports/master/airports.json',
}


def database(sql):
    command = ['docker', 'exec', '-i', 'locationdb', 'sh', '-c',
               'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 '
               '-uroot "$MYSQL_DATABASE" --batch --raw']
    return subprocess.run(command, input=sql, text=True, capture_output=True, check=True).stdout


def literal(value):
    # Hex UTF-8 literals avoid SQL quoting and backslash-mode ambiguity.
    return "CONVERT(X'" + str(value).encode('utf-8').hex() + "' USING utf8mb4)"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--output', default='/root/location-seed')
    args = parser.parse_args()
    directory = Path(args.output) / datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    directory.mkdir(parents=True, mode=0o700)
    payloads = {}
    hashes = {}
    for name, url in SOURCES.items():
        payload = urllib.request.urlopen(url, timeout=60).read()
        (directory / (name + '.source')).write_bytes(payload)
        payloads[name] = payload.decode('utf-8-sig')
        hashes[name] = hashlib.sha256(payload).hexdigest()
    countries = {row['code']: row['name'] for row in csv.DictReader(io.StringIO(payloads['countries']))}
    zones = json.loads(payloads['timezones'])
    zones_by_iata = {}
    for airport in zones.values():
        if airport.get('iata'):
            zones_by_iata.setdefault(airport['iata'], []).append(airport)
    rows = []
    skipped = []
    codes = set()
    for row in csv.DictReader(io.StringIO(payloads['airports'])):
        if row['scheduled_service'] != 'yes':
            continue
        if row['type'] != 'large_airport' and not (
                row['iso_country'] == 'VN' and row['type'] == 'medium_airport'):
            continue
        code = row['iata_code']
        # Correct malformed municipality labels in the upstream snapshot.
        if code == 'CXR' and row['iso_country'] == 'VN':
            row['municipality'] = 'Cam Ranh'
        elif code == 'UIH' and row['iso_country'] == 'VN':
            row['municipality'] = 'Quy Nhon'
        if not re.fullmatch('[A-Z]{3}', code) or not row['municipality'].strip():
            continue
        if code in codes:
            raise ValueError('Duplicate source IATA code: ' + code)
        zone = zones.get(row['ident'], {}).get('tz')
        if not zone:
            # Some reference identifiers changed; accept the same IATA/country
            # only when coordinates also identify the same nearby airport.
            matches = [entry for entry in zones_by_iata.get(code, [])
                       if entry.get('country') == row['iso_country']
                       and abs(float(entry['lat']) - float(row['latitude_deg'])) < 1
                       and abs(float(entry['lon']) - float(row['longitude_deg'])) < 1]
            if len(matches) == 1:
                zone = matches[0].get('tz')
        if not zone:
            skipped.append(code)
            continue
        ZoneInfo(zone)
        latitude, longitude = float(row['latitude_deg']), float(row['longitude_deg'])
        assert -90 <= latitude <= 90 and -180 <= longitude <= 180
        assert row['iso_country'] in countries
        assert len(row['name']) <= 255 and len(zone) <= 50
        rows.append({**row, 'tz': zone, 'latitude': latitude, 'longitude': longitude})
        codes.add(code)
    assert {'HAN', 'SGN', 'DAD', 'LHR', 'JFK', 'CDG', 'DXB', 'SIN', 'SYD'} <= codes
    assert len(rows) > 300, 'Unexpectedly small reference dataset'
    rows.sort(key=lambda row: (row['iso_country'], row['municipality'], row['iata_code']))
    grouped = {}
    for row in rows:
        key = (row['iso_country'], row['municipality'].strip().casefold(), row['tz'])
        grouped.setdefault(key, []).append(row)
    statements = ['SET NAMES utf8mb4;', 'START TRANSACTION;']
    for (country, _, zone), airports in grouped.items():
        name = airports[0]['municipality'].strip()
        city_code = airports[0]['iata_code']
        # Reuse an existing city by country/name/timezone before creating one.
        match = (f'country_code={literal(country)} AND LOWER(name)=LOWER({literal(name)}) '
                 f'AND (time_zone_id={literal(zone)} OR time_zone_id IS NULL)')
        statements.append(
            'INSERT INTO city (city_code,country_code,country_name,name,region_code,time_zone_id) '
            f'SELECT {literal(city_code)},{literal(country)},{literal(countries[country])},'
            f'{literal(name)},NULL,{literal(zone)} FROM DUAL '
            f'WHERE NOT EXISTS (SELECT 1 FROM city WHERE {match}) '
            f'AND NOT EXISTS (SELECT 1 FROM city WHERE city_code={literal(city_code)});')
        statements.append(f'SET @city_id=(SELECT id FROM city WHERE {match} ORDER BY id LIMIT 1);')
        for airport in airports:
            statements.append(
                'INSERT INTO airport (iata_code,name,latitude,longitude,time_zone,city_id) '
                f'SELECT {literal(airport["iata_code"])},{literal(airport["name"])},'
                f'{airport["latitude"]},{airport["longitude"]},{literal(zone)},@city_id '
                'FROM DUAL WHERE @city_id IS NOT NULL '
                f'AND NOT EXISTS (SELECT 1 FROM airport WHERE iata_code={literal(airport["iata_code"])});')
    statements.append('COMMIT;')
    sql = '\n'.join(statements)
    (directory / 'seed.sql').write_text(sql, encoding='utf-8')
    manifest = {'sources': SOURCES, 'sha256': hashes, 'airports': len(rows), 'cities': len(grouped),
                'countries': len({row['iso_country'] for row in rows}),
                'skipped_missing_timezone': skipped, 'directory': str(directory)}
    (directory / 'manifest.json').write_text(json.dumps(manifest, indent=2))
    print(json.dumps(manifest, indent=2), flush=True)
    if not args.apply:
        return
    backup = directory / 'location-before.sql'
    with backup.open('wb') as output:
        subprocess.run(['docker', 'exec', 'locationdb', 'sh', '-c',
                        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot '
                        '--single-transaction --no-tablespaces "$MYSQL_DATABASE"'],
                       stdout=output, check=True)
    print('Before:', database('SELECT COUNT(*) FROM city; SELECT COUNT(*) FROM airport;'), flush=True)
    database(sql)
    print('After:', database('SELECT COUNT(*) FROM city; SELECT COUNT(*) FROM airport; '
                            'SELECT COUNT(*) AS countries FROM (SELECT DISTINCT country_code FROM city) c; '
                            'SELECT COUNT(*) AS unlinked_airports FROM airport a LEFT JOIN city c '
                            'ON c.id=a.city_id WHERE c.id IS NULL;'), flush=True)
    # Rerun the same transaction to verify that the seed is idempotent.
    before = database('SELECT COUNT(*) FROM city; SELECT COUNT(*) FROM airport;')
    database(sql)
    assert database('SELECT COUNT(*) FROM city; SELECT COUNT(*) FROM airport;') == before
    for prefix in ['allAirports', 'airports', 'airportsByCity', 'cities', 'citiesByCode']:
        result = subprocess.run(['docker', 'exec', 'redis', 'redis-cli', '--scan', '--pattern', prefix + '::*'],
                                capture_output=True, text=True, check=True)
        keys = result.stdout.splitlines()
        for key in keys:
            subprocess.run(['docker', 'exec', 'redis', 'redis-cli', 'UNLINK', key],
                           capture_output=True, check=True)
        print(f'Cleared {len(keys)} {prefix} cache entries', flush=True)
    print('Seed completed; backup:', backup, flush=True)


if __name__ == '__main__':
    main()
