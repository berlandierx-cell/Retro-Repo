import test from 'node:test';
import assert from 'node:assert/strict';
import {setRegistryValue} from '../wine-registry.js';
test('Wine registry edits retain other sections and replace a key once', () => {
  const original = 'WINE REGISTRY Version 2\n\n[Software\\\\Wine\\\\Direct3D] 123\n"DirectDrawRenderer"="opengl"\n"Other"="keep"\n\n[Software\\\\Game] 456\n"Save"="yes"\n';
  const updated = setRegistryValue(original, 'Software\\Wine\\Direct3D', 'DirectDrawRenderer', 'gdi');
  assert.ok(updated.includes('"DirectDrawRenderer"="gdi"'));
  assert.ok(updated.includes('"Other"="keep"'));
  assert.ok(updated.includes('[Software\\\\Game] 456\n"Save"="yes"'));
  assert.equal(updated.split('"DirectDrawRenderer"=').length, 2);
  assert.equal(setRegistryValue(updated, 'Software\\Wine\\Direct3D', 'DirectDrawRenderer', 'gdi'), updated);
});
test('new registry sections quote literal values and reject line injection', () => {
  const updated = setRegistryValue('WINE REGISTRY Version 2\n#arch=win32\n', 'Software\\Wine\\Drives', 'd:', 'cdrom');
  assert.ok(updated.includes('[Software\\\\Wine\\\\Drives]\n"d:"="cdrom"'));
  assert.throws(() => setRegistryValue(updated, 'Software\nBad', 'key', 'value'), /invalide/);
});
