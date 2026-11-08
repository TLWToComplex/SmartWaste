package com.example.shizhan.rubbish.websocket;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import javax.servlet.http.HttpSession;
import java.util.Map;

public class AdminReminderHandshakeInterceptor implements HandshakeInterceptor {
    private final com.example.shizhan.rubbish.security.TokenService tokens;
    public AdminReminderHandshakeInterceptor(com.example.shizhan.rubbish.security.TokenService tokens){this.tokens=tokens;}
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest) {
            try {
                com.example.shizhan.rubbish.security.AuthPrincipal principal=tokens.useTicket(((ServletServerHttpRequest)request).getServletRequest().getParameter("ticket"));
                if("admin".equals(principal.getRole())){attributes.put("principal",principal);return true;}
            } catch (com.example.shizhan.rubbish.security.AuthFailure ignored) { }
        }
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) {
    }
}
