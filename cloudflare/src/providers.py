from __future__ import annotations

import json
import re
from typing import Any
from urllib.parse import quote, urlparse

import httpx

UA = "AMZCatalog/1.0 (+https://github.com/amzlearn644-arch/AMZ; metadata sync)"
TIMEOUT = httpx.Timeout(18.0, connect=7.0)
MAX_ITEMS = 1000


def _safe_url(value: Any) -> str:
    value = str(value or "")[:1000].strip()
    parsed = urlparse(value)
    if parsed.scheme == "https" and parsed.hostname and not parsed.username and not parsed.password:
        return value
    return ""


def _slug(value: Any) -> str:
    return re.sub(r"[^a-z0-9]+", "-", str(value or "").lower()).strip("-")[:180]


def _path(value: Any, path: str) -> Any:
    current = value
    for part in path.split(".") if path else []:
        current = current.get(part) if isinstance(current, dict) else None
    return current


def _normalize(raw: dict[str, Any], provider: str, stable_id: str, defaults: dict[str, Any] | None = None) -> dict[str, Any] | None:
    defaults = defaults or {}
    name = str(raw.get("name") or raw.get("title") or defaults.get("name") or "").strip()[:180]
    if not name or not stable_id:
        return None
    summary = str(raw.get("summary") or raw.get("short_description") or raw.get("description") or "")[:500]
    return {
        "slug": _slug(raw.get("slug") or f"{provider}-{stable_id}") or _slug(f"{provider}-{stable_id}"),
        "name": name,
        "summary": summary,
        "description": str(raw.get("description") or summary)[:4000],
        "category": str(raw.get("category") or defaults.get("category") or "نرم‌افزار")[:80],
        "os": str(raw.get("os") or defaults.get("os") or "متفرقه")[:120],
        "version": str(raw.get("version") or raw.get("versionName") or "")[:80],
        "icon_url": _safe_url(raw.get("icon_url") or raw.get("icon")),
        "homepage_url": _safe_url(raw.get("homepage_url") or raw.get("website") or raw.get("url")),
        "download_url": _safe_url(raw.get("download_url") or raw.get("download")),
        "source": provider[:100],
        "source_id": str(stable_id)[:300],
    }


async def _get_json(client: httpx.AsyncClient, url: str, params: dict[str, Any] | None = None, headers: dict[str, str] | None = None) -> Any:
    response = await client.get(url, params=params, headers=headers)
    response.raise_for_status()
    if len(response.content) > 10_000_000:
        raise ValueError("پاسخ منبع بیش از حد بزرگ است")
    return response.json()


async def fetch_fdroid() -> list[dict[str, Any]]:
    url = "https://f-droid.org/repo/index-v1.json"
    async with httpx.AsyncClient(timeout=TIMEOUT, follow_redirects=False, headers={"User-Agent": UA}) as client:
        payload = await _get_json(client, url)
    packages = payload.get("packages") if isinstance(payload, dict) else None
    if not isinstance(packages, dict):
        raise ValueError("ساختار فهرست F-Droid معتبر نیست")
    records = []
    for package_id, package in list(packages.items())[:200]:
        if not isinstance(package, dict):
            continue
        versions = package.get("packages") or []
        latest = versions[0] if versions and isinstance(versions[0], dict) else {}
        apk = str(latest.get("apkName") or "")
        download = f"https://f-droid.org/repo/{apk}" if re.fullmatch(r"[A-Za-z0-9._+-]+\.apk", apk) else ""
        item = _normalize({
            "name": package.get("name") or package_id,
            "summary": package.get("summary") or "برنامهٔ آزاد اندروید",
            "description": package.get("description") or package.get("summary") or "",
            "category": (package.get("categories") or ["اندروید"])[0],
            "os": "Android", "version": latest.get("versionName") or "",
            "homepage_url": f"https://f-droid.org/packages/{package_id}/",
            "download_url": download,
        }, "F-Droid", package_id)
        if item:
            records.append(item)
    return records


async def fetch_openuse() -> list[dict[str, Any]]:
    base = "https://api.openuse.app/v1/public/apps"
    records = []
    async with httpx.AsyncClient(timeout=TIMEOUT, follow_redirects=False, headers={"User-Agent": UA}) as client:
        for offset in range(0, 10000, 100):
            payload = await _get_json(client, base, {"offset": offset, "limit": 100})
            rows = payload if isinstance(payload, list) else (payload.get("items") or payload.get("apps") or payload.get("data") or [])
            if not isinstance(rows, list):
                raise ValueError("ساختار پاسخ OpenUse معتبر نیست")
            for raw in rows:
                if not isinstance(raw, dict):
                    continue
                stable = raw.get("id") or raw.get("package_id") or raw.get("slug")
                item = _normalize(raw, "OpenUse", str(stable or ""))
                if item:
                    records.append(item)
            if len(rows) < 100 or len(records) >= MAX_ITEMS:
                break
    return records[:MAX_ITEMS]


