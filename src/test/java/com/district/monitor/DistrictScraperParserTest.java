package com.district.monitor;

import com.district.monitor.service.DistrictScraperService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DistrictScraperParserTest {

    @Test
    void parsesCurrentDistrictServerStateMovieSessions() throws Exception {
        String html = """
                <html><head>
                <script id="__NEXT_DATA__" type="application/json">
                {
                  "props": {"pageProps": {"data": {"serverState": {"movieSessions": {
                    "2ar4887i5ss": {"pageData": {"nearbyCinemas": [
                      {"cinemaInfo": {"name": "AAA Cinemas, Vizag"}, "sessions": [
                        {"showTime": "2026-11-01T04:35:00", "audi": "Screen 7", "premiumLabel": "Q-Luxon LED",
                         "scrnFmt": "2D", "language": "Telugu", "encSessionId": "session-1", "mcd": "movie-code",
                         "avail": 152, "price": 180}
                      ]}
                    ]}}
                  }}}}}
                }
                </script>
                </head><body></body></html>
                """;

        DistrictScraperService scraper = new DistrictScraperService("https://www.district.in/movies");
        Method parser = DistrictScraperService.class.getDeclaredMethod(
                "extractScreeningsFromPage", Document.class, String.class, String.class, String.class);
        parser.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<DistrictScraperService.ScreeningRecord> records =
                (List<DistrictScraperService.ScreeningRecord>) parser.invoke(
                        scraper, Jsoup.parse(html), html, "Jailer 2", "2026-11-01");

        assertEquals(1, records.size());
        DistrictScraperService.ScreeningRecord record = records.get(0);
        assertEquals("AAA Cinemas, Vizag", record.cinemaName());
        assertEquals("2026-11-01", record.dateIso());
        assertEquals("10:05 AM", record.timeStr());
        assertTrue(record.audi().contains("Luxon"));
        assertEquals(152, record.availableSeats());
        assertEquals("180", record.price().toPlainString());
    }

    @Test
    void readsUpcomingMovieCardsAndPreservesDistrictMovieId() {
        Document doc = Jsoup.parse("""
                <a href="/movies/example-movie-tickets-MV123"><div><h5>Example: Part 2</h5><span>December 18, 2026</span></div></a>
                <a href="/movies/example-movie-tickets-MV123"><h5>Example: Part 2</h5></a>
                <a href="https://evil.example/movies/bad-movie-tickets-MV999"><h5>Bad</h5></a>
                """, "https://www.district.in/movies/upcoming-movies");
        var movies = new DistrictScraperService("https://www.district.in/movies").parseUpcomingMovies(doc);
        assertEquals(1, movies.size());
        assertEquals("Example: Part 2", movies.get(0).get("name"));
        assertEquals("December 18, 2026", movies.get(0).get("releaseDate"));
        assertEquals("https://www.district.in/movies/example-movie-tickets-in-vizag-MV123", movies.get(0).get("url"));
    }
}
