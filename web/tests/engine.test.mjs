import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {V86} from 'v86';
import {PersistentDisk} from '../disk.js';
import {makeDemo} from '../scripts/make-demo.mjs';

const base = new Blob([makeDemo()]);
const bios = await readFile(new URL('../dist/vendor/seabios.bin', import.meta.url));
const vga = await readFile(new URL('../dist/vendor/vgabios.bin', import.meta.url));
const buffer = bytes => bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
const state = new Map();
const persistence = {get: async (id, index) => state.get(index)?.slice(), put: async (id, entries) => {for (const [index, data] of entries) state.set(index, data.slice());}};

function boot(initialState) {
  const disk = new PersistentDisk(base, 'boot-demo', persistence, error => {throw error;});
  const emulator = new V86({wasm_path: new URL('../node_modules/v86/build/v86.wasm', import.meta.url).pathname, bios: {buffer: buffer(bios)}, vga_bios: {buffer: buffer(vga)}, fda: disk, memory_size: 32 * 1024 * 1024, boot_order: 0x321, autostart: true, disable_speaker: true, ...(initialState ? {initial_state: {buffer: initialState}} : {})});
  const screen = Array(80 * 25).fill(' ');
  emulator.add_listener('screen-put-char', ([row, col, char]) => {screen[row * 80 + col] = String.fromCharCode(char);});
  return {emulator, disk, screen};
}
async function waitFor(instance, text) {
  const start = Date.now();
  while (Date.now() - start < 15000) {
    if (instance.screen.join('').includes(text)) return;
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  throw new Error('Missing screen text: ' + text + '\n' + instance.screen.join(''));
}
test('real v86 boots, receives keys, writes a disk and finds the marker after a cold restart', {timeout: 45000}, async () => {
  let snapshot;
  const first = boot();
  try {
    await waitFor(first, 'Premier demarrage');
    await first.emulator.keyboard_send_scancodes([0x1c, 0x9c]);
    await waitFor(first, 'ECRITURE OK');
    await first.emulator.stop(); await first.disk.flush();
    assert.equal(state.get(0)[512], 0x52);
    snapshot = await first.emulator.save_state();
    assert.ok(snapshot.byteLength > 0);
  } finally {await first.emulator.destroy();}
  const second = boot();
  try {await waitFor(second, 'PERSISTENCE OK');} finally {await second.emulator.destroy();}
  const resumed = boot(snapshot);
  try {
    await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('Snapshot did not resume')), 10000);
      resumed.emulator.add_listener('emulator-started', () => {clearTimeout(timeout); resolve();});
    });
    await resumed.emulator.keyboard_send_text('Q');
    await waitFor(resumed, 'Q');
    assert.equal(state.get(0)[512], 0x52);
  } finally {await resumed.emulator.destroy();}
});
