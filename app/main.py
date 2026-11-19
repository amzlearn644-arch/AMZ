from __future__ import annotations

import asyncio
import ipaddress
import os
from contextlib import asynccontextmanager
from pathlib import Path

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.responses import HTMLResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates

APP_DIR = Path(__file__).resolve().parent
load_dotenv(APP_DIR.parent / ".env")

from . import db
from .admin import router as admin_router
from .sync import run_sync, sync_loop
from .urls import safe_external_url


@asynccontextmanager
async def lifespan(_: FastAPI):
    db.init_db()
    task = asyncio.create_task(sync_loop(), name="amz-metadata-sync")
    try:
        yield
    finally:
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass


app = FastAPI(title="AMZ | نرم‌افزارهای منتخب", version="1.0.0", lifespan=lifespan)
app.mount("/static", StaticFiles(directory=APP_DIR / "static"), name="static")
templates = Jinja2Templates(directory=APP_DIR / "templates")
app.include_router(admin_router)


@app.middleware("http")
async def admin_headers(request: Request, call_next):
    response = await call_next(request)
    if request.url.path.startswith("/admin"):
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Frame-Options"] = "DENY"
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Referrer-Policy"] = "same-origin"
    return response


@app.get("/", response_class=HTMLResponse)
async def home(
    request: Request,
    q: str = Query("", max_length=80),
    os: str = Query("", max_length=40),
    category: str = Query("", max_length=80),
    page: int = Query(1, ge=1, le=100000),
):
    per_page = 36
    _, total = db.list_software(q, os, category, limit=1, offset=0)
    page_count = max(1, (total + per_page - 1) // per_page)
    page = min(page, page_count)
    items, _ = db.list_software(q, os, category, limit=per_page, offset=(page - 1) * per_page)
    categories, systems = db.get_facets()
    return templates.TemplateResponse(
        request=request,
        name="index.html",
        context={
            "items": items, "total": total, "q": q, "selected_os": os,
            "selected_category": category, "categories": categories, "systems": systems,
            "last_sync": db.last_success(), "safe_url": safe_external_url,
            "icon_url": safe_external_url, "page": page, "page_count": page_count,
            "per_page": per_page, "first_result": ((page - 1) * per_page + 1) if total else 0,
            "last_result": min(page * per_page, total),
        },
    )



@app.get("/robots.txt", include_in_schema=False)
async def robots_txt(request: Request):
    base = str(request.base_url).rstrip("/")
    content = f"User-agent: *\\nAllow: /\\nDisallow: /admin\\nDisallow: /api/\\nSitemap: {base}/sitemap.xml\\n"
    return Response(content=content, media_type="text/plain; charset=utf-8")


@app.get("/sitemap.xml", include_in_schema=False)
async def sitemap_xml(request: Request):
    from xml.sax.saxutils import escape

    base = str(request.base_url).rstrip("/")
    entries = db.sitemap_entries()
    urls = [f"<url><loc>{escape(base)}/</loc></url>"]
    for entry in entries:
        loc = escape(f"{base}/software/{entry['slug']}")
        lastmod = escape(str(entry.get("updated_at", ""))[:10])
        lastmod_xml = f"<lastmod>{lastmod}</lastmod>" if lastmod else ""
        urls.append(f"<url><loc>{loc}</loc>{lastmod_xml}</url>")
    xml = '<?xml version="1.0" encoding="UTF-8"?>' + '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">' + "".join(urls) + "</urlset>"
    return Response(content=xml, media_type="application/xml; charset=utf-8")


@app.get("/software/{slug}", response_class=HTMLResponse)
async def software_detail(request: Request, slug: str):
    item = db.get_software(slug)
    if not item:
        raise HTTPException(status_code=404, detail="نرم‌افزار پیدا نشد")
    item["download_url"] = safe_external_url(item["download_url"])
    item["homepage_url"] = safe_external_url(item["homepage_url"])
    item["icon_url"] = safe_external_url(item["icon_url"])
    return templates.TemplateResponse(request=request, name="detail.html", context={"item": item, "canonical_url": str(request.base_url).rstrip("/") + "/software/" + item["slug"]})


@app.get("/api/software")
async def api_software(q: str = "", os: str = "", category: str = "", limit: int = Query(48, ge=1, le=100), offset: int = Query(0, ge=0)):
    items, total = db.list_software(q, os, category, limit, offset)
    for item in items:
        item["download_url"] = safe_external_url(item["download_url"])
        item["homepage_url"] = safe_external_url(item["homepage_url"])
        item["icon_url"] = safe_external_url(item["icon_url"])
    return {"items": items, "total": total, "limit": limit, "offset": offset}


@app.get("/api/software-suggestions")
async def api_software_suggestions(q: str = Query("", max_length=80)):
    return {"items": db.search_suggestions(q)}


@app.get("/api/software/{slug}")
async def api_software_detail(slug: str):
    item = db.get_software(slug)
    if not item:
        raise HTTPException(status_code=404, detail="نرم‌افزار پیدا نشد")
    item["download_url"] = safe_external_url(item["download_url"])
    item["homepage_url"] = safe_external_url(item["homepage_url"])
    item["icon_url"] = safe_external_url(item["icon_url"])
    return item


@app.get("/api/sync-status")
async def api_sync_status():
    return {"last_success": db.last_success(), "providers": db.get_sync_states()}


@app.post("/api/sync")
async def api_sync(request: Request):
    if os.getenv("AMZ_ALLOW_MANUAL_SYNC", "1") != "1":
        raise HTTPException(status_code=403, detail="Manual sync is disabled")
    if request.client and request.client.host not in {"127.0.0.1", "::1", "testclient"}:
        raise HTTPException(status_code=403, detail="Manual sync is available only locally")
    return await run_sync(force=True)


@app.get("/health")
async def health():
    return {"status": "ok", "catalog_size": db.list_software(limit=1)[1]}
