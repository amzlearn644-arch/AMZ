from __future__ import annotations

import ipaddress
import json
import re
from typing import Any
from urllib.parse import urljoin, urlparse

import httpx


def _safe_https(value: Any) -> str:
    text = str(value or "")[:1000]
    parsed = urlparse(text)
    return text if parsed.scheme == "https" and parsed.hostname and not parsed.username and not parsed.password else ""


class JsonFeedProvider:
    """Adapter for explicitly configured JSON metadata feeds, not a guessed public API."""

    def __init__(
        self,
        name: str,
        url: str,
        token: str = "",
        records_path: str = "items",
        mapping: dict[str, str] | None = None,
        pagination: dict[str, Any] | None = None,
    ) -> None:
        parsed = urlparse(url)
        if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
            raise ValueError(f"{name} endpoint must be a plain HTTPS URL")
        hostname = parsed.hostname.lower()
        if hostname == "localhost" or hostname.endswith((".localhost", ".local")) or hostname == "metadata.google.internal":
            raise ValueError(f"{name} endpoint must not point to a local host")
        try:
            if not ipaddress.ip_address(hostname).is_global:
                raise ValueError(f"{name} endpoint must not point to a private or reserved address")
        except ValueError as exc:
            if "endpoint must not point" in str(exc):
                raise
        self.name = name
        self.url = url
        self.host = parsed.hostname.lower()
        self.port = parsed.port or 443
        self.token = token[:1000]
        self.records_path = records_path.strip(".")[:200]
        self.mapping = mapping or {}
        self.pagination = pagination or {}
        self.pagination_mode = str(self.pagination.get("mode", ""))
        self.pagination_limit = max(1, min(int(self.pagination.get("page_size", 100)), 100))
        self.pagination_max_pages = max(1, min(int(self.pagination.get("max_pages", 10)), 20))
        self.pagination_offset_param = str(self.pagination.get("offset_param", "offset"))[:80]
        self.pagination_limit_param = str(self.pagination.get("limit_param", "limit"))[:80]
        self.pagination_total_path = str(self.pagination.get("total_path", ""))[:200]

    @staticmethod
    def _extract_path(payload: Any, path: str) -> Any:
        value = payload
        for part in path.split(".") if path else []:
            value = value.get(part) if isinstance(value, dict) else None
        return value

    def _extract_records(self, payload: Any) -> list[Any] | None:
        records = payload if isinstance(payload, list) else self._extract_path(payload, self.records_path)
        return records if isinstance(records, list) else None

    async def fetch(self) -> list[dict[str, Any]]:
        headers = {"User-Agent": "AMZCatalog/1.0 (metadata sync)"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        async with httpx.AsyncClient(timeout=15, follow_redirects=False, headers=headers) as client:
            payloads = []
            for page in range(self.pagination_max_pages if self.pagination_mode == "offset" else 1):
                params = None
                if self.pagination_mode == "offset":
                    params = {
                        self.pagination_offset_param: page * self.pagination_limit,
                        self.pagination_limit_param: self.pagination_limit,
                    }
                response = await client.get(self.url, params=params)
                if getattr(response, "is_redirect", False):
                    location = response.headers.get("location", "")
                    location = urljoin(self.url, location)
                    redirected = urlparse(location)
                    if redirected.scheme != "https" or redirected.hostname != self.host or (redirected.port or 443) != self.port:
                        raise ValueError(f"{self.name} endpoint redirected to a different host")
                    response = await client.get(location, params=params, headers=headers)
                response.raise_for_status()
                response_body = getattr(response, "content", b"")
                if response_body and len(response_body) > 10_000_000:
                    raise ValueError(f"{self.name} metadata response is too large")
                try:
                    payload = response.json()
                except (ValueError, json.JSONDecodeError) as exc:
                    raise ValueError(f"{self.name} did not return valid JSON") from exc
                payloads.append(payload)
                if self.pagination_mode != "offset":
                    break
                page_records = self._extract_records(payload)
                total = self._extract_path(payload, self.pagination_total_path) if self.pagination_total_path else None
                next_offset = (page + 1) * self.pagination_limit
                if not page_records or len(page_records) < self.pagination_limit:
                    break
                if isinstance(total, int) and next_offset >= total:
                    break
        records = []
        for payload in payloads:
            page_records = self._extract_records(payload)
            if page_records is None:
                raise ValueError(f"{self.name} feed must contain a JSON array at '{self.records_path}'")
            records.extend(page_records)
        if not isinstance(records, list):
            raise ValueError(f"{self.name} feed must be a JSON array or {{items: [...]}}")
        normalized = []
        field_map = {key: self.mapping.get(key, key) for key in (
            "id", "slug", "name", "summary", "description", "category", "os", "version",
            "icon_url", "homepage_url", "download_url",
        )}
        for row in records[:1000]:
            if not isinstance(row, dict):
                continue
            item = {}
            for key, path in field_map.items():
                value: Any = row
                for part in path.split(".") if path else []:
                    value = value.get(part) if isinstance(value, dict) else None
                item[key] = value or ""
            if not item.get("name"):
                continue
            stable_value = item.get("id") or item.get("slug")
            if not stable_value:
                continue
            download_url = _safe_https(item.get("download_url"))
            stable_id = str(stable_value)[:300]
            fallback_slug = re.sub(r"[^a-z0-9]+", "-", f"{self.name}-{stable_id}".lower()).strip("-")
            slug = str(item.get("slug") or fallback_slug or f"{self.name.lower()}-app").lower()
            raw_os = item.get("os") or "متفرقه"
            if isinstance(raw_os, list):
                os_value = ", ".join(str(platform) for platform in raw_os[:20])
            else:
                os_value = str(raw_os)
            raw_version = item.get("version") or ""
            if isinstance(raw_version, dict):
                version_value = ", ".join(f"{platform}: {version}" for platform, version in list(raw_version.items())[:8])
            else:
                version_value = str(raw_version)
            raw_category = item.get("category") or "نرم‌افزار"
            if isinstance(raw_category, list):
                category_value = ", ".join(str(category) for category in raw_category[:10])
            else:
                category_value = str(raw_category)
            normalized.append({
                "slug": re.sub(r"[^a-z0-9]+", "-", slug).strip("-")[:180] or fallback_slug[:180], "name": str(item["name"])[:180],
                "summary": str(item.get("summary") or "")[:500],
                "description": str(item.get("description") or item.get("summary") or "")[:4000],
                "category": category_value[:80],
                "os": os_value[:120],
                "version": version_value[:80],
                "icon_url": _safe_https(item.get("icon_url")),
                "homepage_url": _safe_https(item.get("homepage_url")) or download_url,
                "download_url": download_url[:1000], "source": self.name,
                "source_id": stable_id,
            })
        return normalized
