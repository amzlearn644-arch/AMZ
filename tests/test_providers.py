import unittest
from unittest.mock import AsyncMock, patch

from app.providers.fdroid import FdroidProvider
from app.providers.json_feed import JsonFeedProvider


class FeedProviderTests(unittest.IsolatedAsyncioTestCase):
    def test_json_feed_requires_https(self):
        with self.assertRaises(ValueError):
            JsonFeedProvider("test", "http://not-secure.example/feed.json")

    async def test_json_feed_normalizes_and_rejects_unsafe_urls(self):
        provider = JsonFeedProvider("Test", "https://catalog.example/feed.json")
        response = type("Response", (), {
            "raise_for_status": lambda self: None,
            "json": lambda self: {"items": [{
                "id": "org.example.app", "name": "Example", "download_url": "javascript:alert(1)",
                "homepage_url": "https://example.org/", "icon_url": "http://example.org/icon.png",
            }]},
        })()
        class Client:
            get = AsyncMock(return_value=response)
            async def __aenter__(self):
                return self
            async def __aexit__(self, *args):
                return None
        with patch("app.providers.json_feed.httpx.AsyncClient", return_value=Client()):
            records = await provider.fetch()
        self.assertEqual(records[0]["download_url"], "")
        self.assertEqual(records[0]["homepage_url"], "https://example.org/")
        self.assertEqual(records[0]["icon_url"], "")

    async def test_json_feed_supports_nested_mapping_and_list_payload(self):
        provider = JsonFeedProvider(
            "Mapped", "https://catalog.example/feed", records_path="",
            mapping={"id": "meta.id", "name": "title", "summary": "details.short", "icon_url": "assets.icon"},
        )
        response = type("Response", (), {
            "raise_for_status": lambda self: None,
            "json": lambda self: [{"meta": {"id": "pkg-1"}, "title": "Mapped App", "details": {"short": "Summary"}, "assets": {"icon": "https://img.example/icon.png"}}],
        })()
        class Client:
            get = AsyncMock(return_value=response)
            async def __aenter__(self):
                return self
            async def __aexit__(self, *args):
                return None
        with patch("app.providers.json_feed.httpx.AsyncClient", return_value=Client()):
            records = await provider.fetch()
        self.assertEqual(records[0]["name"], "Mapped App")
        self.assertEqual(records[0]["summary"], "Summary")
        self.assertEqual(records[0]["icon_url"], "https://img.example/icon.png")

    async def test_json_feed_supports_slug_ids_and_structured_platforms(self):
        provider = JsonFeedProvider(
            "EveryVersion", "https://catalog.example/feed", records_path="apps",
            mapping={
                "id": "slug", "slug": "slug", "name": "name", "summary": "summary",
                "category": "category", "os": "platforms", "version": "latest_versions",
                "homepage_url": "url", "download_url": "installer_url",
            },
        )
        response = type("Response", (), {
            "raise_for_status": lambda self: None,
            "json": lambda self: {"apps": [{
                "slug": "7-zip", "name": "7-Zip", "platforms": ["windows", "macos"],
                "latest_versions": {"windows": "26.06", "macos": "26.06"},
                "url": "https://catalog.example/7-zip/",
            }]},
        })()
        class Client:
            get = AsyncMock(return_value=response)
            async def __aenter__(self):
                return self
            async def __aexit__(self, *args):
                return None
        with patch("app.providers.json_feed.httpx.AsyncClient", return_value=Client()):
            records = await provider.fetch()
        self.assertEqual(records[0]["source_id"], "7-zip")
        self.assertEqual(records[0]["os"], "windows, macos")
        self.assertEqual(records[0]["version"], "windows: 26.06, macos: 26.06")
        self.assertEqual(records[0]["homepage_url"], "https://catalog.example/7-zip/")
        self.assertEqual(records[0]["download_url"], "")

    async def test_json_feed_offset_pagination_aggregates_pages_and_stops_at_total(self):
        provider = JsonFeedProvider(
            "OpenUse", "https://api.openuse.app/v1/public/apps", records_path="data",
            mapping={"id": "slug", "slug": "slug", "name": "name", "summary": "tagline"},
            pagination={
                "mode": "offset", "page_size": 2, "max_pages": 5,
                "offset_param": "offset", "limit_param": "limit", "total_path": "meta.total",
            },
        )
        payloads = [
            {"data": [{"slug": "app-1", "name": "App 1"}, {"slug": "app-2", "name": "App 2"}], "meta": {"total": 3}},
            {"data": [{"slug": "app-3", "name": "App 3"}], "meta": {"total": 3}},
        ]
        responses = [type("Response", (), {
            "raise_for_status": lambda self: None,
            "json": lambda self, payload=payload: payload,
        })() for payload in payloads]

        class Client:
            def __init__(self):
                self.get = AsyncMock(side_effect=responses)
            async def __aenter__(self):
                return self
            async def __aexit__(self, *args):
                return None

        client = Client()
        with patch("app.providers.json_feed.httpx.AsyncClient", return_value=client):
            records = await provider.fetch()
        self.assertEqual([record["source_id"] for record in records], ["app-1", "app-2", "app-3"])
        self.assertEqual(client.get.await_count, 2)
        self.assertEqual(client.get.await_args_list[0].kwargs["params"], {"offset": 0, "limit": 2})
        self.assertEqual(client.get.await_args_list[1].kwargs["params"], {"offset": 2, "limit": 2})

    async def test_json_feed_offset_pagination_obeys_page_limit(self):
        provider = JsonFeedProvider(
            "Paged", "https://catalog.example/feed", records_path="items",
            mapping={"id": "id", "name": "name"},
            pagination={"mode": "offset", "page_size": 999, "max_pages": 99},
        )
        response = type("Response", (), {
            "raise_for_status": lambda self: None,
            "json": lambda self: {"items": [{"id": f"app-{i}", "name": f"App {i}"} for i in range(100)]},
        })()
        class Client:
            def __init__(self):
                self.get = AsyncMock(return_value=response)
            async def __aenter__(self):
                return self
            async def __aexit__(self, *args):
                return None

        client = Client()
        with patch("app.providers.json_feed.httpx.AsyncClient", return_value=client):
            records = await provider.fetch()
        self.assertEqual(client.get.await_count, 20)
        self.assertEqual(client.get.await_args_list[0].kwargs["params"], {"offset": 0, "limit": 100})
        self.assertEqual(client.get.await_args_list[-1].kwargs["params"], {"offset": 1900, "limit": 100})
        self.assertEqual(len(records), 1000)

    async def test_fdroid_provider_maps_index_without_downloading_apk(self):
        provider = FdroidProvider()
        payload = {"packages": {"org.example.app": {
            "name": "Example App", "summary": "A test app", "description": "Description", "categories": ["Internet"],
            "packages": [{"apkName": "org.example.app_1.apk", "versionName": "1.0"}],
        }}}
        response = type("Response", (), {"raise_for_status": lambda self: None, "json": lambda self: payload})()
        class Client:
            get = AsyncMock(return_value=response)
            async def __aenter__(self):
                return self
            async def __aexit__(self, *args):
                return None
        with patch("app.providers.fdroid.httpx.AsyncClient", return_value=Client()):
            records = await provider.fetch()
        self.assertEqual(records[0]["os"], "Android")
        self.assertEqual(records[0]["version"], "1.0")
        self.assertEqual(records[0]["download_url"], "https://f-droid.org/repo/org.example.app_1.apk")
        self.assertEqual(len(records), 1)


if __name__ == "__main__":
    unittest.main()
