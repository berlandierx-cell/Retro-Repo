import test from 'node:test';
import assert from 'node:assert/strict';
import {exportPrefix, importPrefix, inspectPack} from '../wine-pack.js';
import {makeWineTest} from '../scripts/make-wine-test.mjs';
import {jobScript, windowsArguments} from '../wine-commands.js';
// Small in-memory filesystem with symlinks; imported paths are never delegated to host IO.
function filesystem() {
  const nodes = new Map([['/root', {mode: 0o40755}]]);
  return {nodes, isDir: mode => (mode & 0o170000) === 0o40000, isLink: mode => (mode & 0o170000) === 0o120000,
    mkdirTree(path) { let at = ''; for (const part of path.split('/').filter(Boolean)) { at += '/' + part; if (!nodes.has(at)) nodes.set(at, {mode: 0o40755}); } },
    readdir(path) { return ['.', '..', ...[...nodes.keys()].filter(name => name.startsWith(path + '/') && !name.slice(path.length + 1).includes('/')).map(name => name.slice(path.length + 1))]; },
    lstat(path) { const node = nodes.get(path); return {...node, size: node.data?.length || 0}; },
    readlink: path => nodes.get(path).target,
    symlink(target, path) { nodes.set(path, {mode: 0o120777, target}); },
    chmod(path, mode) { const node = nodes.get(path); node.mode = node.mode & 0o170000 | mode; },
    open(path, mode) { if (mode === 'w') nodes.set(path, {mode: 0o100644, data: new Uint8Array()}); return path; }, close() {},
    read(path, data, start, length, offset) { data.set(nodes.get(path).data.subarray(offset, offset + length), start); return length; },
    write(path, data, start, length, offset) { const node = nodes.get(path), result = new Uint8Array(Math.max(node.data.length, offset + length)); result.set(node.data); result.set(data.subarray(start, start + length), offset); node.data = result; return length; },
  };
}
function pack(entries, payload = []) { const metadata = new TextEncoder().encode(JSON.stringify({schema: 1, entries})), length = new Uint8Array(4); new DataView(length.buffer).setUint32(0, metadata.length, true); return new Blob(['RETROWP1', length, metadata, ...payload]); }
test('complete prefix round trip preserves registry, binary files and links', async () => {
  const source = filesystem(); source.mkdirTree('/root/home/user'); source.nodes.set('/root/home/user/user.reg', {mode: 0o100644, data: new TextEncoder().encode('[Game]\\nSave=1')}); source.nodes.set('/root/game.exe', {mode: 0o100755, data: new Uint8Array(4 * 1024 * 1024 + 9).fill(73)}); source.symlink('../game.exe', '/root/home/shortcut');
  const chunks = []; await exportPrefix(source, '/root', {write: async data => chunks.push(data.slice())}); const backup = new Blob(chunks);
  const manifest = await inspectPack(backup); assert.equal(manifest.entries.length, 5);
  const target = filesystem(); await importPrefix(target, '/root', backup);
  assert.deepEqual(target.nodes.get('/root/game.exe').data, source.nodes.get('/root/game.exe').data); assert.equal(target.nodes.get('/root/home/shortcut').target, '../game.exe'); assert.deepEqual(target.nodes.get('/root/home/user/user.reg').data, source.nodes.get('/root/home/user/user.reg').data);
  await assert.rejects(importPrefix(target, '/root', backup), /vide/);
});
test('restore rejects traversal, symlink-parent writes and truncation before touching files', async () => {
  await assert.rejects(inspectPack(pack([{path: '../outside', type: 'file', mode: 420, size: 0}])), /invalide/);
  await assert.rejects(inspectPack(pack([{path: 'link', type: 'link', target: '/', mode: 511, size: 0}, {path: 'link/x', type: 'file', mode: 420, size: 0}])), /Ordre/);
  await assert.rejects(inspectPack(pack([{path: 'game', type: 'file', mode: 420, size: 3}], ['x'])), /incomplète/);
});
test('Windows paths remain literal shell arguments', () => {
  const path = "D:\\Setup's folder\\installer.exe"; assert.deepEqual(windowsArguments(path), ['explorer', '/desktop=RetroIso,800x600', path]); assert.ok(jobScript(path).includes("Setup'\\''s")); assert.throws(() => windowsArguments('/tmp/program')); assert.throws(() => jobScript(path, 'invalid'));
});
test('original Win32 test has an x86 PE header and real Windows imports', () => {
  const pe = makeWineTest(), view = new DataView(pe.buffer); assert.equal(view.getUint16(0, true), 0x5a4d); assert.equal(view.getUint32(0x80, true), 0x4550); assert.equal(view.getUint16(0x84, true), 0x14c); assert.equal(view.getUint16(0x98 + 68, true), 2); assert.ok(new TextDecoder().decode(pe).includes('WriteFile')); assert.ok(new TextDecoder().decode(pe).includes('MessageBoxA')); assert.ok(new TextDecoder().decode(pe).includes('RegSetValueExA')); assert.ok(!jobScript('test').includes('wine reg'));
});
