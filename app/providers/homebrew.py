from __future__ import annotations

import re
from typing import Any
from urllib.parse import urlparse

import httpx


class HomebrewProvider:
    """Import the public Homebrew formula and cask catalogs as metadata only."""
    name = "Homebrew"
    SOURCES = (
        ("https://formulae.brew.sh/api/formula.json", "macOS / Linux", "Homebrew Formula"),
        ("https://formulae.brew.sh/api/cask.json", "macOS", "Homebrew Cask"),
    )

    @staticmethod
    def _https(value: Any) -> str:
        url = str(value or "").strip()
        parsed = urlparse(url)
        return url[:2000] if parsed.scheme == "https" and parsed.hostname and not parsed.username and not parsed.password else ""

    @staticmethod
    def _slug(value: str) -> str:
        return re.sub(r"[^a-z0-9]+", "-", value.lower()).strip("-")[:170] or "package"

    async def fetch(self) -> list[dict[str, Any]]:
        headers = {"User-Agent": "AMZCatalog/1.0 (public metadata catalog)"}
        timeout = httpx.Timeout(90.0, connect=10.0)
        records: list[dict[str, Any]] = []
        async with httpx.AsyncClient(timeout=timeout, follow_redirects=True, headers=headers) as client:
            for endpoint, systems, source in self.SOURCES:
                response = await client.get(endpoint)
                response.raise_for_status()
                payload = response.json()
                if not isinstance(payload, list):
                    raise ValueError(f"{source} returned an unexpected catalog format")
                for row in payload:
                    if not isinstance(row, dict):
                        continue
                    token = str(row.get("token") or row.get("name") or "").strip()
                    if not token:
                        continue
                    name = str(row.get("name") or token).strip()[:180]
                    desc = str(row.get("desc") or row.get("description") or "").strip()[:4000]
                    homepage = self._https(row.get("homepage"))
                    # Homebrew metadata URLs are not necessarily installer binaries.
                    # Link to the official project page rather than proxying source archives.
                    download = homepage
                    records.append({
                        "slug": f"{self._slug(source)}-{self._slug(token)}",
                        "name": name,
                        "summary": (desc or f"{name} — {source}")[:500],
                        "description": desc,
                        "category": "Developer tools" if source == "Homebrew Formula" else "macOS applications",
                        "os": systems,
                        "version": str(row.get("version") or "")[:80],
                        "icon_url": "",
                        "homepage_url": homepage,
                        "download_url": download,
                        "source": source,
                        "source_id": token,
                    })
        return records
