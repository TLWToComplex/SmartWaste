package com.example.shizhan.rubbish.service;

import com.example.shizhan.rubbish.common.BusinessException;
import org.redisson.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
@lombok.extern.slf4j.Slf4j
public class OrderConcurrency {
    private final RedissonClient redisson;
    private final StringRedisTemplate redis;
    private final TransactionTemplate transaction;
    private final DefaultRedisScript<Long> gate=new DefaultRedisScript<>("if redis.call('SET',KEYS[1],ARGV[1],'NX','PX',ARGV[2]) then return 1 else return 0 end",Long.class);
    private final DefaultRedisScript<Long> release=new DefaultRedisScript<>("if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) else return 0 end",Long.class);
    public OrderConcurrency(RedissonClient redisson,StringRedisTemplate redis,PlatformTransactionManager manager){
        this.redisson=redisson;this.redis=redis;this.transaction=new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public <T>T bin(Long id,Supplier<T> action){
        if(id==null)throw new BusinessException("请选择垃圾桶");
        RLock lock=redisson.getLock("sw:lock:bin:"+id);
        boolean acquired=false;
        try{
            // 不指定固定租期，使用 Redisson 看门狗续期；数据库提交后才释放锁。
            acquired=lock.tryLock(2,TimeUnit.SECONDS);
            if(!acquired)throw new BusinessException("该桶正在处理，请稍后重试");
            return transaction.execute(status->action.get());
        }catch(InterruptedException ex){Thread.currentThread().interrupt();throw new BusinessException("操作被中断，请重试");}
        finally{if(acquired)try{if(lock.isHeldByCurrentThread())lock.unlock();}catch(RuntimeException ex){log.warn("桶锁释放暂未确认，等待租约恢复，binId={}",id);}}
    }
    public <T>T accept(String orderId,Supplier<T> action){
        String key="sw:gate:accept:"+orderId,owner=UUID.randomUUID().toString();
        // Lua 只负责拦截同一订单的瞬时接单竞争；最终接单归属由数据库版本号保证。
        if(!Long.valueOf(1).equals(redis.execute(gate,Collections.singletonList(key),owner,"5000")))throw new BusinessException("该订单正在被接取，请刷新列表");
        try{return transaction.execute(status->action.get());}
        finally{try{redis.execute(release,Collections.singletonList(key),owner);}catch(RuntimeException ex){log.warn("接单短期保护键等待自动到期，orderId={}",orderId);}}
    }
    public <T>T transaction(Supplier<T> action){return transaction.execute(status->action.get());}
}
