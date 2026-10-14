'use strict';
const http = require('node:http');
const crypto = require('node:crypto');

const host = '127.0.0.1';
const port = Number(process.env.COT_SYNC_PROXY_PORT || 18083);
const upstreamPort = Number(process.env.COT_SYNC_PROXY_UPSTREAM_PORT || 18082);
let dropAckResponses = Math.max(0, Number(process.env.COT_SYNC_PROXY_DROP_ACKS || 0));
const mutateEventId = Number(process.env.COT_SYNC_PROXY_MUTATE_EVENT_ID || 0);
const mutateEventState = process.env.COT_SYNC_PROXY_MUTATE_EVENT_STATE || 'CONTRADICTORIO_UAT';
const controlKey = process.env.COT_SYNC_PROXY_CONTROL_KEY || '';
if (!controlKey) throw new Error('UAT proxy control key is required');
const delayNext = new Map();
const cutNext = new Map();
const activeDelayedResponses = new Map();

function log(entry) { process.stdout.write(JSON.stringify({ at: new Date().toISOString(), ...entry }) + '\n'); }
function operation(req) {
  if (req.url.endsWith('/ack')) return 'ACK';
  if (req.url.includes('/eventos')) return 'EVENTOS';
  if (req.url.includes('/snapshot')) return 'SNAPSHOT';
  if (req.url.endsWith('/revalidar')) return 'REVALIDAR';
  return req.method;
}
function cleanHeaders(headers) {
  const result = { ...headers };
  for (const key of ['connection', 'transfer-encoding', 'keep-alive', 'proxy-connection', 'upgrade']) delete result[key];
  return result;
}
function safeKey(value) {
  const a = Buffer.from(String(value || ''));
  const b = Buffer.from(controlKey);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}
function controlOperation(value) { return ['ACK', 'EVENTOS', 'SNAPSHOT', 'REVALIDAR'].includes(value); }
function sendJson(res, status, body) {
  const bytes = Buffer.from(JSON.stringify(body));
  res.writeHead(status, { 'content-type': 'application/json', 'content-length': bytes.length });
  res.end(bytes);
}

