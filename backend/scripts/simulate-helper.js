// Dev tool: pretend to be a nearby helper's phone for end-to-end testing.
// Registers a user, opts in as a helper at the given point, then prints every realtime alert.
// Usage: node scripts/simulate-helper.js [lat] [lng] [seconds] [baseUrl]
'use strict';
const WebSocket = require('ws');

const lat = Number(process.argv[2] || 37.4245);
const lng = Number(process.argv[3] || -122.0840);
const seconds = Number(process.argv[4] || 120);
const base = (process.argv[5] || 'http://127.0.0.1:8080').replace(/\/$/, '');

async function api(method, path, token, body) {
  const res = await fetch(base + path, {
    method,
    headers: Object.assign({ 'Content-Type': 'application/json' },
      token ? { Authorization: 'Bearer ' + token } : {}),
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  if (!res.ok) throw new Error(method + ' ' + path + ' -> ' + res.status + ' ' + text);
  return text ? JSON.parse(text) : null;
}

(async () => {
  const reg = await api('POST', '/api/v1/devices/register', null, { deviceName: 'sim-helper', name: 'Nearby helper' });
  await api('PUT', '/api/v1/helper', reg.token, { enabled: true, lat, lng });
  console.log(JSON.stringify({ event: 'ready', userId: reg.userId, lat, lng }));

  const ws = new WebSocket(base.replace(/^http/, 'ws') + '/api/v1/ws', {
    headers: { Authorization: 'Bearer ' + reg.token },
  });
  ws.on('message', (raw) => {
    const msg = JSON.parse(raw.toString());
    if (msg.type === 'pong') return;
    console.log(JSON.stringify({ event: 'alert', msg }));
    if (msg.id) ws.send(JSON.stringify({ type: 'ack', id: msg.id }));
  });
  ws.on('error', (e) => console.log(JSON.stringify({ event: 'ws_error', message: e.message })));
  setTimeout(() => { ws.close(); process.exit(0); }, seconds * 1000);
})().catch((e) => { console.log(JSON.stringify({ event: 'error', message: e.message })); process.exit(1); });
