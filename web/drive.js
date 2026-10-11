export class Drive {
  constructor() { this.token = null; this.expires = 0; }
  connect(clientId) {
    if (!clientId?.endsWith('.apps.googleusercontent.com')) throw new Error('Renseigne l’identifiant OAuth Web Google dans les réglages.');
    if (!globalThis.google?.accounts?.oauth2) throw new Error('La connexion Google n’est pas disponible. Vérifie ta connexion et réessaie.');
    return new Promise((resolve, reject) => {
      const client = google.accounts.oauth2.initTokenClient({
        client_id: clientId,
        scope: 'https://www.googleapis.com/auth/drive',
        callback: response => {
          if (response.error) { reject(new Error(response.error_description || response.error)); return; }
          this.token = response.access_token;
          this.expires = Date.now() + Math.max(0, Number(response.expires_in) - 60) * 1000;
          resolve();
        },
        error_callback: error => reject(new Error(error.message || 'Connexion Google interrompue.')),
      });
      client.requestAccessToken();
    });
  }
  async request(path, options = {}, upload = false) {
    if (!this.token || Date.now() >= this.expires) throw new Error('Reconnecte Google Drive pour continuer.');
    const headers = new Headers(options.headers);
    headers.set('Authorization', 'Bearer ' + this.token);
    const url = path.startsWith('https://') ? path : (upload ? 'https://www.googleapis.com/upload/drive/v3/' : 'https://www.googleapis.com/drive/v3/') + path;
    const response = await fetch(url, {...options, headers});
    if (!response.ok && response.status !== 308) {
      const detail = await response.json().catch(() => null);
      if (response.status === 401) this.token = null;
      throw new Error(detail?.error?.message || 'Drive : erreur HTTP ' + response.status);
    }
    return response;
  }
  async list(folder) {
    if (!/^[\w-]+$/.test(folder)) throw new Error('Lien ou identifiant du dossier Drive invalide.');
    const files = []; let pageToken;
    do {
      const query = new URLSearchParams({q: `'${folder}' in parents and trashed=false`, fields: 'nextPageToken,files(id,name,mimeType,size,modifiedTime,version)', pageSize: '100', supportsAllDrives: 'true', includeItemsFromAllDrives: 'true'});
      if (pageToken) query.set('pageToken', pageToken);
      const page = await (await this.request('files?' + query)).json();
      files.push(...page.files); pageToken = page.nextPageToken;
    } while (pageToken);
    return files.sort((a, b) => a.name.localeCompare(b.name));
  }
  async download(id) {
    const metadata = await (await this.request(`files/${encodeURIComponent(id)}?fields=id,name,size,version,mimeType&supportsAllDrives=true`)).json();
    const response = await this.request(`files/${encodeURIComponent(id)}?alt=media&supportsAllDrives=true`);
    return {metadata, response};
  }
  async upload(folder, name, source, progress = () => {}) {
    // Each upload creates a new immutable revision. Never replace a remote disk
    // silently when another device has uploaded a newer installation.
    const response = await this.request('files?uploadType=resumable&fields=id,name,size', {
      method: 'POST', headers: {'Content-Type': 'application/json', 'X-Upload-Content-Type': 'application/octet-stream', 'X-Upload-Content-Length': String(source.size)},
      body: JSON.stringify({name, parents: [folder]}),
    }, true);
    const session = response.headers.get('Location');
    if (!session) throw new Error('Google n’a pas fourni de session de transfert.');
    if (!session.startsWith('https://www.googleapis.com/')) throw new Error('Adresse de transfert Drive inattendue.');
    const chunkSize = 8 * 1024 * 1024;
    for (let offset = 0; offset < source.size; offset += chunkSize) {
      const end = Math.min(source.size, offset + chunkSize);
      const uploaded = await this.request(session, {method: 'PUT', headers: {'Content-Type': 'application/octet-stream', 'Content-Range': `bytes ${offset}-${end - 1}/${source.size}`}, body: source.slice(offset, end)});
      progress(end, source.size);
      if (end === source.size) {
        if (uploaded.status === 308) throw new Error('Le transfert Drive n’est pas terminé.');
        return uploaded.json();
      }
    }
    throw new Error('Fichier vide.');
  }
}
export function folderId(value) { return value.match(/\/folders\/([\w-]+)/)?.[1] || value.trim(); }
