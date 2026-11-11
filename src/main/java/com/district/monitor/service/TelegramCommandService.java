package com.district.monitor.service;

import com.district.monitor.dto.WizardSession;
import com.district.monitor.dto.WizardSession.Step;
import com.district.monitor.entity.WatchRule;
import com.district.monitor.repository.WatchRuleRepository;
import com.district.monitor.scheduler.TicketWatchScheduler;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles Interactive Telegram Inline Keyboard Menus & Chat Commands for Vizag (Visakhapatnam).
 * All user actions are fully button-driven for maximum ease of use.
 */
@Service
public class TelegramCommandService {

    private final WatchRuleRepository repository;
    private final TelegramNotificationService telegramService;
    private final TicketWatchScheduler scheduler;
    private final DistrictScraperService scraperService;
    private final WebLinkService webLinkService;
    private final WhatsAppNotificationChannel whatsAppChannel;

    // Per-user interactive wizard session map
    private final Map<String, WizardSession> sessions = new ConcurrentHashMap<>();

    public TelegramCommandService(
            WatchRuleRepository repository,
            TelegramNotificationService telegramService,
            @Lazy TicketWatchScheduler scheduler,
            DistrictScraperService scraperService,
            WebLinkService webLinkService,
            WhatsAppNotificationChannel whatsAppChannel
    ) {
        this.repository = repository;
        this.telegramService = telegramService;
        this.scheduler = scheduler;
        this.scraperService = scraperService;
        this.webLinkService = webLinkService;
        this.whatsAppChannel = whatsAppChannel;
    }

    public void processTelegramUpdate(JsonNode update) {
        if (update.has("callback_query")) {
            handleCallbackQuery(update.get("callback_query"));
            return;
        }

        JsonNode message = update.has("message") ? update.get("message") : update.get("edited_message");
        if (message == null || !message.has("chat")) {
            return;
        }

        String chatId = message.get("chat").get("id").asText();
        String text = message.has("text") ? message.get("text").asText().trim() : "";

        // Handle persistent dock button taps or slash commands
        if (isCommand(text, "whatsapptest")) {
            if (!"private".equals(message.path("chat").path("type").asText())) {
                telegramService.sendMessage(chatId, "Please use /whatsapptest in a private chat.");
            } else {
                try {
                    boolean sent = whatsAppChannel.sendHelloWorldTest(chatId);
                    telegramService.sendMessage(chatId, sent
                            ? "WhatsApp accepted the test request. Check your WhatsApp for Meta's hello_world message."
                            : "WhatsApp test is unavailable. Check the configuration and use the hello_world template with the owner chat ID.");
                } catch (RuntimeException e) {
                    telegramService.sendMessage(chatId, "WhatsApp test failed. Check your Meta test number, token and server logs.");
                }
            }
            return;
        } else if (isCommand(text, "chatid")) {
            if (!"private".equals(message.path("chat").path("type").asText())) {
                telegramService.sendMessage(chatId, "Please send /chatid to me in a private chat.");
            } else {
                telegramService.sendMessage(chatId, "Your private Telegram chat ID: <code>" + chatId + "</code>");
            }
            return;
        } else if (isCommand(text, "link")) {
            if (!"private".equals(message.path("chat").path("type").asText())) {
                telegramService.sendMessage(chatId, "Please send /link to me in a private chat.");
            } else {
                String firstName = message.path("from").path("first_name").asText("");
                String lastName = message.path("from").path("last_name").asText("");
                String username = message.path("from").path("username").asText("");
                String profileName = (firstName + (lastName.isBlank() ? "" : " " + lastName)).trim();
                if (profileName.isBlank() && !username.isBlank()) profileName = "@" + username;
                String code = webLinkService.issueCode(chatId, profileName);
                telegramService.sendMessage(chatId, "🔐 <b>Website linking code</b>\n\n<code>" + code
                        + "</code>\n\nPaste this into the website within 10 minutes. It can be used once; keep it private.");
            }
            return;
        } else if (isCommand(text, "once") || isCommand(text, "repeat")) {
            boolean repeat = isCommand(text, "repeat");
            Long id = parseWatchId(chatId, commandArguments(text), repeat ? "/repeat WATCH_ID" : "/once WATCH_ID");
            if (id != null) repository.findByIdAndChatId(id, chatId).ifPresentOrElse(rule -> {
                rule.setRepeatAlerts(repeat); repository.save(rule);
                telegramService.sendMessage(chatId, "Watch #" + id + (repeat ? " will repeat reminders." : " will notify once."));
            }, () -> telegramService.sendMessage(chatId, "⚠️ Watch #" + id + " was not found."));
            return;
        } else if (isCommand(text, "pause")) {
            handlePauseCommand(chatId, commandArguments(text));
            return;
        } else if (isCommand(text, "resume")) {
            handleResumeCommand(chatId, commandArguments(text));
            return;
        } else if (isCommand(text, "delete")) {
            handleDeleteCommand(chatId, commandArguments(text));
            return;
        } else if (isCommand(text, "snooze")) {
            handleSnoozeCommand(chatId, commandArguments(text));
            return;
        } else if (text.contains("New Movie Alert") || isCommand(text, "new")) {
            sessions.remove(chatId);
            startNewWatchWizard(chatId);
            return;
        } else if (text.contains("Active Alerts") || text.contains("My Alerts") || isCommand(text, "list") || isCommand(text, "mywatches")) {
            sessions.remove(chatId);
            sendActiveWatchesMenu(chatId);
            return;
        } else if (text.contains("Check Tickets") || text.contains("Check Now") || isCommand(text, "check")) {
            sessions.remove(chatId);
            int sent = scheduler.pollActiveWatchesForChat(chatId);
            telegramService.sendMessage(chatId, sent < 0 ? "A ticket check is already running. Please try again shortly."
                    : "🔄 <b>Checked District (Vizag)!</b> Provider requests accepted: " + sent);
            return;
        } else if (text.contains("Clear All") || isCommand(text, "clear")) {
            sessions.remove(chatId);
            promptClearAllConfirmation(chatId);
            return;
        } else if (text.contains("Main Menu") || isCommand(text, "start") || isCommand(text, "menu")) {
            sessions.remove(chatId);
            sendMainMenu(chatId);
            return;
        } else if (isCommand(text, "help")) {
            sessions.remove(chatId);
            sendHelp(chatId);
            return;
        }

        // Check if user is in an active text-input step of the wizard
        WizardSession session = sessions.get(chatId);
        if (session != null && session.getStep() != Step.IDLE && !text.isEmpty()) {
            handleWizardTextInput(chatId, session, text);
            return;
        }

        // Default: display main menu buttons and dock persistent buttons
        sendMainMenu(chatId);
    }

