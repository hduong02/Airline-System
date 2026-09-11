"""Run on the authorized VPS. Stdlib only; reports never include tokens/passwords.

Phases: prepare, scheduled-fixture, supplemental, verify-payment, cleanup.
State and credentials stay in a 0700
run directory. ADMIN_EMAIL/ADMIN_PASSWORD or ADMIN_CREDENTIAL_FILE supply admin.
"""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request


class Smoke:
    def __init__(self, directory):
        os.umask(0o077)
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.state_path = self.directory / 'private-state.json'
        self.state = json.loads(self.state_path.read_text()) if self.state_path.exists() else {
            'run': 'smoke-' + dt.datetime.now(dt.timezone(dt.timedelta(hours=7))).strftime('%Y%m%d-%H%M%S'),
            'records': [], 'accounts': {}, 'checks': [], 'fixtures': {}}
        self.base = 'http://127.0.0.1:5000'

    def save(self):
        self.state_path.write_text(json.dumps(self.state, indent=2))
        report = {k: self.state[k] for k in ['run', 'checks', 'records', 'fixtures']}
        report['target'] = 'root@201.18.216.184'
        report['inventory'] = self.state.get('inventory', [])
        report['counts'] = {s: sum(c['status'] == s and not c.get('superseded') for c in report['checks']) for s in ['PASS', 'FAIL', 'BLOCKED']}
        (self.directory / 'results.json').write_text(json.dumps(report, indent=2))

    def record(self, name, passed=None, expected='', actual='', seconds=None):
        item = {'name': name, 'status': 'BLOCKED' if passed is None else 'PASS' if passed else 'FAIL',
                'expected': expected, 'actual': actual}
        if seconds is not None:
            item['seconds'] = seconds
        self.state['checks'].append(item)
        self.save()
        print(json.dumps(item), flush=True)

    def request(self, path, role=None, method='GET', body=None, base=None, headers=None):
        hdr = {'Accept': 'application/json', **(headers or {})}
        if role:
            account = self.state['accounts'].get(role, {})
            if not account.get('jwt'):
                return 0, {'error': 'Account unavailable'}, {}, 0
            hdr['Authorization'] = 'Bearer ' + account['jwt']
        data = None if body is None else json.dumps(body).encode()
        if data is not None:
            hdr['Content-Type'] = 'application/json'
        start = time.monotonic()
        try:
            with urllib.request.urlopen(urllib.request.Request((base or self.base)+path, data=data, headers=hdr, method=method), timeout=30) as r:
                code, raw, response_headers = r.status, r.read(), dict(r.headers)
        except urllib.error.HTTPError as e:
            code, raw, response_headers = e.code, e.read(), dict(e.headers)
        except Exception as e:
            return 0, {'error': type(e).__name__}, {}, round(time.monotonic()-start, 3)
        try:
            value = json.loads(raw)
        except ValueError:
            value = {'text': raw.decode(errors='replace')[:200]}
        return code, value, response_headers, round(time.monotonic()-start, 3)

    def check(self, name, path, role=None, method='GET', body=None, codes=(200,), predicate=lambda v: True, expected='valid response', **kwargs):
        code, value, headers, elapsed = self.request(path, role, method, body, **kwargs)
        try:
            passed = code in codes and bool(predicate(value))
        except (TypeError, KeyError, AttributeError):
            passed = False
        actual = 'HTTP ' + str(code)
        if not passed and isinstance(value, dict):
            # Never print arbitrary auth/payment response bodies.
            actual += '; fields=' + ','.join(value.keys())
        self.record(name, passed, expected, actual, elapsed)
        return value if passed else None

    def create(self, name, path, body, role='owner'):
        value = self.check(name+' create', path, role, 'POST', body, (200, 201),
                           lambda v: isinstance(v, dict) and bool(v.get('id')), 'created record with ID')
        if value:
            self.state['records'].append({'kind': name, 'path': path+'/'+str(value['id']), 'id': value['id'], 'role': role, 'deleted': False})
            self.state['fixtures'][name] = value['id']
            self.save()
        return value

    def command(self, name, args, predicate=lambda r: r.returncode == 0):
        start = time.monotonic()
        try:
            r = subprocess.run(args, capture_output=True, text=True, timeout=40)
            ok = predicate(r)
            self.record(name, ok, 'command succeeds with valid output', 'exit '+str(r.returncode)+'; '+r.stdout.strip()[:180], round(time.monotonic()-start, 3))
            return r.stdout
        except Exception as e:
            self.record(name, False, 'command succeeds', type(e).__name__)
            return ''

    def infrastructure(self):
        inventory = json.loads(subprocess.check_output(['docker', 'inspect'] + subprocess.check_output(['docker', 'ps', '-aq'], text=True).split()))
        self.state['inventory'] = [{'name': c['Name'].lstrip('/'), 'image': c['Config']['Image'], 'running': c['State']['Running'],
                                    'health': c['State'].get('Health', {}).get('Status', 'not configured'), 'restarts': c['RestartCount'],
                                    'ip': next(iter(c['NetworkSettings']['Networks'].values()))['IPAddress']} for c in inventory]
        self.record('all deployed containers running', bool(inventory) and all(c['running'] for c in self.state['inventory']), 'all running', str(len(inventory))+' containers')
        for c in inventory:
            name = c['Name'].lstrip('/')
            env = dict(x.split('=', 1) for x in c['Config'].get('Env', []) if '=' in x)
            ip = next(iter(c['NetworkSettings']['Networks'].values()))['IPAddress']
            if name.endswith('db'):
                self.command(name+' database query', ['docker','exec',name,'sh','-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -e "SELECT 1"'], lambda r: r.returncode == 0 and r.stdout.strip() == '1')
            ports = {'config-server':8888, 'eureka-server':8761, 'user-service':5001, 'airline-service':5002,
                     'location-service':5004, 'flight-service':5005, 'pricing-service':5006, 'seat-service':5007,
                     'ancillary-service':5008, 'booking-service':5009, 'payment-service':5010, 'notification-service':5011}
            if name in ports:
                code, value, _, elapsed = self.request('/actuator/health', base='http://'+ip+':'+str(ports[name]))
                self.record(name+' health', None if code==404 else code==200 and value.get('status')=='UP',
                            'HTTP 200 and UP when health instrumentation exists',
                            'HTTP 404; no health instrumentation' if code==404 else 'HTTP '+str(code), elapsed)
            if name == 'eureka-server':
                value = self.check('Eureka registered services', '/eureka/apps', base='http://'+ip+':8761',
                                   predicate=lambda v: len(v.get('applications',{}).get('application',[])) >= 11,
                                   expected='gateway and ten business services registered')
                if value:
                    apps = value['applications']['application']
                    names = {a['name'] for a in apps}
                    expected = {n.upper() for n in ports if n.endswith('-service')} | {'API-GATEWAY'}
                    self.record('Eureka names and instance status', expected <= names and all(i.get('status')=='UP' for a in apps for i in (a['instance'] if isinstance(a['instance'],list) else [a['instance']])), 'all expected services UP', ', '.join(sorted(names)))
            if name == 'payment-service':
                self.record('Stripe test mode', env.get('STRIPE_API_KEY','').startswith('sk_test_'), 'test key', 'TEST' if env.get('STRIPE_API_KEY','').startswith('sk_test_') else 'NOT_TEST')
            if name == 'notification-service':
                self.record('Mail delivery sandbox', env.get('MAIL_HOST')=='mailhog', 'mailhog', env.get('MAIL_HOST','unset'))
        self.command('Redis ping', ['docker','exec','redis','redis-cli','ping'], lambda r:r.returncode==0 and r.stdout.strip()=='PONG')
        self.command('Kafka topics', ['docker','exec','kafka','/opt/kafka/bin/kafka-topics.sh','--bootstrap-server','localhost:9092','--list'], lambda r:r.returncode==0 and 'payment.completed' in r.stdout)
        self.command('Kafka consumer groups', ['docker','exec','kafka','/opt/kafka/bin/kafka-consumer-groups.sh','--bootstrap-server','localhost:9092','--describe','--all-groups'])
        self.check('MailHog API', '/api/v2/messages?limit=1', base='http://127.0.0.1:8025', predicate=lambda v: 'total' in v)
        code, _, hdr, _ = self.request('/auth/login', method='OPTIONS', headers={'Origin':'https://airline-system-five.vercel.app','Access-Control-Request-Method':'POST','Access-Control-Request-Headers':'content-type'})
        allow = next((v for k,v in hdr.items() if k.lower()=='access-control-allow-origin'), '')
        self.record('Frontend CORS preflight', code in (200,204) and allow=='https://airline-system-five.vercel.app', 'allowed deployed origin', 'HTTP '+str(code)+'; allow-origin='+allow)

    def accounts(self):
        admin_path = os.getenv('ADMIN_CREDENTIAL_FILE')
        admin = json.loads(Path(admin_path).read_text()) if admin_path else {'email':os.getenv('ADMIN_EMAIL'), 'password':os.getenv('ADMIN_PASSWORD')}
        if admin.get('email') and admin.get('password'):
            v = self.check('super-admin API login','/auth/login',method='POST',body=admin,predicate=lambda v: bool(v.get('jwt')))
            if v:
                self.state['accounts']['admin']={'jwt':v['jwt']}
                self.check('super-admin identity', '/api/users/profile', 'admin', predicate=lambda v:v.get('role')=='ROLE_SYSTEM_ADMIN', expected='ROLE_SYSTEM_ADMIN')
        else:
            self.record('super-admin API login', None, 'admin credentials', 'not supplied')
        for role, enum in [('customer','ROLE_USER'),('other','ROLE_USER'),('owner','ROLE_AIRLINE_OWNER'),('owner2','ROLE_AIRLINE_OWNER')]:
            username = self.state['run']+'-'+role
            account = {'username':username,'email':username+'@example.invalid','password':secrets.token_urlsafe(18), 'fullName':username, 'role':enum,'phone':'+66000000000'}
            v = self.check(role+' registration','/auth/signup',method='POST',body=account,predicate=lambda v:bool(v.get('jwt')))
            if v:
                self.state['accounts'][role] = {**account,'jwt':v['jwt']}
                self.save()
                v = self.check(role+' login','/auth/login',method='POST',body={'email':account['email'],'password':account['password']},predicate=lambda v:bool(v.get('jwt')))
                if v:
                    self.state['accounts'][role]['jwt']=v['jwt']
                self.check(role+' profile','/api/users/profile',role,predicate=lambda v:v.get('role')==enum and v.get('email')==account['email'])
        self.check('unauthenticated profile rejected','/api/users/profile',codes=(401,),expected='HTTP 401')
        self.check('invalid credentials rejected','/auth/login',method='POST',body={'email':self.state['accounts'].get('customer',{}).get('email','unknown@example.invalid'),'password':'intentionally-invalid'},codes=(400,401,403),expected='HTTP 400/401/403')
        self.check('customer admin airline listing denied','/api/airlines','customer',codes=(403,),expected='HTTP 403')
        self.check('customer location creation denied','/api/cities','customer','POST',{},(403,),expected='HTTP 403')

    def supplemental(self):
        """Representative service reads and scoped authorization regression probes."""
        f=self.state['fixtures']
        self.check('public HTTPS gateway profile','/api/users/profile','customer',base='https://api.vnairline.tech',predicate=lambda v:v.get('email')==self.state['accounts']['customer']['email'])
        self.check('owner booking dashboard read','/api/bookings/airline','owner',predicate=lambda v:isinstance(v,list))
        self.check('admin payment catalogue','/api/payments?size=2','admin',predicate=lambda v:isinstance(v.get('content'),list))
        if f.get('meal'):
            self.check('owner meal catalogue','/api/meals/airline','owner',predicate=lambda v:any(x['id']==f['meal'] for x in v))
        if f.get('fare'):
            self.check('fare cabin contract','/api/fares/'+str(f['fare']),'customer',predicate=lambda v:v.get('cabinClass')=='ECONOMY' and v.get('cabinClassId')==f['cabin'],expected='ECONOMY enum for API-created fare')
        if f.get('schedule'):
            self.check('schedule catalogue','/api/flight-schedules','owner',predicate=lambda v:any(x['id']==f['schedule'] for x in v))
        if f.get('disposable-airline'):
            path='/api/airlines/'+str(f['disposable-airline'])
            try:
                self.check('customer cannot approve airline',path+'/approve','customer','POST',{},(403,),expected='HTTP 403')
            finally:
                self.check('restore disposable airline banned',path+'/ban','admin','POST',{},predicate=lambda v:v.get('status')=='BANNED')
        if f.get('aircraft'):
            path='/api/aircrafts/'+str(f['aircraft'])
            # Same-value update reveals authorization without changing the fixture.
            self.check('foreign owner aircraft update denied',path,'owner2','PUT',{'model':'SMOKE-12-UPDATED'},(403,404),expected='HTTP 403/404')
        if f.get('flight'):
            _,flight,_,_=self.request('/api/flights/'+str(f['flight']),'owner')
            body={'flightNumber':flight.get('flightNumber'),'aircraftId':f['aircraft'],'departureAirportId':f['departureAirportId'],'arrivalAirportId':f['arrivalAirportId'],'status':'SCHEDULED'}
            self.check('foreign owner flight update denied','/api/flights/'+str(f['flight']),'owner2','PUT',body,(403,404),expected='HTTP 403/404')
        self.save()

    def fixtures(self):
        f = self.state['fixtures']
        airports = self.check('airport catalogue', '/api/airports', 'customer', predicate=lambda v:len(v if isinstance(v,list) else v.get('content',[]))>=2)
        self.check('city catalogue', '/api/cities?size=2','customer', predicate=lambda v: bool(v))
        self.check('active airline dropdown', '/api/airlines/dropdown','customer', predicate=lambda v:isinstance(v,list))
        if not airports:
            return
        airports = airports if isinstance(airports,list) else airports['content']
        f['departureAirportId'], f['arrivalAirportId'] = [x['id'] for x in airports[:2]]
        f['departureIata'], f['arrivalIata'] = [x.get('iataCode') for x in airports[:2]]
        existing = self.check('super-admin airline listing','/api/airlines?size=200','admin', predicate=lambda v:isinstance(v.get('content'),list))
        used = {x.get('iataCode') for x in (existing or {}).get('content',[])}
        code = next(a+b for a in 'ZXWVU' for b in '9876543210ABCDEFGHIJKLMNOPQRSTUVWXYZ' if a+b not in used)
        body = {'iataCode':code,'icaoCode':'Q'+code,'name':self.state['run'], 'country':'Thailand','status':'INACTIVE','supportEmail':self.state['accounts']['owner']['email']}
        airline = self.create('airline','/api/airlines',body)
        if not airline:
            return
        for action, status in [('approve','ACTIVE'),('suspend','INACTIVE'),('approve','ACTIVE')]:
            self.check('admin airline '+action,'/api/airlines/'+str(airline['id'])+'/'+action,'admin','POST',{},predicate=lambda v:v.get('status')==status,expected=status)
        body['name'] += '-updated'
        body['status'] = 'ACTIVE'
        self.check('owner airline update','/api/airlines','owner','PUT',body,predicate=lambda v:v.get('name')==body['name'])
        self.check('owner airline profile','/api/airlines/admin','owner',predicate=lambda v:v.get('id')==airline['id'])
        b2={**body,'iataCode':code[::-1], 'icaoCode':'R'+code, 'name':self.state['run']+'-disposable'}
        second=self.create('disposable-airline','/api/airlines',b2,'owner2')
        if second:
            self.check('admin ban disposable airline','/api/airlines/'+str(second['id'])+'/ban','admin','POST',{},predicate=lambda v:v.get('status')=='BANNED')
        aircraft_body={'code':self.state['run'],'model':'SMOKE-12','manufacturer':'Smoke Test','seatingCapacity':12,'economySeats':12,'status':'ACTIVE','isAvailable':True,'currentAirportId':f['departureAirportId']}
        aircraft=self.create('aircraft','/api/aircrafts',aircraft_body)
        if not aircraft:
            return
        self.check('aircraft detail','/api/aircrafts/'+str(aircraft['id']),'owner',predicate=lambda v:v.get('id')==aircraft['id'])
        self.check('cross-owner aircraft listing isolation','/api/aircrafts','owner2',predicate=lambda v:aircraft['id'] not in [x['id'] for x in (v if isinstance(v,list) else v.get('content',[]))],expected='other owner does not list test aircraft')
        aircraft_body['model']='SMOKE-12-UPDATED'
        self.check('aircraft update','/api/aircrafts/'+str(aircraft['id']),'owner','PUT',aircraft_body,predicate=lambda v:v.get('model')==aircraft_body['model'])
        cabin=self.create('cabin','/api/cabin-classes',{'name':'ECONOMY','code':'Y','aircraftId':aircraft['id'],'isActive':True,'isBookable':True})
        if not cabin:
            return
        seatmap=self.create('seat-map','/api/seat-maps',{'name':self.state['run'],'cabinClassId':cabin['id'],'totalRows':3,'leftSeatsPerRow':2,'rightSeatsPerRow':2})
        if not seatmap:
            return
        self.check('seat-map update','/api/seat-maps/'+str(seatmap['id']),'owner','PUT',{'name':self.state['run']+' updated','cabinClassId':cabin['id'],'totalRows':3,'leftSeatsPerRow':2,'rightSeatsPerRow':2},predicate=lambda v:v.get('name')==self.state['run']+' updated')
        flight_body={'flightNumber':code+'9001','aircraftId':aircraft['id'],'departureAirportId':f['departureAirportId'],'arrivalAirportId':f['arrivalAirportId'],'status':'SCHEDULED'}
        flight=self.create('flight','/api/flights',flight_body)
        if not flight:
            return
        flight_body['flightNumber']=code+'9002'
        self.check('flight update','/api/flights/'+str(flight['id']),'owner','PUT',flight_body,predicate=lambda v:v.get('flightNumber')==flight_body['flightNumber'])
        self.check('owner flight listing','/api/flights/airline?size=5','owner',predicate=lambda v: bool(v))
        fare_body={'name':self.state['run']+' fare','rbdCode':'Y','flightId':flight['id'],'cabinClassId':cabin['id'],'baseFare':20.0,'taxesAndFees':2.0,'airlineFees':1.0,'currentPrice':23.0}
        for key in ['extraSeatSpace','preferredSeatChoice','advanceSeatSelection','guaranteedSeatTogether','priorityBoarding','priorityCheckin','fastTrackSecurity','complimentaryMeals','premiumMealChoice','inFlightInternet','inFlightEntertainment','complimentaryBeverages','freeDateChange','partialRefund','fullRefund','loungeAccess','airportTransfer']:
            fare_body[key]=False
        fare=self.create('fare','/api/fares',fare_body)
        if not fare:
            return
        fare_body['fareLabel']='Smoke Updated'
        self.check('fare update','/api/fares/'+str(fare['id']),'owner','PUT',fare_body,predicate=lambda v:v.get('fareLabel')=='Smoke Updated')
        self.check('fare benefits read','/api/fares/'+str(fare['id']),'customer',predicate=lambda v:v.get('freeDateChange') is False)
        self.create('fare-rule','/api/fare-rules',{'ruleName':self.state['run'],'fareId':fare['id'],'isRefundable':False,'isChangeable':False,'changeFee':0.0,'cancellationFee':0.0})
        self.create('baggage','/api/baggage-policies',{'name':self.state['run'],'fareId':fare['id'],'cabinBaggageMaxWeight':7.0,'cabinBaggagePieces':1,'checkInBaggageMaxWeight':20.0,'checkInBaggagePieces':1,'freeCheckedBagsAllowance':1,'priorityBaggage':False,'extraBaggageAllowance':False})
        self.check('baggage read','/api/baggage-policies/fare/'+str(fare['id']),'customer',predicate=lambda v:bool(v))
        ancillary=self.create('ancillary','/api/ancillaries',{'type':'BAGGAGE','name':self.state['run']+' Extra bag','description':'Dedicated smoke fixture'})
        if ancillary:
            self.create('flight-ancillary','/api/flight-cabin-ancillaries',{'flightId':flight['id'],'cabinClassId':cabin['id'],'ancillaryId':ancillary['id'],'available':True,'maxQuantity':1,'price':3.0,'includedInFare':False})
            self.check('ancillary update','/api/ancillaries/'+str(ancillary['id']),'owner','PUT',{'type':'BAGGAGE','name':self.state['run']+' Extra bag updated'},predicate=lambda v:v.get('name','').endswith('updated'))
            self.check('traveler ancillary catalogue','/api/flight-cabin-ancillaries/flight/'+str(flight['id'])+'/cabin/'+str(cabin['id']),'customer',predicate=lambda v:bool(v))
        self.create('meal','/api/meals',{'code':'SM'+secrets.token_hex(3).upper(),'name':self.state['run']+' meal','mealType':'STANDARD','requiresAdvanceBooking':False,'advanceBookingHours':0})
        self.check('insurance catalogue','/api/insurance-coverages','owner',predicate=lambda v:isinstance(v,(dict,list)))
        date=(dt.datetime.now(dt.timezone(dt.timedelta(hours=7)))+dt.timedelta(days=7)).date().isoformat()
        f['departureDate']=date
        instance=self.create('flight-instance','/api/flight-instances',{'flightId':flight['id'],'departureAirportId':f['departureAirportId'],'arrivalAirportId':f['arrivalAirportId'],'departureDateTime':date+'T10:00:00','arrivalDateTime':date+'T12:00:00','totalSeats':12,'availableSeats':12,'status':'SCHEDULED','isActive':True,'minAdvanceBookingDays':0,'maxAdvanceBookingDays':365})
        if not instance:
            return
        path='/api/flight-instance-cabins/flight-instance/'+str(instance['id'])+'/cabin-class-type/ECONOMY'
        value={}
        for _ in range(15):
            code,value,_,_=self.request(path,'customer')
            if code==200 and value and len(value.get('seats',[]))==12:
                break
            time.sleep(2)
        self.record('Kafka creates cabin and seat inventory',code==200 and len((value or {}).get('seats',[]))==12,'12 AVAILABLE seats from flight-created event','HTTP '+str(code)+'; seats='+str(len((value or {}).get('seats',[]))))
        if code==200 and value:
            f['instance-cabin']=value['id']
            f['seatIds']=[s['id'] for s in value.get('seats',[]) if s.get('status')=='AVAILABLE']
            self.state['records'].append({'kind':'instance-cabin','path':'/api/flight-instance-cabins/'+str(value['id']),'id':value['id'],'role':'owner','deleted':False})
        f['searchPath']='/api/flights/search?'+urllib.parse.urlencode({'departureAirportId':f['departureAirportId'],'arrivalAirportId':f['arrivalAirportId'],'departureDate':date,'passengers':1,'cabinClass':'ECONOMY','airlines':airline['id'],'sortBy':'price','sortOrder':'ASC'})
        self.check('traveler flight search',f['searchPath'],'customer',predicate=lambda v:instance['id'] in [x['id'] for x in v.get('content',[])],expected='new future departure in search results')
        self.check('search price exclusion',f['searchPath']+'&maxPrice=1','customer',predicate=lambda v:v.get('totalElements')==0,expected='no matches under fare price')
        self.check('search insufficient capacity',f['searchPath'].replace('passengers=1','passengers=99'),'customer',predicate=lambda v:v.get('totalElements')==0,expected='no matches for 99 travelers')
        self.check('owner departure listing','/api/flight-instances?size=5','owner',predicate=lambda v:instance['id'] in [x['id'] for x in v.get('content',[])])
        self.check('cross-owner departure isolation','/api/flight-instances?size=5','owner2',predicate=lambda v:v.get('totalElements')==0)
        self.save()

    def booking_body(self, seat):
        f=self.state['fixtures']
        return {'flightId':f['flight'],'flightInstanceId':f['flight-instance'],'cabinClass':'ECONOMY','fareId':f['fare'],
                'passengers':[{'firstName':'Smoke','lastName':'Traveler','email':self.state['accounts']['customer']['email'],'phone':'+66000000000','dateOfBirth':'1990-01-01','gender':'MALE','seatInstanceId':seat,'nationality':'TH'}],
                'contactInfo':{'email':self.state['accounts']['customer']['email'],'phone':'+66000000000'},'ancillaryIds':([f['flight-ancillary']] if f.get('flight-ancillary') else []),'mealIds':[]}

    def scheduled_fixture(self):
        f=self.state['fixtures']
        if f.get('flight-instance'):
            raise SystemExit('Scheduled fixture already exists; do not create it twice')
        if not f.get('flight'):
            self.record('scheduled fixture',None,'created master flight','unavailable')
            return
        self.check('restore test airline approved status','/api/airlines/'+str(f['airline'])+'/approve','admin','POST',{},predicate=lambda v:v.get('status')=='ACTIVE')
        date=f['departureDate']
        day=dt.date.fromisoformat(date).strftime('%A').upper()
        body={'flightId':f['flight'],'departureTime':'10:00:00','arrivalTime':'12:00:00','startDate':date,'endDate':date,'operatingDays':[day],'isActive':True}
        regular=self.create('schedule','/api/flight-schedules',body)
        if not regular:
            # No direct database writes: create an empty recurrence, configure it,
            # then use the supported manual instance API with its real schedule ID.
            body['operatingDays']=[]
            regular=self.create('schedule','/api/flight-schedules',body)
            if not regular:
                return
            body['operatingDays']=[day]
            self.check('schedule update','/api/flight-schedules/'+str(regular['id']),'owner','PUT',body,predicate=lambda v:v.get('id')==regular['id'])
        _,listing,_,_=self.request('/api/flight-instances?size=10','owner')
        instances=listing.get('content',[]) if isinstance(listing,dict) else []
        if instances:
            instance=instances[0]
            f['flight-instance']=instance['id']
            self.state['records'].append({'kind':'flight-instance','path':'/api/flight-instances/'+str(instance['id']),'id':instance['id'],'role':'owner','deleted':False})
        else:
            instance=self.create('flight-instance','/api/flight-instances',{'scheduleId':regular['id'],'flightId':f['flight'],'departureAirportId':f['departureAirportId'],'arrivalAirportId':f['arrivalAirportId'],'departureDateTime':date+'T10:00:00','arrivalDateTime':date+'T12:00:00','totalSeats':12,'availableSeats':12,'status':'SCHEDULED','isActive':True,'minAdvanceBookingDays':0,'maxAdvanceBookingDays':365})
        if not instance:
            return
        path='/api/flight-instance-cabins/flight-instance/'+str(instance['id'])+'/cabin-class-type/ECONOMY'
        code,v=0,{}
        for _ in range(15):
            code,v,_,_=self.request(path,'customer')
            if code==200 and len((v or {}).get('seats',[]))==12:
                break
            time.sleep(2)
        self.record('Kafka creates cabin and seat inventory',code==200 and len((v or {}).get('seats',[]))==12,'12 seats from flight-created event','HTTP '+str(code)+'; seats='+str(len((v or {}).get('seats',[]))))
        if code==200 and v:
            f['instance-cabin']=v['id']
            f['seatIds']=[s['id'] for s in v.get('seats',[]) if s.get('status')=='AVAILABLE']
            self.state['records'].append({'kind':'instance-cabin','path':'/api/flight-instance-cabins/'+str(v['id']),'id':v['id'],'role':'owner','deleted':False})
        f['searchPath']='/api/flights/search?'+urllib.parse.urlencode({'departureAirportId':f['departureAirportId'],'arrivalAirportId':f['arrivalAirportId'],'departureDate':date,'passengers':1,'cabinClass':'ECONOMY','airlines':f['airline'],'sortBy':'price','sortOrder':'ASC'})
        self.check('traveler flight search',f['searchPath'],'customer',predicate=lambda v:instance['id'] in [x['id'] for x in v.get('content',[])])
        self.check('search price exclusion',f['searchPath']+'&maxPrice=1','customer',predicate=lambda v:v.get('totalElements')==0)
        self.check('search insufficient capacity',f['searchPath'].replace('passengers=1','passengers=99'),'customer',predicate=lambda v:v.get('totalElements')==0)
        self.check('owner departure listing','/api/flight-instances?size=5','owner',predicate=lambda v:instance['id'] in [x['id'] for x in v.get('content',[])])
        self.check('cross-owner departure isolation','/api/flight-instances?size=5','owner2',predicate=lambda v:v.get('totalElements')==0)
        self.save()
        self.bookings()

    def bookings(self):
        f=self.state['fixtures']
        self.check('invalid booking input rejected','/api/bookings','customer','POST',{},(400,),expected='HTTP 400 validation')
        if not f.get('seatIds') or not f.get('fare'):
            self.record('booking and payment journey',None,'future departure with fare and seats','fixture creation failed')
            return
        if not self.payment_sandbox_ready():
            return
        body=self.booking_body(f['seatIds'][-1])
        payment=self.check('booking starts Stripe test checkout','/api/bookings','customer','POST',body,(201,),predicate=lambda v:bool(v.get('checkoutUrl') or v.get('stripeCheckoutUrl')),expected='HTTP 201 with Stripe Checkout URL')
        if not payment:
            for name in ['Stripe checkout completion','payment success and booking confirmation','ticket generation','paid seat BOOKED','confirmation email delivery','repeat payment verification idempotency','held seat double booking rejection','unpaid booking cancellation and release','booking ownership isolation']:
                self.record(name,None,'successful booking creation','booking creation failed; see the preceding assertion')
            return
        self.state['payment']=payment
        history=self.check('customer pending booking history','/api/bookings/user/history','customer',predicate=lambda v:isinstance(v,list) and len(v)>=1)
        if not history:
            return
        booking=next((v for v in history if v.get('flightId')==f['flight']),history[-1])
        f['booking']=booking['id']
        self.state['records'].append({'kind':'booking','path':'/api/bookings/'+str(booking['id']),'id':booking['id'],'role':'customer','deleted':False})
        self.check('booking is pending','/api/bookings/'+str(booking['id']),'customer',predicate=lambda v:v.get('status')=='PENDING',expected='PENDING')
        self.check('other customer cannot read booking','/api/bookings/'+str(booking['id']),'other',codes=(403,404),expected='HTTP 403/404')
        self.check('other customer cannot cancel booking','/api/bookings/'+str(booking['id'])+'/cancel','other','PATCH',{},(403,404),expected='HTTP 403/404')
        cabin_path='/api/flight-instance-cabins/flight-instance/'+str(f['flight-instance'])+'/cabin-class-type/ECONOMY'
        self.check('selected seat held',cabin_path,'customer',predicate=lambda v:any(s['id']==f['seatIds'][-1] and s.get('status')=='RESERVED' for s in v.get('seats',[])),expected='selected seat RESERVED')
        self.check('held seat cannot be double booked','/api/bookings','other','POST',body,(400,409),expected='HTTP 400/409')
        pending=self.check('second unpaid checkout','/api/bookings','customer','POST',self.booking_body(f['seatIds'][-2]),(201,),predicate=lambda v:bool(v.get('checkoutUrl') or v.get('stripeCheckoutUrl')))
        if pending:
            _,hist,_,_=self.request('/api/bookings/user/history','customer')
            ids=[v['id'] for v in hist if v['id']!=booking['id']]
            if ids:
                bid=max(ids)
                self.state['records'].append({'kind':'unpaid-booking','path':'/api/bookings/'+str(bid),'id':bid,'role':'customer','deleted':False})
                self.check('unpaid booking cancellation','/api/bookings/'+str(bid)+'/cancel','customer','PATCH',{},predicate=lambda v:v.get('status')=='CANCELLED')
                for _ in range(10):
                    _,v,_,_=self.request(cabin_path,'customer')
                    if any(s['id']==f['seatIds'][-2] and s.get('status')=='AVAILABLE' for s in (v or {}).get('seats',[])):
                        break
                    time.sleep(1)
                self.record('cancelled booking releases held seat',any(s['id']==f['seatIds'][-2] and s.get('status')=='AVAILABLE' for s in (v or {}).get('seats',[])),'AVAILABLE','seat state checked after cancellation')
        self.check('owner booking listing','/api/bookings/airline?flightInstanceId='+str(f['flight-instance']),'owner',predicate=lambda v:any(x['id']==booking['id'] for x in v))
        self.check('payment catalogue','/api/payments?size=2','admin',predicate=lambda v:isinstance(v.get('content'),list))
        self.save()
        print('CHECKOUT_READY booking='+str(booking['id'])+'; URL stored privately',flush=True)

    def verify_payment(self):
        if not self.payment_sandbox_ready():
            return
        f=self.state['fixtures']
        if not f.get('booking'):
            self.record('paid booking verification',None,'created booking','unavailable')
            return
        path='/api/bookings/'+str(f['booking'])
        value=self.check('paid booking confirmed',path,'customer',predicate=lambda v:v.get('status')=='CONFIRMED' and v.get('paymentStatus')=='SUCCESS' and bool(v.get('tickets')),expected='CONFIRMED, SUCCESS, nonempty tickets')
        cabin='/api/flight-instance-cabins/flight-instance/'+str(f['flight-instance'])+'/cabin-class-type/ECONOMY'
        self.check('paid seat BOOKED',cabin,'customer',predicate=lambda v:any(s['id']==f['seatIds'][-1] and s.get('status')=='BOOKED' for s in v.get('seats',[])),expected='BOOKED')
        if not value:
            self.record('duplicate payment verification',None,'completed checkout','booking not confirmed')
            return
        self.check('confirmed booking in history','/api/bookings/user/history','customer',predicate=lambda v:any(x['id']==f['booking'] and x.get('status')=='CONFIRMED' for x in v))
        ticket_ids=[t['id'] for t in value['tickets']]
        email=self.state['accounts']['customer']['email']
        def matching():
            _,mail,_,_=self.request('/api/v2/messages?limit=100','',base='http://127.0.0.1:8025')
            return [m for m in (mail or {}).get('items',[]) if email in json.dumps(m) and value.get('bookingReference','missing-reference') in json.dumps(m)]
        messages=matching()
        self.record('booking confirmation email',len(messages)==1,'one matching email with booking reference',str(len(messages))+' matching messages')
        # Read the provider payment ID via the service API, without exposing it.
        _,payments,_,_=self.request('/api/payments/batch/bookings','customer','POST',[f['booking']])
        p=(payments or {}).get(str(f['booking']),{})
        intent=p.get('stripePaymentIntentId') or p.get('gatewayPaymentId')
        if intent and intent.startswith('pi_'):
            self.check('repeat payment verification','/api/payments/verify','customer','POST',{'stripePaymentIntentId':intent},predicate=lambda v:v.get('status') in ('SUCCESS','COMPLETED'))
            time.sleep(3)
            _,again,_,_=self.request(path,'customer')
            self.record('no duplicate tickets',ticket_ids==[t['id'] for t in again.get('tickets',[])],'same ticket IDs','ticket IDs compared')
            self.record('no duplicate notification',len(matching())==len(messages),'unchanged matching email count',str(len(matching())))
        else:
            self.record('repeat payment verification',None,'provider PaymentIntent ID','not exposed by payment response')

    def payment_sandbox_ready(self):
        """Fail closed before any valid booking/payment mutation."""
        try:
            items=json.loads(subprocess.check_output(['docker','inspect','payment-service','notification-service']))
            envs={x['Name'].lstrip('/'):dict(e.split('=',1) for e in x['Config'].get('Env',[]) if '=' in e) for x in items}
            ready=envs['payment-service'].get('STRIPE_API_KEY','').startswith('sk_test_') and envs['notification-service'].get('MAIL_HOST')=='mailhog'
        except Exception:
            ready=False
        self.record('payment sandbox precondition',ready,'Stripe test key and MailHog','test sandbox verified' if ready else 'payment tests stopped; sandbox unverified')
        return ready

    def cleanup(self):
        # Confirmed booking chains remain intact because refunds are unsupported.
        retained=set()
        for r in self.state['records']:
            if 'booking' in r['kind'] and not r['deleted']:
                code,v,_,_=self.request(r['path'],r['role'])
                if code==200 and v.get('status') in ('CONFIRMED','COMPLETED'):
                    retained.update(['booking','flight-instance','instance-cabin','schedule','flight','fare','fare-rule','baggage','aircraft','cabin','seat-map','airline','flight-ancillary','ancillary'])
                    self.record('cleanup retain paid booking chain',None,'safe API cleanup','confirmed booking cannot be cancelled without refunds')
                elif code==200 and v.get('status')!='CANCELLED':
                    self.check('cleanup cancel '+r['kind'],r['path']+'/cancel',r['role'],'PATCH',{},predicate=lambda v:v.get('status')=='CANCELLED')
        order=['unpaid-booking','booking','flight-ancillary','meal','ancillary','baggage','fare-rule','fare','instance-cabin','flight-instance','schedule','flight','seat-map','cabin','aircraft','disposable-airline','airline']
        for kind in order:
            for r in self.state['records']:
                if r['kind']!=kind or r['deleted'] or kind in retained:
                    continue
                code,_,_,elapsed=self.request(r['path'],r['role'],'DELETE')
                r['deleted']=code in (200,204)
                self.record('cleanup '+kind,r['deleted'],'HTTP 200/204', 'HTTP '+str(code), elapsed)
        self.record('cleanup test accounts',None,'supported account deletion API','no user deletion endpoint; four test accounts retained')
        self.save()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase',choices=['prepare','scheduled-fixture','supplemental','verify-payment','cleanup'])
    parser.add_argument('--directory',required=True)
    args=parser.parse_args()
    smoke=Smoke(args.directory)
    if args.phase=='prepare':
        if smoke.state['checks']:
            raise SystemExit('Refusing to prepare twice in the same run directory')
        for name,fn in [('infrastructure',smoke.infrastructure),('accounts',smoke.accounts),('fixtures',smoke.fixtures),('bookings',smoke.bookings)]:
            try:
                fn()
            except Exception as e:
                smoke.record(name+' incomplete',None,'completed phase',type(e).__name__)
    elif args.phase=='scheduled-fixture':
        smoke.scheduled_fixture()
    elif args.phase=='supplemental':
        smoke.supplemental()
    elif args.phase=='verify-payment':
        smoke.verify_payment()
    else:
        smoke.cleanup()
    smoke.save()
    counts={s:sum(c['status']==s for c in smoke.state['checks']) for s in ['PASS','FAIL','BLOCKED']}
    print('RESULTS '+json.dumps(counts))
    raise SystemExit(1 if counts['FAIL'] or counts['BLOCKED'] else 0)


if __name__=='__main__':
    main()
