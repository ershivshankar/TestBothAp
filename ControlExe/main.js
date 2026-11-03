const { app, BrowserWindow, ipcMain, shell } = require('electron');
const path = require('path');
const WebSocket = require('ws');

// ── Frame type constants (matches relay protocol) ───────────────────────────
const TYPE_SCREEN       = 0x01;
const TYPE_MIC          = 0x02;
const TYPE_DEVICE_AUDIO = 0x03;
const TYPE_JSON         = 0x04;
const TYPE_DEVICE_INFO  = 0x05;
const TYPE_DEVICE_LIST  = 0x06;
const TYPE_PING         = 0x07;
const TYPE_PONG         = 0x08;

const RELAY_URL = 'wss://testbothap.onrender.com';

let mainWindow = null;
let monitorWindow = null;
let ws = null;
let reconnectTimer = null;
let reconnectDelay = 1000;
let subscribedDeviceId = null;

// ── Window factory ──────────────────────────────────────────────────────────
function createMainWindow() {
  mainWindow = new BrowserWindow({
    width: 900,
    height: 680,
    minWidth: 700,
    minHeight: 500,
    backgroundColor: '#0d0d0d',
    titleBarStyle: 'default',
    title: 'Control',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  });
  mainWindow.loadFile('renderer/index.html');
  mainWindow.on('closed', () => { mainWindow = null; });
}

function createMonitorWindow(deviceId, deviceName) {
  if (monitorWindow) { monitorWindow.focus(); return; }
  monitorWindow = new BrowserWindow({
    width: 1200,
    height: 800,
    minWidth: 900,
    minHeight: 600,
    backgroundColor: '#0d0d0d',
    title: `Monitoring: ${deviceName}`,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  });
  monitorWindow.loadFile('renderer/monitor.html');
  monitorWindow.webContents.once('did-finish-load', () => {
    monitorWindow.webContents.send('init-device', { deviceId, deviceName });
  });
  monitorWindow.on('closed', () => {
    monitorWindow = null;
    // Unsubscribe
    if (subscribedDeviceId) {
      sendJson({ action: 'unsubscribe', deviceId: subscribedDeviceId });
      subscribedDeviceId = null;
    }
  });
}

// ── WebSocket ───────────────────────────────────────────────────────────────
function connect() {
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;

  ws = new WebSocket(RELAY_URL);
  ws.binaryType = 'nodebuffer';

  ws.on('open', () => {
    reconnectDelay = 1000;
    broadcastToAll('ws-status', { connected: true });
    sendJson({ action: 'controller_connect' });
    // Re-subscribe if we had a device
    if (subscribedDeviceId) {
      sendJson({ action: 'subscribe', deviceId: subscribedDeviceId });
    }
  });

  ws.on('message', (data) => {
    if (typeof data === 'string') {
      handleJsonMessage(data); return;
    }
    if (!Buffer.isBuffer(data) || data.length === 0) return;
    const type = data[0];
    const payload = data.slice(1);

    switch (type) {
      case TYPE_SCREEN:
        // Send raw buffer as base64 to renderer for canvas display
        if (monitorWindow && !monitorWindow.isDestroyed()) {
          monitorWindow.webContents.send('screen-frame', payload.toString('base64'));
        }
        break;
      case TYPE_MIC:
        if (monitorWindow && !monitorWindow.isDestroyed()) {
          monitorWindow.webContents.send('mic-audio', payload.toString('base64'));
        }
        break;
      case TYPE_DEVICE_AUDIO:
        if (monitorWindow && !monitorWindow.isDestroyed()) {
          monitorWindow.webContents.send('device-audio', payload.toString('base64'));
        }
        break;
      case TYPE_JSON:
      case TYPE_DEVICE_INFO:
        handleJsonMessage(payload.toString('utf8'));
        break;
      case TYPE_DEVICE_LIST:
        handleDeviceList(payload.toString('utf8'));
        break;
      case TYPE_PING:
        // Respond with PONG
        const pong = Buffer.alloc(1);
        pong[0] = TYPE_PONG;
        ws.send(pong);
        break;
    }
  });

  ws.on('close', () => {
    broadcastToAll('ws-status', { connected: false });
    scheduleReconnect();
  });

  ws.on('error', () => {
    broadcastToAll('ws-status', { connected: false });
    scheduleReconnect();
  });
}

function scheduleReconnect() {
  if (reconnectTimer) return;
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connect();
  }, reconnectDelay);
  reconnectDelay = Math.min(reconnectDelay * 2, 30000);
}

function sendJson(obj) {
  if (!ws || ws.readyState !== WebSocket.OPEN) return;
  try {
    const payload = Buffer.from(JSON.stringify(obj), 'utf8');
    const frame = Buffer.alloc(payload.length + 1);
    frame[0] = TYPE_JSON;
    payload.copy(frame, 1);
    ws.send(frame);
  } catch (_) {}
}

function handleJsonMessage(text) {
  try {
    const obj = JSON.parse(text);
    if (obj.type === 'device_list' || obj.devices) {
      handleDeviceList(text);
    } else if (obj.type === 'device_info' || obj.deviceId) {
      broadcastToAll('device-info', obj.device || obj);
    }
  } catch (_) {}
}

function handleDeviceList(text) {
  try {
    const obj = JSON.parse(text);
    const devices = obj.devices || obj;
    broadcastToAll('device-list', Array.isArray(devices) ? devices : []);
  } catch (_) {}
}

function broadcastToAll(channel, data) {
  [mainWindow, monitorWindow].forEach(win => {
    if (win && !win.isDestroyed()) {
      win.webContents.send(channel, data);
    }
  });
}

// ── IPC from renderer ───────────────────────────────────────────────────────
ipcMain.on('subscribe-device', (_, { deviceId, deviceName }) => {
  subscribedDeviceId = deviceId;
  sendJson({ action: 'subscribe', deviceId });
  createMonitorWindow(deviceId, deviceName);
});

ipcMain.on('control-event', (_, json) => {
  sendJson(json);
});

ipcMain.on('request-device-list', () => {
  sendJson({ action: 'get_device_list' });
});

ipcMain.on('open-url', (_, url) => shell.openExternal(url));

ipcMain.on('back-to-list', () => {
  if (monitorWindow && !monitorWindow.isDestroyed()) {
    monitorWindow.close();
  }
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.focus();
  }
});

// ── App lifecycle ───────────────────────────────────────────────────────────
app.whenReady().then(() => {
  createMainWindow();
  connect();
  // Periodically request device list
  setInterval(() => sendJson({ action: 'get_device_list' }), 5000);
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});

app.on('activate', () => {
  if (BrowserWindow.getAllWindows().length === 0) createMainWindow();
});
