"""Owner-only live VPS smoke pass. Reuses vps_smoke; private state stays on VPS."""
import datetime as dt
import json
import secrets
import sys
import time
from vps_smoke import Smoke

s = Smoke(sys.argv[2])
f = s.state['fixtures']
phase = sys.argv[1]

if phase == 'init':
    credentials = json.load(sys.stdin)
    v = s.check('fresh UI owner API login', '/auth/login', method='POST', body=credentials,
                predicate=lambda v: bool(v.get('jwt')))
    if not v:
        raise SystemExit(1)
    s.state['accounts']['owner'] = {'jwt': v['jwt'], 'email': credentials['email']}
    s.check('owner role', '/api/users/profile', 'owner', predicate=lambda v:v.get('role')=='ROLE_AIRLINE_OWNER')
    a = s.check('UI onboarding persisted airline', '/api/airlines/admin', 'owner', predicate=lambda v:v.get('iataCode')=='QH')
    if a:
        f['airline'] = a['id']
        s.state['records'].append({'kind':'airline','path':'/api/airlines/'+str(a['id']),'id':a['id'],'role':'owner','deleted':False})
        s.record('onboarding airline status agrees with live screen', a.get('status')=='ACTIVE', 'ACTIVE', str(a.get('status')))
    for role, enum in [('owner2','ROLE_AIRLINE_OWNER'), ('customer','ROLE_USER')]:
        body={'email':s.state['run']+'-'+role+'@example.invalid','password':secrets.token_urlsafe(20),'fullName':'Owner smoke '+role,'phone':'+66000000000','role':enum}
        v=s.check(role+' registration','/auth/signup',method='POST',body=body,predicate=lambda v:bool(v.get('jwt')))
        if v:s.state['accounts'][role]={'jwt':v['jwt'],'email':body['email']}
    s.save()

elif phase == 'catalog':
    airports=s.check('airport dropdown','/api/airports','owner',predicate=lambda v:len(v if isinstance(v,list) else v.get('content',[]))>=2)
    airports=airports if isinstance(airports,list) else airports['content']
    f['departureAirportId'],f['arrivalAirportId']=[x['id'] for x in airports[:2]]
    aircraft=s.create('aircraft','/api/aircrafts',{'code':s.state['run'],'model':'OWNER-SMOKE-12','manufacturer':'Smoke Test','seatingCapacity':12,'economySeats':12,'status':'ACTIVE','isAvailable':True,'currentAirportId':f['departureAirportId']})
    cabin=s.create('cabin','/api/cabin-classes',{'name':'ECONOMY','code':'Y','aircraftId':aircraft['id'],'isActive':True,'isBookable':True})
    seatmap=s.create('seat-map','/api/seat-maps',{'name':s.state['run'],'cabinClassId':cabin['id'],'totalRows':3,'leftSeatsPerRow':2,'rightSeatsPerRow':2})
    flight=s.create('flight','/api/flights',{'flightNumber':'QH9016','aircraftId':aircraft['id'],'departureAirportId':f['departureAirportId'],'arrivalAirportId':f['arrivalAirportId'],'status':'SCHEDULED'})
    farebody={'name':s.state['run'],'rbdCode':'Y','flightId':flight['id'],'cabinClassId':cabin['id'],'baseFare':20,'taxesAndFees':2,'airlineFees':1,'currentPrice':23}
    fare=s.create('fare','/api/fares',farebody)
    s.check('fare cabin contract','/api/fares/'+str(fare['id']),'owner',predicate=lambda v:v.get('cabinClass')=='ECONOMY')
    s.create('fare-rule','/api/fare-rules',{'ruleName':s.state['run'],'fareId':fare['id'],'airlineId':f['airline'],'isRefundable':False,'isChangeable':False,'changeFee':0,'cancellationFee':0})
    s.create('baggage','/api/baggage-policies',{'name':s.state['run'],'fareId':fare['id'],'cabinBaggageMaxWeight':7,'cabinBaggagePieces':1,'checkInBaggageMaxWeight':20,'checkInBaggagePieces':1,'freeCheckedBagsAllowance':1,'priorityBaggage':False,'extraBaggageAllowance':False})
    a=s.create('ancillary','/api/ancillaries',{'type':'BAGGAGE','name':s.state['run'],'description':'Dedicated owner smoke fixture'})
    s.create('flight-ancillary','/api/flight-cabin-ancillaries',{'flightId':flight['id'],'cabinClassId':cabin['id'],'ancillaryId':a['id'],'available':True,'maxQuantity':1,'price':3,'includedInFare':False})
    meal=s.create('meal','/api/meals',{'code':'OS'+secrets.token_hex(3).upper(),'name':s.state['run'],'description':'Dedicated owner smoke fixture','mealType':'VEGETARIAN','isActive':True})
    s.create('flight-meal','/api/flight-meals',{'flightId':flight['id'],'mealId':meal['id'],'available':True,'price':4,'displayOrder':1})
    insurance=s.create('insurance-ancillary','/api/ancillaries',{'type':'TRAVEL_PROTECTION','name':s.state['run']+' protection','description':'Dedicated owner smoke fixture'})
    s.create('insurance-coverage','/api/insurance-coverages',{'ancillaryId':insurance['id'],'coverageType':'BAGGAGE_LOSS','name':s.state['run'],'coverageAmount':100,'isFlat':True,'active':True})
    date=(dt.datetime.now(dt.timezone(dt.timedelta(hours=7))).date()+dt.timedelta(days=45)).isoformat()
    f['departureDate']=date
    schedule=s.create('schedule','/api/flight-schedules',{'flightId':flight['id'],'departureTime':'10:00:00','arrivalTime':'12:00:00','startDate':date,'endDate':date,'operatingDays':[dt.date.fromisoformat(date).strftime('%A').upper()],'isActive':True})
    listing=s.check('scheduled instance generated','/api/flight-instances?size=100','owner',predicate=lambda v:any(x.get('flightId')==flight['id'] for x in v.get('content',[])))
    if listing:
        instance=next(x for x in listing['content'] if x.get('flightId')==flight['id'])
        f['flight-instance']=instance['id']
        s.state['records'].append({'kind':'flight-instance','path':'/api/flight-instances/'+str(instance['id']),'id':instance['id'],'role':'owner','deleted':False})
        path='/api/flight-instance-cabins/flight-instance/'+str(instance['id'])+'/cabin-class-type/ECONOMY'
        for attempt in range(10):
            code,value,_,_=s.request(path,'owner')
            if code==200 and len((value or {}).get('seats',[]))==12:break
            time.sleep(2)
        s.record('Kafka seat generation',code==200 and len((value or {}).get('seats',[]))==12,'12 seats','HTTP '+str(code)+'; seats='+str(len((value or {}).get('seats',[]))))
        if code==200 and value:
            f['instance-cabin']=value['id']
            f['seatIds']=[x['id'] for x in value.get('seats',[])]
            s.state['records'].append({'kind':'instance-cabin','path':'/api/flight-instance-cabins/'+str(value['id']),'id':value['id'],'role':'owner','deleted':False})
    s.save()

