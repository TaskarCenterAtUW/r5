#!/usr/bin/env python3
"""
tdei_r5_proxy.py - on-demand R5 instances per TDEI dataset.

  GET/POST /{tdei_dataset_id}/<anything>   (Authorization: Bearer <TDEI token>)

First request for a dataset:
  1. checks the caller's token against TDEI and downloads the OSW dataset zip
  2. unzips it to DATA_DIR/<id>/
  3. launches R5 (LAUNCH_CMD) on a free local port and waits until it answers
  4. forwards the request as /<anything> to that R5 instance

Later requests reuse the running instance. Every new (token, dataset) pair is
re-checked against TDEI before it is served, so loading a dataset for one user
doesn't open it to others. Instances with no traffic for IDLE_TIMEOUT seconds
are stopped.

Config (environment variables):
  TDEI_DOWNLOAD_URL  default https://api.tdei.us/api/v1/osw/{id}?format=osw
  LAUNCH_CMD         R5 launch template; {dir}, {port} and {id} are substituted
  LAUNCH_CWD         directory to run LAUNCH_CMD in (your R5 checkout)
  JAVA_TOOL_OPTIONS  passed through to R5 (GC flags)
  WALKSHEDS_COMPAT   passed through to R5; set to 0 to attach points to the network at the nearest
                     place on the ground instead of where Walksheds would (osw-tools/DIFFERENCES.md);
                     default on
  DATA_DIR           default ./datasets
  IDLE_TIMEOUT       seconds, default 1800
  MAX_INSTANCES      default 8; least-recently-used idle instance is stopped
  STARTUP_TIMEOUT    seconds, default 180
  AUTH_CACHE_TTL     seconds a verified (token, dataset) pair is trusted, default 600
  READY_PATH         path polled to detect R5 is up, default /
  RETRY_AFTER        seconds suggested in the 503 Retry-After header while loading, default 5
  OPEN_DEBUG_UI      set to 1 to serve the debug map page of a dataset that is already loaded
                     without a token (see below); default off
  DOCS_DIR           folder holding docs.html and openapi.yaml, served at /docs and /openapi.yaml;
                     default is the demo page's folder in the R5 checkout this file sits in

With OPEN_DEBUG_UI=1, / lists the loaded datasets, and /{tdei_dataset_id}/ (the map page) and
the three calls the page makes (api/info, api/network, api/walkshed) need no token. That only
works for a dataset someone has already loaded with a token: the proxy cannot download one
without it. It shows that dataset's network to anyone who can reach the proxy, so leave it off
where datasets are not public. The Walksheds-compatible API under api/v1/ always needs a token.

/docs is the API documentation (Swagger UI) and /openapi.yaml the specification behind it.
Neither needs a token. Without OPEN_DEBUG_UI, / redirects to /docs.

Responses from the proxy itself:
  401  missing token, or TDEI rejected it for this dataset
  404  TDEI has no such dataset
  503  dataset is downloading / R5 is starting (Retry-After set), or at MAX_INSTANCES
  502  TDEI error, download failed, or R5 failed to start (the next request retries)
  LISTEN_HOST / LISTEN_PORT   default 0.0.0.0:8000

Requires: pip install aiohttp
"""
import asyncio
import hashlib
import logging
import os
import re
import shlex
import shutil
import signal
import socket
import time
import zipfile
from pathlib import Path

from aiohttp import ClientSession, ClientTimeout, web

log = logging.getLogger("tdei-r5")

TDEI_DOWNLOAD_URL = os.environ.get(
    "TDEI_DOWNLOAD_URL", "https://api.tdei.us/api/v1/osw/{id}?format=osw")
LAUNCH_CMD = os.environ.get(
    "LAUNCH_CMD",
    'gradle --no-daemon --quiet runOswDemo --args="--osw {dir} --port {port} --dataset-id {id}"')
LAUNCH_CWD = os.environ.get("LAUNCH_CWD", ".")
DATA_DIR = Path(os.environ.get("DATA_DIR", "./datasets")).resolve()
IDLE_TIMEOUT = float(os.environ.get("IDLE_TIMEOUT", 1800))
MAX_INSTANCES = int(os.environ.get("MAX_INSTANCES", 8))
STARTUP_TIMEOUT = float(os.environ.get("STARTUP_TIMEOUT", 180))
AUTH_CACHE_TTL = float(os.environ.get("AUTH_CACHE_TTL", 600))
READY_PATH = os.environ.get("READY_PATH", "/")
RETRY_AFTER = int(os.environ.get("RETRY_AFTER", 5))
OPEN_DEBUG_UI = os.environ.get("OPEN_DEBUG_UI", "").lower() in ("1", "true", "yes")
DOCS_DIR = Path(os.environ.get(
    "DOCS_DIR", Path(__file__).resolve().parent.parent / "src" / "main" / "resources" / "osw-demo"))