    private boolean isCommand(String text, String command) {
        if (text == null || text.isBlank()) return false;
        String firstToken = text.trim().split("\\s+", 2)[0];
        return firstToken.equalsIgnoreCase("/" + command)
                || firstToken.toLowerCase(Locale.ROOT).startsWith("/" + command.toLowerCase(Locale.ROOT) + "@");
    }

    private String commandArguments(String text) {
        String[] parts = text.trim().split("\\s+", 2);
        return parts.length == 2 ? parts[1].trim() : "";
    }

    private Long parseWatchId(String chatId, String arguments, String usage) {
        try {
            if (arguments == null || arguments.isBlank()) throw new NumberFormatException();
            return Long.parseLong(arguments.split("\\s+")[0]);
        } catch (NumberFormatException e) {
            telegramService.sendMessage(chatId, "⚠️ Usage: <code>" + usage + "</code>");
            return null;
        }
    }

    private void handlePauseCommand(String chatId, String arguments) {
        Long id = parseWatchId(chatId, arguments, "/pause WATCH_ID");
        if (id == null) return;
        repository.findByIdAndChatId(id, chatId).ifPresentOrElse(rule -> {
            rule.setAcknowledged(true);
            rule.setSnoozedUntil(null);
            repository.save(rule);
            telegramService.sendMessage(chatId, "⏸ <b>Paused Watch #" + id + "</b> for " + escapeHtml(rule.getMovieName()) + ". Use <code>/resume " + id + "</code> to restart it.");
        }, () -> telegramService.sendMessage(chatId, "⚠️ Watch #" + id + " was not found."));
    }

    private void handleResumeCommand(String chatId, String arguments) {
        Long id = parseWatchId(chatId, arguments, "/resume WATCH_ID");
        if (id == null) return;
        repository.findByIdAndChatId(id, chatId).ifPresentOrElse(rule -> {
            rule.setAcknowledged(false);
            rule.setAlertCount(0);
            rule.setLastAlertedAt(null);
            rule.setSnoozedUntil(null);
            repository.save(rule);
            telegramService.sendMessage(chatId, "▶️ <b>Resumed Watch #" + id + "</b> for " + escapeHtml(rule.getMovieName()) + ".");
        }, () -> telegramService.sendMessage(chatId, "⚠️ Watch #" + id + " was not found."));
    }

    private void handleDeleteCommand(String chatId, String arguments) {
        Long id = parseWatchId(chatId, arguments, "/delete WATCH_ID");
        if (id == null) return;
        repository.findByIdAndChatId(id, chatId).ifPresentOrElse(rule -> {
            repository.delete(rule);
            telegramService.sendMessage(chatId, "🗑 Deleted Watch #" + id + " (" + escapeHtml(rule.getMovieName()) + ").");
        }, () -> telegramService.sendMessage(chatId, "⚠️ Watch #" + id + " was not found."));
    }

    private void handleSnoozeCommand(String chatId, String arguments) {
        Long id = parseWatchId(chatId, arguments, "/snooze WATCH_ID [MINUTES]");
        if (id == null) return;
        String[] parts = arguments.trim().split("\\s+");
        int minutes = 15;
        if (parts.length > 1) {
            try {
                minutes = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                telegramService.sendMessage(chatId, "⚠️ Minutes must be a number between 1 and 1440.");
                return;
            }
        }
        minutes = Math.max(1, Math.min(minutes, 1440));
        final int snoozeMinutes = minutes;
        repository.findByIdAndChatId(id, chatId).ifPresentOrElse(rule -> {
            rule.setAcknowledged(false);
            rule.setSnoozedUntil(Instant.now().plus(snoozeMinutes, ChronoUnit.MINUTES));
            repository.save(rule);
            telegramService.sendMessage(chatId, "⏸ <b>Snoozed Watch #" + id + "</b> for " + snoozeMinutes + " minute(s).");
        }, () -> telegramService.sendMessage(chatId, "⚠️ Watch #" + id + " was not found."));
    }

    private void sendHelp(String chatId) {
        telegramService.sendMessage(chatId, """
                🧭 <b>Watch Management Commands</b>

                <code>/new</code> — create a movie watch
                <code>/mywatches</code> — list active and paused watches
                <code>/pause ID</code> — pause a watch
                <code>/resume ID</code> — resume a watch
                <code>/snooze ID MINUTES</code> — temporarily silence a watch
                <code>/once ID</code> — send one alert
                <code>/repeat ID</code> — repeat reminders
                <code>/delete ID</code> — delete a watch
                <code>/check</code> — check your watches immediately
                <code>/clear</code> — delete all your watches
                """);
    }

