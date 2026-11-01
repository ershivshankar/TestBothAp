'use strict';
// language: Node.js, file: server.js, runtime: Node 18+, target: Linux/Windows

require('dotenv').config();

const http    = require('http');
const { WebSocketServer, WebSocket } = require('ws');
const { v4: uuidv4 } = require('uuid');
const admin   = require('firebase-admin');
const fs      = require('fs');
const path    = require('path');

// ─── Constants ───────────────────────────────────────────────────────────────

const PORT = parseInt(process.env.PORT || '8080', 10);

const TYPE = Object.freeze({
  SCREEN_FRAME:  0x01,
  MIC_AUDIO:     0x02,
  DEVICE_AUDIO:  0x03,
  CONTROL_EVENT: 0x04,
  DEVICE_INFO:   0x05,
  DEVICE_LIST:   0x06,
  PING:          0x07,
  PONG:          0x08,
});

const HEARTBEAT_INTERVAL_MS = 15_000;
const CLIENT_TIMEOUT_MS     = 45_000; // 3 missed pings

// ─── Firebase Admin ───────────────────────────────────────────────────────────

let fcmEnabled = false;

(function initFirebase() {
  const saPath = process.env.FIREBASE_SERVICE_ACCOUNT_PATH
    ? path.resolve(process.env.FIREBASE_SERVICE_ACCOUNT_PATH)
    : path.resolve('./firebase-service-account.json');

  if (!fs.existsSync(saPath)) {
    console.warn('[FCM] Service account not found at', saPath, '— FCM wakeup disabled.');
    return;
  }

  try {
    const serviceAccount = JSON.parse(fs.readFileSync(saPath, 'utf8'));
    admin.initializeApp({ credential: admin.credential.cert(serviceAccount) });
    fcmEnabled = true;
    console.log('[FCM] Firebase Admin initialised, FCM wakeup enabled.');
  } catch (err) {
    console.error('[FCM] Init failed:', err.message, '— FCM wakeup disabled.');
  }
})();

async function sendFcmWakeup(fcmToken, deviceId) {
  if (!fcmEnabled || !fcmToken) return;
  try {
    await admin.messaging().send({
      token: fcmToken,
      android: {
        priority: 'high',
        data: {
          action:   'WAKEUP',
          deviceId: deviceId,
        },
        ttl: 60_000,
      },
    });
    console.log(`[FCM] Wakeup sent to device ${deviceId}`);
  } catch (err) {
    console.error(`[FCM] Failed to send wakeup to ${deviceId}:`, err.message);
  }
}

// ─── Registries ───────────────────────────────────────────────────────────────

/**
 * deviceRegistry: Map<deviceId, {
 *   ws        : WebSocket | null,
 *   info      : { deviceId, deviceName, model, androidVersion, battery, storage, network },
 *   fcmToken  : string | null,
 *   online    : boolean,
 *   lastSeen  : number,       // Date.now()
 * }>
 */
const deviceRegistry = new Map();

/**
 * controllerSubs: Map<deviceId, Set<WebSocket>>
 * Each controller WebSocket is listed under the deviceId it subscribed to.
 * A controller may appear under multiple deviceIds.
 */
const controllerSubs = new Map();

/**
 * wsRole: WeakMap<WebSocket, { role: 'device'|'controller', deviceId: string|null }>
 */
const wsRole = new WeakMap();

// ─── Framing helpers ─────────────────────────────────────────────────────────

/**
 * Build a binary frame:  [typeByte (1)] [payload (n)]
 * payload can be a Buffer or a string (auto-encoded as UTF-8).
 */
function buildFrame(typeByte, payload) {
  const body = Buffer.isBuffer(payload) ? payload : Buffer.from(payload, 'utf8');
  const frame = Buffer.allocUnsafe(1 + body.length);
  frame[0] = typeByte;
  body.copy(frame, 1);
  return frame;
}

function buildJsonFrame(typeByte, obj) {
  return buildFrame(typeByte, JSON.stringify(obj));
}

function sendFrame(ws, typeByte, payload) {
  if (ws.readyState !== WebSocket.OPEN) return;
  ws.send(buildFrame(typeByte, payload));
}

function sendJsonFrame(ws, typeByte, obj) {
  if (ws.readyState !== WebSocket.OPEN) return;
  ws.send(buildJsonFrame(typeByte, obj));
}

