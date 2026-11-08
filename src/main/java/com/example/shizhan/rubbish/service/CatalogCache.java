package com.example.shizhan.rubbish.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.shizhan.rubbish.common.JacksonObjectMapper;
import com.example.shizhan.rubbish.entity.*;
import com.example.shizhan.rubbish.mapper.*;
import com.github.benmanes.caffeine.cache.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** 两级缓存仅存基础资料；桶的满空状态、有效任务、设备密钥始终从数据库读取。 */
@Service
@Slf4j
public class CatalogCache {
    private final Cache<String,String> local=Caffeine.newBuilder().maximumSize(200).expireAfterWrite(60,TimeUnit.SECONDS).build();
    private final StringRedisTemplate redis;
    private final CatalogRevisionMapper revisions;
    private final WasteBinMapper bins;
    private final WasteStationMapper stations;
    private final JacksonObjectMapper json=new JacksonObjectMapper();
    public CatalogCache(StringRedisTemplate redis,CatalogRevisionMapper revisions,WasteBinMapper bins,WasteStationMapper stations){this.redis=redis;this.revisions=revisions;this.bins=bins;this.stations=stations;}
    public List<WasteBin> bins(){
        List<WasteBin> result=read("bins",WasteBin.class,()->bins.selectList(new LambdaQueryWrapper<WasteBin>()
                .select(WasteBin::getId,WasteBin::getCode,WasteBin::getName,WasteBin::getAddress,WasteBin::getWasteType,
                        WasteBin::getLongitude,WasteBin::getLatitude,WasteBin::getStationId,WasteBin::getStatus)
                .eq(WasteBin::getStatus,1).orderByAsc(WasteBin::getCode)));
        Map<Long,WasteBin> live=new HashMap<>();
        for(WasteBin b:bins.selectList(new LambdaQueryWrapper<WasteBin>().select(WasteBin::getId,WasteBin::getFillStatus,WasteBin::getActiveOrderId,WasteBin::getLastReportTime,WasteBin::getLastEmptiedAt).eq(WasteBin::getStatus,1)))live.put(b.getId(),b);
        result.removeIf(b->!live.containsKey(b.getId()));
        for(WasteBin b:result){WasteBin state=live.get(b.getId());b.setFillStatus(state.getFillStatus());b.setActiveOrderId(state.getActiveOrderId());b.setLastReportTime(state.getLastReportTime());b.setLastEmptiedAt(state.getLastEmptiedAt());}
        return result;
    }
    public List<WasteStation> stations(){return read("stations",WasteStation.class,()->stations.selectList(new LambdaQueryWrapper<WasteStation>().eq(WasteStation::getStatus,1).orderByAsc(WasteStation::getName)));}
    private <T> List<T> read(String kind,Class<T> type,Supplier<List<T>> loader){
        // 版本号与资料写入同一事务：跨实例和 Redis 故障恢复后均不会读取旧版本缓存。
        String key="sw:catalog:v2:"+revisions.revision()+":"+kind;
        String value=local.getIfPresent(key);
        if(value==null)try{value=redis.opsForValue().get(key);}catch(RuntimeException ex){log.debug("资料缓存不可用，回源数据库");}
        try{
            if(value==null){value=json.writeValueAsString(loader.get());try{redis.opsForValue().set(key,value,Duration.ofMinutes(10));}catch(RuntimeException ex){log.debug("跳过资料缓存写入");}}
            List<T> result=json.readValue(value,json.getTypeFactory().constructCollectionType(ArrayList.class,type));
            local.put(key,value);return result;
        }catch(java.io.IOException ex){local.invalidate(key);try{redis.delete(key);}catch(RuntimeException ignored){}return loader.get();}
    }
    public void changed(){
        revisions.advance();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){local.invalidateAll();}});
    }
}
