package com.example.shizhan.rubbish.messaging;

import com.example.shizhan.rubbish.mapper.WasteOrderEventMapper;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.*;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Configuration
public class FullBinMessaging {
    public static final String EXCHANGE="smartwaste.full-bin",QUEUE="smartwaste.full-bin.orders",KEY="full-bin",DEAD_EXCHANGE="smartwaste.full-bin.failed",DEAD_QUEUE="smartwaste.full-bin.failed.orders";
    @Bean public Declarables fullBinTopology(){
        DirectExchange exchange=new DirectExchange(EXCHANGE,true,false),dead=new DirectExchange(DEAD_EXCHANGE,true,false);
        Queue queue=QueueBuilder.durable(QUEUE).withArgument("x-dead-letter-exchange",DEAD_EXCHANGE).withArgument("x-dead-letter-routing-key","failed").build();
        Queue failed=QueueBuilder.durable(DEAD_QUEUE).build();
        return new Declarables(exchange,dead,queue,failed,BindingBuilder.bind(queue).to(exchange).with(KEY),BindingBuilder.bind(failed).to(dead).with("failed"));
    }
    @Bean public RabbitTemplate rabbitTemplate(ConnectionFactory factory,WasteOrderEventMapper events){
        RabbitTemplate template=new RabbitTemplate(factory);template.setMandatory(true);
        template.setConfirmCallback((data,ack,cause)->{
            if(data==null)return;
            String[] id=data.getId().split(":");
            if(ack)events.confirmed(Long.valueOf(id[0]),Integer.parseInt(id[1]));
            else events.publishFailed(Long.valueOf(id[0]),Integer.parseInt(id[1]),"消息代理未确认",LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusSeconds(10));
        });
        template.setReturnCallback((message,code,text,exchange,key)->{
            Object attempt=message.getMessageProperties().getHeaders().get("publishAttempt");
            events.publishFailed(Long.valueOf(message.getMessageProperties().getMessageId()),((Number)attempt).intValue(),"消息未路由至队列",LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusSeconds(10));
        });
        return template;
    }
}
