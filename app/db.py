from __future__ import annotations

import json
import os
import re
import sqlite3
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator
from uuid import uuid4

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DB_PATH = Path(os.getenv("AMZ_DB_PATH", str(PROJECT_ROOT / "data" / "amz.sqlite3")))
if not DB_PATH.is_absolute():
    DB_PATH = PROJECT_ROOT / DB_PATH


def _connect() -> sqlite3.Connection:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(DB_PATH, timeout=10)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys = ON")
    connection.execute("PRAGMA journal_mode = WAL")
    connection.execute("PRAGMA busy_timeout = 5000")
    return connection


@contextmanager
def connection() -> Iterator[sqlite3.Connection]:
    conn = _connect()
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def init_db() -> None:
    with connection() as conn:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS software (
                id INTEGER PRIMARY KEY,
                slug TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                summary TEXT NOT NULL DEFAULT '',
                description TEXT NOT NULL DEFAULT '',
                category TEXT NOT NULL DEFAULT 'ابزار کاربردی',
                os TEXT NOT NULL DEFAULT 'Windows',
                version TEXT NOT NULL DEFAULT '',
                icon_url TEXT NOT NULL DEFAULT '',
                homepage_url TEXT NOT NULL DEFAULT '',
                download_url TEXT NOT NULL DEFAULT '',
                source TEXT NOT NULL DEFAULT 'AMZ',
                source_id TEXT NOT NULL DEFAULT '',
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                UNIQUE(source, source_id)
            );
            CREATE TABLE IF NOT EXISTS api_sources (
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL UNIQUE,
                url TEXT NOT NULL,
                token TEXT NOT NULL DEFAULT '',
                records_path TEXT NOT NULL DEFAULT 'items',
                mapping TEXT NOT NULL DEFAULT '{}',
                pagination TEXT NOT NULL DEFAULT '{}',
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            );
            """
        )
        # Add columns when upgrading a database created by an earlier local build.
        source_columns = {row[1] for row in conn.execute("PRAGMA table_info(api_sources)")}
        if "records_path" not in source_columns:
            conn.execute("ALTER TABLE api_sources ADD COLUMN records_path TEXT NOT NULL DEFAULT 'items'")
        if "mapping" not in source_columns:
            conn.execute("ALTER TABLE api_sources ADD COLUMN mapping TEXT NOT NULL DEFAULT '{}'")
        if "pagination" not in source_columns:
            conn.execute("ALTER TABLE api_sources ADD COLUMN pagination TEXT NOT NULL DEFAULT '{}'")
        conn.executescript(
            """
            CREATE INDEX IF NOT EXISTS idx_software_os ON software(os);
            CREATE INDEX IF NOT EXISTS idx_software_category ON software(category);
            CREATE INDEX IF NOT EXISTS idx_software_name ON software(name COLLATE NOCASE);
            CREATE TABLE IF NOT EXISTS sync_state (
                provider TEXT PRIMARY KEY,
                last_attempt TEXT NOT NULL DEFAULT '',
                last_success TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT 'idle',
                message TEXT NOT NULL DEFAULT ''
            );
            """
        )
        count = conn.execute("SELECT COUNT(*) FROM software").fetchone()[0]
        if count == 0:
            for item in SAMPLE_SOFTWARE:
                _upsert(conn, item)


SAMPLE_SOFTWARE: list[dict[str, Any]] = [
    {
        "slug": "7-zip", "name": "7-Zip", "summary": "فشرده‌سازی سریع و رایگان فایل‌ها",
        "description": "ابزار سبک و متن‌باز برای ساخت و بازکردن آرشیوها با پشتیبانی از فرمت‌های گوناگون.",
        "category": "ابزار کاربردی", "os": "Windows", "version": "24.09",
        "icon_url": "https://cdn.simpleicons.org/7zip/426A4C", "homepage_url": "https://www.7-zip.org/",
        "download_url": "https://www.7-zip.org/download.html", "source": "نمونهٔ AMZ", "source_id": "7zip",
    },
    {
        "slug": "vlc", "name": "VLC Media Player", "summary": "پخش‌کنندهٔ چندرسانه‌ای همه‌فن‌حریف",
        "description": "پخش‌کنندهٔ متن‌باز و رایگان برای ویدئو و موسیقی، بدون نیاز به نصب کُدک‌های اضافی.",
        "category": "چندرسانه‌ای", "os": "Windows, macOS, Linux", "version": "3.0.21",
        "icon_url": "", "homepage_url": "https://www.videolan.org/vlc/",
        "download_url": "https://www.videolan.org/vlc/", "source": "نمونهٔ AMZ", "source_id": "vlc",
    },
    {
        "slug": "firefox", "name": "Mozilla Firefox", "summary": "مرورگر آزاد با تمرکز بر حریم خصوصی",
        "description": "مرورگر سریع و متن‌باز موزیلا با ابزارهای کاربردی برای حفظ حریم خصوصی و شخصی‌سازی.",
        "category": "اینترنت", "os": "Windows, macOS, Linux, Android", "version": "آخرین نسخه",
        "icon_url": "", "homepage_url": "https://www.mozilla.org/firefox/",
        "download_url": "https://www.mozilla.org/firefox/all/", "source": "نمونهٔ AMZ", "source_id": "firefox",
    },
    {
        "slug": "sharex", "name": "ShareX", "summary": "ثبت تصویر و اشتراک‌گذاری آسان",
        "description": "ابزار متن‌باز ویندوز برای اسکرین‌شات، ضبط صفحه و گردش‌کارهای اشتراک‌گذاری.",
        "category": "ابزار کاربردی", "os": "Windows", "version": "17.0.0",
        "icon_url": "", "homepage_url": "https://getsharex.com/",
        "download_url": "https://getsharex.com/", "source": "نمونهٔ AMZ", "source_id": "sharex",
    },
    {
        "slug": "localsend", "name": "LocalSend", "summary": "انتقال فایل در شبکهٔ محلی، ساده و امن",
        "description": "ارسال فایل بین دستگاه‌های مختلف در شبکهٔ محلی بدون نیاز به اینترنت یا سرور واسطه.",
        "category": "شبکه و اینترنت", "os": "Windows, macOS, Linux, Android, iOS", "version": "1.17.0",
        "icon_url": "", "homepage_url": "https://localsend.org/",
        "download_url": "https://localsend.org/download", "source": "نمونهٔ AMZ", "source_id": "localsend",
    },
    {
        "slug": "kdenlive", "name": "Kdenlive", "summary": "تدوین‌گر ویدئوی قدرتمند و متن‌باز",
        "description": "ویرایشگر ویدئویی آزاد با ابزارهای حرفه‌ای، مناسب پروژه‌های سبک تا نیمه‌حرفه‌ای.",
        "category": "چندرسانه‌ای", "os": "Windows, macOS, Linux", "version": "24.12",
        "icon_url": "", "homepage_url": "https://kdenlive.org/",
        "download_url": "https://kdenlive.org/download/", "source": "نمونهٔ AMZ", "source_id": "kdenlive",
    },
    {
        "slug": "fdroid", "name": "F-Droid", "summary": "فروشگاه اپلیکیشن‌های آزاد اندروید",
        "description": "فهرست و فروشگاه نرم‌افزارهای آزاد و متن‌باز برای دستگاه‌های اندرویدی.",
        "category": "ابزار کاربردی", "os": "Android", "version": "1.20.0",
        "icon_url": "", "homepage_url": "https://f-droid.org/",
        "download_url": "https://f-droid.org/FDroid.apk", "source": "نمونهٔ AMZ", "source_id": "fdroid",
    },
    {
        "slug": "krita", "name": "Krita", "summary": "نقاشی دیجیتال برای هنرمندان",
        "description": "نرم‌افزار نقاشی دیجیتال آزاد و حرفه‌ای با مجموعه‌ای کامل از قلم‌ها و ابزارهای طراحی.",
        "category": "طراحی", "os": "Windows, macOS, Linux, Android", "version": "5.2.9",
        "icon_url": "", "homepage_url": "https://krita.org/",
        "download_url": "https://krita.org/en/download/", "source": "نمونهٔ AMZ", "source_id": "krita",
    },
]


def _upsert(conn: sqlite3.Connection, item: dict[str, Any]) -> None:
    fields = ("slug", "name", "summary", "description", "category", "os", "version", "icon_url", "homepage_url", "download_url", "source", "source_id")
    values = [str(item.get(field, "") or "") for field in fields]
    values[0] = re.sub(r"[^a-z0-9]+", "-", values[0].lower()).strip("-")[:180] or "software"
    collision = conn.execute(
        "SELECT source, source_id FROM software WHERE slug = ?", (values[0],)
    ).fetchone()
    if collision and (collision["source"], collision["source_id"]) != (values[10], values[11]):
        source_prefix = re.sub(r"[^a-z0-9]+", "-", values[10].lower()).strip("-")[:40] or "catalog"
        values[0] = f"{source_prefix}-{values[0]}"[:180]
    conn.execute(
        f"INSERT INTO software ({','.join(fields)}) VALUES ({','.join('?' for _ in fields)}) "
        "ON CONFLICT(source, source_id) DO UPDATE SET "
        "slug=excluded.slug, name=excluded.name, summary=excluded.summary, description=excluded.description, "
        "category=excluded.category, os=excluded.os, version=excluded.version, icon_url=excluded.icon_url, "
        "homepage_url=excluded.homepage_url, download_url=excluded.download_url, updated_at=CURRENT_TIMESTAMP",
        values,
    )


def upsert_many(items: list[dict[str, Any]]) -> int:
    with connection() as conn:
        for item in items:
            _upsert(conn, item)
    return len(items)


def list_software(query: str = "", os_name: str = "", category: str = "", limit: int = 48, offset: int = 0, sort: str = "name") -> tuple[list[dict[str, Any]], int]:
    clauses: list[str] = []
    params: list[Any] = []
    if query.strip():
        raw_needle = query.strip()[:80]
        needle = "%" + raw_needle.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
        clauses.append("(name LIKE ? ESCAPE '!' OR summary LIKE ? ESCAPE '!' OR description LIKE ? ESCAPE '!')")
        params.extend([needle, needle, needle])
    if os_name:
        clauses.append("(',' || lower(replace(os, ', ', ',')) || ',') LIKE ?")
        params.append(f"%,{os_name.strip().lower()},%")
    if category:
        clauses.append("category = ?")
        params.append(category[:80])
    where = " WHERE " + " AND ".join(clauses) if clauses else ""
    with connection() as conn:
        total = conn.execute("SELECT COUNT(*) FROM software" + where, params).fetchone()[0]
        order_by = {
            "name": "name COLLATE NOCASE ASC, id ASC",
            "newest": "updated_at DESC, id DESC",
            "name_desc": "name COLLATE NOCASE DESC, id DESC",
        }.get(sort, "name COLLATE NOCASE ASC, id ASC")
        rows = conn.execute("SELECT * FROM software" + where + " ORDER BY " + order_by + " LIMIT ? OFFSET ?", [*params, max(1, min(limit, 100)), max(0, offset)]).fetchall()
    return [dict(row) for row in rows], total


 
def sitemap_entries() -> list[dict[str, str]]:
    """Return public catalog URLs and their last metadata update timestamp."""
    with connection() as conn:
        rows = conn.execute(
            "SELECT slug, updated_at FROM software ORDER BY slug COLLATE NOCASE"
        ).fetchall()
    return [{"slug": row["slug"], "updated_at": row["updated_at"]} for row in rows]

def search_suggestions(query: str, limit: int = 8) -> list[dict[str, str]]:
    """Return a small set of name-prefix matches for live search suggestions."""
    needle = query.strip()[:80]
    if len(needle) < 2:
        return []
    escaped = needle.replace("!", "!!").replace("%", "!%").replace("_", "!_")
    with connection() as conn:
        rows = conn.execute(
            "SELECT name, slug FROM software WHERE name LIKE ? ESCAPE '!' ORDER BY name COLLATE NOCASE LIMIT ?",
            (escaped + "%", max(1, min(limit, 10))),
        ).fetchall()
    return [{"name": row["name"], "slug": row["slug"]} for row in rows]


def get_software(slug: str) -> dict[str, Any] | None:
    with connection() as conn:
        row = conn.execute("SELECT * FROM software WHERE slug = ?", (slug,)).fetchone()
    return dict(row) if row else None


EDITABLE_FIELDS = ("slug", "name", "summary", "description", "category", "os", "version", "icon_url", "homepage_url", "download_url")


def create_software(item: dict[str, Any]) -> dict[str, Any]:
    """Insert an entry created in the admin panel, with its own stable source id."""
    values = [str(item.get(field, "") or "") for field in EDITABLE_FIELDS]
    with connection() as conn:
        cursor = conn.execute(
            f"INSERT INTO software ({','.join(EDITABLE_FIELDS)}, source, source_id) "
            f"VALUES ({','.join('?' for _ in EDITABLE_FIELDS)}, 'AMZ', ?)",
            [*values, "manual-" + uuid4().hex[:12]],
        )
        row = conn.execute("SELECT * FROM software WHERE id = ?", (cursor.lastrowid,)).fetchone()
    return dict(row)


def update_software(slug: str, item: dict[str, Any]) -> dict[str, Any] | None:
    """Update an entry found by its current slug; keeps source/source_id so syncs still match."""
    values = [str(item.get(field, "") or "") for field in EDITABLE_FIELDS]
    with connection() as conn:
        row = conn.execute("SELECT id FROM software WHERE slug = ?", (slug,)).fetchone()
        if not row:
            return None
        assignments = ", ".join(f"{field} = ?" for field in EDITABLE_FIELDS)
        conn.execute(
            f"UPDATE software SET {assignments}, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
            [*values, row["id"]],
        )
        updated = conn.execute("SELECT * FROM software WHERE id = ?", (row["id"],)).fetchone()
    return dict(updated)


def delete_software(slug: str) -> bool:
    with connection() as conn:
        cursor = conn.execute("DELETE FROM software WHERE slug = ?", (slug,))
        deleted = cursor.rowcount > 0
    return deleted


def recent_software(limit: int = 6) -> list[dict[str, Any]]:
    with connection() as conn:
        rows = conn.execute(
            "SELECT * FROM software ORDER BY updated_at DESC, id DESC LIMIT ?",
            (max(1, min(limit, 50)),),
        ).fetchall()
    return [dict(row) for row in rows]


def catalog_stats() -> dict[str, int]:
    with connection() as conn:
        total = conn.execute("SELECT COUNT(*) FROM software").fetchone()[0]
        categories = conn.execute("SELECT COUNT(DISTINCT category) FROM software").fetchone()[0]
        sources = conn.execute("SELECT COUNT(DISTINCT source) FROM software").fetchone()[0]
        os_values = [row[0] for row in conn.execute("SELECT DISTINCT os FROM software")]
    systems = {part.strip() for value in os_values for part in value.split(",") if part.strip()}
    return {"total": total, "categories": categories, "sources": sources, "systems": len(systems)}


def get_facets() -> tuple[list[str], list[str]]:
    with connection() as conn:
        categories = [row[0] for row in conn.execute("SELECT DISTINCT category FROM software ORDER BY category")]
        os_values = [row[0] for row in conn.execute("SELECT DISTINCT os FROM software")]
    systems = sorted({part.strip() for value in os_values for part in value.split(",") if part.strip()})
    return categories, systems


def last_success() -> str:
    with connection() as conn:
        row = conn.execute("SELECT MAX(last_success) FROM sync_state WHERE status='ok'").fetchone()
    return row[0] or ""


def set_sync_state(provider: str, status: str, message: str = "", success: bool = False) -> None:
    with connection() as conn:
        conn.execute(
            "INSERT INTO sync_state(provider,last_attempt,last_success,status,message) "
            "VALUES(?,CURRENT_TIMESTAMP,CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE '' END,?,?) "
            "ON CONFLICT(provider) DO UPDATE SET last_attempt=CURRENT_TIMESTAMP, "
            "last_success=CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE sync_state.last_success END, status=excluded.status, message=excluded.message",
            (provider, int(success), status, message[:300], int(success)),
        )


def get_sync_states() -> list[dict[str, Any]]:
    with connection() as conn:
        return [dict(row) for row in conn.execute("SELECT * FROM sync_state ORDER BY provider")]


def list_api_sources() -> list[dict[str, Any]]:
    with connection() as conn:
        rows = conn.execute("SELECT id, name, url, records_path, mapping, pagination, created_at, updated_at FROM api_sources ORDER BY name COLLATE NOCASE").fetchall()
    return [dict(row) for row in rows]


def get_api_sources_with_secrets() -> list[dict[str, Any]]:
    with connection() as conn:
        rows = conn.execute("SELECT id, name, url, token, records_path, mapping, pagination FROM api_sources ORDER BY name COLLATE NOCASE").fetchall()
    return [dict(row) for row in rows]


def get_api_source(source_id: int) -> dict[str, Any] | None:
    with connection() as conn:
        row = conn.execute("SELECT id, name, url, records_path, mapping, pagination FROM api_sources WHERE id = ?", (source_id,)).fetchone()
    return dict(row) if row else None


def save_api_source(
    name: str,
    url: str,
    token: str | None,
    records_path: str,
    mapping: dict[str, str],
    source_id: int | None = None,
    pagination: dict[str, Any] | None = None,
) -> int:
    mapping_json = json.dumps(mapping, ensure_ascii=False, separators=(",", ":"))
    pagination_json = json.dumps(pagination or {}, ensure_ascii=False, separators=(",", ":"))
    with connection() as conn:
        if source_id is None:
            cursor = conn.execute(
                "INSERT INTO api_sources(name, url, token, records_path, mapping, pagination) VALUES(?,?,?,?,?,?)",
                (name, url, token or "", records_path, mapping_json, pagination_json),
            )
            return int(cursor.lastrowid)
        if token is None:
            conn.execute(
                "UPDATE api_sources SET name=?, url=?, records_path=?, mapping=?, pagination=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                (name, url, records_path, mapping_json, pagination_json, source_id),
            )
        else:
            conn.execute(
                "UPDATE api_sources SET name=?, url=?, token=?, records_path=?, mapping=?, pagination=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                (name, url, token, records_path, mapping_json, pagination_json, source_id),
            )
    return source_id


def delete_api_source(source_id: int) -> bool:
    with connection() as conn:
        cursor = conn.execute("DELETE FROM api_sources WHERE id = ?", (source_id,))
    return cursor.rowcount > 0
