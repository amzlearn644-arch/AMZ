from __future__ import annotations

import re
from typing import Any
from urllib.parse import quote

import httpx


class WingetProvider:
    """Read a small, bounded set of popular package manifests via GitHub Contents API."""

    name = "Winget"
    DEFAULT_API = "https://api.github.com/repos/microsoft/winget-pkgs/contents/manifests"
    # The supplied endpoint is a repository directory, not a flat catalog API. Keep
    # syncs lightweight and within GitHub's unauthenticated rate limit.
    PACKAGE_IDS = (
        "7zip.7zip", "Mozilla.Firefox", "Google.Chrome", "Microsoft.Edge",
        "Microsoft.VisualStudioCode", "Git.Git", "VideoLAN.VLC",
        "Notepad++.Notepad++", "OBSProject.OBSStudio", "ShareX.ShareX",
    )

    def __init__(self, api_url: str | None = None) -> None:
        self.url = (api_url or self.DEFAULT_API).rstrip("/")
        if self.url != self.DEFAULT_API:
            raise ValueError("Winget manifests endpoint must be the verified Microsoft GitHub Contents API")
        self.api_root = self.url
        self.host = "api.github.com"

    @staticmethod
    def _parse_yaml_scalar(text: str, field: str) -> str:
        match = re.search(rf"(?m)^{re.escape(field)}:\s*(.+?)\s*$", text)
        if not match:
            return ""
        value = match.group(1).strip()
        if value.startswith(('"', "'")) and value.endswith(value[0]):
            value = value[1:-1]
        return value[:1000]

    @staticmethod
    def _latest_version(names: list[str]) -> str | None:
        def version_key(value: str) -> tuple[Any, ...]:
            return tuple(int(part) if part.isdigit() else part.lower() for part in re.findall(r"\d+|[A-Za-z]+", value))
        candidates = [name for name in names if re.fullmatch(r"[0-9A-Za-z][0-9A-Za-z.+_-]{0,79}", name)]
        return max(candidates, key=version_key) if candidates else None

    async def fetch(self) -> list[dict[str, Any]]:
        headers = {"User-Agent": "AMZCatalog/1.0 (metadata sync)", "Accept": "application/vnd.github+json"}
        timeout = httpx.Timeout(12.0, connect=5.0)
        async with httpx.AsyncClient(timeout=timeout, follow_redirects=False, headers=headers) as client:
            requests = 0
            cache: dict[str, Any] = {}

            async def get_json(url: str) -> Any:
                nonlocal requests
                if url in cache:
                    return cache[url]
                if requests >= 54:
                    return None
                response = await client.get(url)
                requests += 1
                response.raise_for_status()
                if len(response.content) > 1_000_000:
                    raise ValueError("GitHub directory response is too large")
                payload = response.json()
                cache[url] = payload
                return payload

            async def get_raw(url: str) -> str:
                nonlocal requests
                if requests >= 54:
                    return ""
                response = await client.get(url, headers={"Accept": "application/vnd.github.raw+json"})
                requests += 1
                response.raise_for_status()
                if len(response.content) > 250_000:
                    return ""
                return response.text

            roots = await get_json(self.api_root)
            if not isinstance(roots, list):
                raise ValueError("GitHub did not return the Winget manifests directory")
            root_dirs = {str(row.get("name", "")).lower(): row for row in roots if isinstance(row, dict) and row.get("type") == "dir"}
            records: list[dict[str, Any]] = []
            for package_id in self.PACKAGE_IDS:
                parts = package_id.split(".")
                if len(parts) < 2:
                    continue
                shard = parts[0][0].lower()
                root = root_dirs.get(shard)
                if not root:
                    continue
                publishers = await get_json(root.get("url", ""))
                publisher = next((row for row in publishers or [] if isinstance(row, dict) and row.get("name", "").lower() == parts[0].lower()), None)
                if not publisher:
                    continue
                packages = await get_json(publisher.get("url", ""))
                package_dir = next((row for row in packages or [] if isinstance(row, dict) and row.get("name", "").lower() == parts[1].lower()), None)
                if not package_dir:
                    continue
                versions = await get_json(package_dir.get("url", ""))
                version = self._latest_version([str(row.get("name", "")) for row in versions or [] if isinstance(row, dict) and row.get("type") == "dir"])
                if not version:
                    continue
                version_url = f"{package_dir.get('url', '').split('?', 1)[0].rstrip('/')}/{quote(version)}?ref=master"
                files = await get_json(version_url)
                locale = next((row for row in files or [] if isinstance(row, dict) and row.get("name", "").lower().endswith(".locale.en-us.yaml")), None)
                if not locale:
                    locale = next((row for row in files or [] if isinstance(row, dict) and ".locale." in row.get("name", "").lower() and row.get("name", "").lower().endswith(".yaml")), None)
                if not locale or not locale.get("download_url"):
                    continue
                manifest = await get_raw(locale["download_url"])
                name = self._parse_yaml_scalar(manifest, "PackageName")
                if not name:
                    continue
                summary = self._parse_yaml_scalar(manifest, "ShortDescription")
                homepage = self._parse_yaml_scalar(manifest, "PackageUrl")
                installer = next((row for row in files or [] if isinstance(row, dict) and row.get("name", "").lower().endswith(".installer.yaml")), None)
                installer_text = await get_raw(installer["download_url"]) if installer and installer.get("download_url") else ""
                download_match = re.search(r'(?m)^\\s*InstallerUrl:\\s*[\\"\\']?(https://[^\\s\\"\\']+)', installer_text)
                download = download_match.group(1).rstrip("\\"'") if download_match else ""
                records.append({
                    "slug": re.sub(r"[^a-z0-9]+", "-", package_id.lower()).strip("-")[:180],
                    "name": name[:180], "summary": summary[:500], "description": summary[:4000],
                    "category": "Windows software", "os": "Windows", "version": version[:80],
                    "icon_url": "", "homepage_url": homepage[:1000],
                    "download_url": download[:2000], "source": self.name, "source_id": package_id[:300],
                })
            return records
