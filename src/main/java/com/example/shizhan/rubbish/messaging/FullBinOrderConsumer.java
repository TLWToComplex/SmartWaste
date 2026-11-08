package com.example.shizhan.rubbish.messaging;

import com.example.shizhan.rubbish.entity.WasteOrderEvent;
import com.example.shizhan.rubbish.mapper.WasteOrderEventMapper;
import com.example.shizhan.rubbish.service.WasteOrderWorkflowService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.time.*;

@Component
@Slf4j
public class FullBinOrderConsumer {
    private final WasteOrderWorkflowService workflow;private final WasteOrderEventMapper events;
    @Value("${smartwaste.orders.consume-max-attempts:5}")private int maxAttempts;
    public FullBinOrderConsumer(WasteOrderWorkflowService workflow,WasteOrderEventMapper events){this.workflow=workflow;this.events=events;}
    @RabbitListener(queues=FullBinMessaging.QUEUE,ackMode="MANUAL")
    public void receive(Message message,Channel channel)throws java.io.IOException{
        long tag=message.getMessageProperties().getDeliveryTag();
        Long id;
        try{id=Long.valueOf(new String(message.getBody(),StandardCharsets.UTF_8));}catch(NumberFormatException ex){channel.basicReject(tag,false);return;}
        try{
            workflow.processEvent(id);
            // service 返回时数据库事务已提交；确认丢失导致的重投由事件 DONE 状态处理。
            channel.basicAck(tag,false);
        }catch(RuntimeException ex){
            try{
                WasteOrderEvent completed=events.selectById(id);
                if(completed!=null&&"DONE".equals(completed.getState())){channel.basicAck(tag,false);return;}
                String reason=ex instanceof com.example.shizhan.rubbish.common.BusinessException?ex.getMessage():"基础服务异常："+ex.getClass().getSimpleName();
                events.consumeFailed(id,Math.max(1,maxAttempts),reason.substring(0,Math.min(reason.length(),255)),LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
                WasteOrderEvent event=events.selectById(id);
                boolean retry=event!=null&&!"FAILED".equals(event.getState())&&!"DONE".equals(event.getState());
                channel.basicNack(tag,false,retry);
                log.warn("满桶事件消费失败，eventId={}，是否重试={}",id,retry);
            }catch(RuntimeException storageFailure){channel.basicNack(tag,false,true);log.warn("无法记录消费结果，保留消息等待恢复，eventId={}",id);}
        }
    }
}
