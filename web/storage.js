const DB_NAME = 'retroiso-web-v1';
let opened;
export function db() {
  return opened ||= new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, 1);
    request.onupgradeneeded = () => {
      const database = request.result;
      database.createObjectStore('machines', {keyPath: 'id'});
      const blocks = database.createObjectStore('blocks', {keyPath: ['disk', 'index']});
      blocks.createIndex('disk', 'disk');
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error('Ferme les autres onglets Rétro Iso puis réessaie.'));
  });
}
export async function transaction(store, mode, callback) {
  const database = await db();
  return new Promise((resolve, reject) => {
    const tx = database.transaction(store, mode);
    let result;
    try { result = callback(tx.objectStore(store)); } catch (error) { tx.abort(); reject(error); return; }
    tx.oncomplete = () => resolve(result?.result);
    tx.onerror = tx.onabort = () => reject(tx.error || new Error('Écriture interrompue.'));
  });
}
export const machines = {
  list: () => transaction('machines', 'readonly', store => store.getAll()),
  put: machine => transaction('machines', 'readwrite', store => store.put(machine)),
  delete: id => transaction('machines', 'readwrite', store => store.delete(id)),
};
export const blocks = {
  get: async (disk, index) => (await transaction('blocks', 'readonly', store => store.get([disk, index])))?.data,
  range: (disk, start, end) => transaction('blocks', 'readonly', store => store.getAll(IDBKeyRange.bound([disk, start], [disk, end], false, true))),
  put: (disk, entries) => transaction('blocks', 'readwrite', store => {
    for (const [index, data] of entries) store.put({disk, index, data});
  }),
  delete: disk => transaction('blocks', 'readwrite', store => {
    const request = store.index('disk').openKeyCursor(IDBKeyRange.only(disk));
    request.onsuccess = () => { const cursor = request.result; if (cursor) { store.delete(cursor.primaryKey); cursor.continue(); } };
  }),
};
export async function root() {
  if (!navigator.storage?.getDirectory) throw new Error('Ce navigateur ne fournit pas le stockage nécessaire. Utilise Chrome récent sur Android ou Linux.');
  return (await navigator.storage.getDirectory()).getDirectoryHandle('retroiso', {create: true});
}
export async function file(name) { return (await (await root()).getFileHandle(name)).getFile(); }
export async function removeFile(name) {
  try { await (await root()).removeEntry(name); } catch (error) { if (error.name !== 'NotFoundError') throw error; }
}
export async function saveFile(name, source, {size = source.size, progress = () => {}} = {}) {
  const handle = await (await root()).getFileHandle(name, {create: true});
  const writer = await handle.createWritable();
  let received = 0;
  try {
    const stream = source instanceof Response ? source.body : source.stream();
    if (!stream) throw new Error('Téléchargement vide.');
    const reader = stream.getReader();
    try {
      while (true) {
        const {value, done} = await reader.read();
        if (done) break;
        await writer.write(value);
        received += value.length;
        progress(received, size);
      }
    } finally { reader.releaseLock(); }
    if (size && received !== size) throw new Error('Téléchargement incomplet : ' + received + ' / ' + size + ' octets.');
    await writer.close();
  } catch (error) { await writer.abort().catch(() => {}); await removeFile(name); throw error; }
  return {name, size: received};
}
export async function blankDisk(name, size) {
  const handle = await (await root()).getFileHandle(name, {create: true});
  const writer = await handle.createWritable();
  try { await writer.truncate(size); await writer.close(); } catch (error) { await writer.abort().catch(() => {}); await removeFile(name); throw error; }
  return {name, size};
}
export async function validateRawDisk(source) {
  if (source.size < 512 || source.size % 512 !== 0) throw new Error('Le disque doit être une image brute .img/.raw, d’une taille multiple de 512 octets.');
  const bytes = new Uint8Array(await source.slice(0, 4).arrayBuffer());
  if (bytes[0] === 0x51 && bytes[1] === 0x46 && bytes[2] === 0x49 && bytes[3] === 0xfb) throw new Error('Une image QCOW2 doit d’abord être convertie en image brute.');
  const tail = new TextDecoder().decode(await source.slice(-512, -504).arrayBuffer());
  if (tail === 'conectix') throw new Error('Une image VHD doit d’abord être convertie en image brute.');
}
