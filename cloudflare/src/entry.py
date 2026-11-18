from __future__ import annotations
import re
from urllib.parse import parse_qs, urlparse
from workers import WorkerEntrypoint, Response

def json_response(data, status=200, headers=None):
    h = {"content-type": "application/json; charset=utf-8", "cache-control": "no-store"}
    if headers: h.update(headers)
    return Response.json(data, status=status, headers=h)

def row(value):
    return value if isinstance(value, dict) else dict(value)

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
                clauses.append("(name LIKE ? ESCAPE '\\\\' OR summary LIKE ? ESCAPE '\\\\' OR category LIKE ? ESCAPE '\\\\')")
                term = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
                binds.extend([term, term, term])
            if os_name:
                clauses.append("os LIKE ?")
                binds.append("%" + os_name + "%")
            if category:
                clauses.append("category = ?")
                binds.append(category)
            where = (" WHERE " + " AND ".join(clauses)) if clauses else ""
            total = await db.prepare("SELECT COUNT(*) AS total FROM software" + where).bind(*binds).first()
            found = await db.prepare(
                "SELECT slug,name,summary,description,category,os,version,icon_url,homepage_url,download_url,source,updated_at "
                "FROM software" + where + " ORDER BY name COLLATE NOCASE LIMIT ? OFFSET ?"
            ).bind(*binds, limit, offset).all()
            return json_response({"items": [row(x) for x in found.results],
                "total": int(total.total if total else 0), "limit": limit, "offset": offset})

        if path == "/api/software-suggestions" and method == "GET":
            q = (query.get("q") or [""])[0][:60].strip()
            if len(q) < 2: return json_response({"items": []})
            found = await db.prepare(
                "SELECT slug,name FROM software WHERE name LIKE ? ORDER BY name COLLATE NOCASE LIMIT 8"
            ).bind("%" + q.replace("%", "\\%").replace("_", "\\_") + "%").all()
            return json_response({"items": [row(x) for x in found.results]})

        if path.startswith("/api/software/") and method == "GET":
            slug = path.rsplit("/", 1)[-1]
            item = await db.prepare(
                "SELECT slug,name,summary,description,category,os,version,icon_url,homepage_url,download_url,source,updated_at "
                "FROM software WHERE slug = ?"
            ).bind(slug).first()
            return json_response(row(item)) if item else json_response({"error": "نرم‌افزار پیدا نشد"}, 404)

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
                sources = await db.prepare("SELECT id,name,url,records_path FROM api_sources ORDER BY name").all()
                states = await db.prepare("SELECT provider,last_attempt,last_success,status,message FROM sync_state ORDER BY provider").all()
                return json_response({"stats": row(stats), "sources": [row(x) for x in sources],
                    "sync": [row(x) for x in states]})
            if path == "/admin/api/software" and method == "POST":
                try: item = await request.json()
                except Exception: return json_response({"error": "invalid_json"}, 400)
                fields = ("slug","name","summary","description","category","os","version","icon_url","homepage_url","download_url")
                clean = {k: str(item.get(k) or "").strip()[:4000] for k in fields}
                if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", clean["slug"]) or not clean["name"]:
                    return json_response({"error": "invalid_slug_or_name"}, 400)
                for key in ("icon_url","homepage_url","download_url"):
                    if clean[key] and not clean[key].startswith("https://"):
                        return json_response({"error": "https_only_urls"}, 400)
                try:
                    await db.prepare(
                        "INSERT INTO software (slug,name,summary,description,category,os,version,icon_url,homepage_url,download_url,source,source_id) "
                        "VALUES (?,?,?,?,?,?,?,?,?,?, 'AMZ Admin', ?)"
                    ).bind(*(clean[k] for k in fields), "admin:" + clean["slug"]).run()
                except Exception:
                    return json_response({"error": "duplicate_or_database_error"}, 409)
                return json_response({"ok": True, "slug": clean["slug"]}, 201)
            if path.startswith("/admin/api/software/") and method == "DELETE":
                slug = path.rsplit("/", 1)[-1]
                result = await db.prepare("DELETE FROM software WHERE slug = ?").bind(slug).run()
                return json_response({"ok": True, "changes": result.meta.changes})
            return json_response({"error": "not_found"}, 404)

        try:
            return await self.env.ASSETS.fetch(request)
        except Exception:
            return Response("AMZ asset not found", status=404)

    async def admin_authorized(self, request):
        secret = getattr(self.env, "AMZ_ADMIN_TOKEN", "")
        return bool(secret) and request.headers.get("authorization", "") == "Bearer " + str(secret)

    async def scheduled(self, controller, env, ctx):
        # Provider adapters still need a Workers-native outbound-fetch port.
        # Do not report successful syncs or overwrite the existing catalog here.
        print("AMZ scheduled event received; provider sync is not enabled yet.")