async def fetch_winget() -> list[dict[str, Any]]:
    root_url = "https://api.github.com/repos/microsoft/winget-pkgs/contents/manifests"
    package_ids = ("7zip.7zip", "Mozilla.Firefox", "Google.Chrome", "Microsoft.Edge",
                   "Microsoft.VisualStudioCode", "Git.Git", "VideoLAN.VLC",
                   "Notepad++.Notepad++", "OBSProject.OBSStudio", "ShareX.ShareX")
    headers = {"User-Agent": UA, "Accept": "application/vnd.github+json"}
    cache: dict[str, Any] = {}
    count = 0
    async with httpx.AsyncClient(timeout=TIMEOUT, follow_redirects=False, headers=headers) as client:
        async def get(url: str, raw: bool = False) -> Any:
            nonlocal count
            if url in cache:
                return cache[url]
            if count >= 54:
                return "" if raw else None
            response = await client.get(url, headers={"Accept": "application/vnd.github.raw+json"} if raw else None)
            count += 1
            response.raise_for_status()
            if len(response.content) > (250_000 if raw else 1_000_000):
                return "" if raw else None
            result = response.text if raw else response.json()
            cache[url] = result
            return result

        roots = await get(root_url)
        if not isinstance(roots, list):
            raise ValueError("GitHub فهرست Winget را برنگرداند")
        shards = {str(x.get("name", "")).lower(): x for x in roots if isinstance(x, dict) and x.get("type") == "dir"}
        result = []
        for package_id in package_ids:
            parts = package_id.split(".")
            shard = shards.get(parts[0][0].lower())
            if not shard:
                continue
            publishers = await get(shard.get("url", ""))
            publisher = next((x for x in publishers or [] if isinstance(x, dict) and x.get("name", "").lower() == parts[0].lower()), None)
            if not publisher:
                continue
            packages = await get(publisher.get("url", ""))
            package = next((x for x in packages or [] if isinstance(x, dict) and x.get("name", "").lower() == parts[1].lower()), None)
            if not package:
                continue
            versions = await get(package.get("url", ""))
            candidates = [str(x.get("name", "")) for x in versions or [] if isinstance(x, dict) and x.get("type") == "dir" and re.fullmatch(r"[0-9A-Za-z][0-9A-Za-z.+_-]{0,79}", str(x.get("name", "")))]
            if not candidates:
                continue
            version = sorted(candidates, key=lambda v: [int(p) if p.isdigit() else p.lower() for p in re.findall(r"\d+|[A-Za-z]+", v)])[-1]
            files = await get(package.get("url", "").rstrip("/") + "/" + quote(version))
            locale = next((x for x in files or [] if isinstance(x, dict) and str(x.get("name", "")).lower().endswith(".locale.en-us.yaml")), None)
            if not locale:
                locale = next((x for x in files or [] if isinstance(x, dict) and ".locale." in str(x.get("name", "")).lower() and str(x.get("name", "")).lower().endswith(".yaml")), None)
            if not locale or not locale.get("download_url"):
                continue
            manifest = await get(locale["download_url"], raw=True)
            def scalar(field: str) -> str:
                m = re.search(rf"(?m)^{re.escape(field)}:\s*(.+?)\s*$", manifest)
                if not m:
                    return ""
                value = m.group(1).strip()
                return value[1:-1] if len(value) >= 2 and value[0] in "'\"" and value[-1] == value[0] else value
            name = scalar("PackageName")
            if not name:
                continue
            homepage = _safe_url(scalar("PackageUrl"))
            item = _normalize({"name": name, "summary": scalar("ShortDescription"), "description": scalar("ShortDescription"),
                               "category": "Windows software", "os": "Windows", "version": version,
                               "homepage_url": homepage, "download_url": homepage}, "Winget", package_id)
            if item:
                result.append(item)
        return result


