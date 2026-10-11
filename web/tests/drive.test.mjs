import test from 'node:test';
import assert from 'node:assert/strict';
import {Drive, folderId} from '../drive.js';
test('folder IDs are extracted from normal Drive links', () => {assert.equal(folderId('https://drive.google.com/drive/folders/abc-123?usp=sharing'), 'abc-123');});
test('large uploads are streamed in chunks and always create a new file', async t => {
  const requests = [];
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    requests.push({url, ...options});
    if (options.method === 'POST') return new Response(null, {status: 200, headers: {Location: 'https://www.googleapis.com/upload/drive/v3/files?upload_id=test'}});
    const final = options.headers.get('Content-Range').endsWith('/9437184') && options.body.size === 1024 * 1024;
    return new Response(final ? JSON.stringify({id: 'new-version'}) : null, {status: final ? 200 : 308});
  });
  const drive = new Drive(); drive.token = 'test-token'; drive.expires = Date.now() + 60000;
  const result = await drive.upload('folder', 'disk.img', new Blob([new Uint8Array(9 * 1024 * 1024)]));
  assert.equal(result.id, 'new-version'); assert.equal(requests.length, 3);
  assert.equal(requests[0].method, 'POST');
  assert.equal(requests[1].headers.get('Content-Range'), 'bytes 0-8388607/9437184');
  assert.equal(requests[2].headers.get('Content-Range'), 'bytes 8388608-9437183/9437184');
  assert.ok(requests.every(request => request.headers.get('Authorization') === 'Bearer test-token'));
});
test('listing follows every provider page', async t => {
  t.mock.method(globalThis, 'fetch', async url => new Response(JSON.stringify(url.includes('pageToken=second') ? {files: [{name: 'B', id: '2'}]} : {files: [{name: 'A', id: '1'}], nextPageToken: 'second'})));
  const drive = new Drive(); drive.token = 'test-token'; drive.expires = Date.now() + 60000;
  assert.equal((await drive.list('folder')).length, 2);
});
test('expired tokens fail before sending any request', async t => {
  t.mock.method(globalThis, 'fetch', () => assert.fail('Expired credentials sent'));
  const drive = new Drive(); drive.token = 'test-token'; drive.expires = 0;
  await assert.rejects(drive.list('folder'), /Reconnecte/);
});
