from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import sqlite3
import time
from pathlib import Path
from urllib.parse import parse_qs, urlencode, urlparse

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import HTMLResponse, RedirectResponse
from fastapi.templating import Jinja2Templates

from . import db
from .sync import run_sync
from .urls import safe_external_url

APP_DIR = Path(__file__).resolve().parent
templates = Jinja2Templates(directory=APP_DIR / "templates")

COOKIE_NAME = "amz_admin"
SESSION_SECONDS = 12 * 60 * 60
LOCAL_HOSTS = {"127.0.0.1", "::1", "localhost", "testclient"}
LOCAL_REQUEST_HOSTS = {"127.0.0.1", "::1", "localhost", "testserver"}
MAX_FORM_BYTES = 64_000
SLUG_RE = re.compile(r"[a-z0-9]+(?:-[a-z0-9]+)*")

router = APIRouter(prefix="/admin")


# ---------------------------------------------------------------------------
# Authentication: a signed, HttpOnly cookie; no session store and no extra deps.
# ---------------------------------------------------------------------------

def admin_password() -> str:
    return os.getenv("AMZ_ADMIN_PASSWORD", "")


def _session_key() -> bytes:
    secret = os.getenv("AMZ_SECRET_KEY") or admin_password()
    return hashlib.sha256(b"amz-admin-session:" + secret.encode("utf-8")).digest()


def _sign(expiry: str) -> str:
    return hmac.new(_session_key(), expiry.encode("ascii"), hashlib.sha256).hexdigest()


def issue_token() -> str:
    expiry = str(int(time.time()) + SESSION_SECONDS)
    return f"{expiry}.{_sign(expiry)}"


def token_is_valid(token: str) -> bool:
    expiry, _, signature = (token or "").partition(".")
    if not expiry.isdigit() or not signature:
        return False
    if not hmac.compare_digest(signature, _sign(expiry)):
        return False
    return int(expiry) > time.time()


def _is_authenticated(request: Request) -> bool:
    return bool(admin_password()) and token_is_valid(request.cookies.get(COOKIE_NAME, ""))


def _request_hostname(request: Request) -> str:
    raw_host = request.headers.get("host", "")
    return (urlparse("//" + raw_host).hostname or "").lower()


def _require_local(request: Request) -> None:
    client_host = request.client.host if request.client else ""
    if client_host not in LOCAL_HOSTS:
        raise HTTPException(status_code=403, detail="پنل مدیریت فقط از همین دستگاه در دسترس است.")


def _guard(request: Request) -> None:
    _require_local(request)
    if not _is_authenticated(request):
        raise HTTPException(
            status_code=303,
            headers={"Location": "/admin/login?" + urlencode({"next": request.url.path})},
        )


def _check_origin(request: Request) -> None:
    """Reject cross-site browser posts; clients without Origin still require a local host."""
    origin = request.headers.get("origin")
    host = (request.headers.get("host") or "").split(":", 1)[0].strip("[]").lower()
    if host not in LOCAL_REQUEST_HOSTS:
        raise HTTPException(status_code=403, detail="درخواست فقط به سرویس محلی پذیرفته می‌شود.")
    if origin:
        parsed = urlparse(origin)
        origin_host = (parsed.hostname or "").lower()
        if (
            parsed.scheme not in {"http", "https"}
            or origin_host not in LOCAL_REQUEST_HOSTS
            or origin_host != host
            or parsed.netloc.lower() != (request.headers.get("host") or "").lower()
        ):
            raise HTTPException(status_code=403, detail="درخواست از منبع دیگر رد شد.")


def _safe_next(value: str) -> str:
    return value if value.startswith("/admin") and not value.startswith("//") else "/admin"


async def _read_form(request: Request) -> dict[str, str]:
    content_type = request.headers.get("content-type", "").split(";", 1)[0].strip().lower()
    if content_type != "application/x-www-form-urlencoded":
        raise HTTPException(status_code=415, detail="فقط فرم‌های معمولی پذیرفته می‌شوند.")
    if int(request.headers.get("content-length") or 0) > MAX_FORM_BYTES:
        raise HTTPException(status_code=413, detail="فرم بسیار بزرگ است.")
    body = await request.body()
    if len(body) > MAX_FORM_BYTES:
        raise HTTPException(status_code=413, detail="فرم بسیار بزرگ است.")
    try:
        text = body.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise HTTPException(status_code=400, detail="فرم معتبر نیست.") from exc
    return {key: values[-1] for key, values in parse_qs(text, keep_blank_values=True).items()}


