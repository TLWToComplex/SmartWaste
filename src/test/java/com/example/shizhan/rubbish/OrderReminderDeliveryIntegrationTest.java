package com.example.shizhan.rubbish;

import com.example.shizhan.rubbish.service.OrderReminderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.servlet.http.HttpServletRequest;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {ShiZhanApplication.class, OrderReminderDeliveryIntegrationTest.FixtureConfiguration.class})
class OrderReminderDeliveryIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrderReminderService reminders;

    @Test
    void realConnectionsRequireLoginReceiveBroadcastsAndReceiveEmptyUpdates() throws Exception {
        jdbc.execute("DROP TABLE IF EXISTS orders");
        jdbc.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, status INT, order_time VARCHAR(40), "
                + "number VARCHAR(64), user_name VARCHAR(64), start_address VARCHAR(255))");
        StandardWebSocketClient client = new StandardWebSocketClient();
        URI uri = URI.create("ws://localhost:" + port + "/ws/admin/order-reminders");
        assertThrows(ExecutionException.class, () -> client.doHandshake(new CapturingHandler(), new WebSocketHttpHeaders(), uri)
                .get(5, TimeUnit.SECONDS));

        HttpURLConnection login = (HttpURLConnection) new URL("http://localhost:" + port + "/test/order-reminder-session").openConnection();
        String cookie;
        try {
            assertEquals(200, login.getResponseCode());
            cookie = login.getHeaderField("Set-Cookie").split(";", 2)[0];
            login.getInputStream().close();
        } finally {
            login.disconnect();
        }
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.set("Cookie", cookie);
        WebSocketHttpHeaders foreign = new WebSocketHttpHeaders();
        foreign.set("Cookie", cookie);
        foreign.setOrigin("http://untrusted.example");
        assertThrows(ExecutionException.class, () -> client.doHandshake(new CapturingHandler(), foreign, uri).get(5, TimeUnit.SECONDS));

        CapturingHandler first = new CapturingHandler();
        CapturingHandler second = new CapturingHandler();
        WebSocketSession a = client.doHandshake(first, headers, uri).get(5, TimeUnit.SECONDS);
        WebSocketSession b = client.doHandshake(second, headers, uri).get(5, TimeUnit.SECONDS);
        try {
            assertEquals("order-reminders.ready", first.next().get("type").asText());
            assertEquals("order-reminders.ready", second.next().get("type").asText());
            jdbc.update("INSERT INTO orders(id, status, order_time) VALUES (1, 1, ?)",
                    LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(20).toString());
            reminders.scanAndNotify();
            assertEquals(1, first.next().get("total").asInt());
            assertEquals(1, second.next().get("total").asInt());

            jdbc.update("UPDATE orders SET status = 2 WHERE id = 1");
            reminders.scanAndNotify();
            assertEquals(0, first.next().get("total").asInt());
            assertEquals(0, second.next().get("total").asInt());
            a.sendMessage(new TextMessage("ping"));
            assertEquals("pong", first.next().get("type").asText());
        } finally {
            a.close();
            b.close();
        }
    }

    static class CapturingHandler extends TextWebSocketHandler {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.offer(message.getPayload());
        }

        JsonNode next() throws Exception {
            String value = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(value, "Expected a server-initiated WebSocket message");
            return new ObjectMapper().readTree(value);
        }
    }

    @TestConfiguration
    static class FixtureConfiguration {
        @Bean
        SessionFixture sessionFixture() {
            return new SessionFixture();
        }
    }

    // 仅在测试上下文注册，用于验证真实 Servlet Session 的 WebSocket 握手。
    @RestController
    static class SessionFixture {
        @GetMapping("/test/order-reminder-session")
        public String login(HttpServletRequest request) {
            request.getSession().setAttribute("employee", 1L);
            return "ok";
        }
    }
}
