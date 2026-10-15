# Hermes Mobile | هرمس موبایل

**Android · ARM64 · 2.4.0**

[**دانلود فایل نصبی / Download APK — 2.4.0**](https://github.com/HELBOYCODER/hermes-mobile/releases/download/v2.4.0/hermes-mobile-2.4.0-arm64.apk)

[جزئیات نسخه / Release details](https://github.com/HELBOYCODER/hermes-mobile/releases/tag/v2.4.0)

## فارسی

هرمس موبایل یک اپ اندرویدی برای گفت‌وگو با ایجنت هوشمند، ابزارها، حافظهٔ ماندگار، اسکیل‌ها و محیط لینوکس داخلی است. این پروژه ارتباط رسمی با Nous Research ندارد.

### نصب

1. فایل APK بالا را روی گوشی ARM64 دانلود کنید و اجازهٔ نصب از این منبع را در تنظیمات اندروید بدهید.
2. برای Nous/Hermes از داخل برنامه وارد حساب Nous Portal شوید؛ این مسیر به کلید API نیاز ندارد.
3. مدل را انتخاب کنید. دسترسی به مدل‌های پولی به اشتراک، اعتبار و مجوز حساب شما وابسته است.
4. برای پنل محلی 9Router، برنامه را باز نگه دارید و به `http://127.0.0.1:20128` بروید. رمز پنل در **Settings → 9Router** قابل نمایش و کپی است.

اگر اندروید به‌دلیل تفاوت امضای نسخهٔ قبلی اجازهٔ به‌روزرسانی نداد، پیش از حذف نسخهٔ قبلی از اطلاعات مهم خود پشتیبان بگیرید.

### تغییرات این نسخه

- صفحهٔ اصلی تنظیمات با جست‌وجو، دسته‌بندی و آیکون؛ هر بخش صفحهٔ جدا دارد.
- منوی اصلی در گروه‌های گفت‌وگو، توسعه، اتوماسیون و برنامه سازمان‌دهی شده است.
- دکمه‌های پیام فقط **Copy** و **Retry** هستند.
- فضای Coding workspace برای انتخاب مسیر پروژه و آماده‌سازی درخواست بررسی ریپو، پیاده‌سازی، بازبینی و رفع تست ناموفق.
- MCP محلی **Hermes Code** با ابزارهای نمای پروژه، جست‌وجوی متن و بررسی تغییرات Git؛ بدون کلید خارجی، در `/root/projects` و فقط برای خواندن.
- اسکیل‌های پیش‌فرض explore-repository، implement-feature، review-code و verify-changes در کنار اسکیل‌های قبلی دیباگ و تست.
- تنظیم آمادهٔ Context7 و GitHub MCP به‌صورت غیرفعال؛ کلید را در Environment ذخیره کنید و سپس سرور را فعال کنید.

### امکانات

- ایجنت، ابزارها، حافظهٔ ماندگار و اسکیل‌ها.
- Bot Mode با شخصیت، حافظه و مدل برای هر بات.
- تایپ صوتی با پاز، نگهداری متن تشخیص‌داده‌شده و ادامهٔ ضبط به همان متن.
- ورود Nous Portal و نمایش کاتالوگ مدل‌های رایگان و پولی.
- سرور داخلی 9Router و نمایش/کپی رمز داشبورد.
- Alpine و PRoot همراه Node.js، npm، Python، pip، Git و گواهی‌های TLS.
- پیوست فایل و تصویر.

نصب Termux یا روت لازم نیست. مدل‌های آنلاین، ورود حساب و تشخیص گفتار به اینترنت نیاز دارند.

### وضعیت OpenCode Free

در نسخهٔ ۲.۴.۰ کاتالوگ OpenCode Free از OpenCode Zen جدا شده است، اما نمایش مدل در فهرست به معنی قابل استفاده بودن آن نیست. خطای گزارش‌شدهٔ `403 FreeTierError` می‌گوید سرویس رایگان فقط از داخل OpenCode قابل استفاده است. موفقیت درخواست واقعی این مدل‌ها از هرمس تأیید نشده است؛ این نسخه رفع آن محدودیت را تضمین نمی‌کند.

### تصاویر داخل برنامه

اسکرین‌شات واقعی از این نسخه هنوز به ریپو اضافه نشده است.

## English

Hermes Mobile is an Android app for AI conversations, agent tools, persistent memory, skills, and an embedded Linux environment. This project is not officially affiliated with Nous Research.

### Installation

1. Download the ARM64 APK above and allow installation from this source in Android settings.
2. Sign in to Nous Portal inside the app for the Nous/Hermes provider. This provider uses account login rather than a manually entered API key.
3. Select a model. Paid access depends on your account subscription, credit, and permissions.
4. Keep the app open to use the local 9Router dashboard at `http://127.0.0.1:20128`. Display or copy its password in **Settings → 9Router**.

If Android rejects an update because of a signing-key mismatch, back up important data before uninstalling the previous app.

### Changes in this version

- Searchable settings hub with grouped icon rows and separate detail pages.
- Main navigation grouped into Conversations, Development, Automation & connections, and App.
- English-only **Copy** and **Retry** actions.
- Coding workspace with project path and reviewable draft workflows for exploration, implementation, review and debugging.
- Bundled **Hermes Code** MCP: read-only project overview, literal source search and Git changes inside `/root/projects`; no external account or package download.
- Four new bundled skills: explore-repository, implement-feature, review-code and verify-changes.
- Disabled-by-default Context7 documentation and official GitHub read-only MCP presets. Set the required key in Environment, then enable. These external integrations require internet and have not been verified with a live account here.

[Context7 setup](https://context7.com/docs/resources/all-clients) · [GitHub MCP documentation](https://github.com/github/github-mcp-server/blob/main/docs/remote-server.md)

### Features

- Agent tools, persistent memory, and skills.
- Bot Mode with separate personalities, memories, and models.
- Voice pause/resume that preserves recognized text and appends new dictation.
- Nous Portal login with a free and paid model catalog.
- Embedded 9Router with a visible, copyable dashboard password.
- Alpine Linux through PRoot, with Node.js, npm, Python, pip, Git, and TLS certificates bundled.
- File and image attachments.

No Termux installation or root access is required. Online models, account login, and speech recognition require internet access.

### OpenCode Free status

Version 2.4.0 separates OpenCode Free from OpenCode Zen in the catalog. A listed model is not proof that inference works. The reported `403 FreeTierError` states that the free tier can only be used within OpenCode. Successful inference through Hermes has not been verified; this APK does not promise to resolve that restriction.

### In-app screenshots

Real screenshots of this version have not yet been added.

## Download integrity / صحت فایل

File: `hermes-mobile-2.4.0-arm64.apk`
The APK is built and published by GitHub Actions. See the release asset for its SHA-256 digest.