    public void sendMainMenu(String chatId) {
        // 1. Deploy permanent dock buttons at the bottom of the chat screen
        List<List<Map<String, Object>>> persistentKeyboard = List.of(
                List.of(Map.of("text", "➕ New Movie Alert")),
                List.of(Map.of("text", "📋 Active Alerts"), Map.of("text", "🔄 Check Tickets Now")),
                List.of(Map.of("text", "🍿 Main Menu"), Map.of("text", "🗑 Clear All Alerts"))
        );
        telegramService.sendWithPersistentReplyKeyboard(
                chatId,
                "🍿 <b>District Movie Ticket Monitor (Vizag)</b>\nMonitoring tickets &amp; theatres exclusively in <b>Visakhapatnam</b>.",
                persistentKeyboard
        );

        // 2. Also send interactive inline menu buttons
        String welcome = """
                👇 <b>Quick Actions:</b>
                Tap any button below or use the docked buttons on your keyboard:
                """;

        List<List<Map<String, Object>>> keyboard = List.of(
                List.of(
                        Map.of("text", "➕ New Movie Alert", "callback_data", "menu:new")
                ),
                List.of(
                        Map.of("text", "📋 Active Alerts", "callback_data", "menu:list"),
                        Map.of("text", "🔄 Check Tickets Now", "callback_data", "menu:check_now")
                ),
                List.of(
                        Map.of("text", "🗑 Clear All Alerts", "callback_data", "menu:clear_all")
                )
        );
        telegramService.sendWithInlineKeyboard(chatId, welcome, keyboard);
    }

    private void startNewWatchWizard(String chatId) {
        WizardSession session = new WizardSession();
        session.setCity("vizag");
        sessions.put(chatId, session);

        // Only show movies currently listed as released/now-showing in Vizag.
        List<Map<String, String>> nowShowing = scraperService.fetchCurrentVizagMovies();

        String msg = """
                🎬 <b>Step 1/4: Select a Released Movie</b>
                📍 City: <b>VISAKHAPATNAM (VIZAG)</b>

                Select the movie you want to monitor. We will then load its Vizag theatres and screens.
                """;

        List<List<Map<String, Object>>> keyboard = new ArrayList<>();
        List<String> movieOptions = new ArrayList<>();
        List<String> movieUrls = new ArrayList<>();

        if (!nowShowing.isEmpty()) {
            keyboard.add(List.of(
                    Map.of("text", "── 🎥 CURRENTLY RELEASED IN VIZAG ──", "callback_data", "noop")
            ));
            List<Map<String, Object>> nsRow = new ArrayList<>();
            for (Map<String, String> m : nowShowing) {
                String name = m.get("name");
                if (name == null || name.isBlank()) continue;
                int index = movieOptions.size();
                movieOptions.add(name);
                movieUrls.add(scraperService.normalizeToVizagUrl(m.get("url")));
                nsRow.add(Map.of("text", "🎥 " + name, "callback_data", "wiz_movie_idx:" + index));
                if (nsRow.size() == 2) {
                    keyboard.add(nsRow);
                    nsRow = new ArrayList<>();
                }
            }
            if (!nsRow.isEmpty()) {
                keyboard.add(nsRow);
            }
        }

        session.setMovieOptions(movieOptions);
        session.setMovieUrls(movieUrls);

        keyboard.add(List.of(Map.of("text", "🚀 Browse Upcoming Movies", "callback_data", "wiz_upcoming:0")));
        keyboard.add(List.of(
                Map.of("text", "✍️ Type Any Movie Name", "callback_data", "wiz_movie:__custom__")
        ));
        keyboard.add(List.of(
                Map.of("text", "❌ Cancel", "callback_data", "menu:cancel")
        ));

        telegramService.sendWithInlineKeyboard(chatId, msg, keyboard);
    }

    private void promptUpcomingMovies(String chatId, WizardSession session, int page) {
        if (session.getUpcomingMovies().isEmpty()) session.setUpcomingMovies(scraperService.fetchUpcomingVizagMovies());
        List<Map<String, String>> movies = session.getUpcomingMovies();
        if (movies.isEmpty()) {
            telegramService.sendMessage(chatId, "⚠️ District's upcoming movie list is unavailable. Please try again later.");
            return;
        }
        int pages = (movies.size() + 9) / 10;
        if (page < 0 || page >= pages) return;
        List<List<Map<String, Object>>> keyboard = new ArrayList<>();
        for (int i = page * 10; i < Math.min(movies.size(), (page + 1) * 10); i++) {
            Map<String, String> movie = movies.get(i);
            keyboard.add(List.of(Map.of("text", movie.get("name") + " · " + movie.get("releaseDate"),
                    "callback_data", "wiz_upmovie:" + i)));
        }
        List<Map<String, Object>> navigation = new ArrayList<>();
        if (page > 0) navigation.add(Map.of("text", "⬅ Previous", "callback_data", "wiz_upcoming:" + (page - 1)));
        if (page + 1 < pages) navigation.add(Map.of("text", "Next ➡", "callback_data", "wiz_upcoming:" + (page + 1)));
        if (!navigation.isEmpty()) keyboard.add(navigation);
        keyboard.add(List.of(Map.of("text", "❌ Cancel", "callback_data", "menu:cancel")));
        telegramService.sendWithInlineKeyboard(chatId, "🚀 <b>Upcoming movies</b> (" + (page + 1) + "/" + pages + ")\n\nDistrict's nationwide list; Vizag screenings are not guaranteed.", keyboard);
    }

