package com.example.shizhan.rubbish.messaging;

import com.example.shizhan.rubbish.entity.WasteOrderEvent;
import com.example.shizhan.rubbish.mapper.WasteOrderEventMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.time.*;

/** 上报与发件记录同事务落库；宕机、未确认和未路由均可由扫描恢复。 */
@Component
@Slf4j
public class FullBinOutboxPublisher {
    private final WasteOrderEventMapper events;private final RabbitTemplate rabbit;
    public FullBinOutboxPublisher(WasteOrderEventMapper events,RabbitTemplate rabbit){this.events=events;this.rabbit=rabbit;}
    @Scheduled(fixedDelayString="${smartwaste.orders.dispatch-millis:2000}",initialDelay=3000)
    public void publish(){
        try{
            LocalDateTime now=LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
            for(WasteOrderEvent event:events.dispatchable(now)){
                if(events.claim(event.getId(),now,now.plusSeconds(60))!=1)continue;
                WasteOrderEvent current=events.selectById(event.getId());
                if(!"SENDING".equals(current.getState()))continue;
                int attempt=current.getPublishAttempts();
                try{
                    Message message=MessageBuilder.withBody(event.getId().toString().getBytes(StandardCharsets.UTF_8))
                            .setMessageId(event.getId().toString()).setContentType("text/plain").setDeliveryMode(MessageDeliveryMode.PERSISTENT).setHeader("publishAttempt",attempt).build();
                    rabbit.send(FullBinMessaging.EXCHANGE,FullBinMessaging.KEY,message,new CorrelationData(event.getId()+":"+attempt));
                }catch(RuntimeException ex){events.publishFailed(event.getId(),attempt,"发送失败，等待重试",now.plusSeconds(Math.min(60,5L*attempt)));log.warn("满桶事件发送暂未成功，eventId={}",event.getId());}
            }
        }catch(RuntimeException ex){log.warn("满桶事件派发暂不可用，将在下一轮重试");}
    }
}
