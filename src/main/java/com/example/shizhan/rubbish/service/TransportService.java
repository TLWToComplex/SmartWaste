package com.example.shizhan.rubbish.service;

import com.example.shizhan.rubbish.common.*;
import com.example.shizhan.rubbish.entity.*;
import com.example.shizhan.rubbish.mapper.*;
import com.example.shizhan.rubbish.websocket.VehicleWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import lombok.extern.slf4j.Slf4j;
import java.math.*;
import java.time.*;
import java.util.*;

@Service
@Slf4j
public class TransportService {
    private final OrderMapper ordersMapper;private final DriverMapper drivers;private final WasteOrderRouteMapper routes;private final DriverLocationMapper locations;
    private final StringRedisTemplate redis;private final VehicleWebSocketHandler sockets;
    private final JacksonObjectMapper json=new JacksonObjectMapper();
    private final RestTemplate http;
    @Value("${smartwaste.amap.web-key:}")private String webKey;
    @Value("${smartwaste.amap.js-key:}")private String jsKey;
    @Value("${smartwaste.amap.security-js-code:}")private String securityCode;
    public TransportService(OrderMapper ordersMapper,DriverMapper drivers,WasteOrderRouteMapper routes,DriverLocationMapper locations,StringRedisTemplate redis,VehicleWebSocketHandler sockets){
        this.ordersMapper=ordersMapper;this.drivers=drivers;this.routes=routes;this.locations=locations;this.redis=redis;this.sockets=sockets;
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(3000);factory.setReadTimeout(8000);http=new RestTemplate(factory);
    }
    public Map<String,String> mapConfig(){Map<String,String> result=new HashMap<>();result.put("jsKey",jsKey);result.put("securityJsCode",securityCode);return result;}
    public WasteOrderRoute route(BigInteger id,Long driverId,boolean admin){
        orders order=ordersMapper.selectById(id);
        if(order==null)throw new BusinessException("订单不存在");
        if(!admin && !(driverId!=null&&(order.getStatus()==1||driverId.equals(order.getDriverId()))))throw new BusinessException("无权查看该清运路线");
        String origin=coordinate(order.getLongitude(),order.getLatitude()),destination=coordinate(order.getLongitudeEnd(),order.getLatitudeEnd());
        WasteOrderRoute saved=routes.selectById(id);
        if(saved!=null&&origin.equals(saved.getOrigin())&&destination.equals(saved.getDestination()))return saved;
        if(webKey.trim().isEmpty())throw new BusinessException("请配置高德 Web 服务密钥 SMARTWASTE_AMAP_WEB_KEY");
        try{
            java.net.URI uri=UriComponentsBuilder.fromHttpUrl("https://restapi.amap.com/v3/direction/driving")
                    .queryParam("key",webKey).queryParam("origin",origin).queryParam("destination",destination).queryParam("strategy",0).queryParam("extensions","base").build().encode().toUri();
            JsonNode data=json.readTree(http.getForObject(uri,String.class));
            if(!"1".equals(data.path("status").asText())||data.path("route").path("paths").size()==0)throw new BusinessException("高德未返回可用驾车路线，请检查坐标与服务权限");
            JsonNode path=data.path("route").path("paths").get(0);List<String> points=new ArrayList<>();
            for(JsonNode step:path.path("steps"))for(String point:step.path("polyline").asText().split(";"))if(!point.isEmpty()&&(points.isEmpty()||!points.get(points.size()-1).equals(point)))points.add(point);
            if(points.isEmpty())throw new BusinessException("路线坐标为空");
            WasteOrderRoute result=new WasteOrderRoute();result.setOrderId(id);result.setOrigin(origin);result.setDestination(destination);
            result.setDistanceMeters(path.path("distance").asLong());result.setDurationSeconds(path.path("duration").asLong());
            result.setPolyline(String.join(";",points));result.setUpdatedAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
            if(saved==null)try{routes.insert(result);}catch(DuplicateKeyException ex){routes.updateById(result);}else routes.updateById(result);
            return result;
        }catch(BusinessException ex){throw ex;}catch(Exception ex){throw new BusinessException("路线服务暂不可用，请稍后重试");}
    }
    private String coordinate(String lng,String lat){try{BigDecimal x=new BigDecimal(lng),y=new BigDecimal(lat);BusinessChecks.coordinates(x,y);return x.toPlainString()+","+y.toPlainString();}catch(Exception ex){throw new BusinessException("订单缺少有效桶站坐标，请完善桶站资料后重新上报");}}
    @Transactional public DriverLocation report(Long driverId,DriverLocation input){
        if(!"GCJ02".equals(input.getCoordinateSystem()))throw new BusinessException("请使用高德 GCJ02 坐标上报位置");
        BusinessChecks.coordinates(input.getLongitude(),input.getLatitude());
        long now=System.currentTimeMillis();
        if(input.getObservedAt()==null||input.getObservedAt()>now+60000||input.getObservedAt()<now-300000)throw new BusinessException("定位时间已过期，请重新获取位置");
        if(input.getAccuracyMeters()!=null&&(!Double.isFinite(input.getAccuracyMeters())||input.getAccuracyMeters()<0||input.getAccuracyMeters()>1000))throw new BusinessException("定位精度不足，请重新定位");
        orders order=input.getOrderId()==null?null:ordersMapper.selectById(input.getOrderId());
        if(order==null||order.getStatus()!=2||!driverId.equals(order.getDriverId()))throw new BusinessException("只能上报本人运输中订单的位置");
        Driver driver=drivers.selectById(driverId);
        DriverLocation position=new DriverLocation();position.setDriverId(driverId);position.setOrderId(order.getId());position.setCarNumber(driver.getCarNumber());
        position.setLongitude(input.getLongitude());position.setLatitude(input.getLatitude());position.setObservedAt(input.getObservedAt());position.setAccuracyMeters(input.getAccuracyMeters());
        position.setUpdatedAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        if(locations.newer(position)==0){
            DriverLocation old=locations.selectById(driverId);
            if(old!=null)return old;
            try{locations.insert(position);}catch(DuplicateKeyException ex){if(locations.newer(position)==0)return locations.selectById(driverId);}
        }
        position.setDriverName(driver.getName());position.setOrderNumber(order.getNumber());position.setOrderStatus(order.getStatus());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){
            try{Map<String,Object> notice=new LinkedHashMap<>();notice.put("type","vehicle.updated");notice.put("data",position);String payload=json.writeValueAsString(notice);
                try{redis.convertAndSend(VehicleWebSocketHandler.TOPIC,payload);}catch(RuntimeException ex){sockets.broadcast(payload);log.warn("位置跨实例广播暂不可用，管理端可刷新定位快照");}
            }catch(java.io.IOException ex){log.warn("位置通知序列化失败");}
        }});
        return position;
    }
    public List<DriverLocation> snapshots(){return locations.snapshots();}
    @org.springframework.transaction.event.TransactionalEventListener(phase=org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void orderChanged(com.example.shizhan.rubbish.event.OrderStatusChangedEvent event){
        String notice="{\"type\":\"vehicles.refresh\"}";
        try{redis.convertAndSend(VehicleWebSocketHandler.TOPIC,notice);}catch(RuntimeException ex){sockets.broadcast(notice);}
    }
}
