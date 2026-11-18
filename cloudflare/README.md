# استقرار AMZ روی Cloudflare

این پوشه مسیر استقرار جداگانه است. برنامه اصلی ویندوز (app/ و SQLite) عمداً تغییر نکرده است.

## معماری
- Python Workers با D1 برای دیتابیس
- Cloudflare Static Assets برای رابط سبک
- Cron Trigger هر ۶ ساعت (UTC)
- فایل نصب روی AMZ میزبانی یا پراکسی نمی‌شود؛ لینک منبع اصلی باز می‌شود.

## راه‌اندازی
1. در Cloudflare یک D1 با نام amz-catalog بساز.
2. مقدار database_id در wrangler.jsonc را با شناسه واقعی جایگزین کن.
3. از ریشه مخزن اجرا کن:
   ```bash
   npx wrangler d1 migrations apply amz-catalog --remote
   ```
4. توکن مدیریت را ثبت کن:
   ```bash
   cd cloudflare
   npx wrangler secret put AMZ_ADMIN_TOKEN
   ```
5. وابستگی‌ها و اجرای محلی:
   ```bash
   uv sync
   uv run pywrangler dev
   ```
6. انتشار:
   ```bash
   uv run pywrangler deploy
   ```

## API عمومی
- GET /health
- GET /api/software?q=firefox&os=Windows&limit=48&offset=0
- GET /api/software-suggestions?q=fi
- GET /api/software/{slug}
- GET /api/sync-status

## API مدیریت
تمام مسیرهای /admin/api/* به هدر زیر نیاز دارند:
Authorization: Bearer <AMZ_ADMIN_TOKEN>

- GET /admin/api/stats
- POST /admin/api/software (JSON)
- DELETE /admin/api/software/{slug}

توکن را هرگز داخل JavaScript یا HTML عمومی قرار نده. برای پنل گرافیکی مدیریت، Cloudflare Access را روی مسیر /admin/* فعال کن و رابط مدیریتی امن را بعداً متصل کن.

## محدودیت‌های فعلی
- D1 schema، API عمومی و API مدیریت اولیه آماده شده‌اند.
- رابط عمومی اولیه آماده است.
- Cron در پیکربندی ثبت شده، اما اجرای sync خودکار providerها هنوز پیاده‌سازی نشده است. تابع scheduled فعلاً فقط log می‌زند.
- انتقال اطلاعات SQLite فعلی به D1 هنوز انجام نشده؛ باید export/import کنترل‌شده و تطبیق تعداد رکوردها انجام شود.
- پنل HTML مدیریت برنامه محلی هنوز منتقل نشده است.
- این نسخه هنوز آماده انتشار عمومی نیست؛ قبل از انتشار باید تست end-to-end، انتقال داده و provider sync تکمیل شود.
