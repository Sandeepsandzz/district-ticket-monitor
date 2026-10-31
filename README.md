# District Movie Ticket & Theatre Notifier (Vizag Edition)

A cloud-ready **Java 21 + Spring Boot 3.3** microservice integrated with an **Interactive Telegram Bot** exclusively monitoring movie tickets and theatre releases in **Visakhapatnam (Vizag)** on [District by Zomato Movies](https://www.district.in/movies/).

## Key Features & Highlights
1. **Dedicated to Visakhapatnam (Vizag)**:
   - Scrapes movie showtimes and releases strictly for Vizag. No need to select a city!
2. **100% Button-Driven Interactive Telegram UI**:
   - Tap `/start` or `/menu` in Telegram to launch the inline keyboard wizard.
   - **Movie Selection Buttons**: Dynamically loads real, current Vizag movies directly from District (`Jailer 2`, `Ramayana`, `Cult`, `Ranabaali`, `Killer`, etc.).
   - **Vizag Theatre Buttons**: Quick selection for top Vizag cinemas:
     - `INOX Varun Beach`
     - `INOX CMR Central`
     - `Cinepolis Sreekanya`
     - `INOX Chitralayaa`
     - `Kameswari / Kinnera`
     - `AAA Cinemas`
     - `Asian Mukta A2 (Gajuwaka)`
     - `Jagadamba / Sarat`
     - `Any Theatre in Vizag (City-Wide)`
   - **Date, Language & Format Buttons**: One-tap buttons for `Today`, `Tomorrow`, `Any Date`, `Telugu`, `Hindi`, `English`, `IMAX`, `Dolby Atmos`, `3D`, `2D`.
   - **Cancel Button**: Available on every step so you never get stuck.
3. **Zero False Positives**:
   - Parses District session data and Schema.org `ScreeningEvent` records.
   - Unknown date, language, or format values do not satisfy a specific filter; generic seat-layout links alone do not trigger alerts.
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
- Generate `API_ADMIN_TOKEN` with a cryptographically secure random value and store it only in the service environment.

Do not commit bot tokens or admin secrets to the repository. If a bot token has appeared in source or logs, revoke it with BotFather and configure the replacement through the environment.
