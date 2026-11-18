from __future__ import annotations

import os
import re
from itertools import islice
from typing import Any
from urllib.parse import urlparse

import httpx


class FdroidProvider:
    name = "F-Droid"

    def __init__(self) -> None:
        self.index_url = os.getenv("FDROID_INDEX_URL", "https://f-droid.org/repo/index-v1.json")
        self.limit = max(1, min(int(os.getenv("FDROID_MAX_ITEMS", "200")), 1000))

    async def fetch(self) -> list[dict[str, Any]]:
        async with httpx.AsyncClient(timeout=20, follow_redirects=True, headers={"User-Agent": "AMZCatalog/1.0 (metadata sync)"}) as client:
            response = await client.get(self.index_url)
            response.raise_for_status()
            payload = response.json()
        if not isinstance(payload, dict) or not isinstance(payload.get("packages"), dict):
            raise ValueError("ساختار index فای‌دروید معتبر نیست")

        host = urlparse(self.index_url).hostname or "f-droid.org"
        normalized: list[dict[str, Any]] = []
        for package_id, package in islice(payload["packages"].items(), self.limit):
            if not isinstance(package, dict):
                continue
            versions = package.get("packages") or []
            version = versions[0] if versions and isinstance(versions[0], dict) else {}
            file_name = str(version.get("apkName") or "")
            download = f"https://{host}/repo/{file_name}" if file_name else "https://f-droid.org/packages/"
            if urlparse(download).hostname not in {host, "f-droid.org", "www.f-droid.org"} or not re.fullmatch(r"[A-Za-z0-9._+-]+\.apk", file_name):
                download = "https://f-droid.org/packages/"
            name = package.get("name") or package_id
            summary = package.get("summary") or "برنامهٔ آزاد اندروید"
            normalized.append({
                "slug": "fdroid-" + str(package_id).lower().replace(".", "-"),
                "name": str(name)[:180], "summary": str(summary)[:500],
                "description": str(package.get("description") or summary)[:4000],
                "category": str(package.get("categories", ["اندروید"])[0] if package.get("categories") else "اندروید")[:80],
                "os": "Android", "version": str(version.get("versionName") or "")[:80],
                "icon_url": "", "homepage_url": f"https://f-droid.org/packages/{package_id}/",
                "download_url": download, "source": self.name, "source_id": str(package_id),
            })
        return normalized
