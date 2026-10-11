import test from 'node:test';
import assert from 'node:assert/strict';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
test('real Boxedwine guest accepts a second host command and shuts down', {timeout: 30000}, async () => {
  const {stdout} = await promisify(execFile)(process.execPath, [new URL('../scripts/control-probe.cjs', import.meta.url).pathname], {timeout: 25000, maxBuffer: 20000});
  assert.match(stdout, /GUEST BRIDGE: FIRST, SECOND, STOPPED/);
});
