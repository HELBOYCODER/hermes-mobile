# Hermes Mobile | هرمس موبایل

**Android · ARM64 · 2.5.0**

🌐 [**وب‌سایت هرمس — معرفی، راهنمای نصب و دانلود / Hermes website**](https://hermes-mobile.genialgnome10.chatgpt.site)

[پوستر فارسی / Persian poster](https://hermes-mobile.genialgnome10.chatgpt.site/assets/hermes-poster-fa.png) · [متن آمادهٔ معرفی / Share text](https://hermes-mobile.genialgnome10.chatgpt.site/assets/caption-fa.txt)

[**دانلود فایل نصبی / Download APK — 2.5.0**](https://github.com/HELBOYCODER/hermes-mobile/releases/download/v2.5.0/hermes-mobile-2.5.0-arm64.apk)

[جزئیات نسخه / Release details](https://github.com/HELBOYCODER/hermes-mobile/releases/tag/v2.5.0)

## فارسی

هرمس موبایل یک اپ اندرویدی برای گفت‌وگو با ایجنت هوشمند، ابزارها، حافظهٔ ماندگار، اسکیل‌ها و محیط لینوکس داخلی است. این پروژه ارتباط رسمی با Nous Research ندارد.

### نصب

1. فایل APK بالا را روی گوشی ARM64 دانلود کنید و اجازهٔ نصب از این منبع را در تنظیمات اندروید بدهید.
2. برای Nous/Hermes از داخل برنامه وارد حساب Nous Portal شوید؛ این مسیر به کلید API نیاز ندارد.
3. مدل را انتخاب کنید. دسترسی به مدل‌های پولی به اشتراک، اعتبار و مجوز حساب شما وابسته است.
4. برای پنل محلی 9Router، برنامه را باز نگه دارید و به `http://127.0.0.1:20128` بروید. رمز پنل در **Settings → 9Router** قابل نمایش و کپی است.

اگر اندروید به‌دلیل تفاوت امضای نسخهٔ قبلی اجازهٔ به‌روزرسانی نداد، پیش از حذف نسخهٔ قبلی از اطلاعات مهم خود پشتیبان بگیرید.

### تغییرات این نسخه

- ورود Nous پس از تأیید حساب از انتخاب مدل جدا شده است؛ خطای مدل، حساب متصل را خارج نمی‌کند. درخواست ورود ناتمام رمزگذاری می‌شود و پس از بسته‌شدن فرایند از همان مهلت قبلی ادامه می‌یابد.
- دکمهٔ **Test** کنار مدل‌ها، بدون ارسال تاریخچه یا تغییر مدل فعال؛ مدل‌های پولی پیش از تست تأیید هزینه می‌گیرند.
- ابزارهای چت، فایل‌های همان گفتگو، تاریخ‌بندی نشست‌ها و نشانگر پردازش ساده‌تر.
- گردش کار مبتنی بر قالب‌های Spec Kit، دستورالعمل‌های کدنویسی/دیباگ Superpowers، وظایف ماندگار و شواهد واقعی تست. تکمیل وظیفه به شناسهٔ تست موفق ذخیره‌شده نیاز دارد.
- برای ویرایش‌های ابزار فایل در پروژهٔ انتخاب‌شده checkpoint ایجاد می‌شود؛ بازیابی در صورت تغییر بعدی فایل متوقف می‌شود. تغییرات ترمینال باید با Git بررسی شوند.
- لینک عمومی یوتیوب/اینستاگرام در پیام مستقل، کپشن و اطلاعات قابل دسترس را استخراج و فایل قابل دانلود تولید می‌کند. نبود کپشن، محدودیت محتوا یا خطای سرویس صریحاً گزارش می‌شود.
- موتور بومی Whisper چندزبانه؛ مدل tiny اختیاری ۷۷٫۷ مگابایتی از **Coding → Offline transcription** دانلود و با SHA-256 بررسی می‌شود. فایل صوتی عمومی در نبود کپشن قابل تبدیل به متن است؛ تحلیل تصویر و ویدئو ادعا نمی‌شود.
- فهرست منابع، مجوزها و پیش‌نیازهای اتصال Context7، GitHub، Serena، Playwright و ابزارهای تست؛ سرویس نیازمند میزبان جداگانه به‌عنوان نصب‌شده نمایش داده نمی‌شود.


- بازیابی مستقل پس از کرش با **Open safely**؛ چت‌ها و تنظیمات پاک نمی‌شوند. ورود امن اجرای خودکار MCP، روتر و سرویس پس‌زمینه را متوقف می‌کند. برای اجرای عادی از Settings → Startup recovery استفاده کنید.
- آمار خراب باعث بسته‌شدن صفحهٔ مصرف نمی‌شود؛ خطاهای راه‌اندازی پس‌زمینه و نگه‌داشتن CPU ثبت و مهار می‌شوند.
- منوی اصلی فقط گفت‌وگو، نشست‌ها، Bot Mode، Coding و Settings را نمایش می‌دهد؛ دیگر ابزارها داخل تنظیمات هستند.
- علت دقیق کرش گوشی گزارش‌دهنده هنوز با لاگ همان گوشی تأیید نشده است. تست‌های رابط و بازیابی با Robolectric اجرا می‌شوند؛ تست دستگاه واقعی تأیید نشده است.

- صفحهٔ اصلی تنظیمات با جست‌وجو، دسته‌بندی و آیکون؛ هر بخش صفحهٔ جدا دارد.
- دکمه‌های پیام فقط **Copy** و **Retry** هستند.
- فضای Coding workspace برای انتخاب مسیر پروژه و آماده‌سازی درخواست بررسی ریپو، پیاده‌سازی، بازبینی و رفع تست ناموفق.
- MCP محلی **Hermes Code** با ابزارهای نمای پروژه، جست‌وجوی متن و بررسی تغییرات Git؛ بدون کلید خارجی، در `/root/projects` و فقط برای خواندن.
- اسکیل‌های پیش‌فرض explore-repository، implement-feature، review-code و verify-changes در کنار اسکیل‌های قبلی دیباگ و تست.
- تنظیم آمادهٔ Context7 و GitHub MCP به‌صورت غیرفعال؛ کلید را در Environment ذخیره کنید و سپس سرور را فعال کنید.

- فایل‌های خروجی داخل چت با Download، Open و Share نمایش داده می‌شوند؛ Download در Downloads/Hermes ذخیره می‌کند. خروجی‌های /root/outputs بعد از اجرای ابزار خودکار بررسی می‌شوند و ابزار send_file فایل‌های مسیرهای دیگر را پیوست می‌کند.
- لینک‌های HTTP(S) ساده و Markdown قابل لمس هستند؛ بلوک‌های کد دکمهٔ Copy code دارند.

### امکانات

- ایجنت، ابزارها، حافظهٔ ماندگار و اسکیل‌ها.
- Bot Mode با شخصیت، حافظه و مدل برای هر بات.
- تایپ صوتی با پاز، نگهداری متن تشخیص‌داده‌شده و ادامهٔ ضبط به همان متن.
- ورود Nous Portal و نمایش کاتالوگ مدل‌های رایگان و پولی.
- سرور داخلی 9Router و نمایش/کپی رمز داشبورد.
- Alpine و PRoot همراه Node.js، npm، Python، pip، Git و گواهی‌های TLS.
- پیوست فایل و تصویر.

نصب Termux یا روت لازم نیست. مدل‌های آنلاین و ورود حساب به اینترنت نیاز دارند. تبدیل صوت آفلاین پس از دانلود مدل اختیاری انجام می‌شود.

### وضعیت OpenCode Free

در نسخهٔ ۲.۴.۰ کاتالوگ OpenCode Free از OpenCode Zen جدا شده است، اما نمایش مدل در فهرست به معنی قابل استفاده بودن آن نیست. خطای گزارش‌شدهٔ `403 FreeTierError` می‌گوید سرویس رایگان فقط از داخل OpenCode قابل استفاده است. موفقیت درخواست واقعی این مدل‌ها از هرمس تأیید نشده است؛ این نسخه رفع آن محدودیت را تضمین نمی‌کند.

### تصاویر داخل برنامه

تصاویر زیر از کامپوننت‌های واقعی نسخهٔ ۲.۵.۰ با رندر خودکار Robolectric Native Graphics تهیه شده‌اند. گفت‌وگوی چت نمونه و از پیش تنظیم‌شده است؛ تصویر گوشی فیزیکی یا اثبات پاسخ‌گویی مدل نیست. [اجرای موفق ثبت تصاویر](https://github.com/HELBOYCODER/hermes-mobile/actions/runs/38765216828).

<p align="center">
  <img src="https://hermes-mobile.genialgnome10.chatgpt.site/assets/app-chat-demo.png" width="280" alt="رابط واقعی چت هرمس با گفت‌وگوی نمونه">
  <img src="https://hermes-mobile.genialgnome10.chatgpt.site/assets/app-settings-dark.png" width="280" alt="رابط واقعی تنظیمات هرمس">
</p>

### حمایت از توسعه

پشت این پروژه، ساعت‌ها توسعه، دیباگ، آزمون و هزینهٔ ابزار و زیرساخت است. اگر هرمس برایتان مفید بود، حمایت مالی اختیاری شما به ادامهٔ توسعه و رفع اشکال‌ها کمک می‌کند. دانلود برنامه به پرداخت نیاز ندارد و دونیت، اشتراک یا اعتبار مدل‌ها ایجاد نمی‌کند.

**USDT — فقط شبکهٔ BNB Smart Chain / BEP20**

```text
0x9193975EBa38d01A639dCa9202E224153D51949F
```

[QR و کپی آدرس حمایت](https://hermes-mobile.genialgnome10.chatgpt.site/#support) · [گزارش باگ و مشارکت](https://github.com/HELBOYCODER/hermes-mobile/issues)

### پوستر معرفی

پوستر زیر با هوش مصنوعی تولید شده است؛ نمای گوشی در پوستر مفهومی است و اسکرین‌شات واقعی برنامه نیست.

<p align="center"><img src="https://hermes-mobile.genialgnome10.chatgpt.site/assets/hermes-poster-fa.webp" width="500" alt="پوستر فارسی هرمس موبایل"></p>

## English

Hermes Mobile is an Android app for AI conversations, agent tools, persistent memory, skills, and an embedded Linux environment. This project is not officially affiliated with Nous Research.

### Installation

1. Download the ARM64 APK above and allow installation from this source in Android settings.
2. Sign in to Nous Portal inside the app for the Nous/Hermes provider. This provider uses account login rather than a manually entered API key.
3. Select a model. Paid access depends on your account subscription, credit, and permissions.
4. Keep the app open to use the local 9Router dashboard at `http://127.0.0.1:20128`. Display or copy its password in **Settings → 9Router**.

If Android rejects an update because of a signing-key mismatch, back up important data before uninstalling the previous app.

### Changes in this version

- Account authorization survives model-configuration failure; encrypted pending Nous device grants resume within their original expiry. MCP browser approval attempts a return to Hermes, with a manual return link.
- Inline **Test** sends a minimal synthetic request without chat history or changing selection; paid/unknown models require cost confirmation.
- Scoped chat files, date-grouped conversations and a restrained processing indicator.
- Pinned Spec Kit templates and Superpowers coding references, persistent tasks, real saved test evidence and conflict-aware file-tool checkpoints. Terminal mutations still require Git review.
- Public YouTube/Instagram link evidence with downloadable captions/metadata. Optional native multilingual Whisper tiny model (77.7 MB, SHA-256 verified) transcribes accessible audio when captions are missing. Metadata is explicitly distinguished from transcripts; visuals are not analyzed.
- Integration catalogue documents sources, licenses and setup requirements. Remote/CI tools require their own host; they are not silently installed or reported ready.


- Independent crash recovery with **Open safely**, preserving chats and settings. Safe startup skips automatic MCP, router and background service startup. Use Settings → Startup recovery to resume normal startup.
- Malformed usage data no longer crashes statistics. Background and CPU wake-lock failures are logged and handled.
- The drawer contains Chat, Sessions, Bot Mode, Coding and Settings; secondary tools are available inside Settings.
- The reported phone's exact crash cause remains unverified. UI and recovery checks use Robolectric; physical-device testing is unverified.

- Searchable settings hub with grouped icon rows and separate detail pages.
- Main navigation grouped into Conversations, Development, Automation & connections, and App.
- English-only **Copy** and **Retry** actions.
- Coding workspace with project path and reviewable draft workflows for exploration, implementation, review and debugging.
- Bundled **Hermes Code** MCP: read-only project overview, literal source search and Git changes inside `/root/projects`; no external account or package download.
- Four new bundled skills: explore-repository, implement-feature, review-code and verify-changes.
- Disabled-by-default Context7 documentation and official GitHub read-only MCP presets. Set the required key in Environment, then enable. These external integrations require internet and have not been verified with a live account here.

[Context7 setup](https://context7.com/docs/resources/all-clients) · [GitHub MCP documentation](https://github.com/github/github-mcp-server/blob/main/docs/remote-server.md)

- Generated file cards include Download, Open and Share. Downloads are saved to Downloads/Hermes. Changed files under /root/outputs are detected after tool execution; send_file attaches deliverables elsewhere. Files are snapshotted independently of working files (512 MB per attachment).
- Plain HTTP(S) links and Markdown links are clickable; code blocks include Copy code.

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

Version 2.5.0 separates OpenCode Free from OpenCode Zen in the catalog. A listed model is not proof that inference works. The reported `403 FreeTierError` states that the free tier can only be used within OpenCode. Successful inference through Hermes has not been verified; this APK does not promise to resolve that restriction.

### In-app screenshots

Real screenshots of this version have not yet been added.

## Download integrity / صحت فایل

File: `hermes-mobile-2.5.0-arm64.apk`
The APK is built and published by GitHub Actions. See the release asset for its SHA-256 digest.


### Support independent development

Hermes Mobile takes development time, debugging, testing and infrastructure costs. If it helps your work, an optional donation supports continued development. Downloading does not require payment; donations do not purchase provider subscriptions or model credit.

**USDT on BNB Smart Chain (BEP20) only:** `0x9193975EBa38d01A639dCa9202E224153D51949F`

[Donation QR and copy button](https://hermes-mobile.genialgnome10.chatgpt.site/#support). The website includes Persian introduction, version requirements, installation instructions and direct APK download. App UI images are automated renders of actual2.5.0 components with staged demo text, not physical-device screenshots. The promotional poster is AI-generated conceptual artwork.
