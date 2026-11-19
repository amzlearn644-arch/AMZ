from __future__ import annotations

import asyncio
import os
import logging
import re
from typing import Any


import httpx

logger = logging.getLogger(__name__)


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

    async def _get_payload(self, client: httpx.AsyncClient, page: int) -> dict[str, Any]:
        params = {"take": self.PAGE_SIZE, "page": page, "sort": "Latest.Name", "order": 1}
        last_error: Exception | None = None
        for attempt in range(7):
            try:
                response = await client.get(self.url, params=params)
                if response.status_code in {429, 500, 502, 503, 504}:
                    retry_after = response.headers.get("Retry-After", "")
                    try:
                        delay = min(30.0, max(1.0, float(retry_after)))
                    except (TypeError, ValueError):
                        delay = min(30.0, 1.5 ** attempt)
                    last_error = httpx.HTTPStatusError(
                        f"WinGet page {page}: HTTP {response.status_code}",
                        request=response.request, response=response,
                    )
                    logger.warning(
                        "WinGet page %d returned HTTP %d; retry %d/7 in %.1fs",
                        page, response.status_code, attempt + 1, delay,
                    )
                    if attempt < 6:
                        await asyncio.sleep(delay)
                        continue
                    raise last_error
                response.raise_for_status()
                payload = response.json()
                if not isinstance(payload, dict):
                    raise ValueError(f"WinGet page {page} returned invalid JSON")
                return payload
            except httpx.RequestError as exc:
                last_error = exc
                if attempt >= 6:
                    raise
                delay = min(30.0, 1.5 ** attempt)
                logger.warning(
                    "WinGet page %d network error; retry %d/7 in %.1fs: %s",
                    page, attempt + 1, delay, exc,
                )
                await asyncio.sleep(delay)
        if last_error:
            raise last_error
        return {}

    async def fetch(self) -> list[dict[str, Any]]:
        headers = {"User-Agent": "AMZCatalog/1.0 (metadata sync)", "Accept": "application/json"}
        token = os.getenv("WINGET_RUN_TOKEN", "").strip()
        if token:
            headers["xxx-access-token"] = f"Bearer {token}"
        async with httpx.AsyncClient(
            timeout=httpx.Timeout(60.0, connect=15.0),
            follow_redirects=True,
            headers=headers,
        ) as client:
            payload = await self._get_payload(client, 0)
            rows = payload.get("Packages", [])
            total = int(payload.get("Total", 0))
            if not isinstance(rows, list):
                raise ValueError("WinGet API returned an invalid package catalog")
            pages = (total + self.PAGE_SIZE - 1) // self.PAGE_SIZE
            if pages > self.MAX_PAGES:
                raise ValueError(
                    f"WinGet catalog has {total} entries; raise WINGET_MAX_PAGES above {self.MAX_PAGES}"
                )
            # The public API intermittently returns 503 when several pages are
            # requested together. Fetch in order and retry transient failures.
            for page in range(1, pages):
                page_payload = await self._get_payload(client, page)
                batch = page_payload.get("Packages", [])
                if not isinstance(batch, list):
                    raise ValueError(f"WinGet page {page} is invalid")
                rows.extend(batch)
                if page % 100 == 0:
                    logger.warning(
                        "WinGet import progress: %d/%d pages (%d packages)",
                        page + 1, pages, len(rows),
                    )
                await asyncio.sleep(0.05)

            unique: dict[str, dict[str, Any]] = {}
            for row in rows:
                if isinstance(row, dict):
                    item = self._normalize(row)
                    if item:
                        unique[item["source_id"].casefold()] = item
            logger.warning(
                "WinGet catalog fetched: %d unique packages from %d API rows",
                len(unique), len(rows),
            )
            return list(unique.values())
