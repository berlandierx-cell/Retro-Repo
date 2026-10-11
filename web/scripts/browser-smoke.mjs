// Run on GitHub Actions, not in the managed workspace. Real Chromium/WASM/OPFS/IDBFS.
import {chromium} from 'playwright';
import {spawn} from 'node:child_process';
import {mkdir, writeFile} from 'node:fs/promises';
import {PNG} from 'pngjs';
if (!process.env.CI) throw new Error('This browser verification runs on CI.');
const server = spawn('python3', ['-m', 'http.server', '4173', '--bind', '127.0.0.1', '--directory', 'dist'], {stdio: 'ignore'});
let browser; const diagnostic = [];
let rejectBrowserError; const browserError = new Promise((resolve, reject) => { rejectBrowserError = reject; }); browserError.catch(() => {});
await mkdir('browser-results', {recursive: true});
try {
  for (let attempt = 0; attempt < 100; attempt++) {
    try { if ((await fetch('http://127.0.0.1:4173/wine.html')).ok) break; } catch { /* server starts */ }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  browser = await chromium.launch({args: ['--enable-unsafe-swiftshader']});
  const context = await browser.newContext({viewport: {width: 430, height: 930}}), page = await context.newPage();
  // OAuth is verified separately by the user's signed-in session.
  await page.route('https://accounts.google.com/**', route => route.fulfill({status: 200, contentType: 'text/javascript', body: ''}));
  page.on('console', message => { if (message.type() === 'error') diagnostic.push(message.text()); });
  page.on('pageerror', error => { diagnostic.push(error.message); rejectBrowserError(error); });
  await page.goto('http://127.0.0.1:4173/wine.html');
  await page.locator('#status').filter({hasText: 'Prêt.'}).waitFor({timeout: 30000});
  await page.locator('#test').click();
  await Promise.race([page.waitForFunction(expected => {
    const error = document.getElementById('player').dataset.error; if (error) throw new Error(error);
    return document.getElementById('engine-status').textContent.includes(expected);
  }, 'écriture réussie', {timeout: 360000}), browserError]);
  console.log('Windows file write: PASS');
  await page.screenshot({path: 'browser-results/first-launch.png', fullPage: true});
  const screen = await page.locator('#frame-host').screenshot({path: 'browser-results/windows-screen.png'});
  const png = PNG.sync.read(screen); let litPixels = 0;
  for (let i = 0; i < png.data.length; i += 4) if (png.data[i] + png.data[i + 1] + png.data[i + 2] > 45) litPixels++;
  if (litPixels < 500) throw new Error('Windows wrote the file but its screen is black');
  console.log('Visible Windows screen: PASS, pixels=' + litPixels);
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1);
  if (overflow) throw new Error('Wine page overflows the mobile viewport');
  const frame = page.frames().find(item => item.url().endsWith('/wine-frame.html'));
  const pixels = await frame.evaluate(() => {
    const canvas = document.getElementById('canvas');
    // GDI uses the SDL canvas; screenshot is retained for visual review.
    return {width: canvas.width, height: canvas.height, initialized: !!Module.__boxedwineInputHandlersInstalled};
  });
  if (!pixels.initialized || !pixels.width || !pixels.height) throw new Error('Real graphical input/canvas was not initialized');
  await page.locator('#close').click(); await page.locator('#player').waitFor({state: 'hidden', timeout: 120000});
  const registry = await page.evaluate(async () => {
    const {machines} = await import('./storage.js');
    const machine = (await machines.list()).find(item => item.kind === 'wine' && item.diagnostic);
    if (!machine) throw new Error('Windows profile missing after close');
    const directory = '/retro-wine/' + machine.id;
    return new Promise((resolve, reject) => {
      const open = indexedDB.open(directory);
      open.onerror = () => reject(open.error);
      open.onupgradeneeded = () => { open.transaction.abort(); reject(new Error('Wine storage was not persisted')); };
      open.onsuccess = () => {
        const db = open.result, tx = db.transaction('FILE_DATA', 'readonly');
        const read = tx.objectStore('FILE_DATA').get(directory + '/home/username/.wine/user.reg');
        read.onsuccess = () => resolve(read.result?.contents ? new TextDecoder().decode(read.result.contents) : '');
        read.onerror = () => reject(read.error); tx.oncomplete = () => db.close();
      };
    });
  });
  if (!registry.includes('"DirectDrawRenderer"="gdi"') || !registry.includes('"PersistenceMarker"="Windows-registry-write"')) throw new Error('Wine registry was not saved at shutdown');
  console.log('Wine registry persisted on close: PASS');
  await page.reload(); await page.locator('#status').filter({hasText: 'Prêt.'}).waitFor();
  await page.locator('#test').click(); await Promise.race([page.waitForFunction(expected => {
    const error = document.getElementById('player').dataset.error; if (error) throw new Error(error);
    return document.getElementById('engine-status').textContent.includes(expected);
  }, 'PERSISTENCE OK', {timeout: 360000}), browserError]);
  await page.screenshot({path: 'browser-results/cold-restart.png', fullPage: true});
  await page.locator('#close').click(); await page.locator('#player').waitFor({state: 'hidden', timeout: 120000});
  if (diagnostic.length) throw new Error('Browser errors: ' + diagnostic.join('\n'));
  await writeFile('browser-results/result.json', JSON.stringify({windowsFileWrite: true, registryPersisted: true, coldRestart: true, nonBlackScreenPixels: litPixels, canvas: pixels, testedBrowser: await browser.version(), mobileViewport: true, pixel9aHardwareTest: false}, null, 2));
  console.log('REAL WINDOWS WRITE AND COLD RESTART: PASS');
} catch (error) {
  if (browser) {
    const page = browser.contexts()[0]?.pages()[0];
    if (page) {
      await page.screenshot({path: 'browser-results/failure.png', fullPage: true}).catch(() => {});
      diagnostic.push(await page.locator('#status').textContent().catch(() => ''));
      diagnostic.push(await page.locator('#engine-status').textContent().catch(() => ''));
      diagnostic.push(await page.locator('#logs').textContent().catch(() => ''));
      const engine = page.frames().find(item => item.url().endsWith('/wine-frame.html'));
      if (engine) { await engine.locator('#boxedwine-webgl-canvas-0').screenshot({path: 'browser-results/failure-windows.png'}).catch(() => {}); }
      if (engine) diagnostic.push(await engine.evaluate(() => {
        if (!globalThis.FS) return 'No runtime filesystem';
        return JSON.stringify(Object.fromEntries(['job.id', 'ack', 'result', 'registry.log', 'program.log', 'stop', 'stopped'].map(name => {
          try { return [name, FS.readFile('/control/' + name, {encoding: 'utf8'}).slice(-12000)]; } catch (error) { return [name, error.message]; }
        })), null, 2);
      }).catch(error => error.message));
    }
  }
  await writeFile('browser-results/errors.txt', diagnostic.join('\n'));
  console.error(diagnostic.join('\n').slice(-24000)); throw error;
} finally { await browser?.close(); server.kill(); }
