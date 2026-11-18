from __future__ import annotations

from typing import Any, Protocol


class Provider(Protocol):
    name: str

    async def fetch(self) -> list[dict[str, Any]]:
        """Fetch and normalize provider records; never download application binaries."""
