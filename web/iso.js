const SECTOR = 2048;
const decoder = new TextDecoder('ascii');
const safeName = name => {
  if (!name || name === '.' || name === '..' || /[\/\\\0]/.test(name)) throw new Error('Nom de fichier ISO invalide.');
  return name;
};
const uint32 = (bytes, offset) => new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength).getUint32(offset, true);
function extent(source, sector, size) {
  const start = sector * SECTOR;
  if (!Number.isSafeInteger(start) || !Number.isSafeInteger(size) || start + size > source.size) throw new Error('L’ISO contient un fichier hors de son image.');
  return source.slice(start, start + size);
}
function record(bytes, offset, joliet) {
  const length = bytes[offset];
  if (length < 34 || offset + length > bytes.length || bytes[offset + 32] > length - 33) throw new Error('Répertoire ISO incomplet.');
  const id = bytes.subarray(offset + 33, offset + 33 + bytes[offset + 32]);
  let name;
  if (id.length === 1 && id[0] < 2) name = null;
  else if (joliet) {
    if (id.length % 2) throw new Error('Nom Joliet invalide.');
    name = ''; for (let i = 0; i < id.length; i += 2) name += String.fromCharCode(id[i] * 256 + id[i + 1]);
  } else name = decoder.decode(id);
  if (name !== null) name = safeName(name.replace(/;\d+$/, '').replace(/\.$/, ''));
  if (bytes[offset + 25] & 0x80) throw new Error('Les fichiers ISO à plusieurs extents ne sont pas encore pris en charge.');
  return {length, name, directory: !!(bytes[offset + 25] & 2), sector: uint32(bytes, offset + 2), size: uint32(bytes, offset + 10)};
}
export async function readISO(source) {
  let primary, supplementary;
  for (let i = 16; i < 48; i++) {
    const bytes = new Uint8Array(await source.slice(i * SECTOR, (i + 1) * SECTOR).arrayBuffer());
    if (bytes.length !== SECTOR || decoder.decode(bytes.subarray(1, 6)) !== 'CD001') throw new Error('Image ISO 9660 attendue. Les images BIN/CUE et UDF seules ne sont pas prises en charge.');
    if (bytes[0] === 1) primary = bytes;
    if (bytes[0] === 2 && bytes[88] === 0x25 && bytes[89] === 0x2f && [0x40, 0x43, 0x45].includes(bytes[90])) supplementary = bytes;
    if (bytes[0] === 255) break;
  }
  const descriptor = supplementary || primary;
  if (!descriptor || new DataView(descriptor.buffer).getUint16(128, true) !== SECTOR) throw new Error('Ce format de CD ne peut pas être lu.');
  const joliet = !!supplementary, root = record(descriptor, 156, joliet), entries = [], visited = new Set(), paths = new Set();
  let total = 0;
  async function walk(directory, parent, depth) {
    if (depth > 32 || directory.size > 16 * 1024 * 1024 || visited.has(directory.sector)) throw new Error('Structure ISO trop complexe ou cyclique.');
    visited.add(directory.sector);
    const bytes = new Uint8Array(await extent(source, directory.sector, directory.size).arrayBuffer());
    for (let offset = 0; offset < bytes.length;) {
      if (!bytes[offset]) { offset = (Math.floor(offset / SECTOR) + 1) * SECTOR; continue; }
      const item = record(bytes, offset, joliet); offset += item.length;
      if (!item.name) continue;
      const path = parent ? parent + '/' + item.name : item.name;
      if (paths.has(path.toLowerCase()) || entries.length >= 30000) throw new Error('L’ISO contient trop de fichiers ou des noms ambigus.');
      paths.add(path.toLowerCase());
      const blob = extent(source, item.sector, item.size);
      entries.push({path, directory: item.directory, size: item.size, blob});
      if (item.directory) await walk(item, path, depth + 1);
      else { total += item.size; if (total > 2 * 1024 ** 3) throw new Error('Le contenu du CD dépasse 2 Go.'); }
    }
  }
  await walk(root, '', 0);
  return {entries, total, label: decoder.decode(primary?.subarray(40, 72) || new Uint8Array()).replace(/\0+$/, '').trim()};
}
