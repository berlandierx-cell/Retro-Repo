const MAGIC = new TextEncoder().encode('RETROWP1');
const encoder = new TextEncoder(), decoder = new TextDecoder();
export function validPath(path) {
  return typeof path === 'string' && path.length > 0 && path.length <= 2048 && !path.startsWith('/') && !/[\\\0]/.test(path) && path.split('/').every(part => part && part !== '.' && part !== '..');
}
export function collectPrefix(fs, root) {
  const rows = [];
  function walk(directory, parent = '') {
    for (const name of fs.readdir(directory)) {
      if (name === '.' || name === '..') continue;
      const relative = parent ? parent + '/' + name : name;
      if (!validPath(relative) || rows.length >= 50000) throw new Error('Préfixe Wine trop complexe.');
      const full = root + '/' + relative, stat = fs.lstat(full);
      const type = fs.isDir(stat.mode) ? 'directory' : fs.isLink(stat.mode) ? 'link' : 'file';
      const row = {path: relative, type, mode: stat.mode & 0o777, size: type === 'file' ? stat.size : 0};
      if (type === 'link') row.target = fs.readlink(full);
      rows.push(row);
      if (type === 'directory') walk(full, relative);
    }
  }
  walk(root); return rows;
}
export async function exportPrefix(fs, root, writer, progress = () => {}) {
  const entries = collectPrefix(fs, root), total = entries.reduce((sum, item) => sum + item.size, 0);
  const metadata = encoder.encode(JSON.stringify({schema: 1, entries}));
  const length = new Uint8Array(4); new DataView(length.buffer).setUint32(0, metadata.length, true);
  await writer.write(MAGIC); await writer.write(length); await writer.write(metadata);
  let done = 0;
  for (const row of entries) if (row.type === 'file') {
    const stream = fs.open(root + '/' + row.path, 'r');
    try {
      for (let offset = 0; offset < row.size; offset += 4 * 1024 * 1024) {
        const data = new Uint8Array(Math.min(4 * 1024 * 1024, row.size - offset));
        if (fs.read(stream, data, 0, data.length, offset) !== data.length) throw new Error('Fichier modifié pendant la sauvegarde.');
        await writer.write(data); done += data.length; progress(done, total);
      }
    } finally { fs.close(stream); }
  }
  return {files: entries.length, size: total};
}
export async function inspectPack(source) {
  const header = new Uint8Array(await source.slice(0, 12).arrayBuffer());
  if (header.length !== 12 || !MAGIC.every((value, i) => header[i] === value)) throw new Error('Sauvegarde Wine invalide.');
  const length = new DataView(header.buffer).getUint32(8, true);
  if (length > 16 * 1024 * 1024 || length + 12 > source.size) throw new Error('Métadonnées Wine invalides.');
  const manifest = JSON.parse(decoder.decode(await source.slice(12, 12 + length).arrayBuffer()));
  if (manifest.schema !== 1 || !Array.isArray(manifest.entries) || manifest.entries.length > 50000) throw new Error('Version de sauvegarde incompatible.');
  let offset = 12 + length; const seen = new Map();
  for (const row of manifest.entries) {
    if (!row || !validPath(row.path) || seen.has(row.path) || !['directory', 'file', 'link'].includes(row.type) || !Number.isInteger(row.mode) || row.mode < 0 || row.mode > 0o777 || !Number.isInteger(row.size) || row.size < 0 || row.size > 2 * 1024 ** 3) throw new Error('Chemin ou fichier invalide dans la sauvegarde.');
    const parts = row.path.split('/'); parts.pop();
    if (parts.length && seen.get(parts.join('/')) !== 'directory') throw new Error('Ordre des répertoires invalide.');
    if (row.type !== 'file' && row.size !== 0) throw new Error('Taille invalide.');
    if (row.type === 'link' && (typeof row.target !== 'string' || row.target.length > 2048 || row.target.includes('\0'))) throw new Error('Lien invalide.');
    row.offset = offset; offset += row.size; seen.set(row.path, row.type);
  }
  if (offset !== source.size) throw new Error('Sauvegarde Wine incomplète.');
  return manifest;
}
export async function importPrefix(fs, root, source) {
  const manifest = await inspectPack(source); fs.mkdirTree(root);
  if (fs.readdir(root).some(name => name !== '.' && name !== '..')) throw new Error('Le dossier de restauration doit être vide.');
  // No imported symlink is followed while restoring file data.
  for (const row of manifest.entries) {
    const full = root + '/' + row.path;
    if (row.type === 'directory') fs.mkdirTree(full);
    else if (row.type === 'file') {
      const stream = fs.open(full, 'w');
      try {
        for (let offset = 0; offset < row.size; offset += 4 * 1024 * 1024) {
          const data = new Uint8Array(await source.slice(row.offset + offset, row.offset + Math.min(row.size, offset + 4 * 1024 * 1024)).arrayBuffer());
          if (fs.write(stream, data, 0, data.length, offset) !== data.length) throw new Error('Restauration incomplète.');
        }
      } finally { fs.close(stream); }
    }
  }
  for (const row of manifest.entries) if (row.type === 'link') fs.symlink(row.target, root + '/' + row.path);
  // Apply read-only directory permissions only after their children were restored.
  for (const row of [...manifest.entries].reverse()) if (row.type !== 'link') fs.chmod(root + '/' + row.path, row.mode & 0o777);
}
