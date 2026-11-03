const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('ctrl', {
  subscribeDevice: (deviceId, deviceName) =>
    ipcRenderer.send('subscribe-device', { deviceId, deviceName }),
  sendControlEvent: (json) =>
    ipcRenderer.send('control-event', json),
  requestDeviceList: () =>
    ipcRenderer.send('request-device-list'),

  onDeviceList:        (cb) => ipcRenderer.on('device-list',       (_, d) => cb(d)),
  onDeviceInfo:        (cb) => ipcRenderer.on('device-info',       (_, d) => cb(d)),
  onWsStatus:          (cb) => ipcRenderer.on('ws-status',         (_, d) => cb(d)),
  onInitDevice:        (cb) => ipcRenderer.on('init-device',       (_, d) => cb(d ? d.deviceId || d : d, d ? d.deviceName || '' : '')),
  onScreenFrame:       (cb) => ipcRenderer.on('screen-frame',      (_, d) => cb(d)),
  onMicAudio:          (cb) => ipcRenderer.on('mic-audio',         (_, d) => cb(d)),
  onDeviceAudio:       (cb) => ipcRenderer.on('device-audio',      (_, d) => cb(d)),
  onConnectionStatus:  (cb) => ipcRenderer.on('ws-status',         (_, d) => cb(d && d.connected ? 'connected' : 'disconnected')),
  backToList:          ()   => ipcRenderer.send('back-to-list'),
});
