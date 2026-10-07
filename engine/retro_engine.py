#!/usr/bin/env python3
"""Moteur PC de Retro ISO : sert l'interface, parle à Google Drive, lance Wine, synchronise les sauvegardes."""
import argparse, json, os, re, shutil, subprocess, sys, tempfile, threading, time, unicodedata, webbrowser, zipfile
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote

from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from google_auth_oauthlib.flow import InstalledAppFlow
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload, MediaIoBaseDownload

REPO = Path(__file__).resolve().parent.parent
DATA = Path(os.environ.get("RETROISO_HOME", Path.home() / ".retroiso"))
ROOT_NAME = os.environ.get("RETROISO_ROOT", "RetroGames")
WINE = os.environ.get("RETROISO_WINE", "wine")
SCOPES = ["https://www.googleapis.com/auth/drive"]
FOLDER = "application/vnd.google-apps.folder"
SEC = 2048
BAD_EXE = ("unins", "uninst", "setup", "install", "dxsetup", "vcredist", "dotnet", "redist", "crash")
DEFAULT_PROFILE = {"installeur": "setup.exe", "winver": "winxp", "executable_nom": None,
                   "saves_rel": ["save", "saves", "savegame", "savegames"], "env": {}}

jobs, errors = {}, {}
lock = threading.Lock()
library = {"ts": 0.0, "games": {}}
_tl = threading.local()
_creds = None


def slug(name):
    n = unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode().lower()
    return re.sub(r"[^a-z0-9]+", "-", n).strip("-") or "jeu"


# ---------------- Drive ----------------
def creds():
    global _creds
    tok, secret = DATA / "token.json", DATA / "client_secret.json"
    if _creds is None and tok.exists():
        _creds = Credentials.from_authorized_user_file(str(tok), SCOPES)
    if _creds and _creds.expired and _creds.refresh_token:
        _creds.refresh(Request())
        tok.write_text(_creds.to_json())
    if not _creds or not _creds.valid:
        if not secret.exists():
            sys.exit(f"Il manque {secret} (identifiant OAuth « application de bureau », voir README).")
        _creds = InstalledAppFlow.from_client_secrets_file(str(secret), SCOPES).run_local_server(port=0)
        tok.write_text(_creds.to_json())
    return _creds


def svc():
    if not hasattr(_tl, "s"):
        _tl.s = build("drive", "v3", credentials=creds(), cache_discovery=False)
    return _tl.s


def ls(q, fields):
    return svc().files().list(q=q, fields=fields, pageSize=1000).execute().get("files", [])


def find_root():
    r = ls(f"name='{ROOT_NAME}' and mimeType='{FOLDER}' and 'root' in parents and trashed=false", "files(id)")
    if not r:
        raise RuntimeError(f"Dossier « {ROOT_NAME} » introuvable à la racine de ton Drive.")
    return r[0]["id"]


def fetch_game(f):
    kids = ls(f"'{f['id']}' in parents and trashed=false", "files(id,name,mimeType,size)")
    iso = next((k for k in kids if k["name"].lower().endswith(".iso")), None)
    saves = next((k for k in kids if k["name"] == "saves" and k["mimeType"] == FOLDER), None)
    cur = None
    if saves:
        c = ls(f"'{saves['id']}' in parents and name='current.zip' and trashed=false", "files(id,modifiedTime)")
        cur = c[0] if c else None
    return {"id": slug(f["name"]), "name": f["name"], "folder": f["id"], "iso": iso,
            "saves": saves["id"] if saves else None, "current": cur}


def refresh_library(force=False):
    with lock:
        if not force and time.time() - library["ts"] < 120:
            return
    folders = ls(f"'{find_root()}' in parents and mimeType='{FOLDER}' and trashed=false", "files(id,name)")
    games = {g["id"]: g for g in map(fetch_game, folders)}
    with lock:
        library.update(ts=time.time(), games=games)


def run_req(req, on):
    resp = None
    while resp is None:
        st, resp = req.next_chunk()
        if st:
            on(int(st.progress() * 100))
    return resp


def download(file_id, dest, on):
    tmp = str(dest) + ".part"
    with open(tmp, "wb") as fh:
        d, done = MediaIoBaseDownload(fh, svc().files().get_media(fileId=file_id), chunksize=16 << 20), False
        while not done:
            st, done = d.next_chunk()
            on(int(st.progress() * 100))
    os.replace(tmp, dest)


def subfolder(parent, name):
    r = ls(f"'{parent}' in parents and name='{name}' and mimeType='{FOLDER}' and trashed=false", "files(id)")
    if r:
        return r[0]["id"]
    return svc().files().create(body={"name": name, "mimeType": FOLDER, "parents": [parent]}, fields="id").execute()["id"]


# ---------------- état local ----------------
def gdir(gid):
    d = DATA / "games" / gid
    d.mkdir(parents=True, exist_ok=True)
    return d


