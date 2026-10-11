import test from 'node:test';
import assert from 'node:assert/strict';
import {readISO} from '../iso.js';
function image({joliet = false, badExtent = false, cycle = false} = {}) {
  const output = new Uint8Array(24 * 2048);
  function record(sector, size, name, directory = false) {
    const id = typeof name === 'number' ? new Uint8Array([name]) : joliet ? Uint8Array.from([...name].flatMap(character => [character.charCodeAt(0) >> 8, character.charCodeAt(0) & 255])) : new TextEncoder().encode(name);
    const value = new Uint8Array(33 + id.length + (id.length % 2 === 0 ? 1 : 0)), view = new DataView(value.buffer);
    value[0] = value.length; view.setUint32(2, sector, true); view.setUint32(10, size, true); value[25] = directory ? 2 : 0; value[32] = id.length; value.set(id, 33); return value;
  }
  const pvd = output.subarray(16 * 2048, 17 * 2048); pvd[0] = 1; pvd.set(new TextEncoder().encode('CD001'), 1); pvd[6] = 1; new DataView(pvd.buffer, pvd.byteOffset).setUint16(128, 2048, true); pvd.set(record(20, 2048, 0, true), 156);
  pvd.set(new TextEncoder().encode('TEST CD'), 40);
  let terminator = 17;
  if (joliet) { const svd = output.subarray(17 * 2048, 18 * 2048); svd.set(pvd); svd[0] = 2; svd.set([0x25, 0x2f, 0x45], 88); terminator = 18; }
  output[terminator * 2048] = 255; output.set(new TextEncoder().encode('CD001'), terminator * 2048 + 1);
  let at = 20 * 2048;
  for (const row of [record(20, 2048, 0, true), record(20, 2048, 1, true), record(cycle ? 20 : badExtent ? 100 : 21, cycle ? 2048 : 4, joliet ? 'Jeu été.exe;1' : 'SETUP.EXE;1', cycle)]) { output.set(row, at); at += row.length; }
  output.set([77, 90, 42, 43], 21 * 2048); return new Blob([output]);
}
test('ISO 9660 exposes the actual setup bytes and normalizes versions', async () => {
  const iso = await readISO(image()); assert.equal(iso.label, 'TEST CD'); assert.equal(iso.total, 4); assert.equal(iso.entries[0].path, 'SETUP.EXE'); assert.deepEqual([...new Uint8Array(await iso.entries[0].blob.arrayBuffer())], [77, 90, 42, 43]);
});
test('Joliet preserves names and rejects out-of-bounds files and cycles', async () => {
  assert.equal((await readISO(image({joliet: true}))).entries[0].path, 'Jeu été.exe');
  await assert.rejects(readISO(image({badExtent: true})), /hors/); await assert.rejects(readISO(image({cycle: true})), /cyclique/); await assert.rejects(readISO(new Blob(['not an ISO'])), /ISO 9660/);
});
