/**
 * JARVIS NEXUS Relay Server
 * Production-ready WebSocket Relay for Android-to-Android Remote Device Management.
 * 
 * Features:
 * - Device Registration & Presence Tracking
 * - End-to-End Encrypted Packet Forwarding
 * - Session Management & Heartbeat Ping/Pong
 * - Pairing Handshake Signalling
 * - Replay & Rate-Limiting Protection
 */

const http = require('http');
const { WebSocketServer, WebSocket } = require('ws');
const crypto = require('crypto');

const SERVER_RELAY_TOKEN = process.env.RELAY_TOKEN || "jarvis_default_relay_secret";

// Rate limiting constants
const MAX_MSG_PER_WINDOW = 50;
const RATE_LIMIT_WINDOW_MS = 10000;

const PORT = process.env.PORT || 8080;
const PING_INTERVAL_MS = 30000;

// Device Registry: deviceId -> { ws, deviceName, publicKey, lastSeen, pairedDevices: Set }
const devices = new Map();
// Known public keys for deviceId TOFU binding
const knownKeys = new Map();
// Rate Limit Map: remoteAddress -> { count, windowStart }
const rateLimits = new Map();
// Active Sessions: sessionId -> { hostDeviceId, controllerDeviceId, createdAt }
const sessions = new Map();

const server = http.createServer((req, res) => {
  if (req.url === '/health' || req.url === '/') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      service: 'JARVIS NEXUS Relay Server',
      status: 'OPERATIONAL',
      timestamp: Date.now()
    }));
  } else {
    res.writeHead(404);
    res.end();
  }
});

const wss = new WebSocketServer({ server });

wss.on('connection', (ws, req) => {
  ws.authDeviceId = null;
  ws.challenge = null;
  ws.isAlive = true;

  ws.on('pong', () => {
    ws.isAlive = true;
    if (ws.authDeviceId && devices.has(ws.authDeviceId)) {
      devices.get(ws.authDeviceId).lastSeen = Date.now();
    }
  });

  ws.on('message', (data) => {
    // Rate limiting
    const ip = req.socket.remoteAddress;
    const now = Date.now();
    const rl = rateLimits.get(ip) || { count: 0, windowStart: now };
    if (now - rl.windowStart > RATE_LIMIT_WINDOW_MS) {
      rl.count = 0;
      rl.windowStart = now;
    }
    rl.count++;
    rateLimits.set(ip, rl);
    if (rl.count > MAX_MSG_PER_WINDOW) {
      console.warn(`[NEXUS Relay] Rate limit exceeded for IP: ${ip}`);
      return;
    }

    // Maximum payload size check (32KB)
    if (data.length > 32768) {
      console.warn(`[NEXUS Relay] Payload too large from IP: ${ip}`);
      return;
    }

    try {
      const msg = JSON.parse(data.toString());
      handleMessage(ws, msg);
    } catch (err) {
      console.error('[NEXUS Relay] Message parse error:', err.message);
      safeSend(ws, { type: 'ERROR', message: 'Malformed JSON packet' });
    }
  });

  ws.on('close', () => {
    if (ws.authDeviceId) {
      console.log(`[NEXUS Relay] Device disconnected: ${ws.authDeviceId}`);
      devices.delete(ws.authDeviceId);
      notifyPeersDeviceOffline(ws.authDeviceId);
    }
  });

  ws.on('error', (err) => {
    console.warn('[NEXUS Relay] Socket error:', err.message);
  });
});

