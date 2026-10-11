// Original minimal Win32 PE. It writes through kernel32 and displays through user32.
// No SDK, proprietary program, or Windows system files are embedded.
export function makeWineTest() {
  const bytes = [], labels = new Map(), fixes = [];
  const emit = (...values) => bytes.push(...values);
  const u32 = value => emit(value & 255, value >>> 8 & 255, value >>> 16 & 255, value >>> 24 & 255);
  const label = name => labels.set(name, bytes.length);
  const address = name => { fixes.push({at: bytes.length, name, absolute: true}); u32(0); };
  const jump = (opcode, name) => { emit(0x0f, opcode); fixes.push({at: bytes.length, name}); u32(0); };
  const push = value => { emit(0x68); u32(value); };
  const pushAddress = name => { emit(0x68); address(name); };
  const call = name => { emit(0xff, 0x15); address('iat-' + name); };
  const move = (opcode, name) => { emit(opcode); address(name); };
  const first = 'PREMIER LANCEMENT - ECRITURE WINDOWS OK\r\n';
  const again = 'PERSISTENCE OK\r\n';
  pushAddress('filename'); call('GetFileAttributesA'); emit(0x83, 0xf8, 0xff); jump(0x85, 'existing');
  move(0xbe, 'first'); emit(0xbf); u32(first.length); jump(0x84, 'open');
  label('existing'); move(0xbe, 'again'); emit(0xbf); u32(again.length);
  label('open'); push(0); push(128); push(4); push(0); push(3); push(4); pushAddress('filename'); call('CreateFileA');
  emit(0x89, 0xc3, 0x83, 0xf8, 0xff); jump(0x84, 'failed');
  push(0); pushAddress('written'); emit(0x57, 0x56, 0x53); call('WriteFile'); emit(0x85, 0xc0); jump(0x84, 'failed');
  emit(0x53); call('CloseHandle');
  push(8); pushAddress('env-buffer'); pushAddress('env'); call('GetEnvironmentVariableA'); emit(0x85, 0xc0); jump(0x85, 'done');
  push(0x40); pushAddress('title'); emit(0x56); push(0); call('MessageBoxA');
  label('done'); push(0); call('ExitProcess');
  label('failed'); push(1); call('ExitProcess');
  for (const [name, text] of Object.entries({filename: 'C:\\retroiso-persistence.txt', first, again, title: 'RETRO ISO - TEST WINDOWS', env: 'RETROISO_HEADLESS'})) {
    label(name); emit(...new TextEncoder().encode(text), 0);
  }
  label('written'); u32(0); label('env-buffer'); emit(...Array(8).fill(0));
  while (bytes.length % 4) emit(0);
  const imports = {kernel32: ['GetFileAttributesA', 'CreateFileA', 'WriteFile', 'CloseHandle', 'GetEnvironmentVariableA', 'ExitProcess'], user32: ['MessageBoxA']};
  label('imports'); const descriptors = bytes.length; emit(...Array(60).fill(0));
  const set32 = (at, value) => { for (let i = 0; i < 4; i++) bytes[at + i] = value >>> (i * 8) & 255; };
  let index = 0;
  for (const [dll, functions] of Object.entries(imports)) {
    const name = bytes.length; emit(...new TextEncoder().encode(dll + '.dll'), 0); if (bytes.length % 2) emit(0);
    const names = functions.map(fn => { const start = bytes.length; emit(0, 0, ...new TextEncoder().encode(fn), 0); if (bytes.length % 2) emit(0); return start + 0x1000; });
    while (bytes.length % 4) emit(0);
    const table = bytes.length;
    names.forEach((rva, i) => { label('iat-' + functions[i]); u32(rva); }); u32(0);
    set32(descriptors + index * 20, table + 0x1000); set32(descriptors + index * 20 + 12, name + 0x1000); set32(descriptors + index * 20 + 16, table + 0x1000); index++;
  }
  for (const fix of fixes) {
    if (!labels.has(fix.name)) throw new Error('Missing PE label: ' + fix.name);
    set32(fix.at, fix.absolute ? 0x401000 + labels.get(fix.name) : labels.get(fix.name) - fix.at - 4);
  }
  const rawSize = Math.ceil(bytes.length / 512) * 512, output = new Uint8Array(512 + rawSize), view = new DataView(output.buffer);
  const word = (at, value) => view.setUint16(at, value, true), dword = (at, value) => view.setUint32(at, value, true);
  word(0, 0x5a4d); dword(0x3c, 0x80); dword(0x80, 0x4550); word(0x84, 0x14c); word(0x86, 1); word(0x94, 224); word(0x96, 0x010f);
  const optional = 0x98; word(optional, 0x10b); dword(optional + 4, rawSize); dword(optional + 16, 0x1000); dword(optional + 20, 0x1000); dword(optional + 24, 0x1000); dword(optional + 28, 0x400000);
  dword(optional + 32, 4096); dword(optional + 36, 512); word(optional + 40, 4); word(optional + 48, 4); dword(optional + 56, 8192); dword(optional + 60, 512); word(optional + 68, 2);
  dword(optional + 72, 1048576); dword(optional + 76, 4096); dword(optional + 80, 1048576); dword(optional + 84, 4096); dword(optional + 92, 16); dword(optional + 104, labels.get('imports') + 4096); dword(optional + 108, 60);
  const section = optional + 224; output.set(new TextEncoder().encode('.text'), section); dword(section + 8, bytes.length); dword(section + 12, 4096); dword(section + 16, rawSize); dword(section + 20, 512); dword(section + 36, 0xe0000060);
  output.set(bytes, 512); return output;
}