# the debug map page and the calls it makes (see src/main/resources/osw-demo)
DEBUG_UI_PATHS = {"", "index.html", "app.js", "style.css", "docs.html", "openapi.yaml",
                  "api/info", "api/network", "api/walkshed"}

ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,100}$")
HOP_BY_HOP = {"connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
              "te", "trailers", "transfer-encoding", "upgrade", "host",
              "authorization", "content-length"}


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Instance:
    def __init__(self, dataset_id: str):
        self.id = dataset_id
        self.proc: asyncio.subprocess.Process | None = None
        self.port: int | None = None
        self.ready = asyncio.Event()
        self.error: str | None = None
        self.last_used = time.monotonic()
        self.inflight = 0

    @property
    def alive(self) -> bool:
        return self.proc is not None and self.proc.returncode is None

    async def stop(self):
        if self.alive:
            log.info("stopping %s (pid %s)", self.id, self.proc.pid)
            try:
                os.killpg(self.proc.pid, signal.SIGTERM)  # whole group: gradle + JVM
                await asyncio.wait_for(self.proc.wait(), 20)
            except (ProcessLookupError, asyncio.TimeoutError):
                try:
                    os.killpg(self.proc.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass


class Manager:
    def __init__(self):
        self.instances: dict[str, Instance] = {}
        self.auth_ok: dict[tuple[str, str], float] = {}
        self.lock = asyncio.Lock()
        self.http: ClientSession | None = None
        # forwards R5 responses byte-for-byte: no auto-decompression, so a gzip body
        # still matches the Content-Encoding header we pass through
        self.upstream: ClientSession | None = None

    # ---------- TDEI ----------
    async def check_access(self, dataset_id: str, auth: str):
        """Raise HTTPForbidden unless this token can download this dataset."""
        key = (hashlib.sha256(auth.encode()).hexdigest(), dataset_id)
        if time.monotonic() - self.auth_ok.get(key, -1e9) < AUTH_CACHE_TTL:
            return
        url = TDEI_DOWNLOAD_URL.format(id=dataset_id)
        async with self.http.get(url, headers={"Authorization": auth}) as r:
            if r.status in (401, 403):
                raise web.HTTPUnauthorized(
                    text=f"TDEI rejected the token for dataset {dataset_id}",
                    headers={"WWW-Authenticate": 'Bearer realm="tdei"'})
            if r.status == 404:
                raise web.HTTPNotFound(text=f"TDEI has no dataset {dataset_id}")
            if r.status != 200:
                raise web.HTTPBadGateway(text=f"TDEI returned {r.status} for dataset {dataset_id}")
        self.auth_ok[key] = time.monotonic()

    async def fetch_dataset(self, dataset_id: str, auth: str) -> Path:
        dest = DATA_DIR / dataset_id
        if dest.is_dir() and any(dest.iterdir()):
            return dest  # already unzipped on disk; access was checked separately
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        tmp_zip = DATA_DIR / f".{dataset_id}.zip.part"
        url = TDEI_DOWNLOAD_URL.format(id=dataset_id)
        log.info("downloading %s", url)
        async with self.http.get(url, headers={"Authorization": auth},
                                 timeout=ClientTimeout(total=600)) as r:
            if r.status != 200:
                raise web.HTTPBadGateway(text=f"TDEI download failed: {r.status} {await r.text()}")
            with open(tmp_zip, "wb") as f:
                async for chunk in r.content.iter_chunked(1 << 20):
                    f.write(chunk)
        tmp_dir = DATA_DIR / f".{dataset_id}.part"
        shutil.rmtree(tmp_dir, ignore_errors=True)
        await asyncio.to_thread(self._unzip, tmp_zip, tmp_dir)
        tmp_zip.unlink()
        shutil.rmtree(dest, ignore_errors=True)
        tmp_dir.rename(dest)
        return dest

    @staticmethod
    def _unzip(zip_path: Path, out: Path):
        out.mkdir(parents=True)
        with zipfile.ZipFile(zip_path) as z:
            for m in z.infolist():  # refuse paths escaping the target dir
                target = (out / m.filename).resolve()
                if not str(target).startswith(str(out.resolve())):
                    raise ValueError(f"unsafe path in zip: {m.filename}")
            z.extractall(out)
        # unwrap a single top-level folder, and any nested zips
        for inner in list(out.rglob("*.zip")):
            with zipfile.ZipFile(inner) as z:
                z.extractall(inner.parent)
            inner.unlink()
        entries = [p for p in out.iterdir() if not p.name.startswith(("__MACOSX", "."))]
        if len(entries) == 1 and entries[0].is_dir():
            for p in entries[0].iterdir():
                p.rename(out / p.name)
            entries[0].rmdir()

    # ---------- R5 lifecycle ----------
    async def get_instance(self, dataset_id: str, auth: str) -> Instance:
        await self.check_access(dataset_id, auth)
        async with self.lock:
            inst = self.instances.get(dataset_id)
            if inst is None or (inst.ready.is_set() and not inst.alive):
                await self._make_room()
                inst = Instance(dataset_id)
                self.instances[dataset_id] = inst
                asyncio.create_task(self._start(inst, auth))
        inst.last_used = time.monotonic()
        if not inst.ready.is_set():
            raise web.HTTPServiceUnavailable(
                text=f"dataset {dataset_id} is loading; retry shortly",
                headers={"Retry-After": str(RETRY_AFTER)})
        if inst.error:
            raise web.HTTPBadGateway(text=inst.error)
        return inst

    async def _make_room(self):
        live = [i for i in self.instances.values() if i.alive or not i.ready.is_set()]
        while len(live) >= MAX_INSTANCES:
            idle = [i for i in live if i.inflight == 0 and i.ready.is_set()]
            if not idle:
                raise web.HTTPServiceUnavailable(text="at capacity, try again shortly",
                                                headers={"Retry-After": str(RETRY_AFTER * 6)})
            victim = min(idle, key=lambda i: i.last_used)
            await victim.stop()
            self.instances.pop(victim.id, None)
            live.remove(victim)

    async def _start(self, inst: Instance, auth: str):
        try:
            data_dir = await self.fetch_dataset(inst.id, auth)
            inst.port = free_port()
            cmd = LAUNCH_CMD.format(dir=shlex.quote(str(data_dir)), port=inst.port, id=inst.id)
            log.info("launching %s: %s", inst.id, cmd)
            logf = open(DATA_DIR / f"{inst.id}.r5.log", "ab")
            inst.proc = await asyncio.create_subprocess_shell(
                cmd, cwd=LAUNCH_CWD, stdout=logf, stderr=logf, start_new_session=True)
            t0 = time.monotonic()
            while time.monotonic() - t0 < STARTUP_TIMEOUT:
                if inst.proc.returncode is not None:
                    raise RuntimeError(f"R5 exited with {inst.proc.returncode}; see {logf.name}")
                try:
                    async with self.http.get(f"http://127.0.0.1:{inst.port}{READY_PATH}",
                                             timeout=ClientTimeout(total=2)):
                        break  # any HTTP response means it's serving
                except Exception:
                    await asyncio.sleep(0.5)
            else:
                await inst.stop()
                raise RuntimeError("R5 did not start in time")
            log.info("%s ready on :%d in %.1fs", inst.id, inst.port, time.monotonic() - t0)
        except web.HTTPException as e:
            inst.error = e.text
        except Exception as e:
            log.exception("start failed for %s", inst.id)
            inst.error = f"failed to load dataset: {e}"
        finally:
            if inst.error:
                self.instances.pop(inst.id, None)
            inst.last_used = time.monotonic()
            inst.ready.set()

    async def reaper(self):
        while True:
            await asyncio.sleep(30)
            now = time.monotonic()
            for inst in list(self.instances.values()):
                if inst.ready.is_set() and inst.inflight == 0 and now - inst.last_used > IDLE_TIMEOUT:
                    await inst.stop()
                    self.instances.pop(inst.id, None)
                elif inst.ready.is_set() and not inst.alive:
                    log.warning("%s exited unexpectedly", inst.id)
                    self.instances.pop(inst.id, None)
            self.auth_ok = {k: t for k, t in self.auth_ok.items() if now - t < AUTH_CACHE_TTL}


mgr = Manager()


async def handle(request: web.Request):
    dataset_id = request.match_info["dataset_id"]
    if not ID_RE.match(dataset_id):
        raise web.HTTPNotFound()
    auth = request.headers.get("Authorization", "")
    if not auth and OPEN_DEBUG_UI and request.match_info["tail"] in DEBUG_UI_PATHS:
        # debug map page: no token, so it can only use an instance that is already running
        inst = mgr.instances.get(dataset_id)
        if inst is None or not inst.ready.is_set() or inst.error or not inst.alive:
            raise web.HTTPUnauthorized(
                text=f"dataset {dataset_id} is not loaded; load it first with a TDEI token")
    else:
        if not auth:
            raise web.HTTPUnauthorized(text="Authorization header with a TDEI token required")
        if not auth.lower().startswith("bearer "):
            auth = "Bearer " + auth
        inst = await mgr.get_instance(dataset_id, auth)
    inst.inflight += 1
    try:
        url = f"http://127.0.0.1:{inst.port}/{request.match_info['tail']}"
        headers = {k: v for k, v in request.headers.items() if k.lower() not in HOP_BY_HOP}
        async with mgr.upstream.request(request.method, url, params=request.query,
                                    headers=headers, data=await request.read(),
                                    timeout=ClientTimeout(total=300)) as r:
            resp = web.StreamResponse(status=r.status, headers={
                k: v for k, v in r.headers.items() if k.lower() not in HOP_BY_HOP})
            await resp.prepare(request)
            async for chunk in r.content.iter_chunked(1 << 16):
                await resp.write(chunk)
            await resp.write_eof()
            return resp
    finally:
        inst.inflight -= 1
        inst.last_used = time.monotonic()


async def docs(_):
    """The API documentation page. It describes the API, not any dataset, so it needs no token."""
    return web.FileResponse(DOCS_DIR / "docs.html", headers={"Content-Type": "text/html; charset=utf-8"})


async def openapi(_):
    return web.FileResponse(DOCS_DIR / "openapi.yaml", headers={"Content-Type": "application/yaml"})


async def add_slash(request: web.Request):
    # the map page loads its script and data relative to /{dataset_id}/
    raise web.HTTPFound(f"/{request.match_info['dataset_id']}/")


async def index(_):
    """With OPEN_DEBUG_UI, a page listing the loaded datasets with links to their debug maps.
    Without it there is nothing to list, so go straight to the API documentation."""
    if not OPEN_DEBUG_UI:
        raise web.HTTPFound("/docs")
    now = time.monotonic()
    rows = []
    for i in sorted(mgr.instances.values(), key=lambda i: i.id):
        if i.error:
            state, name = "failed to start", i.id
        elif not i.ready.is_set():
            state, name = "loading", i.id
        elif not i.alive:
            state, name = "stopped", i.id
        else:
            state, name = f"idle {round(now - i.last_used)} s", f'<a href="/{i.id}/">{i.id}</a>'
        rows.append(f"<tr><td>{name}</td><td>{state}</td></tr>")  # ids are ID_RE-checked, safe in HTML
    body = ("<table><tr><th>Dataset</th><th>Status</th></tr>" + "".join(rows) + "</table>" if rows else
            "<p>No datasets are loaded. Request one with a TDEI token to load it.</p>")
    return web.Response(content_type="text/html", text=(
        '<!doctype html><html lang="en"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width,initial-scale=1"><title>Loaded datasets</title>'
        "<style>body{font:16px system-ui,sans-serif;margin:2rem;color:#16212b}"
        "td,th{text-align:left;padding:.35rem 1.5rem .35rem 0}td:first-child{font-family:ui-monospace,monospace}"
        "</style></head><body><h1>Loaded datasets</h1>" + body +
        '<p><a href="/docs">API documentation</a></p></body></html>'))


async def status(_):
    now = time.monotonic()
    return web.json_response({i.id: {
        "state": "error" if i.error else ("running" if i.alive else "starting"),
        "port": i.port, "idle_s": round(now - i.last_used), "inflight": i.inflight,
    } for i in mgr.instances.values()})


async def on_startup(app):
    mgr.http = ClientSession(timeout=ClientTimeout(total=60))
    mgr.upstream = ClientSession(timeout=ClientTimeout(total=300), auto_decompress=False)
    app["reaper"] = asyncio.create_task(mgr.reaper())


async def on_cleanup(app):
    app["reaper"].cancel()
    for inst in list(mgr.instances.values()):
        await inst.stop()
    await mgr.http.close()
    await mgr.upstream.close()


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    app = web.Application(client_max_size=50 * 1024 * 1024)
    app.router.add_get("/", index)
    app.router.add_get("/_status", status)
    app.router.add_get("/docs", docs)
    app.router.add_get("/openapi.yaml", openapi)
    app.router.add_get("/{dataset_id}", add_slash)
    app.router.add_route("*", "/{dataset_id}/{tail:.*}", handle)
    app.on_startup.append(on_startup)
    app.on_cleanup.append(on_cleanup)
    web.run_app(app, host=os.environ.get("LISTEN_HOST", "0.0.0.0"),
                port=int(os.environ.get("LISTEN_PORT", 8000)))


if __name__ == "__main__":
    main()
