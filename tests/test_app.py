import tempfile
import unittest
from pathlib import Path

from fastapi.testclient import TestClient

from app import db
from app.main import app


class CatalogSmokeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.original_db_path = db.DB_PATH
        db.DB_PATH = Path(self.temp.name) / "test.sqlite3"

    def tearDown(self):
        db.DB_PATH = self.original_db_path
        self.temp.cleanup()

    def test_public_pages_and_health(self):
        with TestClient(app) as client:
            self.assertEqual(client.get("/").status_code, 200)
            self.assertEqual(client.get("/health").status_code, 200)
            self.assertEqual(client.get("/robots.txt").status_code, 200)
            self.assertEqual(client.get("/sitemap.xml").status_code, 200)
            self.assertEqual(client.get("/favicon.ico", follow_redirects=True).status_code, 200)

    def test_catalog_detail_and_suggestions(self):
        db.init_db()
        self.assertIsNotNone(db.get_software("firefox"))
        self.assertTrue(db.search_suggestions("Mo"))
        with TestClient(app) as client:
            self.assertEqual(client.get("/software/firefox").status_code, 200)
            self.assertEqual(client.get("/", params={"os": "Android"}).status_code, 200)


if __name__ == "__main__":
    unittest.main()