function handleMessage(ws, msg) {
  const { type, payload } = msg;

  switch (type) {
    case 'GET_CHALLENGE': {
      const challenge = crypto.randomBytes(32).toString('hex');
      ws.challenge = { value: challenge, ts: Date.now() };
      safeSend(ws, { type: 'AUTH_CHALLENGE', payload: { challenge } });
      break;
    }

    case 'REGISTER_DEVICE': {
      const { deviceId, deviceName, publicKey, signature, relayToken } = payload || {};

      if (relayToken !== SERVER_RELAY_TOKEN) {
        console.warn(`[NEXUS Relay] Invalid relayToken from device: ${deviceId}`);
        return safeSend(ws, { type: 'ERROR', message: 'Invalid relay token' });
      }

      if (!deviceId || !publicKey || !signature) {
        return safeSend(ws, { type: 'ERROR', message: 'deviceId, publicKey, and signature are required' });
      }

      if (!ws.challenge || Date.now() - ws.challenge.ts > 60000) {
        return safeSend(ws, { type: 'ERROR', message: 'Challenge expired or missing. Call GET_CHALLENGE first.' });
      }

      // Verify TOFU key binding
      const prevKey = knownKeys.get(deviceId);
      if (prevKey && prevKey !== publicKey) {
        console.warn(`[NEXUS Relay] Key mismatch for deviceId: ${deviceId}`);
        return safeSend(ws, { type: 'ERROR', message: 'Key mismatch for deviceId' });
      }

      // Verify signature
      try {
        const verifier = crypto.createVerify('sha256');
        verifier.update(ws.challenge.value);
        const isValid = verifier.verify(
          `-----BEGIN PUBLIC KEY-----\n${publicKey}\n-----END PUBLIC KEY-----`,
          signature,
          'hex'
        );

        if (!isValid) {
          console.warn(`[NEXUS Relay] Signature verification failed for device: ${deviceId}`);
          return safeSend(ws, { type: 'ERROR', message: 'Signature verification failed' });
        }
      } catch (err) {
        console.error('[NEXUS Relay] Crypto error during verification:', err.message);
        return safeSend(ws, { type: 'ERROR', message: 'Crypto verification error' });
      }

      ws.challenge = null;
      knownKeys.set(deviceId, publicKey);
      ws.authDeviceId = deviceId;

      devices.set(deviceId, {
        ws,
        deviceName: deviceName || 'Android Device',
        publicKey,
        lastSeen: Date.now(),
        pairedDevices: new Set()
      });

      console.log(`[NEXUS Relay] Registered device: ${deviceName} (${deviceId})`);
      safeSend(ws, {
        type: 'REGISTER_ACK',
        payload: { status: 'SUCCESS', deviceId, serverTimestamp: Date.now() }
      });
      break;
    }

    case 'QUERY_DEVICE_PRESENCE': {
      if (!ws.authDeviceId) return safeSend(ws, { type: 'ERROR', message: 'Not registered' });
      const { targetDeviceId } = payload || {};
      const target = devices.get(targetDeviceId);
      safeSend(ws, {
        type: 'DEVICE_PRESENCE_RESULT',
        payload: {
          targetDeviceId,
          isOnline: !!target && target.ws.readyState === WebSocket.OPEN,
          lastSeen: target ? target.lastSeen : null,
          deviceName: target ? target.deviceName : null
        }
      });
      break;
    }

    case 'PAIRING_SIGNAL': {
      if (!ws.authDeviceId) return safeSend(ws, { type: 'ERROR', message: 'Not registered' });
      const { targetDeviceId, encryptedSignal } = payload || {};
      const target = devices.get(targetDeviceId);
      if (!target || target.ws.readyState !== WebSocket.OPEN) {
        return safeSend(ws, {
          type: 'ERROR',
          message: `Target device ${targetDeviceId} is currently offline`
        });
      }

      safeSend(target.ws, {
        type: 'INCOMING_PAIRING_SIGNAL',
        payload: {
          fromDeviceId: ws.authDeviceId,
          encryptedSignal,
          timestamp: Date.now()
        }
      });
      break;
    }

    case 'RELAY_COMMAND': {
      if (!ws.authDeviceId) return safeSend(ws, { type: 'ERROR', message: 'Not registered' });
      const { targetDeviceId, sessionId, encryptedPayload, nonce } = payload || {};
      const target = devices.get(targetDeviceId);

      if (!target || target.ws.readyState !== WebSocket.OPEN) {
        return safeSend(ws, {
          type: 'ERROR',
          message: `Target device is unreachable`
        });
      }

      safeSend(target.ws, {
        type: 'RELAYED_COMMAND',
        payload: {
          fromDeviceId: ws.authDeviceId,
          sessionId,
          encryptedPayload,
          nonce,
          timestamp: Date.now()
        }
      });
      break;
    }

    case 'RELAY_RESPONSE': {
      if (!ws.authDeviceId) return safeSend(ws, { type: 'ERROR', message: 'Not registered' });
      const { targetDeviceId, sessionId, encryptedPayload, nonce } = payload || {};
      const target = devices.get(targetDeviceId);
      if (target && target.ws.readyState === WebSocket.OPEN) {
        safeSend(target.ws, {
          type: 'RELAYED_RESPONSE',
          payload: {
            fromDeviceId: ws.authDeviceId,
            sessionId,
            encryptedPayload,
            nonce,
            timestamp: Date.now()
          }
        });
      }
      break;
    }

    default:
      safeSend(ws, { type: 'ERROR', message: `Unknown packet type: ${type}` });
  }
}

function notifyPeersDeviceOffline(offlineDeviceId) {
  for (const [_, dev] of devices) {
    if (dev.pairedDevices.has(offlineDeviceId) && dev.ws.readyState === WebSocket.OPEN) {
      safeSend(dev.ws, {
        type: 'PEER_OFFLINE',
        payload: { deviceId: offlineDeviceId }
      });
    }
  }
}

function safeSend(ws, obj) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(obj));
  }
}

// Heartbeat ping interval
setInterval(() => {
  wss.clients.forEach((ws) => {
    if (!ws.isAlive) {
      return ws.terminate();
    }
    ws.isAlive = false;
    ws.ping();
  });
}, PING_INTERVAL_MS);

server.listen(PORT, () => {
  console.log(`[JARVIS NEXUS Relay] Server listening on port ${PORT}`);
});
