import {file, root, removeFile} from './storage.js';
import {readISO} from './iso.js';
import {exportPrefix, importPrefix} from './wine-pack.js';
import {DAEMON, ROOT_BASE, jobScript} from './wine-commands.js';
import {softwarePresenter, configureGLCanvas} from './wine-video.js';
const send = data => parent.postMessage({retroWine: true, ...data}, location.origin);
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
let config, prefix, initialized = false, timer, stopped = false, jobId = 0, initialMarkerSize = 0, lastResult = '', syncTail = Promise.resolve();
let checkpointing = false;
const errorText = error => error?.message || error?.code || (error?.errno ? 'erreur de stockage ' + error.errno : String(error));
const fs = () => globalThis.FS;
const sync = populate => {
  const operation = syncTail.then(() => new Promise((resolve, reject) => fs().syncfs(populate, error => error ? reject(error) : resolve())));
  syncTail = operation.catch(() => {}); return operation;
};
async function checkpoint() {
  if (checkpointing || stopped) return;
  checkpointing = true;
  // IDBFS first scans paths, then reads them asynchronously. Freeze the single
  // guest CPU so Wine cannot rename/delete a scanned file before it is copied.
  globalThis.Module.pauseMainLoop?.();
  try { await sync(false); globalThis.Module.resumeMainLoop?.(); }
  finally { checkpointing = false; }
}
function tell(stage, detail) { send({stage, detail}); }
function queueJob(program) {
  if (stopped) throw new Error('Cette session est fermée. Relance le PC.');
  if (jobId && fs().readFile('/control/ack', {encoding: 'utf8'}).trim() !== String(jobId)) throw new Error('Un lancement est déjà en attente.');
  // Fixed filenames are necessary: Boxedwine caches directory entries on first access.
  fs().writeFile('/control/result', ''); lastResult = '';
  fs().writeFile('/control/job.sh', jobScript(program, config.renderer));
  fs().writeFile('/control/job.id', String(++jobId) + '\n');
}
async function prepare() {
  const FS = fs(); prefix = ROOT_BASE + '/' + config.id;
  FS.mkdirTree(prefix); FS.mount(globalThis.IDBFS, {}, prefix); await sync(true);
  if (config.pendingPack) {
    if (FS.readdir(prefix).some(name => name !== '.' && name !== '..')) throw new Error('Restauration déjà commencée. Supprime cette copie locale puis restaure la sauvegarde à nouveau.');
    tell('restore', 'Restauration de l’installation…'); await importPrefix(FS, prefix, await file(config.pendingPack)); await sync(false);
    send({imported: true});
  }
  FS.mkdirTree('/control'); FS.mkdirTree('/cdrom');
  for (const name of ['stop', 'stopped', 'job.id', 'job.sh', 'ack', 'result', 'registry.log', 'program.log']) FS.writeFile('/control/' + name, '');
  FS.writeFile('/control/daemon.sh', DAEMON); FS.chmod('/control/daemon.sh', 0o755);
  const driveC = prefix + '/home/username/.wine/drive_c'; FS.mkdirTree(driveC);
  const test = await fetch('retroiso-test.exe'); if (!test.ok) throw new Error('Programme de test indisponible.');
  FS.writeFile(driveC + '/retroiso-test.exe', new Uint8Array(await test.arrayBuffer()));
  const marker = driveC + '/retroiso-persistence.txt';
  if (FS.analyzePath(marker).exists) initialMarkerSize = FS.stat(marker).size;
  tell('wine', 'Chargement de Wine depuis le stockage local…');
  FS.writeFile('/wine.zip', new Uint8Array(await (await file(config.wineFile)).arrayBuffer()), {canOwn: true});
  if (config.cdrom) {
    tell('cd', 'Lecture du CD : ' + config.cdrom.originalName);
    const iso = await readISO(await file(config.cdrom.name)); let done = 0;
    for (const entry of iso.entries) {
      const target = '/cdrom/' + entry.path;
      if (entry.directory) FS.mkdirTree(target);
      else {
        const stream = FS.open(target, 'w');
        try {
          for (let offset = 0; offset < entry.size; offset += 4 * 1024 * 1024) {
            const bytes = new Uint8Array(await entry.blob.slice(offset, offset + 4 * 1024 * 1024).arrayBuffer());
            if (FS.write(stream, bytes, 0, bytes.length, offset) !== bytes.length) throw new Error('Lecture du CD incomplète.');
            done += bytes.length;
          }
        } finally { FS.close(stream); }
      }
      tell('cd', `Lecture du CD : ${Math.round(done / Math.max(iso.total, 1) * 100)} %`);
    }
    // The mounted CD is a directory of extracted ISO files, not a raw optical device.
    for (const entry of [...iso.entries].reverse()) FS.chmod('/cdrom/' + entry.path, entry.directory ? 0o555 : 0o444);
    FS.chmod('/cdrom', 0o555);
  }
  if (!config.maintenance) queueJob(config.program || 'test');
}
async function stopGuest() {
  clearInterval(timer);
  await syncTail;
  globalThis.Module.resumeMainLoop?.();
  if (!config.maintenance && !stopped) {
    tell('closing', 'Fermeture des programmes et enregistrement du registre…');
    fs().writeFile('/control/stop', 'stop\n');
    const deadline = Date.now() + 90000;
    while (!fs().readFile('/control/stopped', {encoding: 'utf8'}).includes('STOPPED')) {
      if (Date.now() > deadline) throw new Error('Wine ne s’est pas fermé. La session reste ouverte : réessaie Enregistrer et fermer.');
      await sleep(200);
    }
    stopped = true;
  }
  globalThis.Module.pauseMainLoop?.(); await sync(false);
}
function programs() {
  const result = [];
  const walk = (directory, relative, letter, depth = 0) => {
    if (depth > 32 || result.length > 1000) return;
    for (const name of fs().readdir(directory)) {
      if (name === '.' || name === '..') continue;
      const target = directory + '/' + name, stat = fs().lstat(target), path = relative ? relative + '\\' + name : name;
      if (fs().isLink(stat.mode)) continue;
      if (fs().isDir(stat.mode)) walk(target, path, letter, depth + 1);
      else if (/\.exe$/i.test(name)) result.push(letter + ':\\' + path);
    }
  };
  walk('/cdrom', '', 'D'); walk(prefix + '/home/username/.wine/drive_c', '', 'C');
  return result.sort((a, b) => a.localeCompare(b));
}
async function command(message) {
  if (!initialized) throw new Error('Le moteur charge encore.');
  if (message.action === 'run') { queueJob(message.program); return true; }
  if (message.action === 'programs') return programs();
  if (message.action === 'close') { await stopGuest(); return true; }
  if (message.action === 'export') {
    await stopGuest(); const name = 'wine-backup-' + config.id + '-' + Date.now() + '.rwp';
    const writer = await (await (await root()).getFileHandle(name, {create: true})).createWritable();
    try { await exportPrefix(fs(), prefix, writer, (done, total) => tell('export', `Sauvegarde : ${Math.round(done / Math.max(total, 1) * 100)} %`)); await writer.close(); }
    catch (error) { await writer.abort().catch(() => {}); await removeFile(name); throw error; }
    return {name, size: (await file(name)).size};
  }
  throw new Error('Commande inconnue.');
}
function input(message) {
  const canvas = document.getElementById('canvas'); canvas.focus();
  if (message.input === 'key') {
    for (const type of ['keydown', 'keyup']) document.dispatchEvent(new KeyboardEvent(type, {key: message.key, code: message.code, keyCode: message.keyCode, which: message.keyCode, bubbles: true, cancelable: true}));
  } else if (message.input === 'text') {
    // SDL text input is carried by keypress charCode for printable characters.
    for (const character of String(message.text).slice(0, 512)) document.dispatchEvent(new KeyboardEvent('keypress', {key: character, charCode: character.charCodeAt(0), which: character.charCodeAt(0), bubbles: true}));
  }
}
window.addEventListener('message', async event => {
  if (event.origin !== location.origin || event.source !== parent || !event.data?.retroWine) return;
  const message = event.data;
  if (message.input) { if (initialized) input(message); return; }
  try {
    if (message.action === 'init') {
      if (config || !/^[a-zA-Z0-9-]{1,80}$/.test(message.config?.id)) throw new Error('Session invalide.');
      config = message.config; tell('engine', 'Démarrage du moteur…');
      configureGLCanvas(document.getElementById('canvas'));
      globalThis.Module = {
        noInitialRun: true, noExitRuntime: true, canvas: document.getElementById('canvas'),
        boxedwinePresentSoftwareFrame: softwarePresenter(document.getElementById('boxedwine-webgl-canvas-0')),
        locateFile: name => new URL('vendor/boxedwine/' + name, location.href).href,
        print: line => { if (!String(line).startsWith('ran main loop')) send({log: String(line)}); }, printErr: line => send({log: String(line)}),
        onAbort: error => send({fatal: 'Moteur arrêté : ' + error}),
        preRun: [() => {
          globalThis.addRunDependency('retroiso-storage');
          prepare().then(() => globalThis.removeRunDependency('retroiso-storage')).catch(error => send({fatal: error.message}));
        }],
        onRuntimeInitialized: () => {
          initialized = true; send({reply: message.request, result: true});
          if (!config.maintenance) {
            tell('starting', 'Wine démarre. Le premier lancement peut prendre plusieurs minutes.');
            globalThis.callMain(['-root', prefix, '-zip', '/wine.zip', '-mount_drive', '/cdrom', 'd', '-mount', '/control', '/home/username/retroiso-control', '-env', 'WINELOADERNOEXEC=1', '-env', 'WINEDEBUG=-all', '-resolution', '800x600', '/bin/sh', '/home/username/retroiso-control/daemon.sh']);
            timer = setInterval(() => {
              checkpoint().catch(error => { globalThis.Module.pauseMainLoop?.(); send({fatal: 'Enregistrement local impossible : ' + errorText(error)}); clearInterval(timer); });
              const marker = prefix + '/home/username/.wine/drive_c/retroiso-persistence.txt';
              if (config.program === 'test' && fs().analyzePath(marker).exists && fs().stat(marker).size > initialMarkerSize) send({proof: fs().readFile(marker, {encoding: 'utf8'})});
              const result = fs().readFile('/control/result', {encoding: 'utf8'}).trim();
              if (result && result !== lastResult) {
                lastResult = result;
                for (const name of ['registry.log', 'program.log']) send({log: fs().readFile('/control/' + name, {encoding: 'utf8'}).slice(-8000)});
                if (result !== '0') send({fatal: 'Le programme Windows a échoué (' + result + '). Consulte le diagnostic. La session reste ouverte pour enregistrer.'});
              }
            }, 5000);
          }
        },
      };
      const script = document.createElement('script'); script.src = 'vendor/boxedwine/boxedwine.js';
      script.onerror = () => send({fatal: 'Téléchargement du moteur impossible. Vérifie ta connexion.'}); document.head.append(script);
      return;
    }
    send({reply: message.request, result: await command(message)});
  } catch (error) { send({reply: message.request, error: error.message}); }
});
window.addEventListener('error', event => send({fatal: event.message}));
window.addEventListener('unhandledrejection', event => send({fatal: String(event.reason?.message || event.reason)}));
// Translate touch gestures into the upstream mouse path, including dragging.
let touchId;
for (const type of ['touchstart', 'touchmove', 'touchend', 'touchcancel']) {
  document.getElementById('canvas').addEventListener(type, event => {
    if (!initialized) return;
    const touch = [...event.changedTouches].find(item => touchId === undefined || item.identifier === touchId);
    if (!touch) return;
    if (type === 'touchstart') { if (touchId !== undefined) return; touchId = touch.identifier; }
    if (touch.identifier !== touchId) return;
    const mouseType = type === 'touchstart' ? 'mousedown' : type === 'touchmove' ? 'mousemove' : 'mouseup';
    document.getElementById('canvas').dispatchEvent(new MouseEvent(mouseType, {clientX: touch.clientX, clientY: touch.clientY, button: 0, buttons: mouseType === 'mouseup' ? 0 : 1, bubbles: true, cancelable: true}));
    if (mouseType === 'mouseup') touchId = undefined;
    event.preventDefault(); event.stopImmediatePropagation();
  }, {capture: true, passive: false});
}
