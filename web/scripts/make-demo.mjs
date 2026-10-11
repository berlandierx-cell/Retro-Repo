// Original public-domain boot sector: exercises CPU, BIOS, keyboard and a
// writable floppy. No DOS or Windows image is redistributed by this project.
export function makeDemo() {
  const code = [], labels = new Map(), patches = [];
  const emit = (...bytes) => code.push(...bytes);
  const word = value => emit(value & 255, value >> 8 & 255);
  const label = name => labels.set(name, code.length);
  const address = name => { patches.push({at: code.length, name, kind: 'address'}); word(0); };
  const relative = name => { patches.push({at: code.length, name, kind: 'relative'}); word(0); };
  const jump = name => { emit(0xe9); relative(name); };
  const call = name => { emit(0xe8); relative(name); };
  const conditional = (op, name) => { emit(0x0f, op); relative(name); };
  const message = name => { emit(0xbe); address(name); call('print'); };
  emit(0xfa, 0x31, 0xc0, 0x8e, 0xd8, 0x8e, 0xc0, 0x8e, 0xd0, 0xbc, 0x00, 0x7c, 0xfb);
  emit(0x88, 0x16); address('drive');
  emit(0xb8, 0x03, 0x00, 0xcd, 0x10); message('welcome');
  // Read sector 2 into 0000:8000.
  emit(0xbb, 0x00, 0x80, 0xb8, 0x01, 0x02, 0xb9, 0x02, 0x00, 0x30, 0xf6, 0x8a, 0x16); address('drive'); emit(0xcd, 0x13);
  emit(0x80, 0x3e, 0x00, 0x80, 0x52); conditional(0x85, 'new');
  message('saved'); jump('keys');
  label('new'); message('fresh');
  label('keys'); message('instructions');
  label('loop'); emit(0x30, 0xe4, 0xcd, 0x16, 0x3c, 0x0d); conditional(0x84, 'save');
  emit(0x3c, 0x1b); conditional(0x84, 'reboot');
  emit(0x84, 0xc0); conditional(0x84, 'loop');
  emit(0xb4, 0x0e, 0xbb, 0x07, 0x00, 0xcd, 0x10); jump('loop');
  label('save'); emit(0xc6, 0x06, 0x00, 0x80, 0x52);
  emit(0xbb, 0x00, 0x80, 0xb8, 0x01, 0x03, 0xb9, 0x02, 0x00, 0x30, 0xf6, 0x8a, 0x16); address('drive'); emit(0xcd, 0x13);
  conditional(0x82, 'failed'); message('ok'); jump('loop');
  label('failed'); message('failure'); jump('loop');
  label('reboot'); emit(0xcd, 0x19); jump('loop');
  label('print'); emit(0xac, 0x84, 0xc0); conditional(0x84, 'ret');
  emit(0xb4, 0x0e, 0xbb, 0x07, 0x00, 0xcd, 0x10); jump('print');
  label('ret'); emit(0xc3);
  label('drive'); emit(0);
  const text = (name, value) => { label(name); emit(...new TextEncoder().encode(value), 0); };
  text('welcome', 'RETRO ISO - PC VIRTUEL\r\n\r\n');
  text('saved', 'PERSISTENCE OK : marqueur retrouve.\r\n');
  text('fresh', 'Premier demarrage : disque pret.\r\n');
  text('instructions', 'Entree : enregistrer un marqueur.\r\nEchap : redemarrer.\r\n');
  text('ok', '\r\nECRITURE OK. Fermez puis relancez ce PC.\r\n');
  text('failure', '\r\nERREUR DISQUE.\r\n');
  if (code.length > 510) throw new Error('Boot sector too large: ' + code.length);
  for (const patch of patches) {
    const destination = labels.get(patch.name); if (destination === undefined) throw new Error(patch.name);
    const value = patch.kind === 'address' ? 0x7c00 + destination : destination - patch.at - 2;
    code[patch.at] = value & 255; code[patch.at + 1] = value >> 8 & 255;
  }
  const disk = new Uint8Array(1440 * 1024); disk.set(code); disk[510] = 0x55; disk[511] = 0xaa;
  return disk;
}
