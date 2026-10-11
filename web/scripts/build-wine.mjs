import {mkdir, readFile, writeFile, copyFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import path from 'node:path';
export const WINE_BUILD = {
  source: '65467424603484756a73308412f40b906d28b650',
  engine: {url: 'https://boxedwine.org/builds/branches/master/builds/87/artifacts/build-87.zip', sha256: 'efd56751338a509271cc2861eefd17304c8adce875a3a5a90d41b3c7c5660f02'},
  wine: {url: 'https://boxedwine.org/v2/14/TinyCore15Wine11.0-web.zip', sha256: '3365e31caaf6a1fc832a0a7c4812592d25410b9f5c9536277131388c8e103a49'},
};
export async function buildWine(web, destination) {
  const cache = path.join(web, '.cache/wine'); await mkdir(cache, {recursive: true});
  for (const [name, asset] of Object.entries({engine: WINE_BUILD.engine, wine: WINE_BUILD.wine})) {
    const filename = path.join(cache, name + '.zip'); let data;
    try { data = await readFile(filename); } catch { /* download below */ }
    if (!data || createHash('sha256').update(data).digest('hex') !== asset.sha256) {
      console.log('Downloading pinned ' + name + '…');
      const response = await fetch(asset.url, {signal: AbortSignal.timeout(300000)});
      if (!response.ok) throw new Error(name + ': HTTP ' + response.status);
      data = Buffer.from(await response.arrayBuffer());
      if (createHash('sha256').update(data).digest('hex') !== asset.sha256) throw new Error(name + ': checksum mismatch');
      await writeFile(filename, data);
    }
  }
  const target = path.join(destination, 'vendor/boxedwine'); await mkdir(target, {recursive: true});
  execFileSync('python3', ['-c', 'import zipfile,sys,pathlib\nz=zipfile.ZipFile(sys.argv[1])\nfor n in ("boxedwine.js","boxedwine.wasm"):\n pathlib.Path(sys.argv[2],n).write_bytes(z.read("Web/SingleThreadedJit/"+n))', path.join(cache, 'engine.zip'), target]);
  await copyFile(path.join(cache, 'wine.zip'), path.join(destination, 'vendor/wine.zip'));
  const templates = path.join(destination, 'vendor/wine-prefix'); await mkdir(templates, {recursive: true});
  execFileSync('python3', ['-c', 'import zipfile,sys,pathlib\nz=zipfile.ZipFile(sys.argv[1])\nfor n in ("user.reg","system.reg"):\n matches=[p for p in z.namelist() if p.endswith("home/username/.wine/"+n)]\n if len(matches)!=1: raise RuntimeError("Wine registry template missing: "+n)\n pathlib.Path(sys.argv[2],n).write_bytes(z.read(matches[0]))', path.join(cache, 'wine.zip'), templates]);
  const response = await fetch(`https://raw.githubusercontent.com/danoon2/Boxedwine/${WINE_BUILD.source}/license.txt`);
  if (!response.ok) throw new Error('Boxedwine license: HTTP ' + response.status);
  await writeFile(path.join(target, 'LICENSE'), await response.text());
  await writeFile(path.join(destination, 'wine-build-info.json'), JSON.stringify(WINE_BUILD, null, 2));
  return `\nBoxedwine GPL-2.0, build 87, source ${WINE_BUILD.source}\nSource and build instructions: https://github.com/danoon2/Boxedwine/tree/${WINE_BUILD.source}\nLicense: vendor/boxedwine/LICENSE\nThe software-frame adapter in wine-video.js is adapted from upstream boxedwine-shell.js, GPL-2.0-or-later. Its modified source is shipped with this site.\nWine 11 / TinyCore 15, upstream web filesystem v14 (Wine LGPL-2.1; see licenses inside vendor/wine.zip).\nOriginal filesystem: ${WINE_BUILD.wine.url}\nWine source: https://github.com/wine-mirror/wine/tree/db11d0fe6a169c457e23d007e20404643d067aa8\nWine build recipes and patches: https://github.com/danoon2/Boxedwine/tree/${WINE_BUILD.source}/tools/buildWine\nThese upstream binaries are unmodified. No Microsoft Windows installation is supplied.\n`;
}