# ---------------------------------------------------------------------------
# Validation
# ---------------------------------------------------------------------------

FORM_DEFAULTS = {
    "slug": "", "name": "", "summary": "", "description": "",
    "category": "ابزار کاربردی", "os": "Windows", "version": "",
    "icon_url": "", "homepage_url": "", "download_url": "",
}
URL_FIELDS = ("icon_url", "homepage_url", "download_url")


def _validate(values: dict[str, str]) -> tuple[list[str], dict[str, str]]:
    errors: list[str] = []
    clean = {key: str(values.get(key, "") or "").strip() for key in FORM_DEFAULTS}

    clean["slug"] = clean["slug"].lower()
    if not SLUG_RE.fullmatch(clean["slug"]) or len(clean["slug"]) > 80:
        errors.append("نشانی (slug) باید با حروف کوچک انگلیسی، عدد و خط تیره باشد؛ مثلاً my-app.")

    limits = {"name": 180, "summary": 500, "description": 4000, "category": 80, "os": 120, "version": 80}
    for field, limit in limits.items():
        if len(clean[field]) > limit:
            errors.append(f"طول «{field}» بیش از حد مجاز است.")
            clean[field] = clean[field][:limit]
    if not clean["name"]:
        errors.append("نام نرم‌افزار الزامی است.")
    if not clean["category"]:
        clean["category"] = "ابزار کاربردی"
    if not clean["os"]:
        clean["os"] = "متفرقه"

    for field in URL_FIELDS:
        if clean[field] and not safe_external_url(clean[field]):
            errors.append("آدرس تصویر، وب‌سایت و دانلود باید HTTPS معتبر باشند.")
            clean[field] = ""

    clean["os"] = ", ".join(part.strip() for part in clean["os"].split(",") if part.strip())[:120]
    return errors, clean


def _render(request: Request, name: str, context: dict, status_code: int = 200):
    return templates.TemplateResponse(request=request, name=name, context=context, status_code=status_code)


# ---------------------------------------------------------------------------
# Login
# ---------------------------------------------------------------------------

@router.get("/login", response_class=HTMLResponse)
async def login_page(request: Request, next: str = ""):
    _require_local(request)
    if _is_authenticated(request):
        return RedirectResponse("/admin", status_code=303)
    return _render(request, "admin_login.html", {
        "enabled": bool(admin_password()), "next": _safe_next(next), "error": "",
    })


@router.post("/login", response_class=HTMLResponse)
async def login(request: Request):
    _require_local(request)
    _check_origin(request)
    form = await _read_form(request)
    next_path = _safe_next(form.get("next", ""))
    password = admin_password()
    if not password:
        return _render(request, "admin_login.html", {
            "enabled": False, "next": next_path, "error": "",
        }, status_code=503)
    submitted = form.get("password", "")[:1024].encode("utf-8")
    expected = password[:1024].encode("utf-8")
    if not hmac.compare_digest(submitted, expected) or len(form.get("password", "")) > 1024:
        return _render(request, "admin_login.html", {
            "enabled": True, "next": next_path, "error": "رمز عبور نادرست است.",
        }, status_code=401)
    response = RedirectResponse(next_path, status_code=303)
    response.set_cookie(
        COOKIE_NAME, issue_token(), max_age=SESSION_SECONDS,
        httponly=True, samesite="strict", path="/admin",
    )
    return response


@router.post("/logout")
async def logout(request: Request):
    _require_local(request)
    _check_origin(request)
    response = RedirectResponse("/admin/login", status_code=303)
    response.delete_cookie(COOKIE_NAME, path="/admin")
    return response


# ---------------------------------------------------------------------------
# Dashboard + catalog management
# ---------------------------------------------------------------------------

@router.get("", response_class=HTMLResponse)
async def dashboard(request: Request):
    _guard(request)
    return _render(request, "admin_dashboard.html", {
        "active": "dashboard",
        "stats": db.catalog_stats(),
        "states": db.get_sync_states(),
        "last_sync": db.last_success(),
        "recent": db.recent_software(6),
        "sync_message": request.query_params.get("sync", ""),
    })


