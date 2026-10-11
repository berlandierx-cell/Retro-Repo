import {machines, root, file, saveFile, removeFile} from './storage.js';
import {Drive, folderId} from './drive.js';
import {readISO} from './iso.js';
import {inspectPack} from './wine-pack.js';
import {ENGINE, ROOT_BASE} from './wine-commands.js';
const $ = selector => document.querySelector(selector), drive = new Drive();
let settings, library = [], selected, active, busy = false, folder, stack = [], requestId = 0;
const pending = new Map(); const WINE_FILE = 'wine11-v14.zip';
const status = text => { $('#status').textContent = text; };
const progress = label => (done, total) => status(label + ' : ' + (total ? Math.round(done / total * 100) + ' %' : Math.round(done / 1024 ** 2) + ' Mo'));
async function task(fn) {
  if (busy) return; busy = true;
  try { await fn(); } catch (error) { status(error.message); console.error(error); }
  finally { busy = false; }
}
function button(label, action) { const element = document.createElement('button'); element.textContent = label; element.className = 'quiet'; element.onclick = () => task(action); return element; }
async function render() {
  library = (await machines.list()).filter(item => item.kind === 'wine');
  $('#library').replaceChildren();
  for (const machine of library) {
    const card = document.createElement('div'); card.className = 'wine-card' + (selected?.id === machine.id ? ' selected' : '');
    const title = document.createElement('h3'); title.textContent = machine.name;
    const detail = document.createElement('p'); detail.textContent = (machine.cdrom?.originalName || 'Aucun CD choisi') + ' · ' + machine.renderer.toUpperCase();
    const actions = document.createElement('div'); actions.className = 'wine-actions';
    actions.append(button('Sélectionner', async () => { selected = machine; await render(); status('Profil sélectionné : ' + machine.name); }), button('Ouvrir', () => boot(machine, machine.program || 'desktop')), button('Test Windows', () => boot(machine, 'test')), button('ISO local', async () => { selected = machine; $('#iso').click(); }), button('Sauvegarder sur Drive', () => backup(machine)), button('Supprimer la copie locale', () => erase(machine)));
    card.append(title, detail, actions); $('#library').append(card);
  }
}
async function create(name, renderer = 'gdi') {
  const machine = {id: crypto.randomUUID(), name, renderer, kind: 'wine', engine: ENGINE, created: new Date().toISOString()};
  await machines.put(machine); selected = machine; await render(); return machine;
}
async function cacheWine() {
  const expected = 99456246;
  try { if ((await file(WINE_FILE)).size === expected) return; } catch { /* first download */ }
  status('Premier téléchargement de Wine — environ 95 Mo…');
  const response = await fetch('vendor/wine.zip'); if (!response.ok) throw new Error('Wine : HTTP ' + response.status);
  await saveFile(WINE_FILE, response, {size: expected, progress: progress('Téléchargement de Wine')});
  // Assets are checksum-verified during the build; HTTPS supplies this pinned copy.
}
function request(action, data = {}, timeout = 120000) {
  if (!active) return Promise.reject(new Error('Ouvre un profil d’abord.'));
  const id = ++requestId;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { pending.delete(id); reject(new Error('Le moteur ne répond pas encore. Consulte le diagnostic et réessaie.')); }, timeout);
    pending.set(id, {resolve, reject, timer});
    active.frame.contentWindow.postMessage({retroWine: true, request: id, action, ...data}, location.origin);
  });
}
function destroy() {
  active?.frame.remove(); active?.release(); active = null; $('#player').hidden = true;
  for (const item of pending.values()) { clearTimeout(item.timer); item.reject(new Error('Session fermée.')); } pending.clear();
}
async function close() { if (active) { await request('close'); destroy(); status('Installation et registre enregistrés localement.'); } }
async function lock(id) {
  if (!navigator.locks) throw new Error('Ce navigateur ne permet pas de protéger la sauvegarde. Utilise Chrome récent.');
  let release;
  await new Promise((resolve, reject) => {
    navigator.locks.request('retroiso-wine-' + id, {ifAvailable: true}, async held => {
      if (!held) { reject(new Error('Ce profil est ouvert dans un autre onglet. Ferme-le d’abord.')); return; }
      await new Promise(done => { release = done; resolve(); });
    }).catch(reject);
  });
  return release;
}
async function boot(machine, program, maintenance = false) {
  await close(); await cacheWine(); const release = await lock(machine.id);
  const frame = document.createElement('iframe'); frame.title = machine.name; frame.src = 'wine-frame.html';
  active = {machine, frame, release}; selected = machine;
  $('#title').textContent = machine.name; $('#logs').textContent = ''; $('#program').replaceChildren(); $('#player').hidden = false;
  const loaded = new Promise((resolve, reject) => { frame.onload = resolve; frame.onerror = () => reject(new Error('Ouverture du moteur impossible.')); });
  $('#frame-host').replaceChildren(frame);
  try { await loaded; await request('init', {config: {...machine, program, maintenance, wineFile: WINE_FILE}}, 420000); }
  catch (error) { destroy(); throw error; }
  if (!maintenance) { await listPrograms(); $('#player').scrollIntoView({block: 'start', behavior: 'smooth'}); status('Moteur chargé. Wine initialise le bureau.'); }
}
window.addEventListener('message', async event => {
  if (!active || event.origin !== location.origin || event.source !== active.frame.contentWindow || !event.data?.retroWine) return;
  const message = event.data;
  if (message.reply) {
    const item = pending.get(message.reply);
    if (item) { clearTimeout(item.timer); pending.delete(message.reply); message.error ? item.reject(new Error(message.error)) : item.resolve(message.result); }
  }
  if (message.detail) $('#engine-status').textContent = message.detail;
  if (message.log) { const logs = $('#logs'); logs.textContent = (logs.textContent + message.log + '\n').slice(-24000); }
  if (message.proof) { $('#engine-status').textContent = message.proof.includes('PERSISTENCE OK') ? 'Test Windows : PERSISTENCE OK. Le fichier a survécu à un redémarrage.' : 'Test Windows : écriture réussie. Ferme puis relance le test pour vérifier la conservation.'; }
  if (message.fatal) {
    status(message.fatal); $('#engine-status').textContent = message.fatal;
    for (const item of pending.values()) { clearTimeout(item.timer); item.reject(new Error(message.fatal)); } pending.clear();
  }
  if (message.imported) {
    const machine = active.machine, name = machine.pendingPack; delete machine.pendingPack;
    try { await machines.put(machine); if (name) await removeFile(name); } catch (error) { status('Restauration écrite, mais catalogue non enregistré : ' + error.message); }
  }
});
async function listPrograms() {
  const paths = await request('programs'); const select = $('#program'); select.replaceChildren();
  for (const path of paths) { const option = document.createElement('option'); option.value = path; option.textContent = path; select.append(option); }
  if (active?.machine.program && paths.includes(active.machine.program)) select.value = active.machine.program;
  if (!paths.length) { const option = document.createElement('option'); option.textContent = 'Monte un CD ou lance le test Windows'; option.value = ''; select.append(option); }
}
async function useISO(media) {
  if (!selected) throw new Error('Crée ou sélectionne un profil avant de choisir le CD.');
  const machine = selected; await close(); await readISO(await file(media.name));
  const previous = machine.cdrom; machine.cdrom = media; await machines.put(machine); await render();
  if (previous && previous.name !== media.name) await removeFile(previous.name);
  status('CD prêt : ' + media.originalName + '. Ouvre le profil pour lancer l’installation.');
}
async function localDownload(id, label) {
  const {metadata, response} = await drive.download(id), name = crypto.randomUUID() + '-' + label;
  await saveFile(name, response, {size: Number(metadata.size), progress: progress(metadata.name)});
  return {name, size: Number(metadata.size), originalName: metadata.name, driveId: metadata.id, driveVersion: metadata.version};
}
async function listDrive(target = folder) {
  if (!target) throw new Error('Indique le dossier Drive dans les réglages.');
  folder = folderId(target); const files = await drive.list(folder); $('#drive-files').replaceChildren();
  if (stack.length) $('#drive-files').append(button('← Dossier précédent', () => listDrive(stack.pop())));
  for (const item of files) {
    const row = document.createElement('div'), label = document.createElement('span'); label.textContent = item.name; row.append(label, ' ');
    if (item.mimeType === 'application/vnd.google-apps.folder') row.append(button('Ouvrir', async () => { stack.push(folder); await listDrive(item.id); }));
    else if (/\.iso$/i.test(item.name)) row.append(button('Utiliser ce CD', async () => {
      if (!selected) throw new Error('Sélectionne un profil d’abord.');
      const media = await localDownload(item.id, 'cd.iso'); try { await useISO(media); } catch (error) { await removeFile(media.name); throw error; }
    }));
    else if (/\.retroiso-wine\.json$/i.test(item.name)) row.append(button('Restaurer', () => restore(item.id)));
    $('#drive-files').append(row);
  }
  $('#drive-status').textContent = 'Drive connecté. Dossier : ' + folder; status('Bibliothèque Drive actualisée.');
}
async function backup(machine) {
  if (!drive.token) throw new Error('Connecte Drive avant de sauvegarder.');
  const destination = folderId(settings.folder || ''); if (!destination) throw new Error('Indique un dossier de sauvegarde Drive.');
  await boot(machine, null, true); let archive;
  try { archive = await request('export', {}, 420000); } finally { if (active) destroy(); }
  try {
    const stamp = new Date().toISOString().replace(/[:.]/g, '-'), name = machine.name.replace(/[^\p{L}\p{N} _-]/gu, '_') + '-' + stamp;
    const prefix = await drive.upload(destination, name + '.rwp', await file(archive.name), progress('Installation vers Drive'));
    let cdrom;
    if (machine.cdrom) {
      if (machine.cdrom.driveId) cdrom = {id: machine.cdrom.driveId, name: machine.cdrom.originalName, version: machine.cdrom.driveVersion, size: machine.cdrom.size};
      else { const remote = await drive.upload(destination, name + '.iso', await file(machine.cdrom.name), progress('CD vers Drive')); cdrom = {id: remote.id, name: machine.cdrom.originalName, size: machine.cdrom.size}; }
    }
    const manifest = {schema: 1, kind: 'wine', engine: ENGINE, name: machine.name, renderer: machine.renderer, program: machine.program, saved: new Date().toISOString(), prefix: {id: prefix.id, size: archive.size}, cdrom};
    await drive.upload(destination, name + '.retroiso-wine.json', new Blob([JSON.stringify(manifest, null, 2)]));
    status('Installation sauvegardée sur Drive. Restaure le fichier .retroiso-wine.json sur l’autre appareil.');
  } finally { await removeFile(archive.name); }
}
async function restore(id) {
  const {response} = await drive.download(id), source = await response.text();
  if (source.length > 65536) throw new Error('Fichier de sauvegarde invalide.');
  const manifest = JSON.parse(source);
  if (manifest.schema !== 1 || manifest.kind !== 'wine' || manifest.engine !== ENGINE || !['gdi', 'opengl'].includes(manifest.renderer) || typeof manifest.name !== 'string' || !/^[\w-]+$/.test(manifest.prefix?.id) || manifest.cdrom && !/^[\w-]+$/.test(manifest.cdrom.id)) throw new Error('Sauvegarde incompatible avec ce moteur.');
  const imported = [];
  try {
    const pack = await localDownload(manifest.prefix.id, 'prefix.rwp'); imported.push(pack.name); await inspectPack(await file(pack.name));
    const machine = {id: crypto.randomUUID(), name: manifest.name.slice(0, 80), kind: 'wine', engine: ENGINE, renderer: manifest.renderer, program: manifest.program, pendingPack: pack.name};
    if (manifest.cdrom) {
      machine.cdrom = await localDownload(manifest.cdrom.id, 'cd.iso'); imported.push(machine.cdrom.name);
      if (manifest.cdrom.version && machine.cdrom.driveVersion !== manifest.cdrom.version) throw new Error('Le CD Drive a changé depuis cette sauvegarde. Restaure une sauvegarde récente.');
      await readISO(await file(machine.cdrom.name));
    }
    await machines.put(machine); selected = machine; await render(); status('Sauvegarde téléchargée. Ouvre ce profil pour retrouver son installation.');
  } catch (error) { for (const name of imported) await removeFile(name); throw error; }
}
async function erase(machine) {
  if (!confirm('Supprimer la copie locale de « ' + machine.name + ' » ? Les sauvegardes Drive restent disponibles.')) return;
  await close(); const release = await lock(machine.id);
  try {
    await new Promise((resolve, reject) => { const operation = indexedDB.deleteDatabase(ROOT_BASE + '/' + machine.id); operation.onsuccess = resolve; operation.onerror = () => reject(operation.error); operation.onblocked = () => reject(new Error('Ferme les autres onglets de ce profil.')); });
    await machines.delete(machine.id); if (machine.cdrom) await removeFile(machine.cdrom.name); if (machine.pendingPack) await removeFile(machine.pendingPack);
    if (selected?.id === machine.id) selected = null; await render(); status('Copie locale supprimée.');
  } finally { release(); }
}
$('#create').onsubmit = event => { event.preventDefault(); task(async () => { const form = event.currentTarget; await create(form.elements.name.value, form.elements.renderer.value); status('Profil créé. Choisis son CD.'); }); };
$('#test').onclick = () => task(async () => { let machine = library.find(item => item.diagnostic); if (!machine) { machine = await create('Test Windows et persistance'); machine.diagnostic = true; await machines.put(machine); } await boot(machine, 'test'); });
$('#close').onclick = () => task(close);
$('#programs').onclick = () => task(listPrograms);
$('#run').onclick = () => task(async () => { const program = $('#program').value; if (!program) throw new Error('Aucun programme choisi.'); await request('run', {program}); active.machine.program = program; await machines.put(active.machine); status('Lancement : ' + program); });
$('#fullscreen').onclick = () => task(() => $('#frame-host').requestFullscreen());
$('#connect').onclick = () => task(async () => { await drive.connect(settings.clientId); stack = []; await listDrive(settings.folder); });
$('#refresh').onclick = () => task(() => listDrive());
$('#persist').onclick = () => task(async () => status(await navigator.storage.persist() ? 'Conservation du stockage accordée.' : 'Conservation non accordée. Sauvegarde tes installations sur Drive.'));
$('#settings').onsubmit = event => { event.preventDefault(); const form = event.currentTarget; settings = {clientId: form.elements.clientId.value.trim(), folder: folderId(form.elements.folder.value)}; localStorage.setItem('retroiso-settings', JSON.stringify(settings)); status('Réglages enregistrés.'); };
$('#iso').onchange = event => task(async () => { const source = event.target.files[0]; if (!source) return; const name = crypto.randomUUID() + '.iso'; try { await saveFile(name, source, {progress: progress('Copie du CD')}); await useISO({name, size: source.size, originalName: source.name}); } catch (error) { await removeFile(name); throw error; } finally { $('#iso').value = ''; } });
$('#keyboard').onsubmit = event => { event.preventDefault(); if (active) { active.frame.contentWindow.postMessage({retroWine: true, input: 'text', text: event.currentTarget.elements.text.value}, location.origin); event.currentTarget.reset(); } };
$('#keys').onclick = event => { const key = event.target.closest('[data-key]'); if (key && active) active.frame.contentWindow.postMessage({retroWine: true, input: 'key', key: key.dataset.key, code: key.dataset.code, keyCode: Number(key.dataset.number)}, location.origin); };
window.addEventListener('beforeunload', event => { if (active) { event.preventDefault(); event.returnValue = ''; } });
task(async () => {
  const defaults = await (await fetch('config.json')).json(); settings = {clientId: defaults.googleClientId || defaults.clientId || '', folder: defaults.driveFolderId || '', ...JSON.parse(localStorage.getItem('retroiso-settings') || '{}')};
  $('#settings').elements.clientId.value = settings.clientId; $('#settings').elements.folder.value = settings.folder;
  await root(); await render(); status('Prêt. Commence par le test Windows.');
});
