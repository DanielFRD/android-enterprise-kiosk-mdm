import express from 'express';
import cors from 'cors';
import morgan from 'morgan';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const app = express();
const PORT = process.env.PORT || 5000;

app.use(cors());
app.use(express.json());
app.use(morgan('dev'));

// Static public updates directory (Serves mock APK files)
app.use('/updates', express.static(path.join(__dirname, 'updates')));

// Fleet State
let serverConfig = {
  portalUrl: 'http://10.0.2.2:5000/portal',
  latestVersion: 1,
  apkDownloadUrl: 'http://10.0.2.2:5000/updates/app-release-v2.apk',
  targetSSID: 'ENTERPRISE_KIOSK_WIFI',
};

// In-memory fleet registry of tablets
const fleetDevices = new Map([
  [
    '70:A7:41:A1:00:12',
    {
      mac: '70:A7:41:A1:00:12',
      serial: 'SM-T290-0091823',
      installedVersion: 1,
      ip: '192.168.1.150',
      lastPing: new Date(),
      status: 'ONLINE',
    },
  ],
  [
    '5C:F9:38:BC:21:44',
    {
      mac: '5C:F9:38:BC:21:44',
      serial: 'LENOVO-TB-X505-8812',
      installedVersion: 1,
      ip: '192.168.1.151',
      lastPing: new Date(Date.now() - 1000 * 60 * 3),
      status: 'ONLINE',
    },
  ],
]);

// Handshake endpoint called by Android tablets on boot and periodic reload
app.get('/api/v1/device/handshake', (req, res) => {
  const mac = (req.query.mac || 'UNKNOWN_MAC').toUpperCase();
  const serial = req.query.serial || req.query.serie || 'UNKNOWN_SERIAL';
  const version = parseInt(req.query.version || '1', 10);
  const clientIp = req.ip || req.socket.remoteAddress;

  console.log(`[Handshake] Tablet Connected: MAC=${mac} | Serial=${serial} | InstalledVersion=v${version}`);

  fleetDevices.set(mac, {
    mac,
    serial,
    installedVersion: version,
    ip: clientIp,
    lastPing: new Date(),
    status: 'ONLINE',
  });

  res.json({
    url: serverConfig.portalUrl,
    latestVersion: serverConfig.latestVersion,
    apkDownloadUrl: serverConfig.apkDownloadUrl,
    ssid: serverConfig.targetSSID,
    timestamp: new Date().toISOString(),
  });
});

// Admin API to trigger OTA update broadcast
app.post('/api/v1/admin/publish-update', (req, res) => {
  const { newVersion, newUrl } = req.body;
  if (newVersion) serverConfig.latestVersion = Number(newVersion);
  if (newUrl) serverConfig.portalUrl = newUrl;

  console.log(`[Admin OTA] Published Update: Version=v${serverConfig.latestVersion} | PortalURL=${serverConfig.portalUrl}`);
  res.json({ success: true, config: serverConfig });
});

// Admin API to query registered fleet
app.get('/api/v1/admin/fleet', (_req, res) => {
  res.json({
    totalCount: fleetDevices.size,
    config: serverConfig,
    devices: Array.from(fleetDevices.values()),
  });
});

// Mock Kiosk Splash Page (Rendered inside the tablet WebView)
app.get('/portal', (_req, res) => {
  res.send(`
<!DOCTYPE html>
<html lang="es">
<head>
  <meta charset="utf-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
  <title>Kiosk Terminal Active</title>
  <script src="https://cdn.tailwindcss.com"></script>
</head>
<body class="bg-slate-950 text-slate-100 min-h-screen flex flex-col justify-between p-8 font-sans select-none">
  <header class="flex justify-between items-center border-b border-slate-800 pb-4">
    <div class="flex items-center space-x-3">
      <div class="w-10 h-10 rounded-xl bg-blue-600 flex items-center justify-center font-bold text-lg">K</div>
      <div>
        <h1 class="font-bold text-lg text-white">Terminal Kiosko Comercial</h1>
        <p class="text-xs text-slate-400">Flota Gestionada por Android Enterprise MDM</p>
      </div>
    </div>
    <div class="px-3 py-1 rounded-full bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 text-xs font-semibold">
      ● OPERACIÓN NORMAL
    </div>
  </header>

  <main class="grid grid-cols-3 gap-6 my-auto">
    <div class="bg-slate-900 border border-slate-800 rounded-2xl p-6 text-center hover:border-blue-500 transition cursor-pointer">
      <div class="text-4xl mb-3">📋</div>
      <h2 class="font-bold text-base mb-1">Catálogo de Productos</h2>
      <p class="text-xs text-slate-400">Consulta de inventario y precios</p>
    </div>
    <div class="bg-slate-900 border border-slate-800 rounded-2xl p-6 text-center hover:border-blue-500 transition cursor-pointer">
      <div class="text-4xl mb-3">💳</div>
      <h2 class="font-bold text-base mb-1">Punto de Cobro (POS)</h2>
      <p class="text-xs text-slate-400">Terminal de autoservicio para clientes</p>
    </div>
    <div class="bg-slate-900 border border-slate-800 rounded-2xl p-6 text-center hover:border-blue-500 transition cursor-pointer">
      <div class="text-4xl mb-3">🛎️</div>
      <h2 class="font-bold text-base mb-1">Asistencia al Huésped</h2>
      <p class="text-xs text-slate-400">Solicitudes y amenidades directas</p>
    </div>
  </main>

  <footer class="text-center text-xs text-slate-600 border-t border-slate-900 pt-4">
    Protegido por LockTaskMode • Device Owner Provisioned • Arquitectura de 5,000+ Dispositivos
  </footer>
</body>
</html>
  `);
});