    private void promptForTheatreSelection(String chatId, WizardSession session) {
        session.setStep(Step.AWAITING_THEATRE_CHOICE);
        var catalogue = scraperService.fetchVizagTheatres();
        List<String> theatres = catalogue.items();
        session.setTheatreOptions(theatres);

        String msg = """
                🏛 <b>Step 2/4: Select Theatre</b>
                Movie: <b>%s</b>

                %s
                """.formatted(escapeHtml(session.getMovieName()), catalogue.suggested()
                ? "District's theatre list is unavailable. These are partial suggestions; you can also type a theatre name."
                : ("cached".equals(catalogue.source()) ? "Showing the last loaded Vizag theatre list. " : "Showing District's Vizag theatres. ")
                    + "A listing does not mean bookings have opened for this movie.");

        List<List<Map<String, Object>>> keyboard = new ArrayList<>();
        List<Map<String, Object>> row = new ArrayList<>();
        for (int index = 0; index < theatres.size(); index++) {
            row.add(Map.of("text", "🍿 " + theatres.get(index), "callback_data", "wiz_theatre_idx:" + index));
            if (row.size() == 2) {
                keyboard.add(row);
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) keyboard.add(row);
        keyboard.add(List.of(Map.of("text", "✍️ Type Custom Theatre", "callback_data", "wiz_theatre:__custom__")));
        keyboard.add(List.of(Map.of("text", "❌ Cancel", "callback_data", "menu:cancel")));
        telegramService.sendWithInlineKeyboard(chatId, msg, keyboard);
    }

    private void promptForScreenSelection(String chatId, WizardSession session) {
        session.setStep(Step.AWAITING_SCREEN_CHOICE);
        String theatreLabel = (session.getTheatreName() == null || session.getTheatreName().isBlank())
                ? "Any Theatre in Vizag" : session.getTheatreName();

        String msg = """
                🖥 <b>Step 3/4: Select Screen</b>
                Movie: <b>%s</b> | Theatre: <b>%s</b>

                Select exactly one screen to monitor for this theatre.
                """.formatted(escapeHtml(session.getMovieName()), escapeHtml(theatreLabel));

        List<List<Map<String, Object>>> keyboard = new ArrayList<>();
        List<String> liveScreens = scraperService.getAvailableScreensForTheatre(session.getMovieUrl(), session.getTheatreName());
        List<String> theatreScreens = getScreensForTheatre(session.getTheatreName());
        LinkedHashSet<String> mergedScreens = new LinkedHashSet<>();
        mergedScreens.addAll(liveScreens);
        if (mergedScreens.isEmpty()) {
            mergedScreens.addAll(theatreScreens);
            msg += "\nSuggested screens are not verified by District; you can also enter one manually.";
        }
        session.setScreenOptions(new ArrayList<>(mergedScreens));
        List<Map<String, Object>> row = new ArrayList<>();
        int index = 0;
        for (String screen : mergedScreens) {
            row.add(Map.of("text", "🖥 " + screen, "callback_data", "wiz_screen_idx:" + index++));
            if (row.size() == 2) {
                keyboard.add(row);
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) keyboard.add(row);
        keyboard.add(List.of(
                Map.of("text", "✍️ Type Custom Screen / Audi", "callback_data", "wiz_screen:__custom__")
        ));
        keyboard.add(List.of(
                Map.of("text", "❌ Cancel", "callback_data", "menu:cancel")
        ));

        telegramService.sendWithInlineKeyboard(chatId, msg, keyboard);
    }

    public List<String> getScreensForTheatre(String theatreName) {
        if (theatreName == null || theatreName.isBlank()) {
            return List.of("Screen 1", "Screen 2", "Screen 3", "Screen 4", "Screen 5", "Screen 6", "Screen 7", "Screen 8");
        }
        String lower = theatreName.toLowerCase();
        if (lower.contains("aaa")) {
            return List.of("Screen 1", "Screen 2", "Screen 3", "Screen 4", "Screen 5", "Screen 6", "Screen 7 (Q-Luxon LED)");
        } else if (lower.contains("varun beach")) {
            return List.of("Screen 1", "Screen 2 (Dolby Atmos)", "Screen 3", "Screen 4", "Screen 5", "Screen 6");
        } else if (lower.contains("cmr central")) {
            return List.of("Screen 1", "Screen 2 (Dolby Atmos)", "Screen 3 (Q-Luxon LED)", "Screen 4");
        } else if (lower.contains("sreekanya") || lower.contains("cinepolis")) {
            return List.of("Audi 1", "Audi 2", "Audi 3 (Q-Luxon LED)", "Audi 4", "Audi 5");
        } else if (lower.contains("chitralayaa")) {
            return List.of("Screen 1 (Dolby Atmos)", "Screen 2", "Screen 3");
        } else if (lower.contains("asian mukta") || lower.contains("gajuwaka")) {
            return List.of("Screen 1", "Screen 2", "Screen 3");
        } else if (lower.contains("kinnera") || lower.contains("kameswari")) {
            return List.of("Kinnera", "Kameswari");
        } else if (lower.contains("jagadamba") || lower.contains("sarat")) {
            return List.of("Jagadamba", "Sarat", "Ramadevi");
        }
        return List.of("Screen 1", "Screen 2", "Screen 3", "Screen 4", "Screen 5", "Screen 6", "Screen 7", "Screen 8");
    }

    private boolean isScreenLive(String screenName, List<String> liveScreens) {
        if (liveScreens == null || liveScreens.isEmpty()) return false;
        String s1 = screenName.replaceAll("\\s+", "").toLowerCase();
        java.util.regex.Matcher m1 = java.util.regex.Pattern.compile("\\d+").matcher(s1);
        Integer num1 = m1.find() ? Integer.parseInt(m1.group()) : null;

        for (String live : liveScreens) {
            String s2 = live.replaceAll("\\s+", "").toLowerCase();
            if (s1.equals(s2) || s2.contains(s1) || s1.contains(s2)) {
                return true;
            }
            if (num1 != null) {
                java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("\\d+").matcher(s2);
                if (m2.find() && Integer.parseInt(m2.group()) == num1) {
                    return true;
                }
            }
        }
        return false;
    }

    private void promptForDateSelection(String chatId, WizardSession session) {
        session.setStep(Step.AWAITING_DATE_CHOICE);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        DateTimeFormatter iso = DateTimeFormatter.ISO_LOCAL_DATE;
        DateTimeFormatter labelFmt = DateTimeFormatter.ofPattern("dd MMM (EEE)");

        String d0 = today.format(iso);
        String d1 = today.plusDays(1).format(iso);
        String d2 = today.plusDays(2).format(iso);
        String d3 = today.plusDays(3).format(iso);

        String theatreLabel = (session.getTheatreName() == null || session.getTheatreName().isBlank())
                ? "Any Theatre in Vizag" : session.getTheatreName();
        String screenLabel = (session.getScreenFilter() == null || session.getScreenFilter().equalsIgnoreCase("ANY"))
                ? "Any Screen" : session.getScreenFilter();

        String msg = """
                📅 <b>Step 4/4: Select Date</b>
                Movie: <b>%s</b> | Theatre: <b>%s</b> | Screen: <b>%s</b>
                """.formatted(escapeHtml(session.getMovieName()), escapeHtml(theatreLabel), escapeHtml(screenLabel));

        List<List<Map<String, Object>>> keyboard = List.of(
                List.of(
                        Map.of("text", "📅 Today (" + today.format(labelFmt) + ")", "callback_data", "wiz_date:" + d0),
                        Map.of("text", "📅 Tomorrow (" + today.plusDays(1).format(labelFmt) + ")", "callback_data", "wiz_date:" + d1)
                ),
                List.of(
                        Map.of("text", "📅 " + today.plusDays(2).format(labelFmt), "callback_data", "wiz_date:" + d2),
                        Map.of("text", "📅 " + today.plusDays(3).format(labelFmt), "callback_data", "wiz_date:" + d3)
                ),
                List.of(
                        Map.of("text", "✍️ Type Specific Date (YYYY-MM-DD)", "callback_data", "wiz_date:__custom__")
                ),
                List.of(
                        Map.of("text", "❌ Cancel", "callback_data", "menu:cancel")
                )
        );
        telegramService.sendWithInlineKeyboard(chatId, msg, keyboard);
    }

    private void finalizeWatchFromWizard(String chatId, WizardSession session) {
        // Prevent duplicate watch rules for the same movie, theatre, screen, and date.
        List<WatchRule> existingRules = repository.findByChatIdAndMovieNameIgnoreCaseAndAcknowledgedFalse(chatId, session.getMovieName());
        for (WatchRule ex : existingRules) {
            boolean sameTheatre = Objects.equals(ex.getTheatreName(), session.getTheatreName());
            boolean sameDate = Objects.equals(ex.getTargetDate(), session.getTargetDate());
            boolean sameScreen = Objects.equals(ex.getScreenFilter(), session.getScreenFilter());
            if (sameTheatre && sameDate && sameScreen) {
                sessions.remove(chatId);
                String dupMsg = """
                        ⚠️ <b>Alert Already Active!</b>

                        You already have an active watch <b>#%d</b> for <b>%s</b> in Vizag with these exact settings.
                        """.formatted(ex.getId(), escapeHtml(ex.getMovieName()));

                List<List<Map<String, Object>>> kb = List.of(
                        List.of(
                                Map.of("text", "🔄 Check Now", "callback_data", "menu:check_now"),
                                Map.of("text", "📋 View Alerts", "callback_data", "menu:list")
                        ),
                        List.of(
                                Map.of("text", "➕ New Alert", "callback_data", "menu:new")
                        )
                );
                telegramService.sendWithInlineKeyboard(chatId, dupMsg, kb);
                return;
            }
        }

        WatchRule saved = repository.save(new WatchRule(
                chatId,
                "vizag",
                session.getMovieName(),
                session.getTheatreName(),
                session.getScreenFilter() != null ? session.getScreenFilter() : "ANY",
                session.getTargetDate(),
                "ANY",
                "ANY",
                session.getMovieUrl(),
                null,
                0
        ));
        sessions.remove(chatId);

        String summary = """
                ✅ <b>Watch #%d Activated for Vizag!</b>

                🎬 <b>Movie:</b> %s
                🏙 <b>City:</b> VISAKHAPATNAM (VIZAG)
                🏛 <b>Theatre:</b> %s
                🖥 <b>Screen:</b> %s
                📅 <b>Date:</b> %s

                🔔 <i>Alerts are ON — you will be notified when tickets become bookable for this exact theatre, screen, and date.</i>
                """.formatted(
                saved.getId(),
                escapeHtml(saved.getMovieName()),
                escapeHtml(saved.getTheatreName() == null ? "Any Theatre in Vizag" : saved.getTheatreName()),
                escapeHtml((saved.getScreenFilter() == null || saved.getScreenFilter().equalsIgnoreCase("ANY")) ? "Any Screen" : saved.getScreenFilter()),
                escapeHtml(saved.getTargetDate())
        );

        List<List<Map<String, Object>>> keyboard = List.of(
                List.of(
                        Map.of("text", "🔄 Check Now", "callback_data", "menu:check_now"),
                        Map.of("text", "📋 My Active Alerts", "callback_data", "menu:list")
                ),
                List.of(
                        Map.of("text", "🗑 Delete This Watch", "callback_data", "del:" + saved.getId()),
                        Map.of("text", "➕ Add Another Movie", "callback_data", "menu:new")
                )
        );
        telegramService.sendWithInlineKeyboard(chatId, summary, keyboard);
    }

    private void handleWizardTextInput(String chatId, WizardSession session, String text) {
        switch (session.getStep()) {
            case AWAITING_MOVIE_NAME -> {
                if (text.isBlank() || text.length() > 120) {
                    telegramService.sendMessage(chatId, "⚠️ Movie name must be between 1 and 120 characters. Try again:");
                    return;
                }
                session.setMovieName(text.trim());
                promptForTheatreSelection(chatId, session);
            }
            case AWAITING_CUSTOM_THEATRE -> {
                if (text.isBlank() || text.length() > 255) {
                    telegramService.sendMessage(chatId, "⚠️ Theatre name must be between 1 and 255 characters. Try again:");
                    return;
                }
                session.setTheatreName(text.trim());
                promptForScreenSelection(chatId, session);
            }
            case AWAITING_CUSTOM_SCREEN -> {
                if (text.isBlank() || text.length() > 100) {
                    telegramService.sendMessage(chatId, "⚠️ Screen name must be between 1 and 100 characters. Try again:");
                    return;
                }
                session.setScreenFilter(text.trim());
                promptForDateSelection(chatId, session);
            }
            case AWAITING_CUSTOM_DATE -> {
                try {
                    String parsed = LocalDate.parse(text.trim(), DateTimeFormatter.ISO_LOCAL_DATE).toString();
                    if (!parsed.equals(text.trim())) throw new java.time.format.DateTimeParseException("Non-canonical date", text, 0);
                    session.setTargetDate(parsed);
                } catch (java.time.format.DateTimeParseException e) {
                    telegramService.sendMessage(chatId, "⚠️ Invalid date. Enter a real date in <code>YYYY-MM-DD</code> format:");
                    return;
                }
                finalizeWatchFromWizard(chatId, session);
            }
            default -> sendMainMenu(chatId);
        }
    }

    private void handleCallbackQuery(JsonNode callbackQuery) {
        String queryId = callbackQuery.path("id").asText();
        String data = callbackQuery.path("data").asText();

        JsonNode msgNode = callbackQuery.path("message");
        String rawChatId = msgNode.path("chat").path("id").asText();
        if (rawChatId.isBlank()) {
            rawChatId = callbackQuery.path("from").path("id").asText();
        }
        final String chatId = rawChatId;
        final long messageId = msgNode.path("message_id").asLong();

        telegramService.answerCallbackQuery(queryId, null);

        if (data.equals("noop")) {
            return;
        }

        if (data.equals("menu:new")) {
            startNewWatchWizard(chatId);
        } else if (data.equals("menu:list")) {
            sendActiveWatchesMenu(chatId);
        } else if (data.equals("menu:check_now")) {
            int count = scheduler.pollActiveWatchesForChat(chatId);
            telegramService.sendMessage(chatId, count < 0 ? "A ticket check is already running. Please try again shortly."
                    : "🔄 <b>Checked District (Vizag)!</b> Provider requests accepted: <b>" + count + "</b>");
        } else if (data.equals("menu:cancel")) {
            sessions.remove(chatId);
            telegramService.sendMessage(chatId, "❌ Action cancelled.");
            sendMainMenu(chatId);
        } else if (data.equals("menu:clear_all")) {
            promptClearAllConfirmation(chatId);
        } else if (data.equals("clear_all:confirm")) {
            repository.deleteByChatId(chatId);
            List<List<Map<String, Object>>> kb = List.of(
                    List.of(Map.of("text", "➕ Create New Alert", "callback_data", "menu:new"))
            );
            telegramService.sendWithInlineKeyboard(chatId, "🗑 <b>All watches deleted!</b>", kb);
        } else if (data.startsWith("wiz_upcoming:")) {
            WizardSession session = sessions.get(chatId);
            if (session == null) { telegramService.sendMessage(chatId, "⚠️ This menu expired. Use /new to start again."); return; }
            promptUpcomingMovies(chatId, session, Integer.parseInt(data.substring("wiz_upcoming:".length())));
        } else if (data.startsWith("wiz_upmovie:")) {
            WizardSession session = sessions.get(chatId);
            int index = Integer.parseInt(data.substring("wiz_upmovie:".length()));
            if (session == null || index < 0 || index >= session.getUpcomingMovies().size()) {
                telegramService.sendMessage(chatId, "⚠️ This menu expired. Use /new to start again."); return;
            }
            Map<String, String> movie = session.getUpcomingMovies().get(index);
            session.setMovieName(movie.get("name"));
            session.setMovieUrl(movie.get("url"));
            promptForTheatreSelection(chatId, session);
        } else if (data.startsWith("wiz_movie_idx:")) {
            WizardSession session = sessions.computeIfAbsent(chatId, k -> new WizardSession());
            int index = Integer.parseInt(data.substring("wiz_movie_idx:".length()));
            if (index < 0 || index >= session.getMovieOptions().size()) {
                telegramService.sendMessage(chatId, "⚠️ That movie selection expired. Please start a new alert.");
                return;
            }
            session.setCity("vizag");
            session.setMovieName(session.getMovieOptions().get(index));
            session.setMovieUrl(index < session.getMovieUrls().size() ? session.getMovieUrls().get(index) : null);
            promptForTheatreSelection(chatId, session);
        } else if (data.startsWith("wiz_movie:")) {
            String movie = data.substring("wiz_movie:".length());
            WizardSession session = sessions.computeIfAbsent(chatId, k -> new WizardSession());
            session.setCity("vizag");
            if ("__custom__".equals(movie)) {
                session.setStep(Step.AWAITING_MOVIE_NAME);
                telegramService.sendMessage(chatId, "✍️ Please type and send the <b>Movie Name</b> in chat:");
            } else {
                session.setMovieName(movie);
                for (Map<String, String> m : scraperService.fetchCurrentVizagMovies()) {
                    if (m.get("name").equalsIgnoreCase(movie)) {
                        session.setMovieUrl(scraperService.normalizeToVizagUrl(m.get("url")));
                        break;
                    }
                }
                promptForTheatreSelection(chatId, session);
            }
        } else if (data.startsWith("wiz_theatre_idx:")) {
            WizardSession session = sessions.computeIfAbsent(chatId, k -> new WizardSession());
            int index = Integer.parseInt(data.substring("wiz_theatre_idx:".length()));
            if (index < 0 || index >= session.getTheatreOptions().size()) {
                telegramService.sendMessage(chatId, "⚠️ That theatre selection expired. Please start a new alert.");
                return;
            }
            session.setTheatreName(session.getTheatreOptions().get(index));
            promptForScreenSelection(chatId, session);
        } else if (data.startsWith("wiz_theatre:")) {
            String theatre = data.substring("wiz_theatre:".length());
            WizardSession session = sessions.computeIfAbsent(chatId, k -> new WizardSession());
            if ("__custom__".equals(theatre)) {
                session.setStep(Step.AWAITING_CUSTOM_THEATRE);
                telegramService.sendMessage(chatId, "✍️ Please type and send the <b>Theatre Name</b> in Vizag (e.g., <code>Sangam Sarat</code>, <code>Melody</code>):");
            } else {
                session.setTheatreName(theatre);
                promptForScreenSelection(chatId, session);
            }
        } else if (data.startsWith("wiz_screen_idx:")) {
            WizardSession session = sessions.computeIfAbsent(chatId, k -> new WizardSession());
            int index = Integer.parseInt(data.substring("wiz_screen_idx:".length()));
            if (index < 0 || index >= session.getScreenOptions().size()) {
                telegramService.sendMessage(chatId, "⚠️ That screen selection expired. Please start a new alert.");
                return;
            }
            session.setScreenFilter(session.getScreenOptions().get(index));
            promptForDateSelection(chatId, session);
        } else if (data.startsWith("wiz_screen:")) {
            String screen = data.substring("wiz_screen:".length());
            WizardSession session = sessions.computeIfAbsent(chatId, k -> new WizardSession());
            if ("__custom__".equals(screen)) {
                session.setStep(Step.AWAITING_CUSTOM_SCREEN);
                telegramService.sendMessage(chatId, "✍️ Please type and send the <b>Screen Name / Number</b> (e.g., <code>Screen 1</code>, <code>Audi 2</code>, <code>BigPix</code>):");
            } else {
                session.setScreenFilter(screen);
                promptForDateSelection(chatId, session);
            }
        } else if (data.startsWith("wiz_date:")) {
            String dateVal = data.substring("wiz_date:".length());
            WizardSession session = sessions.computeIfAbsent(chatId, k -> new WizardSession());
            if ("__custom__".equals(dateVal)) {
                session.setStep(Step.AWAITING_CUSTOM_DATE);
                telegramService.sendMessage(chatId, "✍️ Please type target date in <b>YYYY-MM-DD</b> format (e.g., <code>2026-11-05</code>):");
            } else {
                session.setTargetDate(dateVal);
                finalizeWatchFromWizard(chatId, session);
            }
        } else if (data.startsWith("ack:")) {
            Long id = Long.parseLong(data.substring("ack:".length()));
            repository.findByIdAndChatId(id, chatId).ifPresentOrElse(rule -> {
                rule.setAcknowledged(true);
                repository.save(rule);
                // Update inline buttons so user cannot tap again
                telegramService.editMessageReplyMarkup(chatId, messageId, List.of(
                        List.of(Map.of("text", "✅ Alerts Stopped & Acknowledged", "callback_data", "noop"))
                ));
                telegramService.sendMessage(chatId, "🛑 <b>Acknowledged!</b> Stopped persistent alerts for <b>" + escapeHtml(rule.getMovieName()) + "</b> (Watch #" + id + ").");
            }, () -> telegramService.sendMessage(chatId, "⚠️ Watch #" + id + " not found or already closed."));
        } else if (data.startsWith("pause:")) {
            Long id = Long.parseLong(data.substring("pause:".length()));
            repository.findByIdAndChatId(id, chatId).ifPresentOrElse(rule -> {
                rule.setAcknowledged(true);
                rule.setSnoozedUntil(null);
                repository.save(rule);
                telegramService.sendMessage(chatId, "⏸ Paused Watch #" + id + ".");
                sendActiveWatchesMenu(chatId);
            }, () -> telegramService.sendMessage(chatId, "⚠️ Watch #" + id + " not found."));
        } else if (data.startsWith("resume:")) {
            Long id = Long.parseLong(data.substring("resume:".length()));
            repository.findByIdAndChatId(id, chatId).ifPresentOrElse(rule -> {
                rule.setAcknowledged(false);
                rule.setAlertCount(0);
                rule.setLastAlertedAt(null);
                rule.setSnoozedUntil(null);
                repository.save(rule);
                telegramService.sendMessage(chatId, "▶️ Resumed Watch #" + id + ".");
                sendActiveWatchesMenu(chatId);
            }, () -> telegramService.sendMessage(chatId, "⚠️ Watch #" + id + " not found."));
        } else if (data.startsWith("snooze:")) {
            Long id = Long.parseLong(data.substring("snooze:".length()));
            repository.findByIdAndChatId(id, chatId).ifPresentOrElse(rule -> {
                rule.setSnoozedUntil(Instant.now().plus(15, ChronoUnit.MINUTES));
                repository.save(rule);
                // Update inline button to show snoozed
                telegramService.editMessageReplyMarkup(chatId, messageId, List.of(
                        List.of(
                                Map.of("text", "⏸ Snoozed for 15 mins", "callback_data", "noop"),
                                Map.of("text", "🛑 Acknowledge & Stop", "callback_data", "ack:" + rule.getId())
                        )
                ));
                telegramService.sendMessage(chatId, "⏸ <b>Snoozed Watch #" + id + "</b> for 15 minutes.");
            }, () -> telegramService.sendMessage(chatId, "⚠️ Watch #" + id + " not found."));
        } else if (data.startsWith("del:")) {
            Long id = Long.parseLong(data.substring("del:".length()));
            repository.findByIdAndChatId(id, chatId).ifPresentOrElse(rule -> {
                repository.delete(rule);
                List<List<Map<String, Object>>> kb = List.of(
                        List.of(
                                Map.of("text", "📋 View Remaining Alerts", "callback_data", "menu:list"),
                                Map.of("text", "➕ New Alert", "callback_data", "menu:new")
                        )
                );
                telegramService.sendWithInlineKeyboard(chatId, "🗑 Deleted Watch #" + id + " (" + escapeHtml(rule.getMovieName()) + ").", kb);
            }, () -> telegramService.sendMessage(chatId, "⚠️ Watch #" + id + " not found."));
        }
    }

    private void promptClearAllConfirmation(String chatId) {
        List<WatchRule> all = repository.findByChatId(chatId);
        if (all.isEmpty()) {
            telegramService.sendMessage(chatId, "📭 You have no active watches to clear.");
            return;
        }

        String msg = "⚠️ Are you sure you want to delete all <b>" + all.size() + "</b> movie watch(es), including paused watches?";
        List<List<Map<String, Object>>> keyboard = List.of(
                List.of(
                        Map.of("text", "🗑 Yes, Delete All", "callback_data", "clear_all:confirm"),
                        Map.of("text", "❌ No, Cancel", "callback_data", "menu:list")
                )
        );
        telegramService.sendWithInlineKeyboard(chatId, msg, keyboard);
    }

    private void sendActiveWatchesMenu(String chatId) {
        List<WatchRule> rules = repository.findByChatId(chatId);
        if (rules.isEmpty()) {
            List<List<Map<String, Object>>> kb = List.of(
                    List.of(Map.of("text", "➕ Create Your First Movie Alert", "callback_data", "menu:new"))
            );
            telegramService.sendWithInlineKeyboard(chatId, "📭 You have no active movie watches right now in Vizag.", kb);
            return;
        }

        StringBuilder sb = new StringBuilder("📋 <b>Your Watches (Vizag):</b>\n\n");
        List<List<Map<String, Object>>> keyboard = new ArrayList<>();

        for (WatchRule r : rules) {
            String theatreInfo = (r.getTheatreName() != null) ? r.getTheatreName() : "Any Theatre in Vizag";
            String screenInfo = (r.getScreenFilter() != null && !r.getScreenFilter().equalsIgnoreCase("ANY")) ? r.getScreenFilter() : "Any Screen";
            String state = r.isAcknowledged() ? "⏸ PAUSED" : "🟢 ACTIVE";
            sb.append("• <b>#").append(r.getId()).append(" ").append(escapeHtml(r.getMovieName())).append("</b> — ").append(state).append("\n")
                    .append("   🏛 ").append(escapeHtml(theatreInfo))
                    .append(" | 🖥 ").append(escapeHtml(screenInfo))
                    .append(" | 📅 ").append(escapeHtml(r.getTargetDate()))
                    .append(" | ").append(r.isRepeatAlerts() ? "Repeating" : "Once")
                    .append("\n   ").append(escapeHtml(r.getLastCheckMessage() == null ? "Not checked yet" : r.getLastCheckMessage()))
                    .append("\n\n");

            keyboard.add(List.of(
                    Map.of("text", r.isAcknowledged() ? "▶️ Resume #" + r.getId() : "⏸ Pause #" + r.getId(),
                            "callback_data", (r.isAcknowledged() ? "resume:" : "pause:") + r.getId()),
                    Map.of("text", "🗑 Delete", "callback_data", "del:" + r.getId())
            ));
        }

        keyboard.add(List.of(
                Map.of("text", "➕ New Alert", "callback_data", "menu:new"),
                Map.of("text", "🔄 Check Now", "callback_data", "menu:check_now")
        ));
        keyboard.add(List.of(
                Map.of("text", "🗑 Clear All Alerts", "callback_data", "menu:clear_all"),
                Map.of("text", "🔙 Main Menu", "callback_data", "menu:cancel")
        ));

        telegramService.sendWithInlineKeyboard(chatId, sb.toString(), keyboard);
    }

    private String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