elif phase == 'reads-updates':
    for r in list(s.state['records']):
        if r['deleted']:continue
        kind=r['kind']
        path='/api/airlines/admin' if kind=='airline' else r['path']
        v=s.check(kind+' detail read',path,'owner',predicate=lambda v:v.get('id')==r['id'])
        if not v:continue
        changes={'aircraft':{'model':'OWNER-SMOKE-12-UPDATED'},'seat-map':{'name':s.state['run']+' updated'},'fare':{'fareLabel':'Owner Smoke Updated'},'fare-rule':{'ruleName':s.state['run']+' updated'},'baggage':{'name':s.state['run']+' updated'},'ancillary':{'name':s.state['run']+' updated'},'meal':{'name':s.state['run']+' updated'},'insurance-coverage':{'coverageAmount':150},'flight-meal':{'price':5},'flight-ancillary':{'price':4}}
        if kind in changes:
            body={**v,**changes[kind]}
            if kind=='flight-meal': body['mealId']=f['meal']
            if kind=='flight-ancillary': body['ancillaryId']=f['ancillary']
            key=next(iter(changes[kind])); expected=body[key]
            s.check(kind+' update',r['path'],'owner','PUT',body,predicate=lambda v,key=key,expected=expected:v.get(key)==expected)
    for path in ['/api/bookings/airline','/api/bookings/statistics/airline','/api/bookings/route-performance/airline','/api/bookings/airport-performance/airline','/api/payments?size=20','/api/coupons?size=20','/api/meals/airline','/api/flight-schedules','/api/aircrafts','/api/flights/airline?size=20']:
        s.check('owner page data '+path,path,'owner')
    s.check('owner denied admin airline list','/api/airlines','owner',codes=(403,),expected='HTTP 403')
    s.check('unauthenticated owner data denied','/api/bookings/airline',codes=(401,),expected='HTTP 401')
    s.check('foreign aircraft update denied','/api/aircrafts/'+str(f['aircraft']),'owner2','PUT',{'model':'OWNER-SMOKE-12-UPDATED'},codes=(403,404),expected='HTTP 403/404')
    _,v,_,_=s.request('/api/flights/'+str(f['flight']),'owner')
    s.check('foreign flight update denied','/api/flights/'+str(f['flight']),'owner2','PUT',{'flightNumber':v.get('flightNumber'),'aircraftId':f['aircraft'],'departureAirportId':f['departureAirportId'],'arrivalAirportId':f['arrivalAirportId'],'status':'SCHEDULED'},codes=(403,404),expected='HTTP 403/404')
    if f.get('other-airline'):
        s.check('foreign aircraft list isolation','/api/aircrafts','owner2',predicate=lambda v:not v)
        s.check('foreign departure list isolation','/api/flight-instances?size=20','owner2',predicate=lambda v:v.get('totalElements')==0)

elif phase == 'cleanup':
    for r in s.state['records']:
        if r['kind']=='booking' and not r['deleted']:
            s.check('cleanup cancel unpaid booking',r['path']+'/cancel',r['role'],'PATCH',{},predicate=lambda v:v.get('status')=='CANCELLED')
    order=['booking','flight-meal','insurance-coverage','insurance-ancillary','flight-ancillary','meal','ancillary','baggage','fare-rule','fare','manual-instance-cabin','instance-cabin','manual-instance','flight-instance','schedule','flight','seat-map','cabin','ui-aircraft','aircraft','other-airline','airline']
    for kind in order:
        for r in s.state['records']:
            if r['kind']!=kind or r['deleted']:continue
            code,_,_,_=s.request(r['path'],r['role'],'DELETE')
            r['deleted']=code in (200,204)
            s.record(kind+' cleanup',r['deleted'],'HTTP 200/204','HTTP '+str(code))
    s.record('test account cleanup',None,'supported user deletion API','no user-delete endpoint; '+str(len(s.state['accounts']))+' synthetic accounts remain')

s.save()
counts={k:sum(x['status']==k and not x.get('superseded') for x in s.state['checks']) for k in ['PASS','FAIL','BLOCKED']}
print('RESULTS '+json.dumps(counts))
raise SystemExit(1 if counts['FAIL'] or counts['BLOCKED'] else 0)
