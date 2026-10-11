// Logical headless test of the actual Boxedwine guest/host bridge. No graphical claim.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const {DAEMON} = require('../wine-commands.js');
const engine = path.resolve(__dirname, '../dist/vendor/boxedwine');
const logs = [];
const ctx = vm.createContext({require, console, process, Buffer, URL, WebAssembly, crypto: require('node:crypto').webcrypto, performance, setTimeout, clearTimeout, setInterval, clearInterval, setImmediate, clearImmediate,
  AudioContext: class { constructor() { this.sampleRate = 44100; this.state = 'running'; } },
  navigator: {userAgent: 'Node logical guest bridge test', hardwareConcurrency: 2, languages: ['fr-FR']}, document: {title: '', getElementById: () => null, querySelector: () => null},
  __filename: path.join(engine, 'boxedwine.js'), __dirname: engine, requestAnimationFrame: cb => setTimeout(() => cb(performance.now()), 1), cancelAnimationFrame: clearTimeout,
});
const job = text => `echo ${text} >/home/username/retroiso-control/proof\n`;
ctx.Module = {noInitialRun: true, noExitRuntime: true, print: line => { if (!String(line).startsWith('ran main loop')) logs.push(line); }, printErr: line => logs.push(line),
  preRun: [() => {
    ctx.FS.mkdirTree('/root'); ctx.FS.mkdirTree('/control');
    for (const name of ['stop', 'stopped', 'job.id', 'job.sh', 'ack', 'proof']) ctx.FS.writeFile('/control/' + name, '');
    ctx.FS.writeFile('/control/daemon.sh', DAEMON); ctx.FS.writeFile('/control/job.sh', job('FIRST')); ctx.FS.writeFile('/control/job.id', '1\n');
    ctx.FS.writeFile('/wine.zip', fs.readFileSync(path.resolve(__dirname, '../dist/vendor/wine.zip')));
  }],
  onRuntimeInitialized: () => ctx.callMain(['-root', '/root', '-zip', '/wine.zip', '-mount', '/control', '/home/username/retroiso-control', '-nosound', '-novideo', '/bin/sh', '/home/username/retroiso-control/daemon.sh']),
  onAbort: message => { console.error(message); process.exit(1); },
};
vm.runInContext(fs.readFileSync(path.join(engine, 'boxedwine.js'), 'utf8'), ctx);
let stage = 0; const started = Date.now();
setInterval(() => {
  if (!ctx.FS?.analyzePath('/control/proof').exists) return;
  const proof = ctx.FS.readFile('/control/proof', {encoding: 'utf8'});
  if (!stage && proof.includes('FIRST')) { stage = 1; ctx.FS.writeFile('/control/job.sh', job('SECOND')); ctx.FS.writeFile('/control/job.id', '2\n'); }
  if (stage === 1 && proof.includes('SECOND')) { stage = 2; ctx.FS.writeFile('/control/stop', 'stop\n'); }
  if (stage === 2 && ctx.FS.readFile('/control/stopped', {encoding: 'utf8'}).includes('STOPPED')) { console.log('GUEST BRIDGE: FIRST, SECOND, STOPPED'); process.exit(0); }
  if (Date.now() - started > 20000) { console.error(logs.slice(-15).join('\n')); console.error('Guest control bridge timed out at stage ' + stage); process.exit(1); }
}, 50);
