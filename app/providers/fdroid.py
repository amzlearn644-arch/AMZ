from __future__ import annotations

import os
import re
from itertools import islice
from typing import Any
from urllib.parse import quote, urlparse

import httpx


class FdroidProvider:
    """Read an F-Droid v1 repository index and keep the newest APK per app."""

    name = "F-Droid"

    def __init__(
        self,
        name: str | None = None,
        index_url: str | None = None,
        max_items: int | None = None,
    ) -> None:
        self.name = name or self.name
        self.index_url = index_url or os.getenv(
            "FDROID_INDEX_URL", "https://f-droid.org/repo/index-v1.json"
        )
        configured_limit = max_items if max_items is not None else os.getenv("FDROID_MAX_ITEMS", "1000000")
        self.limit = max(1, int(configured_limit))
        parsed = urlparse(self.index_url)
        if parsed.scheme != "https" or not parsed.hostname:
            raise ValueError("Repository index must use HTTPS")
        self.repo_base = self.index_url.rsplit("/", 1)[0] + "/"
        self.repo_host = parsed.hostname.lower()

    @staticmethod
    def _localized(value: Any, limit: int = 4000) -> str:
        if isinstance(value, dict):
            value = (
                value.get("en-US") or value.get("en") or value.get("en-GB")
                or next(iter(value.values()), "")
            )
        if isinstance(value, list):
            value = ", ".join(str(part) for part in value[:8])
        return str(value or "").strip()[:limit]

    @staticmethod
    def _version_code(row: dict[str, Any]) -> int:
        try:
            return int(row.get("versionCode") or 0)
        except (TypeError, ValueError):
            return 0

    def _download_url(self, version: dict[str, Any]) -> str:
        file_name = str(version.get("apkName") or "").strip()
        if not re.fullmatch(r"[A-Za-z0-9._+-]+\.apk", file_name):
            return ""
        url = self.repo_base + quote(file_name, safe="._+-")
        if urlparse(url).hostname != self.repo_host:
            return ""
        return url

    async def fetch(self) -> list[dict[str, Any]]:
        headers = {"User-Agent": "AMZCatalog/1.0 (metadata sync)", "Accept": "application/json"}
        timeout = httpx.Timeout(60.0, connect=15.0)
        async with httpx.AsyncClient(timeout=timeout, follow_redirects=True, headers=headers) as client:
            response = await client.get(self.index_url)
            response.raise_for_status()
            payload = response.json()

        if not isinstance(payload, dict):
            raise ValueError(f"{self.name} repository index is not a JSON object")

        # index-v1 separates human-readable app metadata (apps[]) from APK
        # build metadata (packages{packageName: [versions]}).
        apps = payload.get("apps")
        packages_by_id = payload.get("packages")
        if not isinstance(apps, list) or not isinstance(packages_by_id, dict):
            raise ValueError(f"{self.name} index-v1 format is invalid: apps/packages missing")

        normalized: list[dict[str, Any]] = []
        seen: set[str] = set()
        for app in islice(apps, self.limit):
            if not isinstance(app, dict):
                continue
            package_id = str(app.get("packageName") or "").strip()
            if not package_id or package_id.casefold() in seen:
                continue
            versions = packages_by_id.get(package_id) or []
            if isinstance(versions, dict):
                versions = [versions]
            if not isinstance(versions, list) or not versions:
                continue
            valid_versions = [row for row in versions if isinstance(row, dict)]
            if not valid_versions:
                continue
            latest = max(valid_versions, key=self._version_code)
            download = self._download_url(latest)
            name = self._localized(app.get("name"), 180) or package_id
            summary = self._localized(app.get("summary"), 500) or f"Android app from {self.name}"
            description = self._localized(
                app.get("description") or app.get("full_description") or summary, 4000
            )
            categories = app.get("categories") or []
            category = self._localized(categories[0], 80) if categories else "Android"
            website = self._localized(app.get("webSite") or app.get("website"), 1000)
            if not website.startswith("https://"):
                website = self.repo_base
            icon_name = str(app.get("icon") or "").strip()
            icon_url = ""
            if icon_name and re.fullmatch(r"[A-Za-z0-9._/+ -]+", icon_name):
                icon_url = self.repo_base + quote(icon_name.lstrip("/"), safe="/._+-")

            normalized.append({
                "slug": re.sub(
                    r"[^a-z0-9]+", "-",
                    f"{self.name}-{package_id}".lower()
                ).strip("-")[:180],
                "name": name,
                "summary": summary,
                "description": description,
                "category": category or "Android",
                "os": "Android",
                "version": self._localized(latest.get("versionName"), 80),
                "icon_url": icon_url,
                "homepage_url": website,
                "download_url": download,
                "source": self.name,
                "source_id": package_id,
            })
            seen.add(package_id.casefold())

        if apps and not normalized:
            raise ValueError(
                f"{self.name} index parsed but no apps with downloadable APK versions were found"
            )
        return normalized
