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

    // Per-user interactive wizard session map
    private final Map<String, WizardSession> sessions = new ConcurrentHashMap<>();

    public TelegramCommandService(
            WatchRuleRepository repository,
            TelegramNotificationService telegramService,
            @Lazy TicketWatchScheduler scheduler,
            DistrictScraperService scraperService
    ) {
        this.repository = repository;
        this.telegramService = telegramService;
        this.scheduler = scheduler;
        this.scraperService = scraperService;
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
        if (text.contains("New Movie Alert") || text.startsWith("/new")) {
            sessions.remove(chatId);
            startNewWatchWizard(chatId);
            return;
        } else if (text.contains("Active Alerts") || text.contains("My Alerts") || text.startsWith("/list")) {
            sessions.remove(chatId);
            sendActiveWatchesMenu(chatId);
            return;
        } else if (text.contains("Check Tickets") || text.contains("Check Now") || text.startsWith("/check")) {
            sessions.remove(chatId);
            int sent = scheduler.pollActiveWatchesForChat(chatId);
            telegramService.sendMessage(chatId, "🔄 <b>Checked District (Vizag)!</b> Notifications triggered: " + sent);
            return;
        } else if (text.contains("Clear All") || text.startsWith("/clear")) {
            sessions.remove(chatId);
            promptClearAllConfirmation(chatId);
            return;
        } else if (text.contains("Main Menu") || text.startsWith("/start") || text.startsWith("/menu") || text.startsWith("/help")) {
            sessions.remove(chatId);
            sendMainMenu(chatId);
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

        // Fetch both Now Showing and Upcoming movies
        List<Map<String, String>> nowShowing = scraperService.fetchCurrentVizagMovies();
        List<Map<String, String>> upcoming = scraperService.fetchUpcomingVizagMovies();

        Set<String> seenMovies = new HashSet<>();

        String msg = """
                🎬 <b>Step 1/6: Select Movie in Vizag</b>
                📍 City: <b>VISAKHAPATNAM (VIZAG)</b>

                Choose from <b>Now Showing</b> (🎥) or <b>Upcoming Releases</b> (🚀) below:
                """;

        List<List<Map<String, Object>>> keyboard = new ArrayList<>();

        // 1. Now Showing Movies in Vizag
        if (!nowShowing.isEmpty()) {
            keyboard.add(List.of(
                    Map.of("text", "── 🎥 NOW SHOWING IN VIZAG ──", "callback_data", "noop")
            ));
            List<Map<String, Object>> nsRow = new ArrayList<>();
            int nsCount = 0;
            for (Map<String, String> m : nowShowing) {
                String name = m.get("name");
                if (name == null || name.isBlank()) continue;
                seenMovies.add(name.toLowerCase());
                nsRow.add(Map.of("text", "🎥 " + name, "callback_data", "wiz_movie:" + name));
                nsCount++;
                if (nsRow.size() == 2) {
                    keyboard.add(nsRow);
                    nsRow = new ArrayList<>();
                }
                if (nsCount >= 6) break;
            }
            if (!nsRow.isEmpty()) {
                keyboard.add(nsRow);
            }
        }

        // 2. Upcoming Releases for Vizag
        if (!upcoming.isEmpty()) {
            keyboard.add(List.of(
                    Map.of("text", "── 🚀 UPCOMING RELEASES ──", "callback_data", "noop")
            ));
            List<Map<String, Object>> upRow = new ArrayList<>();
            int upCount = 0;
            for (Map<String, String> m : upcoming) {
                String name = m.get("name");
                if (name == null || name.isBlank() || seenMovies.contains(name.toLowerCase())) continue;
                upRow.add(Map.of("text", "🚀 " + name, "callback_data", "wiz_movie:" + name));
                upCount++;
                if (upRow.size() == 2) {
                    keyboard.add(upRow);
                    upRow = new ArrayList<>();
                }
                if (upCount >= 6) break;
            }
            if (!upRow.isEmpty()) {
                keyboard.add(upRow);
            }
        }

        keyboard.add(List.of(
                Map.of("text", "✍️ Type Any Movie Name", "callback_data", "wiz_movie:__custom__")
        ));
        keyboard.add(List.of(
                Map.of("text", "❌ Cancel", "callback_data", "menu:cancel")
        ));

        telegramService.sendWithInlineKeyboard(chatId, msg, keyboard);
    }

    private void promptForTheatreSelection(String chatId, WizardSession session) {
        session.setStep(Step.AWAITING_THEATRE_CHOICE);
        String msg = """
                🏛 <b>Step 2/6: Select Theatre Preference</b>
                Movie: <b>%s</b>

                💡 <b>For Upcoming Releases:</b> Choose <b>All Theatres in Vizag</b> so as soon as ANY cinema releases tickets, you are alerted immediately!
                """.formatted(escapeHtml(session.getMovieName()));

        List<List<Map<String, Object>>> keyboard = List.of(
                List.of(
                        Map.of("text", "⚡ 1-Click: ALL Theatres & Screens (First Release)", "callback_data", "wiz_quick_all")
                ),
                List.of(
                        Map.of("text", "🌐 All Theatres in Vizag (Recommended)", "callback_data", "wiz_theatre:ANY")
                ),
                List.of(
                        Map.of("text", "🍿 INOX Varun Beach", "callback_data", "wiz_theatre:Varun Beach"),
                        Map.of("text", "🍿 AAA Cinemas", "callback_data", "wiz_theatre:AAA Cinemas")
                ),
                List.of(
                        Map.of("text", "🍿 INOX CMR Central", "callback_data", "wiz_theatre:CMR Central"),
                        Map.of("text", "🍿 Cinepolis Sreekanya", "callback_data", "wiz_theatre:Sreekanya")
                ),
                List.of(
                        Map.of("text", "🍿 INOX Chitralayaa", "callback_data", "wiz_theatre:Chitralayaa"),
                        Map.of("text", "🍿 Kameswari / Kinnera", "callback_data", "wiz_theatre:Kameswari")
                ),
                List.of(
                        Map.of("text", "🍿 Asian Mukta (Gajuwaka)", "callback_data", "wiz_theatre:Asian Mukta"),
                        Map.of("text", "🍿 Jagadamba / Sarat", "callback_data", "wiz_theatre:Jagadamba")
                ),
                List.of(
                        Map.of("text", "✍️ Type Custom Theatre", "callback_data", "wiz_theatre:__custom__")
                ),
                List.of(
                        Map.of("text", "❌ Cancel", "callback_data", "menu:cancel")
                )
        );
        telegramService.sendWithInlineKeyboard(chatId, msg, keyboard);
    }

    private void promptForScreenSelection(String chatId, WizardSession session) {
        session.setStep(Step.AWAITING_SCREEN_CHOICE);
        String theatreLabel = (session.getTheatreName() == null || session.getTheatreName().isBlank())
                ? "Any Theatre in Vizag" : session.getTheatreName();

        String msg = """
                🖥 <b>Step 3/6: Select Screen Filter</b>
                Movie: <b>%s</b> | Theatre: <b>%s</b>

                Choose which screen you want to monitor, or pick Any Screen:
                """.formatted(escapeHtml(session.getMovieName()), escapeHtml(theatreLabel));

        List<List<Map<String, Object>>> keyboard = new ArrayList<>();

        // 1. Always provide 'Any Screen' option first
        String anyScreenText = (session.getTheatreName() == null || session.getTheatreName().isBlank())
                ? "🌐 Any Screen (Recommended for Upcoming)" : "🌐 Any Screen in Theatre";
        keyboard.add(List.of(
                Map.of("text", anyScreenText, "callback_data", "wiz_screen:ANY")
        ));

        // 2. Fetch live screens currently open on District for this theatre
        List<String> liveScreens = Collections.emptyList();
        if (session.getTheatreName() != null && !session.getTheatreName().isBlank()) {
            try {
                liveScreens = scraperService.getAvailableScreensForTheatre(session.getMovieUrl(), session.getTheatreName());
            } catch (Exception ignored) {}
        }

        // 3. Get all screens belonging to this theatre (e.g. AAA Cinemas has Screen 1 to Screen 7)
        List<String> theatreScreens = getScreensForTheatre(session.getTheatreName());

        List<Map<String, Object>> row = new ArrayList<>();
        for (String scr : theatreScreens) {
            boolean live = isScreenLive(scr, liveScreens);
            String btnText = live ? ("🟢 " + scr + " (Open Now)") : ("🖥 " + scr);
            row.add(Map.of("text", btnText, "callback_data", "wiz_screen:" + scr));
            if (row.size() == 2) {
                keyboard.add(row);
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) {
            keyboard.add(row);
        }

        // 4. Also add any extra live screens discovered on District that aren't in standard roster (e.g. IMAX, EPIQ)
        List<Map<String, Object>> extraRow = new ArrayList<>();
        for (String liveScr : liveScreens) {
            if (!isScreenLive(liveScr, theatreScreens)) {
                extraRow.add(Map.of("text", "🟢 " + liveScr + " (Open Now)", "callback_data", "wiz_screen:" + liveScr));
                if (extraRow.size() == 2) {
                    keyboard.add(extraRow);
                    extraRow = new ArrayList<>();
                }
            }
        }
        if (!extraRow.isEmpty()) {
            keyboard.add(extraRow);
        }

        // 5. Custom screen option & cancel
        keyboard.add(List.of(
                Map.of("text", "✍️ Type Custom Screen / Audi", "callback_data", "wiz_screen:__custom__")
        ));
        keyboard.add(List.of(
                Map.of("text", "❌ Cancel", "callback_data", "menu:cancel")
        ));

        telegramService.sendWithInlineKeyboard(chatId, msg, keyboard);
    }

    private List<String> getScreensForTheatre(String theatreName) {
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
        LocalDate today = LocalDate.now();
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
                📅 <b>Step 4/6: Select Date Filter</b>
                Movie: <b>%s</b> | Theatre: <b>%s</b> | Screen: <b>%s</b>
                """.formatted(escapeHtml(session.getMovieName()), escapeHtml(theatreLabel), escapeHtml(screenLabel));

        List<List<Map<String, Object>>> keyboard = List.of(
                List.of(
                        Map.of("text", "📆 Any Date / Release Date (Recommended for Upcoming)", "callback_data", "wiz_date:ANY")
                ),
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

    private void promptForLanguageSelection(String chatId, WizardSession session) {
        session.setStep(Step.AWAITING_LANGUAGE_CHOICE);
        String screenLabel = (session.getScreenFilter() == null || session.getScreenFilter().equalsIgnoreCase("ANY"))
                ? "Any Screen" : session.getScreenFilter();

        String msg = """
                🗣 <b>Step 5/6: Select Language Filter</b>
                Movie: <b>%s</b> | Date: <b>%s</b> | Screen: <b>%s</b>
                """.formatted(escapeHtml(session.getMovieName()), escapeHtml(session.getTargetDate()), escapeHtml(screenLabel));

        List<List<Map<String, Object>>> keyboard = List.of(
                List.of(
                        Map.of("text", "🌐 Any Language", "callback_data", "wiz_lang:ANY")
                ),
                List.of(
                        Map.of("text", "🗣 Telugu", "callback_data", "wiz_lang:Telugu"),
                        Map.of("text", "🗣 Hindi", "callback_data", "wiz_lang:Hindi")
                ),
                List.of(
                        Map.of("text", "🗣 English", "callback_data", "wiz_lang:English"),
                        Map.of("text", "🗣 Tamil", "callback_data", "wiz_lang:Tamil")
                ),
                List.of(
                        Map.of("text", "❌ Cancel", "callback_data", "menu:cancel")
                )
        );
        telegramService.sendWithInlineKeyboard(chatId, msg, keyboard);
    }

    private void promptForFormatSelection(String chatId, WizardSession session) {
        session.setStep(Step.AWAITING_FORMAT_CHOICE);
        String msg = """
                🎞 <b>Step 6/6: Select Screen Format</b>
                Movie: <b>%s</b> | Language: <b>%s</b>
                """.formatted(escapeHtml(session.getMovieName()), escapeHtml(session.getLanguageFilter()));

        List<List<Map<String, Object>>> keyboard = List.of(
                List.of(
                        Map.of("text", "🎞 Any Format", "callback_data", "wiz_fmt:ANY")
                ),
                List.of(
                        Map.of("text", "🎬 2D", "callback_data", "wiz_fmt:2D"),
                        Map.of("text", "🕶 3D", "callback_data", "wiz_fmt:3D")
                ),
                List.of(
                        Map.of("text", "👓 IMAX", "callback_data", "wiz_fmt:IMAX"),
                        Map.of("text", "🔊 Dolby Atmos", "callback_data", "wiz_fmt:Dolby")
                ),
                List.of(
                        Map.of("text", "❌ Cancel", "callback_data", "menu:cancel")
                )
        );
        telegramService.sendWithInlineKeyboard(chatId, msg, keyboard);
    }

    private void finalizeWatchFromWizard(String chatId, WizardSession session) {
        // Prevent duplicate watch rules for the same movie, theatre, screen, and date
        List<WatchRule> existingRules = repository.findByChatIdAndMovieNameIgnoreCaseAndAcknowledgedFalse(chatId, session.getMovieName());
        for (WatchRule ex : existingRules) {
            boolean sameTheatre = Objects.equals(ex.getTheatreName(), session.getTheatreName());
            boolean sameDate = Objects.equals(ex.getTargetDate(), session.getTargetDate());
            boolean sameScreen = Objects.equals(ex.getScreenFilter(), session.getScreenFilter());
            boolean sameLanguage = Objects.equals(ex.getLanguageFilter(), session.getLanguageFilter());
            boolean sameFormat = Objects.equals(ex.getFormatFilter(), session.getFormatFilter());
            if (sameTheatre && sameDate && sameScreen && sameLanguage && sameFormat) {
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
                session.getLanguageFilter(),
                session.getFormatFilter(),
                session.getMovieUrl()
        ));
        sessions.remove(chatId);

        String summary = """
                ✅ <b>Watch #%d Activated for Vizag!</b>

                🎬 <b>Movie:</b> %s
                🏙 <b>City:</b> VISAKHAPATNAM (VIZAG)
                🏛 <b>Theatre:</b> %s
                🖥 <b>Screen:</b> %s
                📅 <b>Date:</b> %s
                🗣 <b>Language:</b> %s
                🎞 <b>Format:</b> %s

                🔔 <i>Persistent alerts are ON — as soon as tickets open in Vizag, you'll be notified repeatedly with direct seat booking links!</i>
                """.formatted(
                saved.getId(),
                escapeHtml(saved.getMovieName()),
                escapeHtml(saved.getTheatreName() == null ? "Any Theatre in Vizag" : saved.getTheatreName()),
                escapeHtml((saved.getScreenFilter() == null || saved.getScreenFilter().equalsIgnoreCase("ANY")) ? "Any Screen" : saved.getScreenFilter()),
                escapeHtml(saved.getTargetDate()),
                escapeHtml(saved.getLanguageFilter()),
                escapeHtml(saved.getFormatFilter())
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
                if (text.isBlank() || text.length() > 100) {
                    telegramService.sendMessage(chatId, "⚠️ Theatre name must be between 1 and 100 characters. Try again:");
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
                promptForLanguageSelection(chatId, session);
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
            telegramService.sendMessage(chatId, "🔄 <b>Checked District (Vizag)!</b> Notifications triggered: <b>" + count + "</b>");
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
        } else if (data.equals("wiz_quick_all")) {
            WizardSession session = sessions.computeIfAbsent(chatId, k -> new WizardSession());
            session.setTheatreName(null);
            session.setScreenFilter("ANY");
            session.setTargetDate("ANY");
            session.setLanguageFilter("ANY");
            session.setFormatFilter("ANY");
            finalizeWatchFromWizard(chatId, session);
        } else if (data.startsWith("wiz_movie:")) {
            String movie = data.substring("wiz_movie:".length());
            WizardSession session = sessions.computeIfAbsent(chatId, k -> new WizardSession());
            session.setCity("vizag");
            if ("__custom__".equals(movie)) {
                session.setStep(Step.AWAITING_MOVIE_NAME);
                telegramService.sendMessage(chatId, "✍️ Please type and send the <b>Movie Name</b> in chat:");
            } else {
                session.setMovieName(movie);
                List<Map<String, String>> allMovies = new ArrayList<>(scraperService.fetchCurrentVizagMovies());
                allMovies.addAll(scraperService.fetchUpcomingVizagMovies());
                for (Map<String, String> m : allMovies) {
                    if (m.get("name").equalsIgnoreCase(movie)) {
                        session.setMovieUrl(scraperService.normalizeToVizagUrl(m.get("url")));
                        break;
                    }
                }
                promptForTheatreSelection(chatId, session);
            }
        } else if (data.startsWith("wiz_theatre:")) {
            String theatre = data.substring("wiz_theatre:".length());
            WizardSession session = sessions.computeIfAbsent(chatId, k -> new WizardSession());
            if ("__custom__".equals(theatre)) {
                session.setStep(Step.AWAITING_CUSTOM_THEATRE);
                telegramService.sendMessage(chatId, "✍️ Please type and send the <b>Theatre Name</b> in Vizag (e.g., <code>Sangam Sarat</code>, <code>Melody</code>):");
            } else {
                session.setTheatreName("ANY".equals(theatre) ? null : theatre);
                promptForScreenSelection(chatId, session);
            }
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
                promptForLanguageSelection(chatId, session);
            }
        } else if (data.startsWith("wiz_lang:")) {
            String lang = data.substring("wiz_lang:".length());
            WizardSession session = sessions.computeIfAbsent(chatId, k -> new WizardSession());
            session.setLanguageFilter(lang);
            promptForFormatSelection(chatId, session);
        } else if (data.startsWith("wiz_fmt:")) {
            String fmt = data.substring("wiz_fmt:".length());
            WizardSession session = sessions.computeIfAbsent(chatId, k -> new WizardSession());
            session.setFormatFilter(fmt);
            finalizeWatchFromWizard(chatId, session);
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
        List<WatchRule> active = repository.findByChatIdAndAcknowledgedFalse(chatId);
        if (active.isEmpty()) {
            telegramService.sendMessage(chatId, "📭 You have no active watches to clear.");
            return;
        }

        String msg = "⚠️ Are you sure you want to delete all <b>" + active.size() + "</b> active movie alert(s)?";
        List<List<Map<String, Object>>> keyboard = List.of(
                List.of(
                        Map.of("text", "🗑 Yes, Delete All", "callback_data", "clear_all:confirm"),
                        Map.of("text", "❌ No, Cancel", "callback_data", "menu:list")
                )
        );
        telegramService.sendWithInlineKeyboard(chatId, msg, keyboard);
    }

    private void sendActiveWatchesMenu(String chatId) {
        List<WatchRule> rules = repository.findByChatIdAndAcknowledgedFalse(chatId);
        if (rules.isEmpty()) {
            List<List<Map<String, Object>>> kb = List.of(
                    List.of(Map.of("text", "➕ Create Your First Movie Alert", "callback_data", "menu:new"))
            );
            telegramService.sendWithInlineKeyboard(chatId, "📭 You have no active movie watches right now in Vizag.", kb);
            return;
        }

        StringBuilder sb = new StringBuilder("📋 <b>Your Active Watches (Vizag):</b>\n\n");
        List<List<Map<String, Object>>> keyboard = new ArrayList<>();

        for (WatchRule r : rules) {
            String theatreInfo = (r.getTheatreName() != null) ? r.getTheatreName() : "Any Theatre in Vizag";
            String screenInfo = (r.getScreenFilter() != null && !r.getScreenFilter().equalsIgnoreCase("ANY")) ? r.getScreenFilter() : "Any Screen";
            sb.append("• <b>#").append(r.getId()).append(" ").append(escapeHtml(r.getMovieName())).append("</b>\n")
                    .append("   🏛 ").append(escapeHtml(theatreInfo))
                    .append(" | 🖥 ").append(escapeHtml(screenInfo))
                    .append(" | 📅 ").append(escapeHtml(r.getTargetDate()))
                    .append(" | 🗣 ").append(escapeHtml(r.getLanguageFilter()))
                    .append(" | 🎞 ").append(escapeHtml(r.getFormatFilter()))
                    .append("\n\n");

            keyboard.add(List.of(
                    Map.of("text", "🗑 Delete #" + r.getId() + " (" + r.getMovieName() + ")", "callback_data", "del:" + r.getId())
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