// ─── Device list snapshot ────────────────────────────────────────────────────

function buildDeviceListPayload() {
  const list = [];
  for (const [id, entry] of deviceRegistry) {
    list.push({
      deviceId:       id,
      deviceName:     entry.info.deviceName   || 'Unknown',
      model:          entry.info.model        || 'Unknown',
      androidVersion: entry.info.androidVersion || 'Unknown',
      battery:        entry.info.battery      ?? null,
      storage:        entry.info.storage      ?? null,
      network:        entry.info.network      ?? null,
      online:         entry.online,
      lastSeen:       entry.lastSeen,
    });
  }
  return list;
}

/** Broadcast the current device list to ALL open controller sockets. */
function broadcastDeviceList() {
  const payload = buildDeviceListPayload();
  const seen    = new Set();

  for (const subs of controllerSubs.values()) {
    for (const ctrlWs of subs) {
      if (!seen.has(ctrlWs) && ctrlWs.readyState === WebSocket.OPEN) {
        sendJsonFrame(ctrlWs, TYPE.DEVICE_LIST, payload);
        seen.add(ctrlWs);
      }
    }
  }
}

/** Notify only the controllers subscribed to a specific deviceId. */
function notifySubscribers(deviceId) {
  const subs = controllerSubs.get(deviceId);
  if (!subs || subs.size === 0) return;

  const payload = buildDeviceListPayload();
  for (const ctrlWs of subs) {
    sendJsonFrame(ctrlWs, TYPE.DEVICE_LIST, payload);
  }
}

// ─── Device registration ──────────────────────────────────────────────────────

function handleDeviceRegister(ws, msg) {
  const { deviceId, deviceName, model, androidVersion, battery, storage, network, fcmToken } = msg;

  if (!deviceId) {
    console.warn('[RELAY] Device register missing deviceId — ignored.');
    return;
  }

  // If there was a previous socket for this deviceId, close it gracefully.
  const existing = deviceRegistry.get(deviceId);
  if (existing && existing.ws && existing.ws !== ws && existing.ws.readyState === WebSocket.OPEN) {
    console.log(`[RELAY] Device ${deviceId} re-registered — closing old socket.`);
    existing.ws.close(1001, 'replaced');
  }

  const entry = {
    ws,
    info: { deviceId, deviceName, model, androidVersion, battery, storage, network },
    fcmToken: fcmToken || (existing ? existing.fcmToken : null),
    online:   true,
    lastSeen: Date.now(),
  };

  deviceRegistry.set(deviceId, entry);
  wsRole.set(ws, { role: 'device', deviceId });

  console.log(`[RELAY] Device registered: ${deviceId} (${deviceName} / ${model})`);

  // Acknowledge
  sendJsonFrame(ws, TYPE.DEVICE_INFO, { status: 'registered', deviceId });

  broadcastDeviceList();
}

// ─── Device info update (battery, network, storage live updates) ──────────────

function handleDeviceInfoUpdate(ws, msg) {
  const meta = wsRole.get(ws);
  if (!meta || meta.role !== 'device' || !meta.deviceId) return;

  const entry = deviceRegistry.get(meta.deviceId);
  if (!entry) return;

  if (msg.battery   !== undefined) entry.info.battery   = msg.battery;
  if (msg.storage   !== undefined) entry.info.storage   = msg.storage;
  if (msg.network   !== undefined) entry.info.network   = msg.network;
  if (msg.fcmToken  !== undefined) entry.fcmToken       = msg.fcmToken;
  entry.lastSeen = Date.now();

  broadcastDeviceList();
}

// ─── Controller subscribe ─────────────────────────────────────────────────────

