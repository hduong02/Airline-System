// Implements only the Stripe resources consumed by this application. No proxy/fallback.
const http = require('node:http');
const { randomUUID } = require('node:crypto');
const sessions = new Map(), intents = new Map();
const outcome = process.env.SIM_OUTCOME || 'paid';
const delay = Number(process.env.SIM_LATENCY_MS || 0);
const port = Number(process.env.PORT || 8080);
let requests = 0;
const server = http.createServer(async (req, res) => {
  const path = new URL(req.url, 'http://simulator').pathname;
  const send = (status, body) => { res.writeHead(status, {'content-type':'application/json'}); res.end(JSON.stringify(body)); };
  if (path === '/health') return send(200, {status:'UP', sessions:sessions.size, requests, outcome});
  requests++;
  console.log(JSON.stringify({time:new Date().toISOString(), method:req.method, path}));
  if (delay) await new Promise(resolve => setTimeout(resolve, delay));
  if (outcome === 'timeout') return;
  if (outcome === '429' || outcome === '500') return send(Number(outcome), {error:{type:outcome==='429'?'rate_limit_error':'api_error',message:'Injected simulator error'}});
  if (req.headers.authorization !== 'Bearer sk_test_simulator') return send(401, {error:{type:'authentication_error',message:'Simulator key required'}});
  let raw = ''; for await (const chunk of req) raw += chunk;
  const form = new URLSearchParams(raw);
  if (path === '/v1/checkout/sessions' && req.method === 'POST') {
    const metadata = Object.fromEntries(['user_id','payment_id','booking_id'].map(k=>[k,form.get(`metadata[${k}]`)]));
    if (Object.values(metadata).some(v=>!v)) return send(400,{error:{type:'invalid_request_error',message:'Missing application metadata'}});
    const id = 'cs_sim_' + randomUUID(), pi = 'pi_sim_' + randomUUID();
    const amount = Number(form.get('line_items[0][price_data][unit_amount]'));
    const record = {id,object:'checkout.session',url:'http://stripe-simulator:8080/checkout/'+id,
      status:'open',payment_status:'unpaid',payment_intent:pi,metadata,amount_total:amount,
      currency:'usd',expires_at:Number(form.get('expires_at')),created:Math.floor(Date.now()/1000)};
    sessions.set(id,record);
    intents.set(pi,{id:pi,object:'payment_intent',status:'succeeded',metadata,amount,currency:'usd'});
    return send(200,record);
  }
  const match = path.match(/^\/v1\/checkout\/sessions\/([^/]+)(\/expire)?$/);
  if (match) {
    const record = sessions.get(match[1]);
    if (!record) return send(404,{error:{type:'invalid_request_error',message:'No such checkout session'}});
    if (req.method === 'POST' && match[2]) {
      if (record.status === 'complete') return send(400,{error:{type:'invalid_request_error',message:'Session already complete'}});
      record.status='expired'; return send(200,record);
    }
    if (req.method === 'GET' && !match[2]) {
      if (record.status === 'open' && (outcome === 'paid' || (outcome === 'delayed' && Date.now()/1000-record.created>=5))) {
        record.status='complete'; record.payment_status='paid';
      }
      return send(200,record);
    }
  }
  if (path.startsWith('/v1/payment_intents/') && req.method === 'GET') {
    const intent = intents.get(path.split('/').pop());
    return intent ? send(200,intent) : send(404,{error:{type:'invalid_request_error',message:'No such payment intent'}});
  }
  return send(501,{error:{type:'api_error',message:'Endpoint not modeled'}});
});
server.requestTimeout = 60000;
server.listen(port,'0.0.0.0',()=>console.log(JSON.stringify({listening:port,outcome})));
