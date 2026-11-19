from __future__ import annotations

import asyncio
import re
from typing import Any

import httpx


class ScoopProvider:
    """Import app metadata from the public Scoop Main bucket on GitHub."""

    name = "Scoop"
    API_URL = "https://api.github.com/repos/ScoopInstaller/Main/contents/bucket"
    RAW_BASE = "https://raw.githubusercontent.com/ScoopInstaller/Main/master/bucket/"
    MAX_ITEMS = 300

    @staticmethod
    def _text(value: Any, limit: int = 500) -> str:
        if isinstance(value, list):
            value = ", ".join(str(part) for part in value[:8])
        if isinstance(value, dict):
            value = next(iter(value.values()), "")
        return str(value or "").strip()[:limit]

    async def fetch(self) -> list[dict[str, Any]]:
        headers = {
            "User-Agent": "AMZCatalog/1.0 (metadata sync)",
            "Accept": "application/vnd.github+json",
        }
        timeout = httpx.Timeout(20.0, connect=6.0)
        async with httpx.AsyncClient(timeout=timeout, follow_redirects=False, headers=headers) as client:
            response = await client.get(self.API_URL, params={"per_page": 1000})
            response.raise_for_status()
            files = response.json()
            if not isinstance(files, list):
                raise ValueError("Scoop Main bucket did not return a file list")
            names = [
                str(row.get("name", "")) for row in files
                if isinstance(row, dict)
                and row.get("type") == "file"
                and str(row.get("name", "")).endswith(".json")
                and re.fullmatch(r"[A-Za-z0-9._+-]+\.json", str(row.get("name", "")))
            ][: self.MAX_ITEMS]

            semaphore = asyncio.Semaphore(8)

            async def fetch_one(filename: str) -> dict[str, Any] | None:
                async with semaphore:
                    try:
                        result = await client.get(self.RAW_BASE + filename)
                        result.raise_for_status()
                        if len(result.content) > 500_000:
                            return None
                        data = result.json()
                    except (httpx.HTTPError, ValueError):
                        return None
                if not isinstance(data, dict):
                    return None
                app_id = filename[:-5]
                name = self._text(data.get("description") and app_id, 180) or app_id
                description = self._text(data.get("description"), 4000)
                homepage = self._text(data.get("homepage"), 1000)
                version = self._text(data.get("version"), 80)
                if not homepage.startswith("https://"):
                    homepage = ""
                return {
                    "slug": "scoop-" + re.sub(r"[^a-z0-9]+", "-", app_id.lower()).strip("-")[:170],
                    "name": name,
                    "summary": description[:500] or f"{app_id} for Windows",
                    "description": description,
                    "category": "Windows software",
                    "os": "Windows",
                    "version": version,
                    "icon_url": "",
                    "homepage_url": homepage,
                    "download_url": homepage,
                    "source": self.name,
                    "source_id": app_id,
                }

            results = await asyncio.gather(*(fetch_one(name) for name in names))
            return [item for item in results if item is not None]
