package com.district.monitor.dto;

import java.math.BigDecimal;

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
        AWAITING_CUSTOM_DATE,
        AWAITING_LANGUAGE_CHOICE,
        AWAITING_FORMAT_CHOICE,
        AWAITING_PRICE_CHOICE,
        AWAITING_CUSTOM_PRICE,
        AWAITING_SEATS_CHOICE,
        AWAITING_CUSTOM_SEATS
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
}
