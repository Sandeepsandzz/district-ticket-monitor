# District Movie Ticket & Theatre Notifier (Vizag Edition)

A cloud-ready **Java 21 + Spring Boot 3.3** microservice integrated with an **Interactive Telegram Bot** exclusively monitoring movie tickets and theatre releases in **Visakhapatnam (Vizag)** on [District by Zomato Movies](https://www.district.in/movies/).

## Key Features & Highlights
1. **Dedicated to Visakhapatnam (Vizag)**:
   - Scrapes movie showtimes and releases strictly for Vizag. No need to select a city!
2. **100% Button-Driven Interactive Telegram UI**:
   - Tap `/start` or `/menu` in Telegram to launch the inline keyboard wizard.
   - **Movie Selection Buttons**: Dynamically loads real, current Vizag movies directly from District (`Jailer 2`, `Ramayana`, `Cult`, `Ranabaali`, `Killer`, etc.).
   - **Upcoming movies**: Browse District's paginated nationwide upcoming releases, then choose a Vizag theatre, screen, and date to watch for ticket sales. A listing does not guarantee that the movie will screen in Vizag. Where District has not published theatres or screens yet, suggestions are unverified.
   - **Vizag Theatre Buttons**: The selected movie's theatres are loaded from District, with a Vizag cinema roster fallback when bookings have not opened yet.
   - **Exact watch wizard**: Choose `Released Movie → Vizag Theatre → Screen → Date`.
   - **Cancel Button**: Available on every step so you never get stuck.
3. **Exact theatre/screen/date matching**:
   - Parses District session data and Schema.org `ScreeningEvent` records.
   - Alerts fire only when District exposes a session for the selected movie, theatre, screen, and date; generic movie or seat-layout links alone do not trigger alerts.
4. **Duplicate Alert Protection**:
   - Prevents creating duplicate alerts for the same movie, theatre, and date.
5. **Interactive Persistent Alerts**:
   - Includes **`🎟 Book Now on District`** with direct seat-layout URLs.
   - **`🛑 Acknowledge & Stop Alerts`** and **`⏸ Snooze 15m`** with instant button state feedback.

---

## Running in Antigravity IDE

1. Set `TELEGRAM_BOT_TOKEN` to the bot token from BotFather. The application no longer has a token fallback in source.
2. Run `DistrictTicketMonitorApplication.java` (or `mvn spring-boot:run`).
3. The server runs on port **8082**.
4. Open [@district_monitot_bot](https://t.me/district_monitot_bot) in Telegram and send `/start` to begin.

## Runtime configuration

- `TELEGRAM_BOT_TOKEN` is required for Telegram polling and notifications.
- `TELEGRAM_POLLING_ENABLED` defaults to `true`. Run one application instance when using `getUpdates`; multiple pollers conflict.
- Set `TELEGRAM_POLLING_ENABLED=false` when you configure Telegram webhooks.
- `API_ADMIN_TOKEN` is required in the `X-Admin-Token` header for the `/api/watches` management endpoints. Without it, those endpoints reject requests.
- `TELEGRAM_WEBHOOK_SECRET` is required in Telegram's `X-Telegram-Bot-Api-Secret-Token` header before `/api/telegram/webhook` accepts updates.
- `H2_CONSOLE_ENABLED` defaults to `false`.
- The database uses `DATA_DIR` (defaults to `./data`). For Render, attach a persistent disk mounted at `/app/data` and set `DATA_DIR=/app/data`; without persistent storage, watches can be lost on restart or redeploy. Keep this service to one instance while using the file-backed H2 database.
- To use external PostgreSQL, set `JDBC_DATABASE_URL` to the provider's **JDBC** URL (for example `jdbc:postgresql://HOST:5432/DATABASE?sslmode=require`), plus `DATABASE_USERNAME` and `DATABASE_PASSWORD`. Follow the provider's TLS settings. Store credentials only in Render environment variables, never in source or chat. If the database cannot be reached, startup fails instead of silently switching to an empty H2 database.
- Generate `API_ADMIN_TOKEN` with a cryptographically secure random value and store it only in the service environment.

## Web interface

Open `/` on the same deployed service and send `/link` to the bot in a private Telegram chat. Paste the one-time code into the website within 10 minutes. Your browser can then create, view, pause, resume, snooze, delete, and check the same alerts used by Telegram. Notifications continue to be delivered to the linked chat.

The code is single-use and the browser session lasts up to 24 hours. Both are held in server memory, so a restart or redeploy requires linking again; the browser also forgets its session when its tab closes. Never share a linking code. If District has not published theatres or screens, the site labels fallback suggestions as unverified. Like Telegram watches, saved alerts need persistent storage to survive a Render redeploy.

## Telegram watch management

After creating a watch, use its numeric ID with these commands:

- `/mywatches` — list active and paused watches.
- `/pause ID` — pause a watch without deleting it.
- `/resume ID` — resume a paused watch and reset its alert cooldown.
- `/snooze ID MINUTES` — temporarily silence a watch (1 to 1440 minutes).
- `/delete ID` — delete one watch.
- `/check` — check your watches immediately.
- `/clear` — delete all watches after confirmation.

The watch wizard intentionally has no price, seat-count, language, or format filters. Its purpose is to notify you when tickets open for one exact movie, theatre, screen, and date.

Use `/once ID` for one accepted alert or `/repeat ID` for recurring reminders. The website also lets you choose this per watch. Repeating reminders stop at the configured `MAX_ALERTS_PER_WATCH` and respect `REPEAT_ALERT_MINS`; expired show dates are no longer polled. A one-time watch can still retry other channels after one provider accepts the alert.

Each watch shows its last check and an outcome. `UNKNOWN` means no bookable sessions were parsed and should not be read as proof of sold-out tickets. Alert history shows the last 50 detected events, showtimes, and per-channel acceptance or retry status. Provider acceptance does not prove delivery to a device. Failed channels retry independently with backoff while the event is fresh; a stale event is checked again before generating another notification. Network interruptions around a successful send may still cause a duplicate.

When several watches refer to the same movie page and date in one polling cycle, the fetched page is reused. Watch checks do not run after the selected date in India time.

### Migrating existing watches

Export the current `/api/admin/watches` JSON **before** changing the database or redeploying on ephemeral storage. The admin dashboard also provides Export and Import buttons after this version is deployed. Keep the JSON private because it contains chat IDs. After configuring PostgreSQL and deploying, import the JSON using the new dashboard or `POST /api/admin/watches/import` with `X-Admin-Token`. Import skips matching watches and does not overwrite existing records. The target database assigns new watch IDs, so buttons on old Telegram messages will no longer refer to the imported watches; use `/mywatches` for the new IDs. Import copies watch definitions and state, not historical alert events. Verify the imported list before deleting any old data.

## Optional WhatsApp test alerts

WhatsApp is disabled by default. This integration supports a single configured recipient and only sends watches belonging to one specified private Telegram chat; it does not route different users to different WhatsApp numbers.

1. Create a Meta developer app with the WhatsApp product and use its test WhatsApp Business account and test sender. In Meta's dashboard, add and verify the WhatsApp recipient number you want to test. Consult Meta's [Cloud API getting-started collection](https://www.postman.com/meta/whatsapp-business-platform/documentation/wlk6lh4/whatsapp-cloud-api) for the access token, sender **phone number ID** (not the phone number itself), and Graph API version. Meta test credentials can expire.
2. In a private Telegram conversation with your bot, send `/chatid` to obtain your owner chat ID. Do not post it publicly.
3. In Render's environment settings, configure `WHATSAPP_ENABLED=true`, `WHATSAPP_ACCESS_TOKEN`, `WHATSAPP_API_VERSION` (for example `v22.0`, using the version offered by Meta), `WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_RECIPIENT` (country code and digits only, for example `919876543210`), `WHATSAPP_OWNER_CHAT_ID`, `WHATSAPP_TEMPLATE_NAME=hello_world`, and `WHATSAPP_TEMPLATE_LANGUAGE=en_US`. Never commit credentials or paste the access token into chat. Restart/redeploy if Render requires it for environment changes.
4. Once deployed, send `/whatsapptest` to the bot in that same private chat. The bot reports whether Meta accepted the request; check WhatsApp to confirm actual delivery. Meta's pre-approved `hello_world` is a generic test message **without movie details**. While configured, a matching ticket alert for the owner will also send that generic test message. Telegram continues to provide the full details.
5. For a real ticket-details message, create and have Meta approve a text template in the selected language with exactly five body variables in order: movie, theatre, screen, show date, and booking URL. For example: `Tickets available: {{1}} at {{2}}, {{3}}, on {{4}}. Book: {{5}}`. Set `WHATSAPP_TEMPLATE_NAME` to its approved name and the appropriate language code. Meta's classification and approval are not guaranteed. The `/whatsapptest` command intentionally works only with `hello_world`.

Meta's test account is for development and is not a guaranteed free production solution. Production messages and providers may charge; verify [Meta's current pricing](https://developers.facebook.com/documentation/business-messaging/whatsapp/pricing) before enabling production. Existing repeat-alert settings also apply to WhatsApp, so each repeat may be billed. A successful API request indicates acceptance, not delivery; the application does not yet track WhatsApp delivery receipts or retry independently from Telegram. Telegram remains enabled.

## Admin dashboard

Open `/admin.html` on the deployed service and enter the `API_ADMIN_TOKEN` configured in the service environment. The dashboard shows watch counts, scraper health, active/paused watches, and actions for pausing, resuming, snoozing, checking, and deleting watches.

The scraper retries failed District requests using `SCRAPER_MAX_ATTEMPTS` and `SCRAPER_RETRY_BACKOFF_MS`, then exposes the latest scraper state through the dashboard summary endpoint.

Do not commit bot tokens or admin secrets to the repository. If a bot token has appeared in source or logs, revoke it with BotFather and configure the replacement through the environment.