// Admin Dashboard UI
app.get('/', (_req, res) => {
  res.send(`
<!DOCTYPE html>
<html lang="es">
<head>
  <meta charset="utf-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
  <title>MDM Fleet Manager & OTA Control Center</title>
  <script src="https://cdn.tailwindcss.com"></script>
</head>
<body class="bg-slate-950 text-slate-100 min-h-screen p-8 font-sans">
  <div class="max-w-5xl mx-auto space-y-6">
    <div class="flex items-center justify-between border-b border-slate-800 pb-5">
      <div>
        <h1 class="text-2xl font-bold text-white tracking-tight">Android Enterprise Kiosk MDM — Control Center</h1>
        <p class="text-xs text-slate-400">Gestor de Telemetría de Flota y Distribución de Actualizaciones Silenciosas (OTA)</p>
      </div>
      <span class="text-xs font-mono bg-blue-500/10 text-blue-400 border border-blue-500/20 px-3 py-1 rounded-full">
        OTA Server Active :5000
      </span>
    </div>

    <div class="grid grid-cols-1 md:grid-cols-3 gap-4">
      <div class="bg-slate-900 p-5 rounded-2xl border border-slate-800">
        <span class="text-xs text-slate-500 uppercase tracking-wider block mb-1">Versión OTA Publicada</span>
        <div class="text-3xl font-bold text-cyan-400">v${serverConfig.latestVersion}</div>
        <span class="text-xs text-slate-400 mt-1 block">APK en /updates/app-release-v2.apk</span>
      </div>
      <div class="bg-slate-900 p-5 rounded-2xl border border-slate-800">
        <span class="text-xs text-slate-500 uppercase tracking-wider block mb-1">Dispositivos en Monitoreo</span>
        <div class="text-3xl font-bold text-emerald-400">${fleetDevices.size} Registrados</div>
        <span class="text-xs text-slate-400 mt-1 block">Telemetría de MAC y Serial</span>
      </div>
      <div class="bg-slate-900 p-5 rounded-2xl border border-slate-800">
        <span class="text-xs text-slate-500 uppercase tracking-wider block mb-1">SSID Corporativo Forzado</span>
        <div class="text-xl font-bold text-amber-400 font-mono mt-1">${serverConfig.targetSSID}</div>
        <span class="text-xs text-slate-400 mt-1 block">Auto-enforced por WifiManager</span>
      </div>
    </div>

    <!-- Tablet Fleet Table -->
    <div class="bg-slate-900 rounded-2xl border border-slate-800 overflow-hidden">
      <div class="p-4 border-b border-slate-800 flex justify-between items-center">
        <h2 class="text-sm font-bold text-white">Flota de Tablets Conectadas (Handshake Telemetry)</h2>
        <span class="text-xs text-slate-400">Actualización en tiempo real</span>
      </div>
      <div class="overflow-x-auto">
        <table class="w-full text-left text-xs">
          <thead class="bg-slate-950/60 text-slate-400 border-b border-slate-800">
            <tr>
              <th class="p-3">Dirección MAC (Hardware)</th>
              <th class="p-3">Número de Serie (OEM)</th>
              <th class="p-3">Versión Instalada</th>
              <th class="p-3">IP Local</th>
              <th class="p-3">Último Ping</th>
              <th class="p-3">Estado OTA</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-800 text-slate-300">
            ${Array.from(fleetDevices.values())
              .map(
                (d) => `
              <tr class="hover:bg-slate-800/30">
                <td class="p-3 font-mono text-cyan-400 font-semibold">${d.mac}</td>
                <td class="p-3 font-mono text-slate-300">${d.serial}</td>
                <td class="p-3">
                  <span class="px-2 py-0.5 rounded-full bg-slate-800 text-slate-300 font-mono text-[11px]">
                    v${d.installedVersion}
                  </span>
                </td>
                <td class="p-3 font-mono text-slate-400">${d.ip}</td>
                <td class="p-3 text-slate-400">${new Date(d.lastPing).toLocaleTimeString()}</td>
                <td class="p-3">
                  ${
                    d.installedVersion < serverConfig.latestVersion
                      ? '<span class="text-amber-400 font-semibold animate-pulse">● Descargando v' +
                        serverConfig.latestVersion +
                        '...</span>'
                      : '<span class="text-emerald-400 font-semibold">● Al Día</span>'
                  }
                </td>
              </tr>
            `
              )
              .join('')}
          </tbody>
        </table>
      </div>
    </div>
  </div>
</body>
</html>
  `);
});

app.listen(PORT, () => {
  console.log(`=======================================================`);
  console.log(`🚀 MDM Kiosk Mock OTA Server running at http://localhost:${PORT}`);
  console.log(`📱 Handshake Endpoint: http://localhost:${PORT}/api/v1/device/handshake`);
  console.log(`🌐 Web Portal URL:     http://localhost:${PORT}/portal`);
  console.log(`=======================================================`);
});
