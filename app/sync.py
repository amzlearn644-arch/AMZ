from __future__ import annotations

import asyncio
import os
from datetime import datetime, timedelta, timezone
from typing import Any

from . import db
from .providers import configured_providers

SYNC_INTERVAL = timedelta(hours=6)
_sync_lock = asyncio.Lock()


def _is_due() -> bool:
    providers = configured_providers()
    if not providers:
        return False
    attempts = {state["provider"]: state["last_attempt"] for state in db.get_sync_states()}
    now = datetime.now(timezone.utc)
    for provider in providers:
        value = attempts.get(provider.name, "")
        if not value:
            return True
        try:
            timestamp = datetime.fromisoformat(value.replace("Z", "+00:00"))
            if timestamp.tzinfo is None:
                timestamp = timestamp.replace(tzinfo=timezone.utc)
            if now - timestamp >= SYNC_INTERVAL:
                return True
        except ValueError:
            return True
    return False


async def run_sync(
    force: bool = False,
    provider_name: str | None = None,
    provider_url: str | None = None,
) -> dict[str, Any]:
    if _sync_lock.locked():
        return {"status": "already_running", "providers": []}
    async with _sync_lock:
        if not force and not _is_due():
            return {"status": "fresh", "providers": []}
        results = []
        providers = configured_providers()
        if provider_name is not None:
            providers = [
                provider for provider in providers
                if provider.name == provider_name
                and (provider_url is None or getattr(provider, "url", None) == provider_url)
            ]
            if not providers:
                return {"status": "not_found", "providers": []}
        for provider in providers:
            try:
                db.set_sync_state(provider.name, "running", "همگام‌سازی در حال اجراست")
                records = await provider.fetch()
                if records:
                    db.upsert_many(records)
                db.set_sync_state(provider.name, "ok", f"{len(records)} مورد دریافت شد", success=True)
                results.append({"provider": provider.name, "status": "ok", "count": len(records)})
            except Exception as exc:
                # A failed provider leaves its old catalog records untouched.
                db.set_sync_state(provider.name, "error", f"{type(exc).__name__}: {exc}")
                results.append({"provider": provider.name, "status": "error", "message": str(exc)[:160]})
        return {"status": "ok" if all(r["status"] == "ok" for r in results) else "partial", "providers": results}


async def sync_loop() -> None:
    # Single loop per local app process; no work on each ordinary request.
    await asyncio.sleep(2)
    while True:
        try:
            if _is_due():
                await run_sync()
        except Exception:
            # Keep the local app alive if SQLite or provider setup has a transient error.
            pass
        await asyncio.sleep(max(60, int(os.getenv("AMZ_SYNC_CHECK_SECONDS", "1800"))))
