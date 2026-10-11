import {machines, blocks, root, file, saveFile, blankDisk, removeFile, validateRawDisk} from './storage.js';
import {PersistentDisk} from './disk.js';
import {Drive, folderId} from './drive.js';

const VERSION = 'v86@0.5.471';
const $ = selector => document.querySelector(selector);
const drive = new Drive();
let settings = {}, library = [], active = null, busy = false, currentFolder = '', folderStack = [];
let logging = [];
const log = (event, detail = '') => { logging.push({at: new Date().toISOString(), event, detail: String(detail)}); if (logging.length > 250) logging.shift(); };
function status(message, error = false) { $('#status').textContent = message; $('#status').classList.toggle('error', error); $('#player-message').textContent = message; log(error ? 'error' : 'status', message); }
function progress(done, total) { $('#progress').hidden = false; $('#progress').max = total || 1; $('#progress').value = done; }
async function task(action) {
  if (busy) return;
  busy = true;
  const buttons = [...document.querySelectorAll('button:not([data-key])')].filter(button => !button.disabled);
  buttons.forEach(button => button.disabled = true);
  try { await action(); } catch (error) { status(error.message || String(error), true); }
  finally { busy = false; buttons.filter(button => button.isConnected).forEach(button => button.disabled = false); $('#refresh-drive').disabled = !drive.token; $('#progress').hidden = true; }
}
function button(label, action, className = 'quiet') {
  const element = document.createElement('button'); element.textContent = label; element.className = className;
  element.onclick = () => task(action); return element;
}
function download(source, name) {
  const url = URL.createObjectURL(source), link = document.createElement('a');
  link.href = url; link.download = name; link.click(); setTimeout(() => URL.revokeObjectURL(url), 120000);
}
async function renderLibrary() {
  library = await machines.list(); $('#library').replaceChildren(); $('#empty').hidden = library.length > 0;
  for (const machine of library) {
    const card = document.createElement('article'); card.className = 'machine';
    const icon = document.createElement('div'); icon.className = 'machine-icon'; icon.textContent = machine.demo ? '⌘' : '▣';
    const title = document.createElement('h2'); title.textContent = machine.name;
    const detail = document.createElement('p'); detail.className = 'subtle';
    detail.textContent = `${machine.memory} Mo · ${machine.demo ? 'test intégré' : 'PC Windows / DOS'} · ${machine.snapshot ? 'session conservée' : 'démarrage complet'}`;
    const media = document.createElement('p'); media.className = 'subtle'; media.textContent = machine.cdrom ? 'CD : ' + machine.cdrom.label : 'Aucun CD monté';
    const actions = document.createElement('div'); actions.className = 'actions';
    actions.append(button(machine.snapshot ? 'Reprendre' : 'Démarrer', () => startMachine(machine), 'primary'));
    if (machine.hda) actions.append(button('Exporter le disque', () => exportDisk(machine, 'hda')));
    if (machine.fda && machine.demo) actions.append(button('Exporter le test', () => exportDisk(machine, 'fda')));
    actions.append(button('Sauvegarder sur Drive', () => syncMachine(machine)));
    actions.append(button('Supprimer la copie locale', () => deleteMachine(machine), 'quiet danger'));
    card.append(icon, title, detail, media, actions); $('#library').append(card);
  }
}
async function localMedia(source, prefix, label = source.name) {
  const name = `${prefix}-${crypto.randomUUID()}`;
  const saved = await saveFile(name, source, {progress});
  return {...saved, id: name, label};
}
async function addMachine(form) {
  const values = new FormData(form);
  const disk = form.elements.disk.files[0], iso = form.elements.iso.files[0], floppy = form.elements.floppy.files[0];
  const machine = {id: crypto.randomUUID(), name: values.get('name').trim(), memory: Number(values.get('memory')), version: VERSION, created: new Date().toISOString(), snapshot: false};
  const created = [];
  try {
    status('Préparation du disque…');
    if (disk) { await validateRawDisk(disk); machine.hda = await localMedia(disk, 'disk'); }
    else { const name = 'disk-' + crypto.randomUUID(); machine.hda = {...await blankDisk(name, 1024 * 1024 * 1024), id: name, label: 'Disque vierge'}; }
    created.push(machine.hda.name);
    if (iso) { machine.cdrom = await localMedia(iso, 'iso'); created.push(machine.cdrom.name); }
    if (floppy) { await validateFloppy(floppy); machine.fda = await localMedia(floppy, 'floppy'); created.push(machine.fda.name); }
    await machines.put(machine); $('#add-dialog').close(); form.reset(); await renderLibrary();
    status(disk ? 'PC prêt. Tu peux le démarrer puis monter l’ISO du jeu.' : 'Disque vierge créé. Il faut installer Windows avant d’installer un jeu Windows.');
  } catch (error) { for (const name of created) await removeFile(name); throw error; }
}
function validateFloppy(source) {
  if (![360, 720, 1200, 1440, 2880].some(kb => kb * 1024 === source.size)) throw new Error('Disquette attendue : 360 Ko, 720 Ko, 1,2 Mo, 1,44 Mo ou 2,88 Mo.');
}
async function addDemo() {
  const response = await fetch('demo.img'); if (!response.ok) throw new Error('Le disque de test est indisponible.');
  const source = new File([await response.blob()], 'Test moteur.img');
  const fda = await localMedia(source, 'demo');
  const machine = {id: crypto.randomUUID(), name: 'Test moteur et persistance', memory: 32, fda, demo: true, version: VERSION, created: new Date().toISOString(), snapshot: false};
  await machines.put(machine); await renderLibrary(); await startMachine(machine);
}
async function lockMachine(id) {
  if (!navigator.locks) throw new Error('Le navigateur doit prendre en charge le verrouillage des disques. Utilise Chrome récent.');
  return new Promise((resolve, reject) => {
    navigator.locks.request('retroiso-' + id, {ifAvailable: true}, async lock => {
      if (!lock) { reject(new Error('Ce PC est déjà ouvert dans un autre onglet.')); return; }
      await new Promise(release => resolve(release));
    }).catch(reject);
  });
}
async function adapter(media, readonly = false) {
  return new PersistentDisk(await file(media.name), media.id, blocks, error => {
    status('Accès au disque interrompu : ' + error.message, true);
    if (active) { active.emulator.stop(); $('#player-state').textContent = 'Erreur disque'; }
  }, readonly);
}
async function startMachine(machine) {
  if (active) throw new Error('Ferme d’abord le PC en cours.');
  const release = await lockMachine(machine.id);
  const disks = {}; let emulator;
  try {
    status('Démarrage de ' + machine.name + '…');
    for (const key of ['hda', 'fda']) if (machine[key]) disks[key] = await adapter(machine[key]);
    $('#player').hidden = false; $('#player-title').textContent = machine.name; $('#player-state').textContent = 'Démarrage…';
    const options = {wasm_path: 'vendor/v86.wasm', bios: {url: 'vendor/seabios.bin'}, vga_bios: {url: 'vendor/vgabios.bin'}, memory_size: machine.memory * 1024 * 1024, vga_memory_size: 8 * 1024 * 1024, screen_container: $('#screen_container'), boot_order: machine.demo ? 0x321 : 0x213, autostart: false, ...disks};
    if (machine.cdrom) options.cdrom = {buffer: await file(machine.cdrom.name), async: true};
    if (machine.snapshot) {
      if (machine.version !== VERSION) throw new Error('La session utilise un autre moteur. Il faut exporter le disque puis le réimporter pour démarrer ce PC avec le nouveau moteur.');
      options.initial_state = {buffer: await (await file(machine.id + '.state')).arrayBuffer()};
    }
    emulator = new V86(options); active = {machine, emulator, disks, release};
    emulator.add_listener('emulator-started', () => { $('#player-state').textContent = 'En marche'; $('#pause-button').textContent = 'Pause'; });
    emulator.add_listener('emulator-stopped', () => { $('#player-state').textContent = 'En pause'; $('#pause-button').textContent = 'Reprendre'; });
    emulator.add_listener('screen-set-mode', graphical => log('screen-mode', graphical ? 'graphique' : 'texte'));
    emulator.add_listener('serial0-output-byte', byte => log('serial', String.fromCharCode(byte)));
    await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('Le moteur n’a pas démarré après 60 secondes. Exporte le diagnostic.')), 60000);
      emulator.add_listener('emulator-loaded', () => { clearTimeout(timeout); resolve(); });
      emulator.add_listener('download-error', error => { clearTimeout(timeout); reject(new Error('Fichier du moteur inaccessible : ' + (error?.file?.url || 'réseau'))); });
    });
    await runActive();
    status(machine.name + ' est ouvert.');
  } catch (error) { if (emulator) await emulator.destroy().catch(() => {}); release(); active = null; $('#player').hidden = true; throw error; }
}
async function runActive() {
  // Once execution resumes, disk writes can diverge from the prior RAM snapshot.
  // Mark it invalid BEFORE running, so a browser crash never restores stale RAM.
  active.machine.snapshot = false; await machines.put(active.machine); await active.emulator.run();
}
async function checkpoint() {
  if (!active) return;
  await active.emulator.stop();
  for (const disk of Object.values(active.disks)) await disk.flush();
  const state = await active.emulator.save_state();
  await saveFile(active.machine.id + '.state', new Blob([state]));
  active.machine.snapshot = true; active.machine.version = VERSION; await machines.put(active.machine);
  log('checkpoint', state.byteLength + ' bytes');
}
async function closePlayer() {
  if (!active) return;
  status('Enregistrement de la session…');
  await checkpoint();
  await active.emulator.destroy(); active.release(); active = null;
  if (document.fullscreenElement) await document.exitFullscreen();
  $('#player').hidden = true;
  await renderLibrary(); status('Session enregistrée. Tu peux fermer cette page.');
}
async function materialize(machine, key) {
  const disk = await adapter(machine[key]);
  const name = `export-${crypto.randomUUID()}.img`, handle = await (await root()).getFileHandle(name, {create: true});
  const writer = await handle.createWritable();
  try { await disk.exportTo(writer, progress); await writer.close(); } catch (error) { await writer.abort().catch(() => {}); await removeFile(name); throw error; }
  return {name, source: await handle.getFile()};
}
async function exportDisk(machine, key) {
  const release = await lockMachine(machine.id);
  try { status('Préparation du disque complet…'); const output = await materialize(machine, key); download(output.source, machine.name.replace(/[^\p{L}\p{N} _-]/gu, '_') + '.img'); status('Disque exporté : il contient les installations et leurs fichiers.'); }
  finally { release(); }
}
async function deleteMachine(machine) {
  if (!confirm(`Supprimer uniquement la copie locale de « ${machine.name} » ? Les fichiers Drive restent conservés. Sans sauvegarde Drive ou export, l’installation locale sera perdue.`)) return;
  const release = await lockMachine(machine.id);
  try {
    for (const key of ['hda', 'fda', 'cdrom']) if (machine[key]) { await blocks.delete(machine[key].id); await removeFile(machine[key].name); }
    await removeFile(machine.id + '.state'); await machines.delete(machine.id); await renderLibrary(); status('Copie locale supprimée.');
  } finally { release(); }
}
async function mountMedia(source, metadata) {
  if (!active) throw new Error('Démarre un PC avant de monter un CD.');
  await active.emulator.stop();
  const old = active.machine.cdrom;
  const media = await localMedia(source, 'iso', metadata?.name || source.name);
  if (metadata) media.driveId = metadata.id;
  // API takes a File here; use v86's built-in asynchronous, read-only CD reader.
  await active.emulator.set_cdrom({buffer: await file(media.name), async: true});
  active.machine.cdrom = media; await runActive();
  if (old) await removeFile(old.name);
  status('CD monté : ' + media.label);
}
async function listDrive(folder = currentFolder) {
  if (!folder) throw new Error('Indique le dossier Drive dans les réglages.');
  currentFolder = folder; status('Lecture de la bibliothèque Drive…');
  const files = await drive.list(folder); const target = $('#drive-dialog').open ? $('#drive-picker') : $('#drive-files'); target.replaceChildren(); $('#drive-path').replaceChildren();
  if (folderStack.length) target.append(button('← Dossier précédent', async () => { await listDrive(folderStack.pop()); }));
  for (const item of files) {
    const isFolder = item.mimeType === 'application/vnd.google-apps.folder';
    if (!isFolder && !/\.(iso|img|raw|bin|retroiso\.json)$/i.test(item.name)) continue;
    const row = document.createElement('div'); row.className = 'drive-row';
    const label = document.createElement('div'), name = document.createElement('strong'), detail = document.createElement('span');
    name.textContent = item.name; detail.className = 'subtle'; detail.textContent = isFolder ? 'Dossier' : (Number(item.size) / 1024 / 1024).toFixed(1) + ' Mo'; label.append(name, detail);
    const actions = document.createElement('div'); actions.className = 'actions';
    if (isFolder) actions.append(button('Ouvrir', async () => { folderStack.push(currentFolder); await listDrive(item.id); }));
    else if (/\.iso$/i.test(item.name)) actions.append(button('Monter le CD', () => mountFromDrive(item)));
    else if ($('#drive-dialog').open) continue;
    else if (/\.retroiso\.json$/i.test(item.name)) actions.append(button('Restaurer ce PC', () => restoreFromDrive(item)));
    else actions.append(button('Importer ce disque', () => diskFromDrive(item)));
    row.append(label, actions); target.append(row);
  }
  $('#drive-status').textContent = 'Drive connecté. Les sauvegardes créent de nouvelles versions sans écraser celles des autres appareils.';
  status('Bibliothèque Drive actualisée.');
}
async function fetchLocalDrive(item, prefix) {
  const {response, metadata} = await drive.download(item.id);
  const name = `${prefix}-${crypto.randomUUID()}`;
  const saved = await saveFile(name, response, {size: Number(metadata.size), progress});
  return {...saved, id: name, label: metadata.name, driveId: metadata.id};
}
async function mountFromDrive(item) {
  if (!active) throw new Error('Démarre d’abord un PC, puis monte le CD depuis Drive.');
  status('Téléchargement du CD…'); const media = await fetchLocalDrive(item, 'iso');
  try {
    await active.emulator.stop(); const old = active.machine.cdrom;
    await active.emulator.set_cdrom({buffer: await file(media.name), async: true});
    active.machine.cdrom = media; await runActive();
    if (old) await removeFile(old.name); $('#drive-dialog').close(); status('CD monté : ' + item.name);
  } catch (error) { await removeFile(media.name); throw error; }
}
async function diskFromDrive(item) {
  status('Téléchargement du disque Windows…'); const hda = await fetchLocalDrive(item, 'disk');
  try {
    await validateRawDisk(await file(hda.name));
    const machine = {id: crypto.randomUUID(), name: item.name.replace(/\.(img|raw|bin)$/i, ''), memory: 128, hda, version: VERSION, snapshot: false, created: new Date().toISOString()};
    await machines.put(machine); await renderLibrary(); status('Disque importé. Tu peux démarrer ce PC.');
  } catch (error) { await removeFile(hda.name); throw error; }
}
async function syncMachine(machine) {
  if (!drive.token) throw new Error('Connecte Google Drive avant de sauvegarder.');
  const folder = folderId(settings.folder || ''); if (!folder) throw new Error('Indique le dossier Drive dans les réglages.');
  const release = await lockMachine(machine.id), media = {};
  try {
    const stamp = new Date().toISOString().replace(/[:.]/g, '-');
    for (const key of ['hda', 'fda', 'cdrom']) {
      if (!machine[key]) continue;
      if (key === 'cdrom' && machine[key].driveId) { media[key] = {id: machine[key].driveId, label: machine[key].label}; continue; }
      status('Sauvegarde Drive : ' + (key === 'cdrom' ? 'CD' : 'disque complet') + '…');
      const output = key === 'cdrom' ? {source: await file(machine[key].name)} : await materialize(machine, key);
      try {
        const result = await drive.upload(folder, `${machine.name}-${stamp}-${key}.${key === 'cdrom' ? 'iso' : 'img'}`, output.source, progress);
        media[key] = {id: result.id, label: machine[key].label, size: output.source.size};
      } finally { if (output.name) await removeFile(output.name); }
    }
    const manifest = {format: 'retroiso-pc', schema: 1, engine: VERSION, name: machine.name, memory: machine.memory, demo: !!machine.demo, media, saved: new Date().toISOString()};
    await drive.upload(folder, `${machine.name}-${stamp}.retroiso.json`, new Blob([JSON.stringify(manifest, null, 2)], {type: 'application/json'}));
    machine.lastDriveBackup = manifest.saved; await machines.put(machine); await renderLibrary();
    status('Installation complète sauvegardée sur Drive. Restaure le fichier .retroiso.json sur l’autre appareil.');
  } finally { release(); }
}
async function restoreFromDrive(item) {
  const {response} = await drive.download(item.id), manifest = await response.json();
  if (manifest.format !== 'retroiso-pc' || manifest.schema !== 1 || manifest.engine !== VERSION || ![32, 64, 128, 256].includes(manifest.memory)) throw new Error('Cette sauvegarde n’est pas compatible avec cette version de Rétro Iso.');
  if (!manifest.media || (!manifest.media.hda && !manifest.media.fda)) throw new Error('Sauvegarde sans disque de démarrage.');
  const machine = {id: crypto.randomUUID(), name: String(manifest.name).slice(0, 100), memory: manifest.memory, demo: !!manifest.demo, version: VERSION, snapshot: false, created: new Date().toISOString()}, created = [];
  try {
    for (const key of ['hda', 'fda', 'cdrom']) if (manifest.media[key]) {
      const source = manifest.media[key]; if (!/^[\w-]+$/.test(source.id)) throw new Error('Identifiant Drive invalide dans la sauvegarde.');
      status('Restauration : ' + key + '…'); machine[key] = await fetchLocalDrive(source, key); created.push(machine[key].name);
      if (key === 'hda') await validateRawDisk(await file(machine[key].name));
      if (key === 'fda') validateFloppy(await file(machine[key].name));
    }
    await machines.put(machine); await renderLibrary(); status('PC restauré depuis Drive, avec son installation complète.');
  } catch (error) { for (const name of created) await removeFile(name); throw error; }
}
$('#device').textContent = /Android/i.test(navigator.userAgent) ? 'Android · navigateur' : 'Ordinateur · navigateur';
$('#add-button').onclick = () => $('#add-dialog').showModal();
$('#add-form').onsubmit = event => { event.preventDefault(); task(() => addMachine(event.target)); };
$('#demo-button').onclick = () => task(addDemo);
document.querySelectorAll('[data-close]').forEach(element => element.onclick = () => document.getElementById(element.dataset.close).close());
$('#settings-button').onclick = async () => {
  $('#settings-form').elements.clientId.value = settings.clientId || ''; $('#settings-form').elements.folder.value = settings.folder || '';
  const estimate = await navigator.storage?.estimate?.();
  $('#storage-status').textContent = estimate ? `Stockage : ${(estimate.usage / 1024 ** 2).toFixed(0)} Mo utilisés / ${(estimate.quota / 1024 ** 3).toFixed(1)} Go disponibles au total. Effacer les données du navigateur supprime les copies locales.` : '';
  $('#settings-dialog').showModal();
};
$('#settings-form').onsubmit = event => {
  event.preventDefault(); settings = {clientId: event.target.elements.clientId.value.trim(), folder: folderId(event.target.elements.folder.value)};
  localStorage.setItem('retroiso-settings', JSON.stringify(settings)); currentFolder = settings.folder; folderStack = [];
  $('#settings-dialog').close(); status('Réglages enregistrés.');
};
$('#persist-button').onclick = () => task(async () => { const granted = await navigator.storage.persist(); status(granted ? 'Stockage persistant accordé.' : 'Le navigateur n’a pas accordé la conservation permanente. Exporte ou sauvegarde tes disques sur Drive.'); });
$('#connect-drive').onclick = () => task(async () => { await drive.connect(settings.clientId); $('#refresh-drive').disabled = false; await listDrive(settings.folder); });
$('#refresh-drive').onclick = () => task(() => listDrive());
$('#close-player').onclick = () => task(closePlayer);
$('#pause-button').onclick = () => task(async () => { if (active.emulator.is_running()) await checkpoint(); else await runActive(); });
$('#mount-button').onclick = () => $('#iso-input').click();
$('#player-drive').onclick = () => {
  $('#drive-dialog').showModal(); $('#drive-picker').replaceChildren();
  if (drive.token) task(() => listDrive(currentFolder || settings.folder));
  else $('#drive-picker').append(button('Connecter Google Drive', async () => { await drive.connect(settings.clientId); await listDrive(settings.folder); }));
};
$('#iso-input').onchange = event => { const iso = event.target.files[0]; if (iso) task(() => mountMedia(iso)); event.target.value = ''; };
$('#fullscreen-button').onclick = () => task(async () => { if (document.fullscreenElement) await document.exitFullscreen(); else await $('#player').requestFullscreen(); });
$('#keyboard-form').onsubmit = event => { event.preventDefault(); if (active) { active.emulator.keyboard_send_text($('#keyboard-text').value); $('#keyboard-text').value = ''; } };
const keys = {enter: [0x1c, 0x9c], escape: [0x01, 0x81], tab: [0x0f, 0x8f], backspace: [0x0e, 0x8e], up: [0xe0, 0x48, 0xe0, 0xc8], left: [0xe0, 0x4b, 0xe0, 0xcb], down: [0xe0, 0x50, 0xe0, 0xd0], right: [0xe0, 0x4d, 0xe0, 0xcd], f1: [0x3b, 0xbb]};
document.querySelectorAll('[data-key]').forEach(element => element.onclick = () => active?.emulator.keyboard_send_scancodes(keys[element.dataset.key]));
function click(right = false) {
  if (!active?.emulator.is_running()) return;
  const emulator = active.emulator; emulator.bus.send('mouse-click', [!right, false, right]); setTimeout(() => emulator.bus.send('mouse-click', [false, false, false]), 80);
}
$('#left-click').onclick = () => click(); $('#right-click').onclick = () => click(true);
let pointer;
$('#touchpad').onpointerdown = event => { event.preventDefault(); event.currentTarget.setPointerCapture(event.pointerId); pointer = {x: event.clientX, y: event.clientY, distance: 0}; };
$('#touchpad').onpointermove = event => { if (!pointer || !active?.emulator.is_running()) return; const dx = event.clientX - pointer.x, dy = event.clientY - pointer.y; pointer.distance += Math.abs(dx) + Math.abs(dy); pointer.x = event.clientX; pointer.y = event.clientY; active.emulator.bus.send('mouse-delta', [dx * 1.6, -dy * 1.6]); };
$('#touchpad').onpointerup = () => { if (pointer?.distance < 6) click(); pointer = null; };
$('#touchpad').onpointercancel = () => { pointer = null; };
document.addEventListener('focusin', event => active?.emulator.keyboard_set_enabled(!event.target.matches('input,select,textarea')));
document.addEventListener('visibilitychange', () => { if (document.hidden && active && !busy) task(checkpoint); });
window.addEventListener('beforeunload', event => { if (active && !active.machine.snapshot) { event.preventDefault(); event.returnValue = ''; } });
window.addEventListener('error', event => { log('javascript-error', event.message); status('Erreur du moteur : ' + event.message, true); });
window.addEventListener('unhandledrejection', event => { log('unhandled-rejection', event.reason?.message || event.reason); status(event.reason?.message || 'Erreur du moteur.', true); });
$('#diagnostic-button').onclick = () => task(async () => {
  const diagnostic = {app: 'retroiso-web', engine: VERSION, date: new Date().toISOString(), browser: navigator.userAgent, storage: await navigator.storage?.estimate?.(), persistent: await navigator.storage?.persisted?.(), machines: library.map(({id, name, memory, snapshot, hda, fda, cdrom}) => ({id, name, memory, snapshot, hda: hda?.size, fda: fda?.size, cdrom: cdrom?.size})), active: active ? {name: active.machine.name, running: active.emulator.is_running(), instructions: active.emulator.get_instruction_counter()} : null, logs: logging};
  download(new Blob([JSON.stringify(diagnostic, null, 2)], {type: 'application/json'}), 'retroiso-diagnostic.json');
});
$('#player-diagnostic').onclick = () => $('#diagnostic-button').click();
await task(async () => {
  const config = await (await fetch('config.json')).json();
  try { settings = JSON.parse(localStorage.getItem('retroiso-settings') || '{}'); } catch { settings = {}; }
  settings.clientId ||= config.googleClientId; settings.folder ||= config.driveFolderId; currentFolder = settings.folder;
  await root(); await renderLibrary();
  const estimate = await navigator.storage.estimate(); log('storage-ready', JSON.stringify(estimate));
  status('Prêt. Les disques restent dans ce navigateur ; Drive permet de les retrouver sur un autre appareil.');
});
