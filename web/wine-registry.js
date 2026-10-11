// Edit Wine's text registry before starting wineserver; preserve all other keys.
const quoted = value => '"' + value.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
export function setRegistryValue(text, section, name, value) {
  for (const item of [section, name, value]) if (typeof item !== 'string' || /[\0\r\n]/.test(item)) throw new Error('Entrée de registre invalide.');
  if (!text.startsWith('WINE REGISTRY Version 2')) throw new Error('Registre Wine invalide.');
  const header = '[' + section.replace(/\\/g, '\\\\') + ']';
  const lines = text.replace(/\r\n/g, '\n').split('\n');
  const begin = lines.findIndex(line => line === header || line.startsWith(header + ' '));
  const entry = quoted(name) + '=' + quoted(value);
  if (begin < 0) return lines.join('\n').trimEnd() + '\n\n' + header + '\n' + entry + '\n';
  let end = begin + 1;
  while (end < lines.length && !lines[end].startsWith('[')) end++;
  const key = quoted(name) + '=';
  const body = lines.slice(begin + 1, end).filter(line => !line.startsWith(key));
  lines.splice(begin + 1, end - begin - 1, entry, ...body);
  return lines.join('\n');
}