@router.get("/software", response_class=HTMLResponse)
async def software_list(request: Request, q: str = "", page: int = 1):
    _guard(request)
    per_page = 40
    page = max(1, page)
    items, total = db.list_software(q.strip()[:80], limit=per_page, offset=(page - 1) * per_page)
    pages = max(1, (total + per_page - 1) // per_page)
    categories, systems = db.get_facets()
    return _render(request, "admin_list.html", {
        "active": "software", "items": items, "total": total, "q": q,
        "page": page, "pages": pages, "categories": categories, "systems": systems,
    })


def _form_values(item: dict | None, submitted: dict[str, str] | None = None) -> dict[str, str]:
    values = dict(FORM_DEFAULTS)
    if item:
        values.update({key: str(item.get(key, "") or "") for key in FORM_DEFAULTS})
    if submitted:
        values.update({key: str(submitted.get(key, "")) for key in FORM_DEFAULTS})
    return values


@router.get("/software/new", response_class=HTMLResponse)
async def new_software_page(request: Request):
    _guard(request)
    categories, systems = db.get_facets()
    return _render(request, "admin_form.html", {
        "active": "new", "editing": False, "original_slug": "",
        "values": _form_values(None), "errors": [], "categories": categories, "systems": systems,
    })


@router.post("/software/new", response_class=HTMLResponse)
async def create_software(request: Request):
    _guard(request)
    _check_origin(request)
    form = await _read_form(request)
    errors, clean = _validate(form)
    if not errors:
        try:
            item = db.create_software(clean)
        except sqlite3.IntegrityError:
            errors.append("این نشانی (slug) قبلاً برای نرم‌افزار دیگری استفاده شده است.")
        else:
            return RedirectResponse(f"/admin/software/{item['slug']}/edit?saved=1", status_code=303)
    categories, systems = db.get_facets()
    return _render(request, "admin_form.html", {
        "active": "new", "editing": False, "original_slug": "",
        "values": _form_values(None, form), "errors": errors,
        "categories": categories, "systems": systems,
    }, status_code=400)


@router.get("/software/{slug}/edit", response_class=HTMLResponse)
async def edit_software_page(request: Request, slug: str):
    _guard(request)
    item = db.get_software(slug)
    if not item:
        raise HTTPException(status_code=404, detail="نرم‌افزار پیدا نشد")
    categories, systems = db.get_facets()
    return _render(request, "admin_form.html", {
        "active": "software", "editing": True, "original_slug": slug,
        "values": _form_values(item), "errors": [], "categories": categories, "systems": systems,
        "item": item,
    })


@router.post("/software/{slug}/edit", response_class=HTMLResponse)
async def update_software(request: Request, slug: str):
    _guard(request)
    _check_origin(request)
    item = db.get_software(slug)
    if not item:
        raise HTTPException(status_code=404, detail="نرم‌افزار پیدا نشد")
    form = await _read_form(request)
    errors, clean = _validate(form)
    if not errors:
        try:
            updated = db.update_software(slug, clean)
        except sqlite3.IntegrityError:
            errors.append("این نشانی (slug) قبلاً برای نرم‌افزار دیگری استفاده شده است.")
        else:
            if updated is None:
                raise HTTPException(status_code=404, detail="نرم‌افزار پیدا نشد")
            return RedirectResponse(f"/admin/software/{updated['slug']}/edit?saved=1", status_code=303)
    categories, systems = db.get_facets()
    return _render(request, "admin_form.html", {
        "active": "software", "editing": True, "original_slug": slug,
        "values": _form_values(item, form), "errors": errors,
        "categories": categories, "systems": systems, "item": item,
    }, status_code=400)


@router.post("/software/{slug}/delete")
async def remove_software(request: Request, slug: str):
    _guard(request)
    _check_origin(request)
    db.delete_software(slug)
    return RedirectResponse("/admin/software?deleted=1", status_code=303)


# ---------------------------------------------------------------------------
# API feeds configured in the local admin panel
# ---------------------------------------------------------------------------

API_FIELDS = ("id", "slug", "name", "summary", "description", "category", "os", "version", "icon_url", "homepage_url", "download_url")
DEFAULT_MAPPING = {field: field for field in API_FIELDS}


def _api_form_values(source: dict | None = None, submitted: dict[str, str] | None = None) -> dict[str, str]:
    values = {
        "name": "", "url": "", "token": "", "records_path": "items",
        "page_mode": "", "page_size": "100", "page_max_pages": "10",
        "page_offset_param": "offset", "page_limit_param": "limit", "page_total_path": "",
    }
    values.update({"map_" + field: field for field in API_FIELDS})
    if source:
        values.update({key: str(source.get(key, "") or "") for key in ("name", "url", "records_path")})
        try:
            mapping = json.loads(source.get("mapping") or "{}")
        except (ValueError, TypeError):
            mapping = {}
        try:
            pagination = json.loads(source.get("pagination") or "{}")
        except (ValueError, TypeError):
            pagination = {}
        for field in API_FIELDS:
            values["map_" + field] = str(mapping.get(field, field))
        form_keys = {
            "mode": "page_mode", "page_size": "page_size", "max_pages": "page_max_pages",
            "offset_param": "page_offset_param", "limit_param": "page_limit_param", "total_path": "page_total_path",
        }
        for field, form_key in form_keys.items():
            values[form_key] = str(pagination.get(field, values[form_key]))
    if submitted:
        values.update({key: str(submitted.get(key, "")) for key in values})
    return values


def _validate_api_form(form: dict[str, str], existing: dict | None = None) -> tuple[list[str], dict[str, str], dict[str, str], str | None, dict[str, Any]]:
    errors: list[str] = []
    values = _api_form_values(existing, form)
    allowed_keys = (
        {"name", "url", "token", "records_path"}
        | {"map_" + field for field in API_FIELDS}
        | {"page_mode", "page_size", "page_max_pages", "page_offset_param", "page_limit_param", "page_total_path"}
    )
    if any(len(value) > 4000 for key, value in form.items() if key in allowed_keys):
        errors.append("یکی از مقادیر فرم بیش از حد طولانی است.")
        form = {key: value[:4000] for key, value in form.items()}
    name = values["name"].strip()
    url = values["url"].strip()
    if len(url) > 1000:
        errors.append("نشانی API بیش از حد طولانی است.")
        url = url[:1000]
    if len(name) > 80:
        errors.append("نام منبع حداکثر ۸۰ نویسه است.")
        name = name[:80]
    token_input = form.get("token", "").strip()
    records_path = values["records_path"].strip(".")
    if not name or len(name) > 80:
        errors.append("نام منبع الزامی است و باید حداکثر ۸۰ نویسه باشد.")
    if not safe_external_url(url):
        errors.append("نشانی API باید URL معتبر HTTPS باشد.")
    parsed_host = (urlparse(url).hostname or "").lower()
    try:
        import ipaddress
        is_non_public_ip = bool(parsed_host and not ipaddress.ip_address(parsed_host).is_global)
    except ValueError:
        is_non_public_ip = False
    except Exception:
        is_non_public_ip = True
    if parsed_host and (is_non_public_ip or parsed_host.endswith((".localhost", ".local")) or parsed_host == "metadata.google.internal"):
        errors.append("نشانی API نباید به یک میزبان محلی یا شبکهٔ خصوصی اشاره کند.")
    if len(token_input) > 1000:
        errors.append("کلید دسترسی بیش از حد طولانی است.")
    if token_input and any(ord(char) < 32 for char in token_input):
        errors.append("کلید دسترسی شامل نویسهٔ غیرمجاز است.")
    if len(records_path) > 200 or not re.fullmatch(r"[A-Za-z0-9_.-]*", records_path):
        errors.append("مسیر آرایهٔ برنامه‌ها فقط می‌تواند شامل حروف انگلیسی، عدد، نقطه، خط تیره و زیرخط باشد.")
    mapping: dict[str, str] = {}
    for field in API_FIELDS:
        path = values["map_" + field].strip()
        if path and (len(path) > 200 or not re.fullmatch(r"[A-Za-z0-9_.-]+", path)):
            errors.append(f"مسیر فیلد {field} معتبر نیست.")
        if path:
            mapping[field] = path
    if not mapping.get("id") or not mapping.get("name"):
        errors.append("نگاشت id و name الزامی است.")
    page_mode = values["page_mode"].strip()
    pagination: dict[str, Any] = {}
    if page_mode not in {"", "offset"}:
        errors.append("نوع صفحه‌بندی فقط می‌تواند خالی یا offset باشد.")
    if page_mode == "offset":
        try:
            page_size = int(values["page_size"])
            max_pages = int(values["page_max_pages"])
            if not 1 <= page_size <= 100 or not 1 <= max_pages <= 20:
                raise ValueError
        except ValueError:
            errors.append("اندازهٔ صفحه باید ۱ تا ۱۰۰ و حداکثر صفحات ۱ تا ۲۰ باشد.")
            page_size, max_pages = 100, 10
        offset_param = values["page_offset_param"].strip()
        limit_param = values["page_limit_param"].strip()
        total_path = values["page_total_path"].strip()
        if not re.fullmatch(r"[A-Za-z0-9_.-]{1,80}", offset_param) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,80}", limit_param):
            errors.append("نام پارامترهای صفحه‌بندی معتبر نیست.")
        if total_path and (len(total_path) > 200 or not re.fullmatch(r"[A-Za-z0-9_.-]+", total_path)):
            errors.append("مسیر تعداد کل رکوردها معتبر نیست.")
        pagination = {
            "mode": "offset", "page_size": page_size, "max_pages": max_pages,
            "offset_param": offset_param, "limit_param": limit_param, "total_path": total_path,
        }
    values.update({"name": name, "url": url, "records_path": records_path})
    token = token_input or None
    if token is None and not existing:
        token = ""
    return errors, values, mapping, token, pagination