async function handleControllerSubscribe(ws, msg) {
  const { deviceId } = msg;
  if (!deviceId) return;

  // Record role
  let meta = wsRole.get(ws);
  if (!meta) {
    meta = { role: 'controller', deviceId };
    wsRole.set(ws, meta);
  } else {
    meta.role     = 'controller';
    meta.deviceId = deviceId;
  }

  // Add to subscription set
  if (!controllerSubs.has(deviceId)) {
    controllerSubs.set(deviceId, new Set());
  }
  controllerSubs.get(deviceId).add(ws);

  console.log(`[RELAY] Controller subscribed to ${deviceId}`);

  // Immediately send device list
  sendJsonFrame(ws, TYPE.DEVICE_LIST, buildDeviceListPayload());

  // If device is offline, fire FCM wakeup
  const entry = deviceRegistry.get(deviceId);
  if (!entry || !entry.online) {
    console.log(`[RELAY] Device ${deviceId} is offline — sending FCM wakeup.`);
    const token = entry ? entry.fcmToken : null;
    await sendFcmWakeup(token, deviceId);
  }
}

// ─── Message router ───────────────────────────────────────────────────────────

/**
 * Route a binary frame received from a device WebSocket to all subscribed controllers.
 * The frame is forwarded verbatim (type byte + payload) to preserve the framing contract.
 */
function routeDeviceFrame(deviceId, rawBuffer) {
  const subs = controllerSubs.get(deviceId);
  if (!subs || subs.size === 0) return;

  for (const ctrlWs of subs) {
    if (ctrlWs.readyState === WebSocket.OPEN) {
      ctrlWs.send(rawBuffer);
    }
  }
}

/**
 * Route a control command frame from a controller to the target device.
 * rawBuffer is the complete binary frame (0x04 + JSON).
 */
function routeControlFrame(deviceId, rawBuffer) {
  const entry = deviceRegistry.get(deviceId);
  if (!entry || !entry.ws || entry.ws.readyState !== WebSocket.OPEN) return;
  entry.ws.send(rawBuffer);
}

// ─── Per-connection message handler ──────────────────────────────────────────

function onMessage(ws, data) {
  // All messages are binary frames: [typeByte][payload...]
  if (!Buffer.isBuffer(data)) {
    // Possibly a plain-text JSON sent before the client knows it's connected —
    // attempt to parse as an unframed bootstrap message.
    try {
      const obj = JSON.parse(data.toString('utf8'));
      dispatchJson(ws, obj, null);
    } catch (_) { /* ignore */ }
    return;
  }

  if (data.length < 1) return;

  const typeByte = data[0];
  const payload  = data.slice(1);

  switch (typeByte) {
    case TYPE.PING: {
      sendFrame(ws, TYPE.PONG, Buffer.alloc(0));
      return;
    }
    case TYPE.PONG: {
      ws.isAlive = true;
      return;
    }
    case TYPE.DEVICE_INFO: {
      // Device sending its registration or a metadata update
      try {
        const msg = JSON.parse(payload.toString('utf8'));
        const meta = wsRole.get(ws);
        if (!meta || meta.role !== 'device') {
          // First contact — treat as registration
          handleDeviceRegister(ws, msg);
        } else {
          handleDeviceInfoUpdate(ws, msg);
        }
      } catch (err) {
        console.error('[RELAY] Bad DEVICE_INFO JSON:', err.message);
      }
      return;
    }
    case TYPE.CONTROL_EVENT: {
      // Could be from controller (command to device) OR device (ack/event to controller)
      const meta = wsRole.get(ws);
      if (!meta) return;

      if (meta.role === 'controller') {
        // Parse to extract deviceId, then route raw frame to device
        try {
          const msg = JSON.parse(payload.toString('utf8'));
          if (msg.action === 'subscribe') {
            handleControllerSubscribe(ws, msg);
            return;
          }
          // Generic control command — route to the subscribed device
          if (meta.deviceId) {
            routeControlFrame(meta.deviceId, data);
          }
        } catch (err) {
          console.error('[RELAY] Bad CONTROL_EVENT JSON from controller:', err.message);
        }
      } else if (meta.role === 'device') {
        // Device pushing a control event (e.g. sensor data, ack) to controllers
        routeDeviceFrame(meta.deviceId, data);
      }
      return;
    }
    case TYPE.SCREEN_FRAME:
    case TYPE.MIC_AUDIO:
    case TYPE.DEVICE_AUDIO: {
      const meta = wsRole.get(ws);
      if (!meta || meta.role !== 'device' || !meta.deviceId) return;

      const entry = deviceRegistry.get(meta.deviceId);
      if (entry) entry.lastSeen = Date.now();

      routeDeviceFrame(meta.deviceId, data);
      return;
    }
    default:
      console.warn(`[RELAY] Unknown frame type 0x${typeByte.toString(16).toUpperCase()}, ignored.`);
  }
}

