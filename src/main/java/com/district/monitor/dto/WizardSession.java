package com.district.monitor.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Holds in-memory conversation state for the interactive Telegram Inline Keyboard wizard.
 */
public class WizardSession {

    public enum Step {
        IDLE,
        AWAITING_CUSTOM_CITY,
        AWAITING_MOVIE_NAME,
        AWAITING_THEATRE_CHOICE,
        AWAITING_CUSTOM_THEATRE,
        AWAITING_SCREEN_CHOICE,
        AWAITING_CUSTOM_SCREEN,
        AWAITING_DATE_CHOICE,
        AWAITING_CUSTOM_DATE
    }

    private Step step = Step.IDLE;
    private String city = "vizag";
    private String movieName;
    private String movieUrl;
    private String theatreName; // null = ANY
    private String screenFilter = "ANY"; // "Screen 1", "Audi 2", "ANY"
    private String targetDate = "ANY";
    private String languageFilter = "ANY";
    private String formatFilter = "ANY";
    private BigDecimal maxPrice;
    private int minAvailableSeats = 0;
    private List<String> theatreOptions = new ArrayList<>();
    private List<String> screenOptions = new ArrayList<>();
    private List<String> movieOptions = new ArrayList<>();
    private List<String> movieUrls = new ArrayList<>();
    private List<Map<String, String>> upcomingMovies = new ArrayList<>();

    public Step getStep() { return step; }
    public void setStep(Step step) { this.step = step; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getMovieName() { return movieName; }
    public void setMovieName(String movieName) { this.movieName = movieName; }
    public String getMovieUrl() { return movieUrl; }
    public void setMovieUrl(String movieUrl) { this.movieUrl = movieUrl; }
    public String getTheatreName() { return theatreName; }
    public void setTheatreName(String theatreName) { this.theatreName = theatreName; }
    public String getScreenFilter() { return screenFilter; }
    public void setScreenFilter(String screenFilter) { this.screenFilter = screenFilter; }
    public String getTargetDate() { return targetDate; }
    public void setTargetDate(String targetDate) { this.targetDate = targetDate; }
    public String getLanguageFilter() { return languageFilter; }
    public void setLanguageFilter(String languageFilter) { this.languageFilter = languageFilter; }
    public String getFormatFilter() { return formatFilter; }
    public void setFormatFilter(String formatFilter) { this.formatFilter = formatFilter; }
    public BigDecimal getMaxPrice() { return maxPrice; }
    public void setMaxPrice(BigDecimal maxPrice) { this.maxPrice = maxPrice; }
    public int getMinAvailableSeats() { return minAvailableSeats; }
    public void setMinAvailableSeats(int minAvailableSeats) { this.minAvailableSeats = Math.max(0, minAvailableSeats); }
    public List<String> getTheatreOptions() { return theatreOptions; }
    public void setTheatreOptions(List<String> theatreOptions) {
        this.theatreOptions = theatreOptions == null ? new ArrayList<>() : new ArrayList<>(theatreOptions);
    }
    public List<String> getScreenOptions() { return screenOptions; }
    public void setScreenOptions(List<String> screenOptions) {
        this.screenOptions = screenOptions == null ? new ArrayList<>() : new ArrayList<>(screenOptions);
    }
    public List<String> getMovieOptions() { return movieOptions; }
    public void setMovieOptions(List<String> movieOptions) {
        this.movieOptions = movieOptions == null ? new ArrayList<>() : new ArrayList<>(movieOptions);
    }
    public List<String> getMovieUrls() { return movieUrls; }
    public void setMovieUrls(List<String> movieUrls) {
        this.movieUrls = movieUrls == null ? new ArrayList<>() : new ArrayList<>(movieUrls);
    }
    public List<Map<String, String>> getUpcomingMovies() { return upcomingMovies; }
    public void setUpcomingMovies(List<Map<String, String>> movies) { this.upcomingMovies = new ArrayList<>(movies); }
}