const server = http.createServer((req, res) => {
  const started = Date.now();
  const startedAt = new Date(started).toISOString();
  if (req.method === 'GET' && req.url === '/__proxy_health') {
    sendJson(res, 200, { status: 'UP', component: 'uat-sync-proxy' });
    return;
  }
  if (req.url.startsWith('/__proxy_control')) {
    if (!safeKey(req.headers['x-uat-control-key'])) { sendJson(res, 403, { error: 'forbidden' }); return; }
    if (req.method === 'GET' && req.url === '/__proxy_control/status') {
      sendJson(res, 200, { activeDelayedResponses: Object.fromEntries(activeDelayedResponses) });
      return;
    }
    if (req.method === 'POST' && req.url === '/__proxy_control') {
      let body = '';
      req.on('data', chunk => { body += chunk; if (body.length > 20_000) req.destroy(); });
      req.on('end', () => {
        let value;
        try { value = JSON.parse(body || '{}'); } catch { sendJson(res, 400, { error: 'invalid' }); return; }
        const op = String(value.operation || '');
        if (!controlOperation(op)) { sendJson(res, 400, { error: 'operation_invalid' }); return; }
        if (value.delayNext === true && Number.isInteger(Number(value.delayMs))
            && Number(value.delayMs) >= 1 && Number(value.delayMs) <= 30000) {
          delayNext.set(op, { count: Math.max(1, Number(value.count || 1)), delayMs: Number(value.delayMs),
            forDeviceId: String(value.forDeviceId || '') });
        }
        if (value.cutNext === true) cutNext.set(op, Math.max(1, Number(value.count || 1)));
        sendJson(res, 200, { ok: true });
      });
      return;
    }
    sendJson(res, 404, { error: 'not_found' });
    return;
  }
  const op = operation(req);
  const deviceId = String(req.headers['x-sync-device-id'] || '');
  let delayThis = null;
  const queuedDelay = delayNext.get(op);
  if (queuedDelay && (!queuedDelay.forDeviceId || queuedDelay.forDeviceId === deviceId)) {
    delayThis = queuedDelay;
    queuedDelay.count--;
    if (queuedDelay.count <= 0) delayNext.delete(op);
  }
  let cutThis = false;
  if (cutNext.has(op) && cutNext.get(op) > 0) {
    const left = cutNext.get(op) - 1;
    if (left <= 0) cutNext.delete(op); else cutNext.set(op, left);
    cutThis = true;
  }
  const dropThisAck = req.method === 'POST' && req.url.endsWith('/ack') && dropAckResponses > 0;
  if (dropThisAck) dropAckResponses--;
  const headers = { ...req.headers, host: `${host}:${upstreamPort}` };
  const upstream = http.request({ hostname: host, port: upstreamPort, path: req.url, method: req.method, headers }, response => {
    if (dropThisAck) {
      response.resume();
      response.on('end', () => {
        log({ startedAt, operation: operation(req), status: response.statusCode, durationMs: Date.now() - started, responseDropped: true });
        res.destroy();
      });
      return;
    }
    if (delayThis || cutThis || (mutateEventId > 0 && req.method === 'GET' && req.url.includes('/eventos'))) {
      const chunks = [];
      response.on('data', chunk => chunks.push(chunk));
      response.on('end', async () => {
        let body = Buffer.concat(chunks);
        try {
          let changed = false;
          if (mutateEventId > 0 && req.method === 'GET' && req.url.includes('/eventos')) {
            const dto = JSON.parse(body.toString('utf8'));
            for (const event of dto.events || []) {
              if (event.eventId === mutateEventId) { event.estado = mutateEventState; changed = true; }
            }
            if (changed) body = Buffer.from(JSON.stringify(dto));
          }
          if (delayThis) {
            activeDelayedResponses.set(op, (activeDelayedResponses.get(op) || 0) + 1);
            log({ event: 'delayed_response_started', operation: op, status: response.statusCode });
            await new Promise(resolve => setTimeout(resolve, delayThis.delayMs));
            const remaining = (activeDelayedResponses.get(op) || 1) - 1;
            if (remaining <= 0) activeDelayedResponses.delete(op); else activeDelayedResponses.set(op, remaining);
            log({ event: 'delayed_response_finished', operation: op, status: response.statusCode });
          }
          if (res.destroyed) return;
          if (cutThis) {
            const partial = body.subarray(0, Math.max(1, Math.floor(body.length / 2)));
            res.writeHead(response.statusCode, { ...cleanHeaders(response.headers), 'content-length': String(body.length) });
            res.write(partial);
            res.destroy();
            log({ startedAt, operation: op, status: response.statusCode, durationMs: Date.now() - started,
              responseCut: true, payloadMutation: changed ? 'synthetic_event_state' : 'none' });
            return;
          }
          const outHeaders = cleanHeaders(response.headers);
          outHeaders['content-length'] = String(body.length);
          res.writeHead(response.statusCode, outHeaders);
          res.end(body);
          log({ startedAt, operation: op, status: response.statusCode, durationMs: Date.now() - started,
            responseDelayed: Boolean(delayThis), payloadMutation: changed ? 'synthetic_event_state' : 'none' });
        } catch {
          res.writeHead(502, { 'content-type': 'application/json' });
          res.end('{"error":"proxy_response_invalid"}');
          log({ startedAt, operation: op, status: 502, durationMs: Date.now() - started, payloadMutation: 'parse_failure' });
        }
      });
      return;
    }
    res.writeHead(response.statusCode, cleanHeaders(response.headers));
    response.pipe(res);
    response.on('end', () => log({ startedAt, operation: op, status: response.statusCode, durationMs: Date.now() - started }));
  });
  upstream.on('error', () => {
    if (!res.headersSent) { res.writeHead(502, { 'content-type': 'application/json' }); res.end('{"error":"sync_upstream_unavailable"}'); }
    else res.destroy();
    log({ startedAt, operation: op, status: 502, durationMs: Date.now() - started, upstreamError: true });
  });
  req.pipe(upstream);
});

server.listen(port, host, () => log({ event: 'started', host, port, upstreamPort,
  dropAckResponses, mutateEventId: mutateEventId || null }));
process.on('SIGTERM', () => server.close(() => process.exit(0)));
