from __future__ import annotations

import asyncio
import os
import re
from typing import Any


import httpx


class WingetProvider:
    """Import the complete deduplicated WinGet package catalog via winget.run's public API."""
    name = "Winget"
    API_URL = "https://api.winget.run/v2/packages"
    PAGE_SIZE = 24
    MAX_PAGES = int(os.getenv("WINGET_MAX_PAGES", "5000"))

    def __init__(self, api_url: str | None = None) -> None:
        self.url = (api_url or self.API_URL).rstrip("/")
        if self.url != self.API_URL:
            raise ValueError("WinGet catalog endpoint is fixed to the documented public API")

    @staticmethod
    def _safe_url(value: Any) -> str:
        value = str(value or "").strip()
        return value[:2000] if value.startswith("https://") else ""

    @staticmethod
    def _normalize(row: dict[str, Any]) -> dict[str, Any] | None:
        package_id = str(row.get("Id") or "").strip()
        latest = row.get("Latest") or {}
        if not package_id or not isinstance(latest, dict):
            return None
        name = str(latest.get("Name") or package_id.split(".", 1)[-1]).strip()
        if not name:
            return None
        versions = row.get("Versions") or []
        description = str(latest.get("Description") or "").strip()
        homepage = WingetProvider._safe_url(latest.get("Homepage"))
        tags = latest.get("Tags") or []
        category = str(tags[0]).strip()[:80] if tags else "Windows software"
        return {
            "slug": "winget-" + re.sub(r"[^a-z0-9]+", "-", package_id.lower()).strip("-")[:165],
            "name": name[:180], "summary": (description or f"{name} for Windows")[:500],
            "description": description[:4000], "category": category or "Windows software",
            "os": "Windows", "version": str(versions[0])[:80] if versions else "",
            "icon_url": WingetProvider._safe_url(row.get("IconUrl")),
            "homepage_url": homepage, "download_url": homepage,
            "source": "Winget", "source_id": package_id[:300],
        }

    async def fetch(self) -> list[dict[str, Any]]:
        headers = {"User-Agent": "AMZCatalog/1.0 (metadata sync)", "Accept": "application/json"}
        token = os.getenv("WINGET_RUN_TOKEN", "").strip()
        if token:
            headers["xxx-access-token"] = f"Bearer {token}"
        async with httpx.AsyncClient(timeout=httpx.Timeout(45.0, connect=10.0), follow_redirects=True, headers=headers) as client:
            first = await client.get(self.url, params={"take": self.PAGE_SIZE, "page": 0, "sort": "Latest.Name", "order": 1})
            first.raise_for_status()
            payload = first.json()
            rows = payload.get("Packages") if isinstance(payload, dict) else None
            total = int(payload.get("Total", 0)) if isinstance(payload, dict) else 0
            if not isinstance(rows, list):
                raise ValueError("WinGet API returned an invalid package catalog")
            pages = (total + self.PAGE_SIZE - 1) // self.PAGE_SIZE
            if pages > self.MAX_PAGES:
                raise ValueError(f"WinGet catalog has {total} entries; raise WINGET_MAX_PAGES above {self.MAX_PAGES}")
            semaphore = asyncio.Semaphore(4)
            async def get_page(page: int) -> list[dict[str, Any]]:
                async with semaphore:
                    response = await client.get(self.url, params={"take": self.PAGE_SIZE, "page": page, "sort": "Latest.Name", "order": 1})
                    response.raise_for_status()
                    data = response.json()
                    result = data.get("Packages", []) if isinstance(data, dict) else []
                    if not isinstance(result, list):
                        raise ValueError(f"WinGet page {page} is invalid")
                    return result
            batches = await asyncio.gather(*(get_page(page) for page in range(1, pages)))
            for batch in batches:
                rows.extend(batch)
            unique: dict[str, dict[str, Any]] = {}
            for row in rows:
                if isinstance(row, dict):
                    item = self._normalize(row)
                    if item:
                        unique[item["source_id"].casefold()] = item
            return list(unique.values())
