import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch

from app import db
from app.main import safe_external_url
from app import sync


class CatalogTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.old_path = db.DB_PATH
        db.DB_PATH = Path(self.temp_dir.name) / "catalog.sqlite3"
        db.init_db()

    def tearDown(self):
        db.DB_PATH = self.old_path
        self.temp_dir.cleanup()

    def test_sample_data_and_search(self):
        items, total = db.list_software(query="Firefox")
        self.assertEqual(total, 1)
        self.assertEqual(items[0]["slug"], "firefox")

    def test_search_suggestions_match_name_prefix_and_escape_wildcards(self):
        self.assertEqual(db.search_suggestions("fi")[0]["slug"], "firefox")
        self.assertEqual(db.search_suggestions("%"), [])
        self.assertEqual(db.search_suggestions("f"), [])

    def test_os_filter_and_detail(self):
        items, _ = db.list_software(os_name="Android")
        self.assertTrue(items)
        self.assertTrue(all("Android" in item["os"] for item in items))
        self.assertEqual(db.get_software("vlc")["name"], "VLC Media Player")
        self.assertIsNone(db.get_software("not-found"))

    def test_upsert_is_idempotent(self):
        item = {"slug": "sample", "name": "Sample", "source": "test", "source_id": "1", "download_url": "https://example.org/file"}
        db.upsert_many([item])
        db.upsert_many([item])
        self.assertEqual(db.list_software(query="Sample")[1], 1)

    def test_sync_state_success_preserves_timestamp_on_error(self):
        db.set_sync_state("test", "ok", "done", success=True)
        initial = db.get_sync_states()[0]["last_success"]
        db.set_sync_state("test", "error", "offline")
        later = db.get_sync_states()[0]
        self.assertEqual(later["last_success"], initial)
        self.assertEqual(later["status"], "error")

    def test_external_links_must_be_https(self):
        self.assertEqual(safe_external_url("https://example.org/app"), "https://example.org/app")
        self.assertEqual(safe_external_url("javascript:alert(1)"), "")
        self.assertEqual(safe_external_url("https://user:pass@example.org"), "")


class SyncTests(unittest.IsolatedAsyncioTestCase):
    async def test_failed_provider_does_not_delete_old_data(self):
        provider = type("Failing", (), {"name": "offline", "fetch": AsyncMock(side_effect=OSError("offline"))})()
        old_path = db.DB_PATH
        try:
            with tempfile.TemporaryDirectory() as temp:
                db.DB_PATH = Path(temp) / "test.sqlite3"
                db.init_db()
                before = db.list_software()[1]
                with patch("app.sync.configured_providers", return_value=[provider]):
                    result = await sync.run_sync(force=True)
                self.assertEqual(result["status"], "partial")
                self.assertEqual(db.list_software()[1], before)
                self.assertEqual(db.get_sync_states()[0]["status"], "error")
        finally:
            db.DB_PATH = old_path

    async def test_manual_provider_sync_only_fetches_selected_provider(self):
        first = type("Provider", (), {"name": "first", "fetch": AsyncMock(return_value=[])})()
        second = type("Provider", (), {"name": "second", "fetch": AsyncMock(return_value=[])})()
        with patch("app.sync.configured_providers", return_value=[first, second]):
            result = await sync.run_sync(force=True, provider_name="second")
        self.assertEqual(result["status"], "ok")
        self.assertEqual([row["provider"] for row in result["providers"]], ["second"])
        first.fetch.assert_not_awaited()
        second.fetch.assert_awaited_once()

    async def test_manual_provider_sync_reports_missing_provider(self):
        provider = type("Provider", (), {"name": "available", "fetch": AsyncMock(return_value=[])})()
        with patch("app.sync.configured_providers", return_value=[provider]):
            result = await sync.run_sync(force=True, provider_name="missing")
        self.assertEqual(result["status"], "not_found")
        provider.fetch.assert_not_awaited()


if __name__ == "__main__":
    unittest.main()