@router.get("/apis", response_class=HTMLResponse)
async def api_source_list(request: Request):
    _guard(request)
    return _render(request, "admin_apis.html", {
        "active": "apis", "sources": db.list_api_sources(), "states": db.get_sync_states(),
        "sync_message": request.query_params.get("sync", ""),
    })


@router.get("/apis/new", response_class=HTMLResponse)
async def new_api_source_page(request: Request):
    _guard(request)
    return _render(request, "admin_api_form.html", {
        "active": "apis", "editing": False, "source_id": "", "values": _api_form_values(), "errors": [],
    })


@router.post("/apis/new", response_class=HTMLResponse)
async def create_api_source(request: Request):
    _guard(request)
    _check_origin(request)
    form = await _read_form(request)
    errors, values, mapping, token, pagination = _validate_api_form(form)
    if not errors:
        try:
            source_id = db.save_api_source(values["name"], values["url"], token, values["records_path"], mapping, pagination=pagination)
        except sqlite3.IntegrityError:
            errors.append("منبعی با این نام از قبل ثبت شده است.")
        else:
            return RedirectResponse(f"/admin/apis/{source_id}/edit?saved=1", status_code=303)
    return _render(request, "admin_api_form.html", {
        "active": "apis", "editing": False, "source_id": "", "values": values, "errors": errors,
    }, status_code=400)


