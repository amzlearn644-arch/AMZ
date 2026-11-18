from __future__ import annotations

from urllib.parse import urlparse


def safe_external_url(value: str) -> str:
    """Return only plain HTTPS URLs so stored feed metadata cannot smuggle other schemes."""
    parsed = urlparse(value or "")
    if parsed.scheme == "https" and parsed.hostname and not parsed.username and not parsed.password:
        return value
    return ""