async def fetch_custom(source: dict[str, Any]) -> list[dict[str, Any]]:
    url = str(source.get("url") or "")
    parsed = urlparse(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("آدرس منبع باید HTTPS معتبر باشد")
    headers = {"User-Agent": UA}
    token = str(source.get("token") or "")
    if token:
        headers["Authorization"] = "Bearer " + token[:1000]
    mapping = json.loads(source.get("mapping") or "{}")
    pagination = json.loads(source.get("pagination") or "{}")
    if not isinstance(mapping, dict) or not isinstance(pagination, dict):
        raise ValueError("تنظیمات mapping یا pagination معتبر نیست")
    records_path = str(source.get("records_path") or "items")
    page_size = max(1, min(int(pagination.get("page_size", 100)), 100))
    max_pages = max(1, min(int(pagination.get("max_pages", 10)), 20)) if pagination.get("mode") == "offset" else 1
    result = []
    async with httpx.AsyncClient(timeout=TIMEOUT, follow_redirects=False, headers=headers) as client:
        for page in range(max_pages):
            params = {str(pagination.get("offset_param", "offset")): page * page_size,
                      str(pagination.get("limit_param", "limit")): page_size} if max_pages > 1 else None
            payload = await _get_json(client, url, params)
            rows = payload if isinstance(payload, list) else _path(payload, records_path)
            if not isinstance(rows, list):
                raise ValueError(f"فیلد {records_path} در پاسخ منبع وجود ندارد")
            for raw in rows:
                if not isinstance(raw, dict):
                    continue
                mapped = {key: _path(raw, str(mapping.get(key, key))) for key in
                          ("id", "slug", "name", "summary", "description", "category", "os", "version", "icon_url", "homepage_url", "download_url")}
                stable = mapped.get("id") or mapped.get("slug")
                item = _normalize(mapped, str(source["name"]), str(stable or ""))
                if item:
                    result.append(item)
            if len(rows) < page_size or len(result) >= MAX_ITEMS:
                break
    return result[:MAX_ITEMS]


async def _set_state(db: Any, provider: str, status: str, message: str, success: bool = False) -> None:
    await db.prepare(
        "INSERT INTO sync_state(provider,last_attempt,last_success,status,message) VALUES(?,CURRENT_TIMESTAMP,CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE '' END,?,?) "
        "ON CONFLICT(provider) DO UPDATE SET last_attempt=CURRENT_TIMESTAMP,last_success=CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE sync_state.last_success END,status=excluded.status,message=excluded.message"
    ).bind(provider, 1 if success else 0, status, message[:300], 1 if success else 0).run()


async def _upsert(db: Any, item: dict[str, Any]) -> None:
    # Avoid slug collisions across independent feeds while preserving stable source identity.
    collision = await db.prepare("SELECT source,source_id FROM software WHERE slug=?").bind(item["slug"]).first()
    if collision and (str(collision.source), str(collision.source_id)) != (item["source"], item["source_id"]):
        item["slug"] = (_slug(item["source"])[:35] + "-" + item["slug"])[:180]
    # A failed fetch never deletes old rows.
    await db.prepare(
        "INSERT INTO software(slug,name,summary,description,category,os,version,icon_url,homepage_url,download_url,source,source_id,updated_at) "
        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP) "
        "ON CONFLICT(source,source_id) DO UPDATE SET name=excluded.name,summary=excluded.summary,description=excluded.description,"
        "category=excluded.category,os=excluded.os,version=excluded.version,icon_url=excluded.icon_url,homepage_url=excluded.homepage_url,"
        "download_url=excluded.download_url,updated_at=CURRENT_TIMESTAMP"
    ).bind(item["slug"], item["name"], item["summary"], item["description"], item["category"], item["os"], item["version"],
           item["icon_url"], item["homepage_url"], item["download_url"], item["source"], item["source_id"]).run()


async def _run_provider(db: Any, name: str, fetcher) -> dict[str, Any]:
    await _set_state(db, name, "running", "همگام‌سازی در حال اجراست")
    try:
        records = await fetcher()
        for item in records:
            await _upsert(db, item)
        await _set_state(db, name, "ok", f"{len(records)} مورد دریافت شد", True)
        return {"provider": name, "status": "ok", "count": len(records)}
    except Exception as exc:
        await _set_state(db, name, "error", f"{type(exc).__name__}: {exc}")
        return {"provider": name, "status": "error", "message": str(exc)[:180]}


async def run_sync(db: Any, env: Any, provider_id: int | None = None) -> dict[str, Any]:
    jobs = []
    if provider_id is None:
        jobs.extend([("OpenUse", fetch_openuse), ("Winget", fetch_winget)])
        if str(getattr(env, "AMZ_ENABLE_FDROID", "1")) != "0":
            jobs.append(("F-Droid", fetch_fdroid))
    sources = await db.prepare("SELECT id,name,url,token,records_path,mapping,pagination FROM api_sources ORDER BY name").all()
    for source in sources.results:
        if provider_id is not None and int(source["id"]) != provider_id:
            continue
        jobs.append((str(source["name"]), lambda source=source: fetch_custom(dict(source))))
    if provider_id is not None and not jobs:
        return {"status": "not_found", "providers": []}
    results = []
    for name, fetcher in jobs:
        results.append(await _run_provider(db, name, fetcher))
    return {"status": "ok" if all(x["status"] == "ok" for x in results) else "partial", "providers": results}
