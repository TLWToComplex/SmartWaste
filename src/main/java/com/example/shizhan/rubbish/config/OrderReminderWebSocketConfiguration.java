package com.example.shizhan.rubbish.config;

import com.example.shizhan.rubbish.websocket.AdminReminderHandshakeInterceptor;
import com.example.shizhan.rubbish.websocket.OrderReminderWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class OrderReminderWebSocketConfiguration implements WebSocketConfigurer {
    private final OrderReminderWebSocketHandler handler;
    private final com.example.shizhan.rubbish.security.TokenService tokens;
    private final com.example.shizhan.rubbish.websocket.VehicleWebSocketHandler vehicles;

    public OrderReminderWebSocketConfiguration(OrderReminderWebSocketHandler handler, com.example.shizhan.rubbish.security.TokenService tokens, com.example.shizhan.rubbish.websocket.VehicleWebSocketHandler vehicles) {
        this.handler = handler;
        this.tokens=tokens;this.vehicles=vehicles;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 保持默认同源限制，使用 JWT 身份签发的一次性票据建立管理端连接。
        registry.addHandler(handler, "/ws/admin/order-reminders")
                .addInterceptors(new AdminReminderHandshakeInterceptor(tokens));
        registry.addHandler(vehicles,"/ws/admin/vehicles").addInterceptors(new AdminReminderHandshakeInterceptor(tokens));
    }
}
