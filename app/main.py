from __future__ import annotations

import asyncio
import ipaddress
import os
from contextlib import asynccontextmanager
from pathlib import Path

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.responses import HTMLResponse, JSONResponse
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
    items, total = db.list_software(q, os, category, limit=per_page, offset=(page - 1) * per_page)
    categories, systems = db.get_facets()
    page_count = max(1, (total + per_page - 1) // per_page)
    page = min(page, page_count)
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


@app.get("/software/{slug}", response_class=HTMLResponse)
async def software_detail(request: Request, slug: str):
    item = db.get_software(slug)
    if not item:
        raise HTTPException(status_code=404, detail="نرم‌افزار پیدا نشد")
    item["download_url"] = safe_external_url(item["download_url"])
    item["homepage_url"] = safe_external_url(item["homepage_url"])
    item["icon_url"] = safe_external_url(item["icon_url"])
    return templates.TemplateResponse(request=request, name="detail.html", context={"item": item})


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