@router.get("/apis/{source_id}/edit", response_class=HTMLResponse)
async def edit_api_source_page(request: Request, source_id: int):
    _guard(request)
    source = db.get_api_source(source_id)
    if not source:
        raise HTTPException(status_code=404, detail="منبع API پیدا نشد")
    return _render(request, "admin_api_form.html", {
        "active": "apis", "editing": True, "source_id": source_id, "values": _api_form_values(source), "errors": [],
    })


@router.post("/apis/{source_id}/edit", response_class=HTMLResponse)
async def update_api_source(request: Request, source_id: int):
    _guard(request)
    _check_origin(request)
    source = db.get_api_source(source_id)
    if not source:
        raise HTTPException(status_code=404, detail="منبع API پیدا نشد")
    form = await _read_form(request)
    errors, values, mapping, token, pagination = _validate_api_form(form, source)
    if not errors:
        try:
            db.save_api_source(values["name"], values["url"], token, values["records_path"], mapping, source_id, pagination)
        except sqlite3.IntegrityError:
            errors.append("منبع دیگری با این نام ثبت شده است.")
        else:
            return RedirectResponse(f"/admin/apis/{source_id}/edit?saved=1", status_code=303)
    return _render(request, "admin_api_form.html", {
        "active": "apis", "editing": True, "source_id": source_id, "values": values, "errors": errors,
    }, status_code=400)


@router.post("/apis/{source_id}/delete")
async def remove_api_source(request: Request, source_id: int):
    _guard(request)
    _check_origin(request)
    db.delete_api_source(source_id)
    return RedirectResponse("/admin/apis?deleted=1", status_code=303)


@router.post("/apis/{source_id}/sync")
async def sync_api_source(request: Request, source_id: int):
    _guard(request)
    _check_origin(request)
    source = db.get_api_source(source_id)
    if not source:
        raise HTTPException(status_code=404, detail="منبع API پیدا نشد")
    result = await run_sync(force=True, provider_name=source["name"], provider_url=source["url"])
    status = "busy" if result.get("status") == "already_running" else "done"
    if result.get("status") == "not_found":
        raise HTTPException(status_code=404, detail="منبع API پیدا نشد")
    return RedirectResponse(f"/admin/apis?sync={status}", status_code=303)


@router.post("/sync")
async def trigger_sync(request: Request):
    _guard(request)
    _check_origin(request)
    result = await run_sync(force=True)
    status = "busy" if result.get("status") == "already_running" else "done"
    return RedirectResponse(f"/admin?sync={status}", status_code=303)
