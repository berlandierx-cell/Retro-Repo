import {mkdir, copyFile, readFile, writeFile, rm} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {makeDemo} from './make-demo.mjs';
import {buildWine} from './build-wine.mjs';
import {makeWineTest} from './make-wine-test.mjs';
const web = path.resolve(fileURLToPath(new URL('..', import.meta.url)));
const destination = path.join(web, 'dist');
await rm(destination, {recursive: true, force: true}); await mkdir(path.join(destination, 'vendor'), {recursive: true});
for (const name of ['index.html', 'style.css', 'app.js', 'disk.js', 'storage.js', 'drive.js', 'config.json', 'manifest.webmanifest', 'wine.html', 'wine.css', 'wine-app.js', 'wine-frame.html', 'wine-frame.js', 'iso.js', 'wine-pack.js', 'wine-commands.js']) await copyFile(path.join(web, name), path.join(destination, name));
for (const name of ['libv86.js', 'v86.wasm', 'v86-fallback.wasm']) await copyFile(path.join(web, 'node_modules/v86/build', name), path.join(destination, 'vendor', name));
const pin = '94faf3d6b19ecabc53a4010aaa24252fa6a162e2';
const sums = {};
for (const name of ['seabios.bin', 'vgabios.bin', 'COPYING.LESSER']) {
  const response = await fetch(`https://raw.githubusercontent.com/copy/v86/${pin}/bios/${name}`);
  if (!response.ok) throw new Error('BIOS download failed: ' + response.status);
  const data = new Uint8Array(await response.arrayBuffer());
  await writeFile(path.join(destination, 'vendor', name), data); sums[name] = createHash('sha256').update(data).digest('hex');
}
await writeFile(path.join(destination, 'demo.img'), makeDemo());
await writeFile(path.join(destination, 'retroiso-test.exe'), makeWineTest());
const license = await readFile(path.join(web, 'node_modules/v86/LICENSE'), 'utf8');
const wineNotices = await buildWine(web, destination);
await writeFile(path.join(destination, 'OPEN-SOURCE-NOTICES.txt'), `Rétro Iso web\n\nv86 0.5.471 (upstream ${pin})\nhttps://github.com/copy/v86\n\n${license}\n\nSeaBIOS and VGA BIOS: see vendor/COPYING.LESSER and source ${pin}/bios at https://github.com/copy/v86/tree/${pin}/bios\nSource/build script: https://github.com/copy/v86/blob/${pin}/bios/fetch-and-build-seabios.sh\n\nThe Rétro Iso diagnostic boot sector is original project code (scripts/make-demo.mjs). No Windows or DOS image is included.\n${wineNotices}`);
await writeFile(path.join(destination, 'build-info.json'), JSON.stringify({engine: 'v86@0.5.471', upstream: pin, bios: sums}, null, 2));
await writeFile(path.join(destination, '.nojekyll'), '');
console.log('Static app built in web/dist (v86 0.5.471, pinned BIOS, original boot test).');
