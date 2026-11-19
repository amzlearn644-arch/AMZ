from __future__ import annotations

import os
import re
from itertools import islice
from typing import Any
from urllib.parse import urlparse

import httpx


class FdroidProvider:
    """Read an F-Droid-compatible signed-repository JSON index (metadata only)."""

    name = "F-Droid"

    def __init__(
        self,
        name: str | None = None,
        index_url: str | None = None,
        max_items: int | None = None,
    ) -> None:
        self.name = name or self.name
        self.index_url = index_url or os.getenv("FDROID_INDEX_URL", "https://f-droid.org/repo/index-v1.json")
        self.limit = max(1, min(int(max_items or os.getenv("FDROID_MAX_ITEMS", "1000")), 2000))
        parsed = urlparse(self.index_url)
        if parsed.scheme != "https" or not parsed.hostname:
            raise ValueError("Repository index must use HTTPS")
        self.repo_base = self.index_url.rsplit("/", 1)[0] + "/"
        self.repo_host = parsed.hostname.lower()

    async def fetch(self) -> list[dict[str, Any]]:
        async with httpx.AsyncClient(timeout=35, follow_redirects=True, headers={"User-Agent": "AMZCatalog/1.0 (metadata sync)"}) as client:
            response = await client.get(self.index_url)
            response.raise_for_status()
            payload = response.json()
        if not isinstance(payload, dict) or not isinstance(payload.get("packages"), dict):
            raise ValueError(f"{self.name} repository index format is invalid")

        normalized: list[dict[str, Any]] = []
        for package_id, package in islice(payload["packages"].items(), self.limit):
            if not isinstance(package, dict):
                continue
            versions = package.get("packages") or []
            version = versions[0] if versions and isinstance(versions[0], dict) else {}
            file_name = str(version.get("apkName") or "")
            download = self.repo_base + file_name if re.fullmatch(r"[A-Za-z0-9._+-]+\.apk", file_name) else ""
            if download and urlparse(download).hostname != self.repo_host:
                download = ""
            name = package.get("name") or package_id
            summary = package.get("summary") or f"برنامهٔ اندروید از {self.name}"
            categories = package.get("categories") or []
            normalized.append({
                "slug": re.sub(r"[^a-z0-9]+", "-", f"{self.name}-{package_id}".lower()).strip("-")[:180],
                "name": str(name)[:180], "summary": str(summary)[:500],
                "description": str(package.get("description") or summary)[:4000],
                "category": str(categories[0] if categories else "Android")[:80],
                "os": "Android", "version": str(version.get("versionName") or "")[:80],
                "icon_url": "", "homepage_url": self.repo_base,
                "download_url": download, "source": self.name, "source_id": str(package_id),
            })
        return normalized
