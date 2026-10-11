// v86 disk adapter. A write is acknowledged only AFTER durable storage commits.
// Large base images stay on disk; the LRU cache is bounded to 16 MiB per disk.
export const PAGE = 4096;
export class PersistentDisk {
  constructor(base, id, persistence, onError = () => {}, readonly = false) {
    this.base = base; this.id = id; this.persistence = persistence;
    this.byteLength = base.size; this.onError = onError; this.readonly = readonly;
    this.cache = new Map(); this.queue = Promise.resolve(); this.failed = null;
  }
  load() { this.onload?.({}); }
  bounds(offset, length) {
    if (!Number.isSafeInteger(offset) || !Number.isSafeInteger(length) || offset < 0 || length < 0 || offset + length > this.byteLength) throw new RangeError('Accès au disque hors limites.');
  }
  remember(index, bytes) {
    this.cache.delete(index); this.cache.set(index, bytes);
    if (this.cache.size > 4096) this.cache.delete(this.cache.keys().next().value);
    return bytes;
  }
  async page(index) {
    if (this.cache.has(index)) return this.remember(index, this.cache.get(index));
    const saved = await this.persistence.get(this.id, index);
    if (saved) return this.remember(index, new Uint8Array(saved));
    const bytes = new Uint8Array(await this.base.slice(index * PAGE, Math.min((index + 1) * PAGE, this.byteLength)).arrayBuffer());
    return this.remember(index, bytes);
  }
  enqueue(task) {
    const operation = this.queue.then(() => {
      if (this.failed) throw this.failed;
      return task();
    });
    this.queue = operation.catch(error => { if (!this.failed) { this.failed = error; this.onError(error); } });
    return operation;
  }
  async read(offset, length) {
    this.bounds(offset, length);
    const output = new Uint8Array(length);
    for (let position = offset; position < offset + length;) {
      const index = Math.floor(position / PAGE), local = position % PAGE;
      const bytes = await this.page(index);
      const count = Math.min(bytes.length - local, offset + length - position);
      output.set(bytes.subarray(local, local + count), position - offset);
      position += count;
    }
    return output;
  }
  get(offset, length, callback) { this.enqueue(async () => callback(await this.read(offset, length))).catch(() => {}); }
  get_and_cache(offset, length, callback) { this.get(offset, length, callback); }
  get_from_cache() { return undefined; }
  set(offset, data, callback) {
    // v86 can reuse the source memory before asynchronous storage completes.
    const input = new Uint8Array(data).slice();
    this.enqueue(async () => {
      if (this.readonly) throw new Error('Écriture inattendue sur un CD en lecture seule.');
      this.bounds(offset, input.length);
      const updated = [];
      for (let position = offset; position < offset + input.length;) {
        const index = Math.floor(position / PAGE), local = position % PAGE;
        const bytes = (await this.page(index)).slice();
        const count = Math.min(bytes.length - local, offset + input.length - position);
        bytes.set(input.subarray(position - offset, position - offset + count), local);
        updated.push([index, bytes]); position += count;
      }
      await this.persistence.put(this.id, updated);
      for (const [index, bytes] of updated) this.remember(index, bytes);
      callback();
    }).catch(() => {});
  }
  async flush() { await this.queue; if (this.failed) throw this.failed; }
  // Persistent disks are versioned separately from volatile RAM snapshots.
  get_state() { return [this.byteLength]; }
  set_state(state) { if (state[0] !== this.byteLength) throw new Error('Le disque et la session sauvegardée ne correspondent pas.'); }
  async exportTo(writer, progress = () => {}) {
    await this.flush();
    for (let offset = 0; offset < this.byteLength; offset += 4 * 1024 * 1024) {
      const count = Math.min(4 * 1024 * 1024, this.byteLength - offset);
      let bytes;
      if (this.persistence.range) {
        bytes = new Uint8Array(await this.base.slice(offset, offset + count).arrayBuffer());
        const modified = await this.persistence.range(this.id, offset / PAGE, Math.ceil((offset + count) / PAGE));
        for (const {index, data} of modified) bytes.set(new Uint8Array(data), index * PAGE - offset);
      } else bytes = await this.read(offset, count);
      await writer.write(bytes); progress(offset + bytes.length, this.byteLength);
    }
  }
}
