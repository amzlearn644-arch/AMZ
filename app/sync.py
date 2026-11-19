from __future__ import annotations

import asyncio
import logging
import os
from datetime import datetime, timedelta, timezone
from typing import Any

from . import db
from .providers import configured_providers

logger = logging.getLogger("amz.sync")
SYNC_INTERVAL = timedelta(hours=6)
_sync_lock = asyncio.Lock()


def _is_due() -> bool:
    providers = configured_providers()
    if not providers:
        return False
    # A failed attempt must not postpone recovery for six hours. Only a successful
    # import advances the provider freshness window.
    attempts = {state["provider"]: state["last_success"] for state in db.get_sync_states()}
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
        except (ValueError, TypeError):
            return True
    return False


async def run_sync(
    force: bool = False,
    provider_name: str | None = None,
    provider_url: str | None = None,
) -> dict[str, Any]:
    if _sync_lock.locked():
        logger.warning("Sync skipped: another synchronization is already running")
        return {"status": "already_running", "providers": []}
    async with _sync_lock:
        if not force and not _is_due():
            return {"status": "fresh", "providers": []}
        logger.warning("AMZ sync: loading enabled provider configuration")
        providers = configured_providers()
        logger.warning("AMZ sync: loaded %d providers: %s", len(providers), ", ".join(p.name for p in providers))
        if provider_name is not None:
            providers = [
                provider for provider in providers
                if provider.name == provider_name
                and (provider_url is None or getattr(provider, "url", None) == provider_url)
            ]
            if not providers:
                logger.error("Requested provider not found: %s", provider_name)
                return {"status": "not_found", "providers": []}
        logger.info("Metadata sync started: %d provider(s): %s", len(providers), ", ".join(p.name for p in providers))
        results = []
        for provider in providers:
            started = datetime.now(timezone.utc)
            logger.info("Provider %s: fetch started", provider.name)
            try:
                db.set_sync_state(provider.name, "running", "همگام‌سازی در حال اجراست")
                records = await provider.fetch()
                if records:
                    db.upsert_many(records)
                elapsed = (datetime.now(timezone.utc) - started).total_seconds()
                db.set_sync_state(provider.name, "ok", f"{len(records)} مورد دریافت شد", success=True)
                logger.info("Provider %s: success; fetched=%d; elapsed=%.1fs", provider.name, len(records), elapsed)
                results.append({"provider": provider.name, "status": "ok", "count": len(records)})
            except Exception as exc:
                db.set_sync_state(provider.name, "error", f"{type(exc).__name__}: {exc}")
                logger.exception("Provider %s failed", provider.name)
                results.append({"provider": provider.name, "status": "error", "message": str(exc)[:160]})
        status = "ok" if results and all(r["status"] == "ok" for r in results) else "partial"
        logger.info("Metadata sync finished: status=%s providers=%d", status, len(results))
        return {"status": status, "providers": results}


async def sync_loop() -> None:
    logger.warning("AMZ catalog sync scheduler started")
    await asyncio.sleep(2)
    try:
        if _is_due():
            logger.warning("AMZ scheduled catalog synchronization starting")
            result = await run_sync()
        else:
            result = {"status": "fresh", "providers": []}
            logger.warning("AMZ catalog is fresh; next scheduled check will follow the 6-hour interval")
        total = db.list_software(limit=1)[1]
        windows = db.list_software(os_name="Windows", limit=1)[1]
        android = db.list_software(os_name="Android", limit=1)[1]
        provider_summary = ", ".join(
            f"{item.get('provider')}={item.get('count', 0)}"
            for item in result.get("providers", [])
        )
        logger.warning(
            "AMZ catalog status=%s total=%d Windows=%d Android=%d providers=[%s]",
            result.get("status"), total, windows, android, provider_summary,
        )
    except Exception:
        logger.exception("AMZ initial catalog status check failed")
    while True:
        try:
            if _is_due():
                result = await run_sync()
                total = db.list_software(limit=1)[1]
                windows = db.list_software(os_name="Windows", limit=1)[1]
                android = db.list_software(os_name="Android", limit=1)[1]
                logger.warning(
                    "AMZ catalog refreshed: status=%s total=%d Windows=%d Android=%d",
                    result.get("status"), total, windows, android,
                )
        except Exception:
            logger.exception("Sync scheduler iteration failed")
        await asyncio.sleep(max(60, int(os.getenv("AMZ_SYNC_CHECK_SECONDS", "1800"))))
