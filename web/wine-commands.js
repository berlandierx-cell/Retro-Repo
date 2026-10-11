export const ENGINE = 'boxedwine@65467424603484756a73308412f40b906d28b650+wine11-v14';
export const ROOT_BASE = '/retro-wine';
export const DAEMON = `#!/bin/sh
export WINEPREFIX=/home/username/.wine
export WINELOADERNOEXEC=1
while :; do
  if [ -f /mnt/control/stop ]; then
    if [ -n "$job_pid" ]; then kill "$job_pid" 2>/dev/null; fi
    /opt/wine/bin/wineserver -k
    /opt/wine/bin/wineserver -w
    echo STOPPED >/mnt/control/stopped
    exit 0
  fi
  if [ -f /mnt/control/job.sh ]; then
    mv /mnt/control/job.sh /mnt/control/current.sh
    /bin/sh /mnt/control/current.sh &
    job_pid=$!
  fi
  /bin/sleep 0.2
done
`;
export const quote = value => "'" + String(value).replace(/'/g, "'\\''") + "'";
export function windowsArguments(program) {
  if (program === 'test') return ['C:\\retroiso-test.exe'];
  if (program === 'desktop') return ['explorer', '/desktop=RetroIso,800x600'];
  if (!/^[CD]:\\/i.test(program) || /[\0\r\n]/.test(program)) throw new Error('Programme attendu sur C: ou D:.');
  return ['explorer', '/desktop=RetroIso,800x600', program];
}
export function jobScript(program, renderer = 'gdi') {
  if (!['gdi', 'opengl'].includes(renderer)) throw new Error('Rendu invalide.');
  const args = windowsArguments(program);
  const path = /^[CD]:\\/i.test(program) ? program.slice(3).replace(/\\/g, '/') : '';
  const directory = path.slice(0, Math.max(path.lastIndexOf('/'), 0));
  const working = /^[D]:/i.test(program) ? '/mnt/drive_d' : '/home/username/.wine/drive_c';
  return `#!/bin/sh\n/bin/wine reg add 'HKCU\\Software\\Wine\\Direct3D' /v DirectDrawRenderer /d ${renderer} /f >/mnt/control/registry.log 2>&1\n/bin/wine reg add 'HKLM\\Software\\Wine\\Drives' /v 'd:' /d cdrom /f >>/mnt/control/registry.log 2>&1\ncd ${quote(working + (directory ? '/' + directory : ''))} || exit 1\n/bin/wine ${args.map(quote).join(' ')} >/mnt/control/program.log 2>&1\necho $? >/mnt/control/result\n`;
}
