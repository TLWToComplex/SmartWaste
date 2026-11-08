package com.example.shizhan.rubbish;

import com.example.shizhan.rubbish.dto.OrderReminderNotice;
import com.example.shizhan.rubbish.websocket.AdminReminderHandshakeInterceptor;
import com.example.shizhan.rubbish.websocket.OrderReminderWebSocketHandler;
import com.example.shizhan.rubbish.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrderReminderWebSocketTest {
    private final TokenService tokens=mock(TokenService.class);
    @Test
    void handshakeRequiresAValidAdministratorTicket() {
        AdminReminderHandshakeInterceptor interceptor = new AdminReminderHandshakeInterceptor(tokens);
        when(tokens.useTicket(null)).thenThrow(new AuthFailure("缺少连接票据"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        Map<String, Object> attributes = new HashMap<>();
        assertFalse(interceptor.beforeHandshake(new ServletServerHttpRequest(request), new ServletServerHttpResponse(response),
                new OrderReminderWebSocketHandler(tokens), attributes));
        assertEquals(401, response.getStatus());
        AuthPrincipal principal = admin();
        request.setParameter("ticket","valid-ticket");
        when(tokens.useTicket("valid-ticket")).thenReturn(principal);
        assertTrue(interceptor.beforeHandshake(new ServletServerHttpRequest(request), new ServletServerHttpResponse(response),
                new OrderReminderWebSocketHandler(tokens), attributes));
        assertSame(principal, attributes.get("principal"));
    }

    @Test
    void broadcastsToAllOnlineAdministratorsAndStopsAfterLogout() throws Exception {
        OrderReminderWebSocketHandler handler = new OrderReminderWebSocketHandler(tokens);
        AuthPrincipal admin = admin();
        WebSocketSession first = socket("one", admin);
        WebSocketSession second = socket("two", admin);
        handler.afterConnectionEstablished(first);
        handler.afterConnectionEstablished(second);
        clearInvocations(first, second);
        handler.onNotice(new OrderReminderNotice("order-reminders.updated", 2, "2026-11-08 10:00:00"));
        verify(first).sendMessage(any(TextMessage.class));
        verify(second).sendMessage(any(TextMessage.class));
        clearInvocations(first, second);
        doThrow(new AuthFailure("登录已撤销")).when(tokens).validateSession(admin);
        handler.onNotice(new OrderReminderNotice("order-reminders.updated", 0, "2026-11-08 10:01:00"));
        verify(first, never()).sendMessage(any());
        verify(first).close(new CloseStatus(4001,"reauthenticate"));
        verify(second).close(new CloseStatus(4001,"reauthenticate"));
    }

    @Test
    void oneBrokenConnectionDoesNotPreventDeliveryToOtherAdministrators() throws Exception {
        OrderReminderWebSocketHandler handler = new OrderReminderWebSocketHandler(tokens);
        AuthPrincipal admin = admin();
        WebSocketSession broken = socket("broken", admin);
        WebSocketSession healthy = socket("healthy", admin);
        handler.afterConnectionEstablished(broken);
        handler.afterConnectionEstablished(healthy);
        clearInvocations(broken, healthy);
        doThrow(new IOException("disconnected")).when(broken).sendMessage(any());
        assertDoesNotThrow(() -> handler.onNotice(new OrderReminderNotice("order-reminders.updated", 1, "now")));
        verify(healthy).sendMessage(any(TextMessage.class));
        verify(broken).close(CloseStatus.SERVER_ERROR);
    }

    private AuthPrincipal admin(){AuthPrincipal p=new AuthPrincipal();p.setId(1L);p.setRole("admin");p.setSessionId("test-session");p.setExpiresAt(Long.MAX_VALUE);return p;}
    private WebSocketSession socket(String id, AuthPrincipal admin) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("principal", admin);
        when(session.getAttributes()).thenReturn(attributes);
        return session;
    }
}
