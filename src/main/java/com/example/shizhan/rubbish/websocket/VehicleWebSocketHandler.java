package com.example.shizhan.rubbish.websocket;

import com.example.shizhan.rubbish.security.*;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.listener.*;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/** Redis 发布订阅将位置广播到持有管理端连接的各后端实例，重连后由 HTTP 补齐快照。 */
@Component
public class VehicleWebSocketHandler extends TextWebSocketHandler implements MessageListener {
    public static final String TOPIC="sw:vehicles:position";
    private final Map<String,WebSocketSession> sessions=new ConcurrentHashMap<>();
    private final TokenService tokens;
    public VehicleWebSocketHandler(TokenService tokens,RedisMessageListenerContainer container){this.tokens=tokens;container.addMessageListener(this,new ChannelTopic(TOPIC));}
    @Override public void afterConnectionEstablished(WebSocketSession session){WebSocketSession safe=new ConcurrentWebSocketSessionDecorator(session,5000,524288);sessions.put(session.getId(),safe);send(safe,"{\"type\":\"vehicles.ready\"}");}
    @Override public void onMessage(Message message,byte[] pattern){broadcast(new String(message.getBody(),StandardCharsets.UTF_8));}
    public void broadcast(String message){sessions.values().forEach(s->send(s,message));}
    @Override protected void handleTextMessage(WebSocketSession session,TextMessage message){if("ping".equals(message.getPayload())){WebSocketSession safe=sessions.get(session.getId());if(safe!=null)send(safe,"{\"type\":\"pong\"}");}}
    private void send(WebSocketSession session,String payload){
        try{AuthPrincipal p=(AuthPrincipal)session.getAttributes().get("principal");if(p==null||!"admin".equals(p.getRole()))throw new AuthFailure("身份无效");tokens.validateSession(p);if(session.isOpen())session.sendMessage(new TextMessage(payload));else sessions.remove(session.getId());}
        catch(AuthFailure ex){close(session,new CloseStatus(4001,"reauthenticate"));}
        catch(Exception ex){close(session,CloseStatus.SERVER_ERROR);}
    }
    private void close(WebSocketSession session,CloseStatus status){sessions.remove(session.getId());try{session.close(status);}catch(Exception ignored){}}
    @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status){sessions.remove(session.getId());}
    @Override public void handleTransportError(WebSocketSession session,Throwable error){close(session,CloseStatus.SERVER_ERROR);}
}
