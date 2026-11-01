package com.district.monitor.service;

import com.district.monitor.entity.WatchRule;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class WhatsAppNotificationChannelTest {
    private WatchRule watch(String chatId) {
        return new WatchRule(chatId, "vizag", "Ramayana", "AAA Cinemas", "Screen 7", "2026-11-06", "ANY", "ANY", null);
    }
    private WhatsAppNotificationChannel channel(RestClient.Builder builder, boolean enabled, String template) {
        return new WhatsAppNotificationChannel(enabled, "example-token", "v22.0", "12345", "919876543210", "123456", template, "en_US", builder);
    }

    @Test void sendsTicketDetailsOnlyForConfiguredOwner() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var channel = channel(builder, true, "ticket_alert");
        server.expect(requestTo("https://graph.facebook.com/v22.0/12345/messages"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("Authorization", "Bearer example-token"))
                .andExpect(jsonPath("$.to").value("919876543210"))
                .andExpect(jsonPath("$.template.name").value("ticket_alert"))
                .andExpect(jsonPath("$.template.components[0].parameters[0].text").value("Ramayana"))
                .andExpect(jsonPath("$.template.components[0].parameters[1].text").value("AAA Cinemas"))
                .andExpect(jsonPath("$.template.components[0].parameters[2].text").value("Screen 7"))
                .andExpect(jsonPath("$.template.components[0].parameters[3].text").value("2026-11-06"))
                .andExpect(jsonPath("$.template.components[0].parameters[4].text").value("https://www.district.in/movies/example"))
                .andRespond(withSuccess("{\"messages\":[{\"id\":\"wamid.test\"}]}", MediaType.APPLICATION_JSON));
        channel.sendTicketAlert(watch("123456"), "", "", "https://www.district.in/movies/example");
        channel.sendTicketAlert(watch("987654"), "", "", "https://www.district.in/movies/example");
        server.verify();
    }

    @Test void metaHelloWorldTestDoesNotIncludeParameters() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var channel = channel(builder, true, "hello_world");
        server.expect(requestTo("https://graph.facebook.com/v22.0/12345/messages"))
                .andExpect(jsonPath("$.template.name").value("hello_world"))
                .andExpect(jsonPath("$.template.components").doesNotExist())
                .andRespond(withSuccess());
        channel.sendTicketAlert(watch("123456"), "", "", "url");
        server.verify();
    }

    @Test void disabledOrIncompleteSettingsNeverSend() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var channel = channel(builder, false, "ticket_alert");
        assertFalse(channel.isEnabled());
        channel.sendTicketAlert(watch("123456"), "", "", "url");
        server.verify();
        assertFalse(new WhatsAppNotificationChannel(true, "", "v22.0", "123", "919876543210", "123456", "hello_world", "en_US", RestClient.builder()).isEnabled());
    }

    @Test void apiFailureIsReportedWithoutExposingCredentials() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var channel = channel(builder, true, "ticket_alert");
        server.expect(requestTo("https://graph.facebook.com/v22.0/12345/messages")).andRespond(withServerError());
        var error = assertThrows(IllegalStateException.class, () -> channel.sendTicketAlert(watch("123456"), "", "", "url"));
        assertEquals("WhatsApp request failed", error.getMessage());
        server.verify();
    }

    @Test void testCommandIsRestrictedToOwnerAndHelloWorld() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var channel = channel(builder, true, "hello_world");
        assertFalse(channel.sendHelloWorldTest("987654"));
        server.expect(requestTo("https://graph.facebook.com/v22.0/12345/messages"))
                .andExpect(jsonPath("$.template.name").value("hello_world"))
                .andRespond(withSuccess());
        assertTrue(channel.sendHelloWorldTest("123456"));
        server.verify();
        assertFalse(channel(RestClient.builder(), true, "ticket_alert").sendHelloWorldTest("123456"));
    }
}