def state(gid):
    p = gdir(gid) / "state.json"
    return json.loads(p.read_text()) if p.exists() else {}


def set_state(gid, **kw):
    s = {**state(gid), **kw}
    (gdir(gid) / "state.json").write_text(json.dumps(s))


def profile(gid):
    p = REPO / "profils" / f"{gid}.json"
    return {**DEFAULT_PROFILE, **(json.loads(p.read_text()) if p.exists() else {})}


def job(gid, msg):
    jobs[gid] = msg


# ---------------- ISO (ISO9660 + Joliet, sans montage ni root) ----------------
def le32(b, o):
    return int.from_bytes(b[o:o + 4], "little")


def extract_iso(iso, dest, on):
    with open(iso, "rb") as f:
        def read(off, n):
            f.seek(off)
            return f.read(n)
        prim = jol = None
        for s in range(16, 64):
            b = read(s * SEC, SEC)
            if b[1:6] != b"CD001" or b[0] == 255:
                break
            if b[0] == 1 and prim is None:
                prim = b
            if b[0] == 2 and b[88:91] in (b"%/@", b"%/C", b"%/E"):
                jol = b
        pvd = jol or prim
        if pvd is None:
            raise RuntimeError("Fichier ISO non reconnu")
        entries = []

        def walk(ext, size, prefix, depth):
            if depth > 32:
                return
            data, pos = read(ext * SEC, size), 0
            while pos < len(data):
                ln = data[pos]
                if ln == 0:
                    pos = (pos // SEC + 1) * SEC
                    continue
                nl, first = data[pos + 32], data[pos + 33]
                if not (nl == 1 and first in (0, 1)):
                    raw = data[pos + 33:pos + 33 + nl]
                    name = raw.decode("utf-16-be" if jol else "latin-1", "replace").split(";")[0]
                    name = name.replace("/", "_").replace("\\", "_") if jol else name.rstrip(".").replace("/", "_")
                    if name not in ("", ".", ".."):
                        e, sz, isdir = le32(data, pos + 2), le32(data, pos + 10), bool(data[pos + 25] & 2)
                        entries.append((prefix + name, e, sz, isdir))
                        if isdir:
                            walk(e, sz, prefix + name + "/", depth + 1)
                pos += ln
        walk(le32(pvd, 158), le32(pvd, 166), "", 0)
        total, done = max(1, sum(e[2] for e in entries if not e[3])), 0
        base = os.path.realpath(dest)
        for path, ext, sz, isdir in entries:
            t = os.path.realpath(os.path.join(base, path))
            if not t.startswith(base):
                continue
            if isdir:
                os.makedirs(t, exist_ok=True)
                continue
            os.makedirs(os.path.dirname(t), exist_ok=True)
            with open(t, "wb") as out:
                f.seek(ext * SEC)
                left = sz
                while left > 0:
                    chunk = f.read(min(1 << 20, left))
                    if not chunk:
                        raise RuntimeError("Lecture ISO interrompue")
                    out.write(chunk)
                    left -= len(chunk)
                    done += len(chunk)
                    on(int(done * 100 / total))


# ---------------- Wine ----------------
def wine(gid, args, cwd=None, wait_all=True):
    env = {**os.environ, "WINEPREFIX": str(gdir(gid) / "prefix"), "WINEDEBUG": "-all", **profile(gid)["env"]}
    subprocess.run([WINE, *args], env=env, cwd=cwd)
    if wait_all:
        subprocess.run(["wineserver", "-w"], env=env)


def prepare(gid, g):
    d, prof = gdir(gid), profile(gid)
    if state(gid).get("iso_size") != g["iso"]["size"] or not (d / "jeu.iso").exists():
        job(gid, "Téléchargement de l'ISO… 0%")
        download(g["iso"]["id"], d / "jeu.iso", lambda p: job(gid, f"Téléchargement de l'ISO… {p}%"))
        shutil.rmtree(d / "cd", ignore_errors=True)
        set_state(gid, iso_size=g["iso"]["size"])
    if not (d / "cd" / ".ok").exists():
        job(gid, "Lecture du CD… 0%")
        shutil.rmtree(d / "cd", ignore_errors=True)
        (d / "cd").mkdir()
        extract_iso(d / "jeu.iso", d / "cd", lambda p: job(gid, f"Lecture du CD… {p}%"))
        (d / "cd" / ".ok").touch()
    if not state(gid).get("prefix_ok"):
        job(gid, "Préparation de Wine…")
        wine(gid, ["wineboot", "-u"])
        wine(gid, ["reg", "add", "HKCU\\Software\\Wine\\Drives", "/v", "d:", "/d", "cdrom", "/f"])
        wine(gid, ["winecfg", "-v", prof["winver"]])
        set_state(gid, prefix_ok=True)
    dd = d / "prefix" / "dosdevices"
    for name, target in (("d:", d / "cd"), ("d::", d / "jeu.iso")):
        if (dd / name).is_symlink() or (dd / name).exists():
            (dd / name).unlink()
        os.symlink(target, dd / name)


def all_exes(c):
    out = set()
    for r, dirs, files in os.walk(c):
        if r == str(c):
            dirs[:] = [x for x in dirs if x.lower() != "windows"]
        out.update(Path(r, x) for x in files if x.lower().endswith(".exe"))
    return out


def install(gid):
    d, prof = gdir(gid), profile(gid)
    c = d / "prefix" / "drive_c"
    before = all_exes(c)
    job(gid, "Installation : suis l'installeur à l'écran")
    wine(gid, ["start", "/wait", "D:\\" + prof["installeur"]], cwd=d / "cd")
    new = all_exes(c) - before
    name = prof["executable_nom"]
    pick = [p for p in all_exes(c) if name and p.name.lower() == name.lower()]
    if not pick:
        pick = [p for p in new if not any(b in p.name.lower() for b in BAD_EXE)]
    if len(pick) != 1:
        found = ", ".join(sorted(p.name for p in new)) or "aucun"
        raise RuntimeError(f"Exécutable du jeu introuvable (nouveaux .exe : {found}). Renseigne « executable_nom » dans profils/{gid}.json.")
    set_state(gid, installed=True, exe=str(pick[0].relative_to(c)))


# ---------------- sauvegardes ----------------
def install_dir(gid):
    exe = state(gid).get("exe")
    return (gdir(gid) / "prefix" / "drive_c" / exe).parent if exe else None


def ci_child(base, name):
    for x in base.iterdir() if base.is_dir() else []:
        if x.name.lower() == name.lower():
            return x
    return None


def local_save_files(gid):
    inst, files = install_dir(gid), []
    if inst:
        for rel in profile(gid)["saves_rel"]:
            d = ci_child(inst, rel)
            if d and d.is_dir():
                files += [(p, f"{rel.lower()}/{p.relative_to(d).as_posix()}") for p in d.rglob("*") if p.is_file()]
    return files


def local_changed(gid):
    ts = state(gid).get("last_sync_ts", 0)
    return any(p.stat().st_mtime > ts for p, _ in local_save_files(gid))


def pull_saves(gid, g):
    cur = fetch_game({"id": g["folder"], "name": g["name"]})["current"]
    st = state(gid)
    if not cur or cur["modifiedTime"] == st.get("last_drive_modified"):
        return
    job(gid, "Récupération de la sauvegarde…")
    inst, tmp = install_dir(gid), Path(tempfile.mkdtemp()) / "s.zip"
    if local_changed(gid):  # les deux ont bougé : on garde l'ancienne copie locale
        shutil.make_archive(str(gdir(gid) / f"conflit-{datetime.now():%Y%m%d-%H%M%S}"), "zip", root_dir=inst) if inst else None
    download(cur["id"], tmp, lambda p: None)
    with zipfile.ZipFile(tmp) as z:
        for m in z.infolist():
            parts = [x for x in m.filename.split("/") if x not in ("", ".", "..")]
            if m.is_dir() or not parts:
                continue
            t = inst
            for part in parts[:-1]:
                t = ci_child(t, part) or (t / part)
                t.mkdir(exist_ok=True)
            (t / parts[-1]).write_bytes(z.read(m))
    set_state(gid, last_drive_modified=cur["modifiedTime"], last_sync_ts=time.time())


def push_saves(gid, g):
    files = local_save_files(gid)
    if not files:
        return
    job(gid, "Envoi de la sauvegarde…")
    tmp = Path(tempfile.mkdtemp()) / "current.zip"
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as z:
        for p, arc in files:
            z.write(p, arc)
    s = svc()
    saves = subfolder(g["folder"], "saves")
    cur = ls(f"'{saves}' in parents and name='current.zip' and trashed=false", "files(id)")
    media = MediaFileUpload(str(tmp), mimetype="application/zip", resumable=True)
    if cur:
        backups = subfolder(saves, "backups")
        s.files().copy(fileId=cur[0]["id"], body={"name": f"{datetime.now():%Y-%m-%d_%H-%M-%S}.zip", "parents": [backups]}).execute()
        resp = run_req(s.files().update(fileId=cur[0]["id"], media_body=media, fields="id,modifiedTime"), lambda p: None)
        old = ls(f"'{backups}' in parents and trashed=false", "files(id,createdTime)")
        for o in sorted(old, key=lambda x: x["createdTime"])[:-10]:
            s.files().delete(fileId=o["id"]).execute()
    else:
        resp = run_req(s.files().create(body={"name": "current.zip", "parents": [saves]}, media_body=media, fields="id,modifiedTime"), lambda p: None)
    set_state(gid, last_drive_modified=resp["modifiedTime"], last_sync_ts=time.time())


# ---------------- tâches ----------------
def play_job(gid):
    try:
        errors.pop(gid, None)
        g = library["games"][gid]
        if not g["iso"]:
            raise RuntimeError("Aucun fichier .iso dans le dossier Drive de ce jeu.")
        prepare(gid, g)
        if not state(gid).get("installed"):
            install(gid)
        pull_saves(gid, g)
        job(gid, "En jeu")
        exe = gdir(gid) / "prefix" / "drive_c" / state(gid)["exe"]
        wine(gid, [str(exe)], cwd=exe.parent)
        push_saves(gid, g)
        refresh_library(True)
    except Exception as e:
        errors[gid] = str(e)
    finally:
        jobs.pop(gid, None)


def upload_job(gid, name):
    try:
        job(gid, "Envoi vers Drive… 0%")
        folder = svc().files().create(body={"name": name, "mimeType": FOLDER, "parents": [find_root()]}, fields="id").execute()["id"]
        media = MediaFileUpload(str(gdir(gid) / "jeu.iso"), resumable=True, chunksize=16 << 20)
        run_req(svc().files().create(body={"name": "jeu.iso", "parents": [folder]}, media_body=media, fields="id"),
                lambda p: job(gid, f"Envoi vers Drive… {p}%"))
        refresh_library(True)
    except Exception as e:
        errors[gid] = str(e)
    finally:
        jobs.pop(gid, None)


def save_status(gid, g):
    cur, st = g["current"], state(gid)
    lc = local_changed(gid)
    if lc:
        return "local-newer"
    if cur and cur["modifiedTime"] != st.get("last_drive_modified"):
        return "drive-newer"
    return "synced" if cur else "none"


def games_json():
    refresh_library()
    out = []
    for gid, g in sorted(library["games"].items(), key=lambda kv: kv[1]["name"].lower()):
        out.append({"id": gid, "name": g["name"], "installed": bool(state(gid).get("installed")),
                    "save": save_status(gid, g), "busy": jobs.get(gid), "error": errors.get(gid)})
    return out


# ---------------- serveur local ----------------
class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def allowed(self):
        o = self.headers.get("Origin")
        return not o or o.split("://", 1)[-1] in (f"127.0.0.1:{self.server.server_port}", f"localhost:{self.server.server_port}")

    def do_GET(self):
        if self.path.startswith("/api/games"):
            try:
                return self.send(200, games_json())
            except Exception as e:
                return self.send(500, {"error": str(e)})
        rel = "index.html" if self.path in ("/", "") else self.path.lstrip("/").split("?")[0]
        f = (REPO / "ui" / rel).resolve()
        if not str(f).startswith(str(REPO / "ui")) or not f.is_file():
            return self.send(404, {"error": "introuvable"})
        body = f.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8" if f.suffix == ".html" else "application/octet-stream")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if not self.allowed():
            return self.send(403, {"error": "origine refusée"})
        m = re.fullmatch(r"/api/games/([a-z0-9-]+)/play", self.path)
        if m:
            gid = m.group(1)
            if gid not in library["games"]:
                return self.send(404, {"error": "jeu inconnu"})
            with lock:
                if gid in jobs:
                    return self.send(409, {"error": "déjà en cours"})
                jobs[gid] = "Démarrage…"
            threading.Thread(target=play_job, args=(gid,), daemon=True).start()
            return self.send(202, {"ok": True})
        if self.path == "/api/games":
            name = Path(unquote(self.headers.get("X-Filename", "jeu.iso"))).stem
            gid, n = slug(name), int(self.headers.get("Content-Length", 0))
            if not n or gid in jobs:
                return self.send(400, {"error": "fichier manquant ou envoi déjà en cours"})
            jobs[gid] = "Réception de l'ISO…"
            with open(gdir(gid) / "jeu.iso", "wb") as out:
                left = n
                while left > 0:
                    chunk = self.rfile.read(min(1 << 20, left))
                    if not chunk:
                        break
                    out.write(chunk)
                    left -= len(chunk)
            shutil.rmtree(gdir(gid) / "cd", ignore_errors=True)
            threading.Thread(target=upload_job, args=(gid, name), daemon=True).start()
            return self.send(201, {"ok": True})
        self.send(404, {"error": "introuvable"})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--no-browser", action="store_true")
    a = ap.parse_args()
    DATA.mkdir(parents=True, exist_ok=True)
    creds()
    srv = ThreadingHTTPServer(("127.0.0.1", a.port), H)
    url = f"http://127.0.0.1:{a.port}/"
    print("Retro ISO prêt :", url)
    if not a.no_browser:
        webbrowser.open(url)
    srv.serve_forever()


if __name__ == "__main__":
    main()
