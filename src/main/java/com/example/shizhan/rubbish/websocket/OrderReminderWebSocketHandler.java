package com.example.shizhan.rubbish.websocket;

import com.example.shizhan.rubbish.dto.OrderReminderNotice;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OrderReminderWebSocketHandler extends TextWebSocketHandler {
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final com.example.shizhan.rubbish.security.TokenService tokens;
    public OrderReminderWebSocketHandler(com.example.shizhan.rubbish.security.TokenService tokens){this.tokens=tokens;}

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(session, 5000, 16384);
        sessions.put(session.getId(), safeSession);
        send(safeSession, "{\"type\":\"order-reminders.ready\"}");
    }

    @EventListener
    public void onNotice(OrderReminderNotice notice) throws IOException {
        String message = objectMapper.writeValueAsString(notice);
        sessions.values().forEach(session -> send(session, message));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        WebSocketSession safeSession = sessions.get(session.getId());
        if (safeSession != null && "ping".equals(message.getPayload())) send(safeSession, "{\"type\":\"pong\"}");
    }

    private void send(WebSocketSession session, String payload) {
        try {
            com.example.shizhan.rubbish.security.AuthPrincipal principal=(com.example.shizhan.rubbish.security.AuthPrincipal)session.getAttributes().get("principal");
            if (principal == null || !"admin".equals(principal.getRole())) {
                removeAndClose(session, CloseStatus.POLICY_VIOLATION);
            } else if (session.isOpen()) {
                tokens.validateSession(principal);
                session.sendMessage(new TextMessage(payload));
            } else {
                sessions.remove(session.getId());
            }
        } catch (com.example.shizhan.rubbish.security.AuthFailure ex) {
            // Access Token 到期时重建连接，客户端刷新令牌后获取新的一次性连接票据。
            removeAndClose(session, new CloseStatus(4001,"reauthenticate"));
        } catch (IOException | RuntimeException ex) {
            removeAndClose(session, CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        removeAndClose(session, CloseStatus.SERVER_ERROR);
    }

    private void removeAndClose(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        try {
            session.close(status);
        } catch (IOException | RuntimeException ignored) {
            // 连接可能已经由容器关闭。
        }
    }
}
