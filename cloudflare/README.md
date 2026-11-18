# استقرار AMZ روی Cloudflare

این شاخه مسیر استقرار جداگانه است؛ برنامهٔ محلی ویندوز و SQLite تغییر نمی‌کند.

## معماری و امکانات
- Python Workers برای API و اجرای همگام‌سازی
- Cloudflare D1 برای ذخیرهٔ کاتالوگ، منابع API و وضعیت sync
- Static Assets برای سایت فارسی RTL و پنل مدیریت
- Cron Trigger هر ۶ ساعت (UTC)
- فایل نصب روی AMZ میزبانی یا پراکسی نمی‌شود؛ لینک HTTPS منبع اصلی نمایش داده می‌شود.
- منابع همگام‌سازی: OpenUse، Winget (مجموعهٔ محدود و منتخب)، F-Droid و JSON Feedهای سفارشی با offset pagination.

## راه‌اندازی
1. در Cloudflare یک D1 با نام `amz-catalog` بساز و شناسهٔ آن را در `wrangler.jsonc` جایگزین `REPLACE_WITH_D1_DATABASE_ID` کن.
2. از پوشهٔ `cloudflare` اجرا کن:
   ```bash
   npx wrangler d1 migrations apply amz-catalog --remote
   npx wrangler secret put AMZ_ADMIN_TOKEN
   uv sync
   uv run pywrangler dev
   ```
3. پس از تست محلی، انتشار:
   ```bash
   uv run pywrangler deploy
   ```

## پنل مدیریت و امنیت
- پنل مدیریت: `/admin/`
- تمام APIهای مدیریت به `Authorization: Bearer <AMZ_ADMIN_TOKEN>` نیاز دارند.
- توکن مدیریت داخل HTML/JS قرار ندارد؛ مدیر آن را در مرورگر وارد می‌کند و تا پایان نشست در `sessionStorage` نگه‌داری می‌شود.
- **قبل از انتشار عمومی، Cloudflare Access را روی مسیر `/admin/*` فعال کن.** توکن را در GitHub یا کد عمومی قرار نده.
- توکن منبع‌های سفارشی فعلاً در ستون محافظت‌شدهٔ D1 ذخیره می‌شود و از API پنل برگردانده نمی‌شود؛ فقط منابع قابل‌اعتماد و توکن‌های محدود/قابل‌تعویض اضافه کن.

## API عمومی
- `GET /health`
- `GET /api/software?q=firefox&os=Windows&limit=48&offset=0`
- `GET /api/software-suggestions?q=fi`
- `GET /api/software/{slug}`
- `GET /api/sync-status`

## API مدیریت
- `GET /admin/api/stats`
- `POST /admin/api/software`
- `PUT /admin/api/software/{slug}`
- `DELETE /admin/api/software/{slug}`
- `GET|POST /admin/api/sources`
- `PUT|DELETE /admin/api/sources/{id}`
- `POST /admin/api/sync` با بدنهٔ اختیاری `{"source_id": 1}` برای اجرای یک منبع سفارشی

## انتقال کاتالوگ SQLite محلی
روی رایانه‌ای که فایل SQLite اصلی در دسترس است:
```bash
python tools/export_sqlite_to_sql.py --db ../data/amz.sqlite3 --out import_catalog.sql
npx wrangler d1 execute amz-catalog --remote --file=import_catalog.sql
```
فایل خروجی توکن‌های API را حذف می‌کند. فایل SQL را commit یا عمومی نکن.

## وضعیت و محدودیت‌ها
- Cron اکنون اجرای واقعی provider sync را فراخوانی می‌کند؛ شکست یک منبع باعث حذف رکوردهای قبلی همان منبع نمی‌شود.
- Winget در این نسخه فقط بسته‌های منتخب را می‌خواند تا مصرف API و زمان اجرا کنترل شود.
- زمان‌بندی Cron بر اساس UTC است.
- **این نسخه هنوز در حساب Cloudflare واقعی اجرا و end-to-end تست نشده است.** پیش از انتشار باید با D1 واقعی آزمایش شود، تعداد رکوردها و گزارش‌ها بررسی شوند، دسترسی به منابع خارجی تأیید شود و Cloudflare Access تنظیم گردد.
- انتقال دیتابیس SQLite محلی به D1 هنوز انجام نشده؛ تا وقتی فایل SQLite در دسترس نباشد، رکوردهای فعلی محلی منتقل نمی‌شوند.
