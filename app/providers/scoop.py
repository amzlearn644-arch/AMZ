from __future__ import annotations

import asyncio
import os
import re
from typing import Any
from urllib.parse import quote, urlparse

import httpx


class ScoopProvider:
    """Import Scoop Main metadata through jsDelivr's public package index/CDN."""
    name = "Scoop"
    INDEX_URL = "https://data.jsdelivr.com/v1/package/gh/ScoopInstaller/Main@master/flat"
    CDN_BASE = "https://cdn.jsdelivr.net/gh/ScoopInstaller/Main@master/"
    MAX_ITEMS = int(os.getenv("SCOOP_MAX_ITEMS", "100000"))

    @staticmethod
    def _text(value: Any, limit: int = 500) -> str:
        if isinstance(value, list):
            value = ", ".join(str(part) for part in value[:8])
        if isinstance(value, dict):
            value = next(iter(value.values()), "")
        return str(value or "").strip()[:limit]

    @staticmethod
    def _download_url(value: Any) -> str:
        candidates = value if isinstance(value, list) else [value]
        for candidate in candidates:
            if isinstance(candidate, dict):
                candidate = candidate.get("url") or candidate.get("href") or ""
            url = str(candidate or "").strip()
            parsed = urlparse(url)
            if parsed.scheme == "https" and parsed.hostname and not parsed.username and not parsed.password:
                return url[:2000]
        return ""

    async def fetch(self) -> list[dict[str, Any]]:
        headers = {"User-Agent": "AMZCatalog/1.0 (public metadata sync)", "Accept": "application/json"}
        timeout = httpx.Timeout(45.0, connect=10.0)
        async with httpx.AsyncClient(timeout=timeout, follow_redirects=True, headers=headers) as client:
            index_response = await client.get(self.INDEX_URL)
            index_response.raise_for_status()
            payload = index_response.json()
            files = payload.get("files") if isinstance(payload, dict) else None
            if not isinstance(files, list):
                raise ValueError("Scoop CDN index returned an invalid file list")

            paths = []
            for row in files:
                if not isinstance(row, dict):
                    continue
                path = str(row.get("name") or "").lstrip("/")
                if path.startswith("bucket/") and path.endswith(".json"):
                    filename = path.rsplit("/", 1)[-1]
                    if re.fullmatch(r"[A-Za-z0-9._+-]+\.json", filename):
                        paths.append(filename)
            paths = sorted(set(paths))[: self.MAX_ITEMS]
            semaphore = asyncio.Semaphore(12)

            async def fetch_one(filename: str) -> dict[str, Any] | None:
                async with semaphore:
                    url = self.CDN_BASE + "bucket/" + quote(filename, safe="._+-")
                    for attempt in range(3):
                        try:
                            result = await client.get(url)
                            if result.status_code == 429 or result.status_code >= 500:
                                if attempt < 2:
                                    await asyncio.sleep(1.5 * (attempt + 1))
                                    continue
                            result.raise_for_status()
                            if len(result.content) > 500_000:
                                return None
                            data = result.json()
                            break
                        except (httpx.HTTPError, ValueError):
                            if attempt == 2:
                                return None
                            await asyncio.sleep(1.0 * (attempt + 1))
                if not isinstance(data, dict):
                    return None
                app_id = filename[:-5]
                description = self._text(data.get("description"), 4000)
                homepage = self._text(data.get("homepage"), 1000)
                if not homepage.startswith("https://"):
                    homepage = ""
                download = self._download_url(data.get("url"))
                return {
                    "slug": "scoop-" + re.sub(r"[^a-z0-9]+", "-", app_id.lower()).strip("-")[:170],
                    "name": app_id[:180],
                    "summary": description[:500] or f"{app_id} for Windows",
                    "description": description,
                    "category": "Windows software",
                    "os": "Windows",
                    "version": self._text(data.get("version"), 80),
                    "icon_url": "",
                    "homepage_url": homepage,
                    "download_url": download,
                    "source": self.name,
                    "source_id": app_id,
                }

            results = await asyncio.gather(*(fetch_one(name) for name in paths))
            records = [item for item in results if item is not None]
            if paths and not records:
                raise ValueError("Scoop CDN index loaded but no manifests could be parsed")
            return records
