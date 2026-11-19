from .fdroid import FdroidProvider
from .json_feed import JsonFeedProvider
from .homebrew import HomebrewProvider
from .scoop import ScoopProvider
from .winget import WingetProvider


def configured_providers():
    """Return the public metadata catalogs enabled for AMZ."""
    import os

    providers = [WingetProvider(), ScoopProvider(), HomebrewProvider()]
    if os.getenv("AMZ_ENABLE_FDROID", "1") == "1":
        providers.append(FdroidProvider())
    if os.getenv("AMZ_ENABLE_FDROID_ARCHIVE", "1") == "1":
        providers.append(FdroidProvider(
            name="F-Droid Archive",
            index_url=os.getenv(
                "FDROID_ARCHIVE_INDEX_URL",
                "https://f-droid.org/archive/index-v1.json",
            ),
            max_items=int(os.getenv("FDROID_ARCHIVE_MAX_ITEMS", "1000000")),
        ))
    if os.getenv("AMZ_ENABLE_IZZY", "1") == "1":
        providers.append(FdroidProvider(
            name="IzzyOnDroid",
            index_url=os.getenv(
                "IZZY_INDEX_URL",
                "https://apt.izzysoft.de/fdroid/repo/index-v1.json",
            ),
            max_items=int(os.getenv("IZZY_MAX_ITEMS", "1500")),
        ))
    for name, label in (("WINGET_FEED_URL", "Winget custom"), ("OPENUSE_FEED_URL", "OpenUse")):
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
