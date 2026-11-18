from __future__ import annotations

import re
from urllib.parse import parse_qs, urlparse

from workers import WorkerEntrypoint, Response
from providers import run_sync


def json_response(data, status=200, headers=None):
    h = {"content-type": "application/json; charset=utf-8", "cache-control": "no-store",
         "x-content-type-options": "nosniff"}
    if headers:
        h.update(headers)
    return Response.json(data, status=status, headers=h)


def row(value):
    return dict(value) if value is not None else None


def safe_https(value):
    value = str(value or "").strip()[:1000]
    parsed = urlparse(value)
    return value if parsed.scheme == "https" and parsed.hostname and not parsed.username and not parsed.password else ""


class Default(WorkerEntrypoint):
    async def fetch(self, request):
        url = urlparse(request.url)
        path = url.path.rstrip("/") or "/"
        query = parse_qs(url.query)
        method = request.method.upper()
        db = self.env.DB

        if path == "/health" and method == "GET":
            try:
                await db.prepare("SELECT 1 AS ok").first()
                return json_response({"ok": True, "service": "AMZ", "database": "connected"})
            except Exception:
                return json_response({"ok": False, "database": "unavailable"}, 503)

        if path == "/api/software" and method == "GET":
            q = (query.get("q") or [""])[0][:100].strip()
            os_name = (query.get("os") or [""])[0][:80].strip()
            category = (query.get("category") or [""])[0][:80].strip()
            try:
                limit = min(60, max(1, int((query.get("limit") or ["48"])[0])))
                offset = min(100000, max(0, int((query.get("offset") or ["0"])[0])))
            except ValueError:
                return json_response({"error": "invalid_pagination"}, 400)
            clauses, binds = [], []
            if q:
                clauses.append("(name LIKE ? OR summary LIKE ? OR description LIKE ?)")
                term = "%" + q.replace("%", "\\%").replace("_", "\\_") + "%"
                binds.extend([term, term, term])
            if os_name:
                clauses.append("(',' || lower(os) || ',') LIKE ?")
                binds.append("%" + os_name.lower() + "%")
            if category:
                clauses.append("category = ?")
                binds.append(category)
            where = (" WHERE " + " AND ".join(clauses)) if clauses else ""
            total = await db.prepare("SELECT COUNT(*) AS total FROM software" + where).bind(*binds).first()
            found = await db.prepare(
                "SELECT slug,name,summary,description,category,os,version,icon_url,homepage_url,download_url,source,updated_at "
                "FROM software" + where + " ORDER BY name COLLATE NOCASE LIMIT ? OFFSET ?"
            ).bind(*binds, limit, offset).all()
            items = []
            for value in found.results:
                item = row(value)
                for key in ("icon_url", "homepage_url", "download_url"):
                    item[key] = safe_https(item.get(key))
                items.append(item)
            return json_response({"items": items, "total": int(total.total if total else 0), "limit": limit, "offset": offset})

        if path == "/api/software-suggestions" and method == "GET":
            q = (query.get("q") or [""])[0][:60].strip()
            if len(q) < 2:
                return json_response({"items": []})
            found = await db.prepare(
                "SELECT slug,name FROM software WHERE name LIKE ? ORDER BY name COLLATE NOCASE LIMIT 8"
            ).bind("%" + q.replace("%", "\\%").replace("_", "\\_") + "%").all()
            return json_response({"items": [row(x) for x in found.results]})

        if path.startswith("/api/software/") and method == "GET":
            slug = path.rsplit("/", 1)[-1]
            item = row(await db.prepare(
                "SELECT slug,name,summary,description,category,os,version,icon_url,homepage_url,download_url,source,updated_at "
                "FROM software WHERE slug = ?"
            ).bind(slug).first())
            if not item:
                return json_response({"error": "نرم‌افزار پیدا نشد"}, 404)
            for key in ("icon_url", "homepage_url", "download_url"):
                item[key] = safe_https(item.get(key))
            return json_response(item)

        if path == "/api/sync-status" and method == "GET":
            found = await db.prepare(
                "SELECT provider,last_attempt,last_success,status,message FROM sync_state ORDER BY provider"
            ).all()
            return json_response({"providers": [row(x) for x in found.results]})

        if path.startswith("/admin/api/"):
            if not await self.admin_authorized(request):
                return json_response({"error": "unauthorized"}, 401, {"www-authenticate": "Bearer"})
            if path == "/admin/api/stats" and method == "GET":
                stats = await db.prepare("SELECT COUNT(*) AS total, COUNT(DISTINCT category) AS categories FROM software").first()
                sources = await db.prepare("SELECT id,name,url,records_path,mapping,pagination,CASE WHEN token='' THEN 0 ELSE 1 END AS token_configured FROM api_sources ORDER BY name").all()
                states = await db.prepare("SELECT provider,last_attempt,last_success,status,message FROM sync_state ORDER BY provider").all()
                return json_response({"stats": row(stats), "sources": [row(x) for x in sources], "sync": [row(x) for x in states]})

            if path == "/admin/api/software" and method == "POST":
                try:
                    item = await request.json()
                except Exception:
                    return json_response({"error": "invalid_json"}, 400)
                result = await self.save_software(db, item)
                return result

            if path.startswith("/admin/api/software/"):
                slug = path.rsplit("/", 1)[-1]
                if method == "DELETE":
                    result = await db.prepare("DELETE FROM software WHERE slug = ?").bind(slug).run()
                    return json_response({"ok": True, "changes": result.meta.changes})
                if method == "PUT":
                    try:
                        item = await request.json()
                    except Exception:
                        return json_response({"error": "invalid_json"}, 400)
                    item["slug"] = slug
                    result = await self.save_software(db, item, update_slug=slug)
                    return result

            if path == "/admin/api/sources" and method == "GET":
                found = await db.prepare("SELECT id,name,url,records_path,mapping,pagination,CASE WHEN token='' THEN 0 ELSE 1 END AS token_configured FROM api_sources ORDER BY name").all()
                return json_response({"items": [row(x) for x in found.results]})

            if path == "/admin/api/sources" and method == "POST":
                try:
                    source = await request.json()
                except Exception:
                    return json_response({"error": "invalid_json"}, 400)
                return await self.save_source(db, source)

            if path.startswith("/admin/api/sources/"):
                try:
                    source_id = int(path.rsplit("/", 1)[-1])
                except ValueError:
                    return json_response({"error": "invalid_id"}, 400)
                if method == "PUT":
                    try:
                        source = await request.json()
                    except Exception:
                        return json_response({"error": "invalid_json"}, 400)
                    return await self.save_source(db, source, source_id)
                if method == "DELETE":
                    result = await db.prepare("DELETE FROM api_sources WHERE id = ?").bind(source_id).run()
                    return json_response({"ok": True, "changes": result.meta.changes})

            if path == "/admin/api/sync" and method == "POST":
                try:
                    body = await request.json()
                except Exception:
                    body = {}
                source_id = body.get("source_id") if isinstance(body, dict) else None
                try:
                    source_id = int(source_id) if source_id is not None else None
                except (ValueError, TypeError):
                    return json_response({"error": "invalid_source_id"}, 400)
                result = await run_sync(db, self.env, source_id)
                return json_response(result, 200 if result["status"] != "not_found" else 404)

            return json_response({"error": "not_found"}, 404)

        if path == "/admin":
            return Response("", status=302, headers={"Location": "/admin/"})
        if path == "/admin/":
            try:
                return await self.env.ASSETS.fetch(request)
            except Exception:
                return Response("Admin asset not found", status=404)

        try:
            return await self.env.ASSETS.fetch(request)
        except Exception:
            return Response("AMZ asset not found", status=404)

    async def save_software(self, db, item, update_slug=None):
        if not isinstance(item, dict):
            return json_response({"error": "invalid_payload"}, 400)
        fields = ("slug", "name", "summary", "description", "category", "os", "version", "icon_url", "homepage_url", "download_url")
        clean = {key: str(item.get(key) or "").strip()[:4000] for key in fields}
        clean["slug"] = clean["slug"].lower()
        if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", clean["slug"]) or not clean["name"]:
            return json_response({"error": "invalid_slug_or_name"}, 400)
        for key in ("icon_url", "homepage_url", "download_url"):
            if clean[key] and not safe_https(clean[key]):
                return json_response({"error": "https_only_urls", "field": key}, 400)
        try:
            if update_slug:
                result = await db.prepare(
                    "UPDATE software SET slug=?,name=?,summary=?,description=?,category=?,os=?,version=?,icon_url=?,homepage_url=?,download_url=?,updated_at=CURRENT_TIMESTAMP WHERE slug=?"
                ).bind(*(clean[k] for k in fields), update_slug).run()
                if not result.meta.changes:
                    return json_response({"error": "not_found"}, 404)
            else:
                await db.prepare(
                    "INSERT INTO software(slug,name,summary,description,category,os,version,icon_url,homepage_url,download_url,source,source_id) "
                    "VALUES(?,?,?,?,?,?,?,?,?,?, 'AMZ Admin', ?)"
                ).bind(*(clean[k] for k in fields), "admin:" + clean["slug"]).run()
            return json_response({"ok": True, "slug": clean["slug"]}, 200 if update_slug else 201)
        except Exception:
            return json_response({"error": "duplicate_or_database_error"}, 409)

    async def save_source(self, db, source, source_id=None):
        if not isinstance(source, dict):
            return json_response({"error": "invalid_payload"}, 400)
        name = str(source.get("name") or "").strip()[:100]
        url = str(source.get("url") or "").strip()[:1000]
        records_path = str(source.get("records_path") or "items").strip(".")[:200]
        token = str(source.get("token") or "")[:1000]
        mapping = source.get("mapping") or {}
        pagination = source.get("pagination") or {}
        if not name or not safe_https(url) or not isinstance(mapping, dict) or not isinstance(pagination, dict):
            return json_response({"error": "invalid_source"}, 400)
        try:
            import json
            mapping_json = json.dumps(mapping, ensure_ascii=False, separators=(",", ":"))
            pagination_json = json.dumps(pagination, ensure_ascii=False, separators=(",", ":"))
            if source_id is None:
                await db.prepare("INSERT INTO api_sources(name,url,token,records_path,mapping,pagination) VALUES(?,?,?,?,?,?)").bind(
                    name, url, token, records_path, mapping_json, pagination_json).run()
                return json_response({"ok": True}, 201)
            existing = await db.prepare("SELECT token FROM api_sources WHERE id=?").bind(source_id).first()
            if not existing:
                return json_response({"error": "not_found"}, 404)
            token_value = token if token else str(existing.token or "")
            await db.prepare("UPDATE api_sources SET name=?,url=?,token=?,records_path=?,mapping=?,pagination=?,updated_at=CURRENT_TIMESTAMP WHERE id=?").bind(
                name, url, token_value, records_path, mapping_json, pagination_json, source_id).run()
            return json_response({"ok": True}, 200)
        except Exception:
            return json_response({"error": "duplicate_or_database_error"}, 409)

    async def admin_authorized(self, request):
        secret = getattr(self.env, "AMZ_ADMIN_TOKEN", "")
        return bool(secret) and request.headers.get("authorization", "") == "Bearer " + str(secret)

    async def scheduled(self, controller, env, ctx):
        try:
            result = await run_sync(env.DB, env)
            print("AMZ scheduled sync:", result)
        except Exception as exc:
            print("AMZ scheduled sync failed:", type(exc).__name__, str(exc)[:200])
