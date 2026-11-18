import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch

from fastapi.testclient import TestClient

from app import db
from app.main import app


APP_FORM = {
    "name": "Test App",
    "slug": "test-app",
    "summary": "A small test application",
    "description": "Used by the admin tests.",
    "category": "ابزار کاربردی",
    "os": "Windows, Linux",
    "version": "1.0",
    "homepage_url": "https://example.org/",
    "download_url": "https://example.org/download",
    "icon_url": "",
}


class AdminTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.old_path = db.DB_PATH
        db.DB_PATH = Path(self.temp_dir.name) / "admin.sqlite3"
        db.init_db()
        self.env = patch.dict(os.environ, {"AMZ_ADMIN_PASSWORD": "test-password"})
        self.env.start()
        self.client = TestClient(app, base_url="http://127.0.0.1")
        self.client.__enter__()

    def tearDown(self):
        self.client.__exit__(None, None, None)
        self.env.stop()
        db.DB_PATH = self.old_path
        self.temp_dir.cleanup()

    def login(self):
        response = self.client.post(
            "/admin/login", data={"password": "test-password"}, follow_redirects=False
        )
        self.assertEqual(response.status_code, 303)

    def test_admin_requires_login_and_login_rejects_wrong_password(self):
        response = self.client.get("/admin", follow_redirects=False)
        self.assertEqual(response.status_code, 303)
        self.assertIn("/admin/login", response.headers["location"])

        wrong = self.client.post("/admin/login", data={"password": "wrong"}, follow_redirects=False)
        self.assertEqual(wrong.status_code, 401)

        self.login()
        page = self.client.get("/admin")
        self.assertEqual(page.status_code, 200)
        self.assertIn("داشبورد", page.text)

    def test_create_edit_and_delete_software(self):
        self.login()
        created = self.client.post("/admin/software/new", data=APP_FORM, follow_redirects=False)
        self.assertEqual(created.status_code, 303)
        item = db.get_software("test-app")
        self.assertIsNotNone(item)
        self.assertEqual(item["name"], "Test App")
        self.assertEqual(item["source"], "AMZ")

        edited_form = {**APP_FORM, "name": "Test App Updated"}
        edited = self.client.post("/admin/software/test-app/edit", data=edited_form, follow_redirects=False)
        self.assertEqual(edited.status_code, 303)
        self.assertEqual(db.get_software("test-app")["name"], "Test App Updated")

        deleted = self.client.post("/admin/software/test-app/delete", follow_redirects=False)
        self.assertEqual(deleted.status_code, 303)
        self.assertIsNone(db.get_software("test-app"))

    def test_unsafe_download_url_is_rejected(self):
        self.login()
        bad = {**APP_FORM, "slug": "bad-app", "download_url": "javascript:alert(1)"}
        response = self.client.post("/admin/software/new", data=bad, follow_redirects=False)
        self.assertEqual(response.status_code, 400)
        self.assertIsNone(db.get_software("bad-app"))

    def test_multipart_uploads_are_not_accepted(self):
        self.login()
        response = self.client.post(
            "/admin/software/new",
            files={"file": ("installer.exe", b"not an installer upload")},
            data=APP_FORM,
            follow_redirects=False,
        )
        self.assertEqual(response.status_code, 415)
        self.assertIsNone(db.get_software("test-app"))

    def test_duplicate_slug_is_reported_without_crashing(self):
        self.login()
        self.assertEqual(self.client.post("/admin/software/new", data=APP_FORM).status_code, 200)
        duplicate = {**APP_FORM, "name": "Different App", "download_url": "https://different.example/download"}
        response = self.client.post("/admin/software/new", data=duplicate, follow_redirects=False)
        self.assertEqual(response.status_code, 400)
        self.assertIn("نشانی", response.text)

    def test_admin_can_create_edit_and_delete_json_api(self):
        self.login()
        payload = {
            "name": "Test Feed",
            "url": "https://catalog.example/api",
            "records_path": "data.apps",
            "token": "secret-token-for-tests",
            "page_mode": "offset",
            "page_size": "50",
            "page_max_pages": "3",
            "page_offset_param": "start",
            "page_limit_param": "size",
            "page_total_path": "meta.total",
            "map_id": "package.id",
            "map_name": "package.title",
            "map_summary": "package.summary",
            "map_description": "package.description",
            "map_category": "package.category",
            "map_os": "package.platform",
            "map_version": "package.release",
            "map_icon_url": "package.icon",
            "map_homepage_url": "package.homepage",
            "map_download_url": "package.download",
        }
        created = self.client.post("/admin/apis/new", data=payload, follow_redirects=False)
        self.assertEqual(created.status_code, 303)
        source = db.list_api_sources()[0]
        self.assertEqual(source["name"], "Test Feed")
        self.assertEqual(source["records_path"], "data.apps")
        self.assertEqual(source["pagination"], '{"mode":"offset","page_size":50,"max_pages":3,"offset_param":"start","limit_param":"size","total_path":"meta.total"}')
        edit_page = self.client.get(f"/admin/apis/{source['id']}/edit")
        self.assertNotIn("secret-token-for-tests", edit_page.text)
        self.assertIn('name="page_mode"', edit_page.text)

        updated = self.client.post(
            f"/admin/apis/{source['id']}/edit",
            data={**payload, "name": "Updated Feed", "token": ""},
            follow_redirects=False,
        )
        self.assertEqual(updated.status_code, 303)
        stored = db.get_api_sources_with_secrets()[0]
        self.assertEqual(stored["name"], "Updated Feed")
        self.assertEqual(stored["token"], "secret-token-for-tests")

        deleted = self.client.post(f"/admin/apis/{source['id']}/delete", follow_redirects=False)
        self.assertEqual(deleted.status_code, 303)
        self.assertEqual(db.list_api_sources(), [])

    def test_private_api_endpoint_is_rejected(self):
        self.login()
        response = self.client.post("/admin/apis/new", data={
            "name": "Private Feed", "url": "https://192.168.1.5/feed", "records_path": "items",
            "map_id": "id", "map_name": "name",
        }, follow_redirects=False)
        self.assertEqual(response.status_code, 400)
        self.assertEqual(db.list_api_sources(), [])

    def test_api_sync_button_targets_only_selected_feed(self):
        self.login()
        first_id = db.save_api_source("First Feed", "https://first.example/feed", "", "items", {"id": "id", "name": "name"})
        db.save_api_source("Second Feed", "https://second.example/feed", "", "items", {"id": "id", "name": "name"})
        with patch("app.admin.run_sync", new_callable=AsyncMock) as run_sync_mock:
            run_sync_mock.return_value = {"status": "ok", "providers": []}
            response = self.client.post(f"/admin/apis/{first_id}/sync", follow_redirects=False)
        self.assertEqual(response.status_code, 303)
        run_sync_mock.assert_awaited_once_with(
            force=True, provider_name="First Feed", provider_url="https://first.example/feed"
        )

    def test_cross_origin_post_is_rejected(self):
        self.login()
        response = self.client.post(
            "/admin/software/new",
            data=APP_FORM,
            headers={"Origin": "https://evil.example"},
            follow_redirects=False,
        )
        self.assertEqual(response.status_code, 403)
        self.assertIsNone(db.get_software("test-app"))

    def test_public_host_header_is_rejected_for_admin_pages(self):
        response = self.client.get("/admin/login", headers={"Host": "attacker.example"})
        self.assertEqual(response.status_code, 403)
        self.assertEqual(self.client.get("/health", headers={"Host": "attacker.example"}).status_code, 200)

    def test_loopback_ip_admin_host_is_allowed(self):
        response = self.client.get("/admin/login", headers={"Host": "127.0.0.1:8000"})
        self.assertEqual(response.status_code, 200)

    def test_post_with_public_host_is_rejected_even_without_origin(self):
        self.login()
        response = self.client.post(
            "/admin/software/new",
            data=APP_FORM,
            headers={"Host": "attacker.example"},
            follow_redirects=False,
        )
        self.assertEqual(response.status_code, 403)
        self.assertIsNone(db.get_software("test-app"))

    def test_logout_ends_the_session(self):
        self.login()
        response = self.client.post("/admin/logout", follow_redirects=False)
        self.assertEqual(response.status_code, 303)
        guarded = self.client.get("/admin", follow_redirects=False)
        self.assertEqual(guarded.status_code, 303)


class AdminDisabledTests(unittest.TestCase):
    def test_panel_stays_disabled_without_password(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {}, clear=False):
            os.environ.pop("AMZ_ADMIN_PASSWORD", None)
            old_path = db.DB_PATH
            db.DB_PATH = Path(temp) / "disabled.sqlite3"
            db.init_db()
            try:
                with TestClient(app, base_url="http://127.0.0.1") as client:
                    page = client.get("/admin/login")
                    self.assertEqual(page.status_code, 200)
                    self.assertIn("AMZ_ADMIN_PASSWORD", page.text)
                    guarded = client.get("/admin", follow_redirects=False)
                    self.assertEqual(guarded.status_code, 303)
            finally:
                db.DB_PATH = old_path


if __name__ == "__main__":
    unittest.main()
