import json,urllib.request,urllib.error,time,secrets,os
BASE='https://api.vnairline.tech'; checks=[]; token=None

def call(path,method='GET',body=None,auth=True):
 headers={'Content-Type':'application/json'}
 if auth and token: headers['Authorization']='Bearer '+token
 req=urllib.request.Request(BASE+path,data=json.dumps(body).encode() if body is not None else None,headers=headers,method=method)
 try:
  with urllib.request.urlopen(req,timeout=25) as r: return r.status,json.loads(r.read() or b'null')
 except urllib.error.HTTPError as e:
  try: v=json.loads(e.read())
  except Exception: v={}
  return e.code,v

def check(name,path,method='GET',body=None,codes=(200,),predicate=None,auth=True):
 status,v=call(path,method,body,auth)
 ok=status in codes and (predicate(v) if predicate and status in codes else True)
 item={'name':name,'method':method,'path':path,'status':status,'pass':ok}
 if status not in codes: item['error']=str(v.get('message',v.get('error','')))[:180] if isinstance(v,dict) else 'Unexpected response'
 if isinstance(v,dict) and 'totalElements' in v: item['totalElements']=v['totalElements']
 checks.append(item); return v if status in codes else None

v=check('admin login','/auth/login','POST',{'email':os.environ['ADMIN_EMAIL'],'password':os.environ['ADMIN_PASSWORD']},predicate=lambda v:bool(v.get('jwt')))
if not v: print(json.dumps(checks)); raise SystemExit(1)
token=v['jwt']
check('admin profile','/api/users/profile',predicate=lambda v:v.get('role')=='ROLE_SYSTEM_ADMIN')
for path in ['/api/users','/api/airlines?size=10','/api/airlines/dropdown','/api/cities?size=10','/api/airports','/api/flights?size=10','/api/flight-instances?size=10','/api/payments?size=10','/api/bookings/dashboard-stats/super-admin','/api/bookings/statistics/super-admin','/api/bookings/route-performance/super-admin','/api/bookings/airport-performance/super-admin','/api/bookings/airline-performance/super-admin']:
 check('admin page data',path,predicate=lambda v:isinstance(v,(dict,list)))
run='ADM'+secrets.token_hex(3).upper(); city=None; airport=None
try:
 body={'name':'Admin Smoke '+run,'cityCode':run,'countryCode':'TH','countryName':'Thailand','timeZoneOffset':'+07:00'}
 city=check('create city','/api/cities','POST',body,codes=(200,201),predicate=lambda v:v.get('cityCode')==run)
 if city and city.get('id'):
  cp='/api/cities/'+str(city['id']); check('read city',cp,predicate=lambda v:v.get('cityCode')==run)
  body['name']+=' Updated'; check('update city',cp,'PUT',body,predicate=lambda v:v.get('name')==body['name'])
  check('city update persisted',cp,predicate=lambda v:v.get('name')==body['name'])
  check('search city','/api/cities/search?keyword='+run,predicate=lambda v:any(x.get('id')==city['id'] for x in v.get('content',[])))
  check('city code exists','/api/cities/exists/'+run,predicate=lambda v:v is True)
  existing=call('/api/airports')[1]; existing=existing if isinstance(existing,list) else existing.get('content',[])
  used={x.get('iataCode') for x in existing}
  import itertools,string
  code=next(''.join(x) for x in itertools.product('Q',string.ascii_uppercase,string.ascii_uppercase) if ''.join(x) not in used)
  ab={'iataCode':code,'name':'Admin Smoke '+run,'cityId':city['id'],'timeZone':'Asia/Bangkok','address':{'street':'Smoke test','postalCode':'00000'},'geoCode':{'latitude':13.75,'longitude':100.5}}
  airport=check('create airport','/api/airports','POST',ab,codes=(200,201),predicate=lambda v:v.get('iataCode')==code)
  if airport and airport.get('id'):
   ap='/api/airports/'+str(airport['id']); check('read airport',ap,predicate=lambda v:v.get('iataCode')==code)
   ab['name']+=' Updated'; check('update airport',ap,'PUT',ab,predicate=lambda v:v.get('name')==ab['name'])
   check('airport update persisted',ap,predicate=lambda v:v.get('name')==ab['name'])
 check('city validation rejects empty body','/api/cities','POST',{},codes=(400,))
 check('airport validation rejects empty body','/api/airports','POST',{},codes=(400,))
finally:
 if airport and airport.get('id'): check('cleanup airport','/api/airports/'+str(airport['id']),'DELETE',codes=(200,204))
 if city and city.get('id'): check('cleanup city','/api/cities/'+str(city['id']),'DELETE',codes=(200,204))
print(json.dumps({'checks':checks,'passed':sum(x['pass'] for x in checks),'failed':sum(not x['pass'] for x in checks),'fixtures':{'city':city.get('id') if city else None,'airport':airport.get('id') if airport else None}},indent=2))