/**
 * Handle unframed JSON (bootstrap / fallback path).
 * Some clients may send a plain JSON string before switching to binary frames.
 */
function dispatchJson(ws, obj, _rawBuffer) {
  if (!obj || typeof obj !== 'object') return;

  switch (obj.type) {
    case 'register':
      handleDeviceRegister(ws, obj);
      break;
    case 'control':
      if (obj.action === 'subscribe') {
        wsRole.set(ws, { role: 'controller', deviceId: null });
        handleControllerSubscribe(ws, obj);
      }
      break;
    default:
      break;
  }
}

// ─── Connection close / cleanup ───────────────────────────────────────────────

function onClose(ws) {
  const meta = wsRole.get(ws);
  if (!meta) return;

  if (meta.role === 'device' && meta.deviceId) {
    const entry = deviceRegistry.get(meta.deviceId);
    if (entry && entry.ws === ws) {
      entry.online  = false;
      entry.ws      = null;
      entry.lastSeen = Date.now();
      console.log(`[RELAY] Device ${meta.deviceId} disconnected — marked offline.`);

      // Notify subscribed controllers with updated list
      notifySubscribers(meta.deviceId);
      broadcastDeviceList();
    }
  } else if (meta.role === 'controller') {
    // Remove this controller from all subscription sets
    for (const [devId, subs] of controllerSubs) {
      subs.delete(ws);
      if (subs.size === 0) controllerSubs.delete(devId);
    }
    console.log('[RELAY] Controller disconnected and removed from all subscriptions.');
  }
}

// ─── HTTP server (health endpoint) ───────────────────────────────────────────

const httpServer = http.createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      status:        'ok',
      uptime:        process.uptime(),
      devices:       deviceRegistry.size,
      onlineDevices: [...deviceRegistry.values()].filter(e => e.online).length,
      controllers:   [...controllerSubs.values()].reduce((n, s) => n + s.size, 0),
      ts:            new Date().toISOString(),
    }));
    return;
  }

  if (req.method === 'GET' && req.url === '/devices') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(buildDeviceListPayload(), null, 2));
    return;
  }

  res.writeHead(404);
  res.end();
});

// ─── WebSocket server ─────────────────────────────────────────────────────────

const wss = new WebSocketServer({ server: httpServer });

wss.on('connection', (ws, req) => {
  const ip = req.socket.remoteAddress;
  console.log(`[RELAY] New connection from ${ip}`);

  ws.isAlive = true;

  ws.on('message', (data, isBinary) => {
    // ws library v8 passes Buffer for binary, string for text
    const buf = isBinary ? data : Buffer.from(data.toString(), 'utf8');
    onMessage(ws, buf);
  });

  ws.on('pong', () => {
    ws.isAlive = true;
  });

  ws.on('close', (code, reason) => {
    console.log(`[RELAY] Connection closed: ${code} ${reason}`);
    onClose(ws);
  });

  ws.on('error', (err) => {
    console.error('[RELAY] Socket error:', err.message);
  });
});

// ─── Heartbeat ────────────────────────────────────────────────────────────────

const heartbeatTimer = setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) {
      // Timed out — terminate and let the close handler clean up
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    ws.ping();
    // Also send our framing-level PING for clients that use binary protocol
    if (ws.readyState === WebSocket.OPEN) {
      sendFrame(ws, TYPE.PING, Buffer.alloc(0));
    }
  }
}, HEARTBEAT_INTERVAL_MS);

wss.on('close', () => clearInterval(heartbeatTimer));

// ─── Start ─────────────────────────────────────────────────────────────────────

httpServer.listen(PORT, () => {
  console.log(`[RELAY] WebSocket relay server listening on ws://0.0.0.0:${PORT}`);
  console.log(`[RELAY] Health endpoint: http://0.0.0.0:${PORT}/health`);
  console.log(`[RELAY] Device list  : http://0.0.0.0:${PORT}/devices`);
});

process.on('uncaughtException', (err) => {
  console.error('[RELAY] Uncaught exception:', err);
});

process.on('unhandledRejection', (reason) => {
  console.error('[RELAY] Unhandled rejection:', reason);
});
