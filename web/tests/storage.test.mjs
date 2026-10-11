import 'fake-indexeddb/auto';
import test from 'node:test';
import assert from 'node:assert/strict';
import {blocks, machines, transaction, db} from '../storage.js';

test('IndexedDB commits disk pages, isolates disks, and deletes only the selected disk', async () => {
  await blocks.put('disk-a', [[0, new Uint8Array([1])], [1, new Uint8Array([2])], [2, new Uint8Array([3])]]);
  await blocks.put('disk-b', [[1, new Uint8Array([9])]]);
  assert.deepEqual(await blocks.get('disk-a', 1), new Uint8Array([2]));
  assert.deepEqual((await blocks.range('disk-a', 1, 2)).map(row => row.index), [1]);
  await blocks.delete('disk-a');
  assert.equal(await blocks.get('disk-a', 1), undefined);
  assert.deepEqual(await blocks.get('disk-b', 1), new Uint8Array([9]));
});

test('an aborted transaction never publishes a partially written machine', async () => {
  await assert.rejects(transaction('machines', 'readwrite', store => {
    store.put({id: 'aborted'});
    throw new Error('Interrupted import');
  }), /Interrupted import/);
  assert.equal((await machines.list()).some(machine => machine.id === 'aborted'), false);
  await machines.put({id: 'complete', name: 'Installed PC'});
  assert.equal((await machines.list()).find(machine => machine.id === 'complete').name, 'Installed PC');
  await machines.delete('complete');
  (await db()).close();
});
