from .fdroid import FdroidProvider
from .json_feed import JsonFeedProvider
from .winget import WingetProvider


def configured_providers():
    """Return only explicitly enabled or configured metadata providers."""
    import os

    providers = [WingetProvider()]
    if os.getenv("AMZ_ENABLE_FDROID", "0") == "1":
        providers.append(FdroidProvider())
    for name, label in (("WINGET_FEED_URL", "Winget"), ("OPENUSE_FEED_URL", "OpenUse")):
        url = os.getenv(name, "").strip()
        if url:
            providers.append(JsonFeedProvider(label, url))
    from .. import db
    import json
    for source in db.get_api_sources_with_secrets():
        try:
            mapping = json.loads(source["mapping"] or "{}")
            pagination = json.loads(source.get("pagination") or "{}")
            if not isinstance(mapping, dict) or not all(isinstance(k, str) and isinstance(v, str) for k, v in mapping.items()):
                continue
            if not isinstance(pagination, dict):
                continue
            providers.append(JsonFeedProvider(
                source["name"], source["url"], source["token"],
                source.get("records_path", "items"), mapping, pagination,
            ))
        except (ValueError, TypeError):
            continue
    return providers
