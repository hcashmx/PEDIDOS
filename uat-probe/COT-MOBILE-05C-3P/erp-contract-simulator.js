'use strict';
const http = require('node:http');
const fs = require('node:fs');
const crypto = require('node:crypto');
const { URL } = require('node:url');

const host = '127.0.0.1';
const port = Number(process.env.COT_ERP_SIM_PORT || 18081);
const issuer = 'effyflow-sync';
const audience = 'erp-cotizaciones';
const kid = 'uat-cot-05c3a';
const techUser = process.env.COT_ERP_SIM_USER;
const techPassword = process.env.COT_ERP_SIM_PASSWORD;
const techBearer = process.env.COT_ERP_SIM_BEARER;
const controlKey = process.env.COT_ERP_SIM_CONTROL_KEY;
const keyFile = process.env.COT_ERP_SIM_PUBLIC_KEYS_FILE;
if (![techUser, techPassword, techBearer, controlKey, keyFile].every(Boolean)) throw new Error('UAT simulator config missing');
const keyLine = fs.readFileSync(keyFile, 'utf8').split(/\r?\n/).find(x => x.startsWith(kid + '='));
if (!keyLine) throw new Error('Configured kid not present in UAT trust file');
const der = Buffer.from(keyLine.slice(kid.length + 1).trim(), 'base64');
const publicKey = crypto.createPublicKey({key: der, format: 'der', type: 'spki'});
const authorized = new Map([[73001,74001],[73002,74002],[73003,74003]]);
const nowIso = () => new Date().toISOString();
const uuidFor = n => `00000000-0000-4000-8000-${String(n).padStart(12,'0')}`;
const docs = [];
for (let id=400; id<=500; id++) docs.push({erpId:id,cotizacionUuid:uuidFor(id),estado:'BORRADOR',version:1,agenteId:74001,updatedAt:'2026-10-13T18:00:00Z'});
docs.push(
 {erpId:501,cotizacionUuid:'11111111-1111-4111-8111-111111111111',estado:'BORRADOR',version:1,agenteId:74001,updatedAt:'2026-10-13T18:00:00Z'},
 {erpId:502,cotizacionUuid:'22222222-2222-4222-8222-222222222222',estado:'VIGENTE',version:1,agenteId:74001,updatedAt:'2026-10-13T18:01:00Z'},
 {erpId:503,cotizacionUuid:'33333333-3333-4333-8333-333333333333',estado:'BORRADOR',version:1,agenteId:74002,updatedAt:'2026-10-13T18:02:00Z'},
 {erpId:504,cotizacionUuid:'44444444-4444-4444-8444-444444444444',estado:'BORRADOR',version:1,agenteId:74003,updatedAt:'2026-10-13T18:03:00Z'}
);
let events = [
 {eventId:101,cotizacionUuid:docs[101].cotizacionUuid,erpId:501,estado:'VIGENTE',version:2,changedAt:'2026-10-13T18:05:00Z',eventType:'EMITIDA',agenteVentasId:74001,agenteAnteriorId:null,usuarioAnteriorId:null,usuarioCreoId:73001},
 {eventId:102,cotizacionUuid:docs[102].cotizacionUuid,erpId:502,estado:'CANCELADA',version:2,changedAt:'2026-10-13T18:06:00Z',eventType:'CANCELADA',agenteVentasId:74001,agenteAnteriorId:null,usuarioAnteriorId:null,usuarioCreoId:73001},
 {eventId:103,cotizacionUuid:docs[102].cotizacionUuid,erpId:502,estado:'BORRADOR',version:3,changedAt:'2026-10-13T18:07:00Z',eventType:'REASIGNADA',agenteVentasId:74002,agenteAnteriorId:74001,usuarioAnteriorId:73001,usuarioCreoId:73001},
 {eventId:104,cotizacionUuid:docs[103].cotizacionUuid,erpId:503,estado:'VIGENTE',version:2,changedAt:'2026-10-13T18:08:00Z',eventType:'EMITIDA',agenteVentasId:74002,agenteAnteriorId:null,usuarioAnteriorId:null,usuarioCreoId:73002},
 {eventId:105,cotizacionUuid:docs[101].cotizacionUuid,erpId:501,estado:'VIGENTE',version:3,changedAt:'2026-10-13T18:09:00Z',eventType:'REASIGNADA',agenteVentasId:74002,agenteAnteriorId:74001,usuarioAnteriorId:73001,usuarioCreoId:73001}
 ,{eventId:106,cotizacionUuid:docs[0].cotizacionUuid,erpId:400,estado:'VIGENTE',version:2,changedAt:'2026-10-13T18:10:00Z',eventType:'ACTUALIZADA',agenteVentasId:74001,agenteAnteriorId:null,usuarioAnteriorId:null,usuarioCreoId:73001}
 ,{eventId:107,cotizacionUuid:docs[0].cotizacionUuid,erpId:400,estado:'BORRADOR',version:1,changedAt:'2026-10-13T18:00:00Z',eventType:'ACTUALIZADA',agenteVentasId:74001,agenteAnteriorId:null,usuarioAnteriorId:null,usuarioCreoId:73001}
 ,{eventId:108,cotizacionUuid:docs[0].cotizacionUuid,erpId:400,estado:'VIGENTE',version:2,changedAt:'2026-10-13T18:10:00Z',eventType:'ACTUALIZADA',agenteVentasId:74001,agenteAnteriorId:null,usuarioAnteriorId:null,usuarioCreoId:73001}
 ,{eventId:109,cotizacionUuid:docs[0].cotizacionUuid,erpId:400,estado:'VIGENTE',version:3,changedAt:'2026-10-13T18:11:00Z',eventType:'REASIGNADA',agenteVentasId:74002,agenteAnteriorId:74001,usuarioAnteriorId:73001,usuarioCreoId:73001}
 ,{eventId:110,cotizacionUuid:docs[0].cotizacionUuid,erpId:400,estado:'VIGENTE',version:2,changedAt:'2026-10-13T18:10:00Z',eventType:'ACTUALIZADA',agenteVentasId:74001,agenteAnteriorId:null,usuarioAnteriorId:null,usuarioCreoId:73001}
 ,{eventId:111,cotizacionUuid:docs[0].cotizacionUuid,erpId:400,estado:'BORRADOR',version:4,changedAt:'2026-10-13T18:12:00Z',eventType:'REASIGNACION_REVERTIDA',agenteVentasId:74001,agenteAnteriorId:74002,usuarioAnteriorId:73001,usuarioCreoId:73001}
];
const control = { failNext: {}, delayNext: {}, cutNext: {}, rejectAssertion: false, revokedUsers: new Set() };
const activeDelayedReads = { bootstrap: 0, snapshot: 0, events: 0 };
const counts = {login:0,bootstrap:0,snapshot:0,events:0,assertionsValid:0,assertionsRejected:0};
function send(res, code, obj, headers={}) { const body=Buffer.from(JSON.stringify(obj)); res.writeHead(code,{'content-type':'application/json; charset=utf-8','content-length':body.length,...headers}); res.end(body); }
function safeLog(entry) { process.stdout.write(JSON.stringify({at:nowIso(),...entry})+'\n'); }
function timingEqual(a,b) { const x=Buffer.from(String(a||'')), y=Buffer.from(String(b||'')); return x.length===y.length && crypto.timingSafeEqual(x,y); }
function parseAssertion(token) {
 const parts=String(token||'').split('.'); if(parts.length!==3) return null;
 let h,p; try { h=JSON.parse(Buffer.from(parts[0],'base64url')); p=JSON.parse(Buffer.from(parts[1],'base64url')); } catch { return null; }
 if(h.alg!=='RS256'||h.typ!=='JWT'||h.kid!==kid) return null;
 let sig; try { sig=Buffer.from(parts[2],'base64url'); } catch { return null; }
 if(!crypto.verify('RSA-SHA256',Buffer.from(parts[0]+'.'+parts[1]),publicKey,sig)) return null;
 const now=Math.floor(Date.now()/1000), aud=Array.isArray(p.aud)?p.aud:[p.aud];
 if(p.iss!==issuer||!aud.includes(audience)||p.operation!=='LEER_ESTADOS_COTIZACIONES') return null;
 if(!Number.isSafeInteger(p.iat)||!Number.isSafeInteger(p.exp)||p.iat>now+10||p.iat<now-300||p.exp<=now||p.exp-p.iat>300) return null;
 if(typeof p.jti!=='string'||!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(p.jti)) return null;
 if(!Number.isSafeInteger(p.usuarioErpId)||!Number.isSafeInteger(p.agenteErpId)||String(p.usuarioErpId)!==p.sub) return null;
 if(!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(p.dispositivoId||'')) return null;
 const agent=authorized.get(p.usuarioErpId); if(!agent||agent!==p.agenteErpId) return null;
 return p;
}
function readBody(req) { return new Promise((resolve,reject)=>{let b='';req.on('data',x=>{b+=x;if(b.length>100000)reject(new Error('body too large'));});req.on('end',()=>{try{resolve(b?JSON.parse(b):{});}catch(e){reject(e);}});}); }
const server=http.createServer(async(req,res)=>{
 const requestStarted=Date.now();
 const log=entry=>safeLog({...entry,durationMs:Date.now()-requestStarted});
 const url=new URL(req.url,`http://${host}:${port}`);
 if(req.method==='GET'&&url.pathname==='/health'){return send(res,200,{status:'UP',component:'erp-contract-simulator',simulation:true});}
 if(req.method==='GET'&&url.pathname==='/__control/status'){
  if(!timingEqual(req.headers['x-uat-control-key'],controlKey)) return send(res,403,{error:'forbidden'});
  return send(res,200,{activeDelayedReads:{...activeDelayedReads}});
 }
 if(req.method==='POST'&&url.pathname==='/__control'){
  if(!timingEqual(req.headers['x-uat-control-key'],controlKey)) return send(res,403,{error:'forbidden'});
  const b=await readBody(req).catch(()=>null); if(!b) return send(res,400,{error:'invalid'});
  if(b.failNext&&['bootstrap','snapshot','events'].includes(b.operation)) {
   const previous=control.failNext[b.operation]||{count:0,status:503,forDeviceId:''};
   control.failNext[b.operation]={count:previous.count+Number(b.count||1),
    status:[429,502,503,504].includes(Number(b.status))?Number(b.status):503,
    forDeviceId:String(b.forDeviceId||'')};
  }
  if(b.delayNext&&['bootstrap','snapshot','events'].includes(b.operation)
      &&Number.isInteger(Number(b.delayMs))&&Number(b.delayMs)>=1&&Number(b.delayMs)<=30000)
   control.delayNext[b.operation]={count:Number(b.count||1),delayMs:Number(b.delayMs),forDeviceId:String(b.forDeviceId||'')};
  if(b.cutNext&&['bootstrap','snapshot','events'].includes(b.operation))
   control.cutNext[b.operation]=(control.cutNext[b.operation]||0)+Number(b.count||1);
  if(Array.isArray(b.appendEvents)) events.push(...b.appendEvents);
  if(Array.isArray(b.replaceEvents)) events=b.replaceEvents;
  if(typeof b.rejectAssertion==='boolean') control.rejectAssertion=b.rejectAssertion;
  if(b.revokeUserId) control.revokedUsers.add(Number(b.revokeUserId));
  if(b.restoreUserId) control.revokedUsers.delete(Number(b.restoreUserId));
  return send(res,200,{ok:true});
 }
 if(req.method==='POST'&&url.pathname==='/api/auth/login'){
  const b=await readBody(req).catch(()=>null); counts.login++;
  const supplied=b?.usernameOrEmail||b?.username||b?.email;
  if(!timingEqual(supplied,techUser)||!timingEqual(b?.password,techPassword)){log({route:'login',status:401});return send(res,401,{error:'invalid credentials'});}
  log({route:'login',status:200}); return send(res,200,{token:techBearer,tokenType:'Bearer'});
 }
 if(!url.pathname.startsWith('/api/cotizaciones/sync/')) return send(res,404,{error:'not_found'});
 if(!timingEqual(req.headers.authorization,`Bearer ${techBearer}`)) return send(res,401,{error:'technical_auth_required'});
 const assertion=parseAssertion(req.headers['x-cotizacion-assertion']);
 if(!assertion||control.rejectAssertion){counts.assertionsRejected++;log({route:url.pathname,status:401,assertion:'rejected'});return send(res,401,{error:'invalid read assertion'});}
 counts.assertionsValid++;
 const failure=url.pathname.endsWith('/bootstrap')?'bootstrap':url.pathname.endsWith('/snapshot')?'snapshot':url.pathname.endsWith('/estados')?'events':null;
 if(failure&&control.delayNext[failure]?.count>0
      &&(!control.delayNext[failure].forDeviceId||control.delayNext[failure].forDeviceId===assertion.dispositivoId)){
  const pending=control.delayNext[failure]; pending.count--; activeDelayedReads[failure]++;
  log({event:'delayed_read_started',operation:failure,userId:assertion.usuarioErpId});
  await new Promise(resolve=>setTimeout(resolve,pending.delayMs));
  activeDelayedReads[failure]--;
  log({event:'delayed_read_finished',operation:failure,userId:assertion.usuarioErpId});
 }
 if(control.revokedUsers.has(assertion.usuarioErpId)){log({route:url.pathname,status:403,userId:assertion.usuarioErpId});return send(res,403,{error:'commercial authorization denied'});}
 if(failure&&control.cutNext[failure]>0){
  control.cutNext[failure]--; res.writeHead(200,{'content-type':'application/json','content-length':'128'}); res.write('{"partial":'); res.destroy();
  log({route:failure,status:'CUT',userId:assertion.usuarioErpId}); return;
 }
 if(failure&&control.failNext[failure]?.count>0
      &&(!control.failNext[failure].forDeviceId||control.failNext[failure].forDeviceId===assertion.dispositivoId)){
  const pending=control.failNext[failure]; pending.count--; const status=pending.status;
  log({route:failure,status,userId:assertion.usuarioErpId}); return send(res,status,{error:'synthetic temporary outage'},{'retry-after':'1'});
 }
 const user=assertion.usuarioErpId, agent=assertion.agenteErpId;
 if(req.method==='GET'&&url.pathname==='/api/cotizaciones/sync/bootstrap'){
  counts.bootstrap++; log({route:'bootstrap',status:200,userId:user,agentId:agent});
  return send(res,200,{startCursor:100,maxCotizacionId:user===73001?502:user===73003?504:503});
 }
 if(req.method==='GET'&&url.pathname==='/api/cotizaciones/sync/snapshot'){
  counts.snapshot++; const after=Number(url.searchParams.get('afterId')||0), max=Number(url.searchParams.get('maxCotizacionId')), limit=Math.min(100,Math.max(1,Number(url.searchParams.get('limit')||50)));
  const visible=docs.filter(d=>d.erpId<=max&&(d.agenteId===agent||(d.agenteId==null&&d.usuarioCreoId===user))).sort((a,b)=>a.erpId-b.erpId);
  const page=visible.filter(d=>d.erpId>after).slice(0,limit); const hasMore=visible.some(d=>d.erpId>(page.at(-1)?.erpId??after));
  log({route:'snapshot',status:200,userId:user,agentId:agent,afterId:after,count:page.length,hasMore});
  return send(res,200,{documents:page,nextAfterId:page.at(-1)?.erpId??after,hasMore});
 }
 if(req.method==='GET'&&url.pathname==='/api/cotizaciones/sync/estados'){
  counts.events++; const cursor=Number(url.searchParams.get('cursor')||0),limit=Math.min(100,Math.max(1,Number(url.searchParams.get('limit')||50)));
  const filtered=events.filter(e=>e.eventId>cursor&&(e.forUserId==null||e.forUserId===user)&&(e.agenteVentasId===agent||e.agenteAnteriorId===agent||e.usuarioAnteriorId===user||(e.agenteVentasId==null&&e.usuarioCreoId===user))).sort((a,b)=>a.eventId-b.eventId);
  const batch=filtered.slice(0,limit).map(e=>({eventId:e.eventId,cotizacionUuid:e.cotizacionUuid,erpId:e.erpId,estado:e.agenteVentasId===agent?e.estado:null,version:e.version,changedAt:e.changedAt,eventType:e.eventType,agenteId:e.agenteVentasId===agent?agent:null,visible:e.agenteVentasId===agent}));
  const next=batch.at(-1)?.eventId??cursor,hasMore=filtered.length>batch.length;
  log({route:'events',status:200,userId:user,agentId:agent,cursor,count:batch.length,nextCursor:next,hasMore});
  return send(res,200,{events:batch,nextCursor:next,hasMore});
 }
 return send(res,404,{error:'not_found'});
});
server.listen(port,host,()=>safeLog({event:'started',host,port,kid,issuer,audience,syntheticUsers:[...authorized.keys()]}));
process.on('SIGTERM',()=>server.close(()=>process.exit(0)));
