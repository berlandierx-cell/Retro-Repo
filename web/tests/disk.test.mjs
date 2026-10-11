import test from 'node:test';
import assert from 'node:assert/strict';
import {PersistentDisk, PAGE} from '../disk.js';

export class MemoryPersistence {
  constructor() { this.data = new Map(); }
  async get(id, index) { return this.data.get(id + ':' + index)?.slice(); }
  async put(id, entries) { for (const [index, data] of entries) this.data.set(id + ':' + index, data.slice()); }
}
const read = (disk, offset, count) => new Promise(resolve => disk.get(offset, count, resolve));
const write = (disk, offset, bytes) => new Promise(resolve => disk.set(offset, bytes, resolve));

test('cross-page disk writes survive a new instance and export the merged image', async () => {
  const original = new Uint8Array(PAGE * 3).fill(0x41), base = new Blob([original]);
  const persistence = new MemoryPersistence(), disk = new PersistentDisk(base, 'test', persistence);
  const changed = new Uint8Array(1024).fill(0x52);
  await write(disk, PAGE - 512, changed);
  const restarted = new PersistentDisk(base, 'test', persistence);
  assert.deepEqual(await read(restarted, PAGE - 512, 1024), changed);
  assert.deepEqual(await read(restarted, 0, 512), original.slice(0, 512));
  const chunks = []; await restarted.exportTo({write: async chunk => chunks.push(chunk)});
  const result = new Uint8Array(await new Blob(chunks).arrayBuffer());
  const expected = original.slice(); expected.set(changed, PAGE - 512);
  assert.deepEqual(result, expected);
});
test('overlapping writes are committed in order and copy volatile source buffers', async () => {
  const persistence = new MemoryPersistence(), disk = new PersistentDisk(new Blob([new Uint8Array(8192)]), 'race', persistence);
  const changed = new Uint8Array(512).fill(1), first = write(disk, 0, changed);
  changed.fill(99);
  const second = write(disk, 256, new Uint8Array(512).fill(2));
  await Promise.all([first, second]);
  const saved = await read(disk, 0, 768);
  assert.deepEqual(saved.slice(0, 256), new Uint8Array(256).fill(1));
  assert.deepEqual(saved.slice(256), new Uint8Array(512).fill(2));
});
test('a failed durable write is never acknowledged and stops further IO', async () => {
  let acknowledged = false, failure;
  const disk = new PersistentDisk(new Blob([new Uint8Array(4096)]), 'full', {get: async () => undefined, put: async () => {throw new Error('Quota exceeded');}}, error => failure = error);
  disk.set(0, new Uint8Array(512), () => acknowledged = true);
  await assert.rejects(disk.flush(), /Quota exceeded/);
  assert.equal(acknowledged, false); assert.match(failure.message, /Quota exceeded/);
});
test('read-only disks and out-of-bounds requests fail without success callbacks', async () => {
  const disk = new PersistentDisk(new Blob([new Uint8Array(4096)]), 'iso', new MemoryPersistence(), () => {}, true);
  disk.set(0, new Uint8Array(512), () => assert.fail('CD write acknowledged'));
  await assert.rejects(disk.flush(), /lecture seule/);
  const other = new PersistentDisk(new Blob([new Uint8Array(512)]), 'short', new MemoryPersistence());
  await assert.rejects(other.read(256, 512), RangeError);
});
test('bulk export overlays saved pages without per-sector database reads', async () => {
  const base = new Blob([new Uint8Array(PAGE * 2).fill(0x41)]), chunks = [];
  const disk = new PersistentDisk(base, 'bulk', {
    get: () => assert.fail('Export should use one ranged read'),
    range: async (id, start, end) => [{index: 1, data: new Uint8Array(PAGE).fill(0x42)}],
  });
  await disk.exportTo({write: async data => chunks.push(data)});
  const result = new Uint8Array(await new Blob(chunks).arrayBuffer());
  assert.deepEqual(result.subarray(0, PAGE), new Uint8Array(PAGE).fill(0x41));
  assert.deepEqual(result.subarray(PAGE), new Uint8Array(PAGE).fill(0x42));
});
