export const ENGINE = 'boxedwine@65467424603484756a73308412f40b906d28b650+wine11-v14';
export const ROOT_BASE = '/retro-wine';
export const DAEMON = `#!/bin/sh
export WINEPREFIX=/home/username/.wine
export WINELOADERNOEXEC=1
last_job=
while :; do
  read -r stop </home/username/retroiso-control/stop
  if [ "$stop" = stop ]; then
    # End the Windows session before waiting for the server to flush its registry.
    read -r windows_started </home/username/retroiso-control/windows.started
    if [ "$windows_started" = 1 ]; then
    WINEDLLOVERRIDES='mscoree,mshtml=' /bin/wine wineboot -e -f -k -s >/home/username/retroiso-control/registry.log 2>&1
    if [ $? -ne 0 ]; then echo CLOSE_FAILED >/home/username/retroiso-control/stopped; exit 1; fi
    /opt/wine/bin/wineserver -w
    fi
    if [ -n "$job_pid" ]; then wait "$job_pid"; fi
    echo STOPPED >/home/username/retroiso-control/stopped
    exit 0
  fi
  read -r job </home/username/retroiso-control/job.id
  if [ -n "$job" ] && [ "$job" != "$last_job" ]; then
    cp /home/username/retroiso-control/job.sh /home/username/retroiso-control/job_$job.sh
    /bin/sh /home/username/retroiso-control/job_$job.sh &
    job_pid=$!
    last_job=$job
    echo "$job" >/home/username/retroiso-control/ack
  fi
  /bin/sleep 0.02
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
  // The compact runtime omits Mono and Gecko; skip their download dialogs.
  return `#!/bin/sh\necho 1 >/home/username/retroiso-control/windows.started\ncd ${quote(working + (directory ? '/' + directory : ''))} || exit 1\nWINEDLLOVERRIDES='mscoree,mshtml=' /bin/wine ${args.map(quote).join(' ')} >/home/username/retroiso-control/program.log 2>&1\necho $? >/home/username/retroiso-control/result\n`;
}
