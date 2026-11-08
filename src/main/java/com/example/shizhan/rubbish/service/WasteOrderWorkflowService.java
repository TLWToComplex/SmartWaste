package com.example.shizhan.rubbish.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.example.shizhan.rubbish.common.*;
import com.example.shizhan.rubbish.dto.FullBinReportReceipt;
import com.example.shizhan.rubbish.entity.*;
import com.example.shizhan.rubbish.event.OrderStatusChangedEvent;
import com.example.shizhan.rubbish.mapper.*;
import com.example.shizhan.rubbish.utils.PasswordUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import java.math.BigInteger;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.function.Supplier;

@Service
public class WasteOrderWorkflowService {
    private final WasteBinMapper bins;
    private final WasteStationMapper stations;
    private final OrderMapper ordersMapper;
    private final FullBinReportMapper reports;
    private final WasteOrderEventMapper events;
    private final UserMapper users;
    private final DriverMapper drivers;
    private final OrderConcurrency concurrency;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public WasteOrderWorkflowService(WasteBinMapper bins,WasteStationMapper stations,OrderMapper ordersMapper,
            FullBinReportMapper reports,WasteOrderEventMapper events,UserMapper users,DriverMapper drivers,
            OrderConcurrency concurrency,ApplicationEventPublisher publisher,@Qualifier("orderReminderClock") Clock clock){
        this.bins=bins;this.stations=stations;this.ordersMapper=ordersMapper;this.reports=reports;this.events=events;
        this.users=users;this.drivers=drivers;this.concurrency=concurrency;this.publisher=publisher;this.clock=clock;
    }
    public FullBinReportReceipt reportByUser(Long binId,String requestId,Long userId){
        User user=users.selectById(userId);
        if(user==null||user.getStatus()!=1)throw new BusinessException("用户不存在或已停用");
        return concurrency.bin(binId,()->acceptReport(binId,requestId,"USER",userId.toString(),null));
    }
    public FullBinReportReceipt reportByDevice(Long binId,String requestId,String deviceId,String token){
        String actor=BusinessChecks.text(deviceId,"设备编号",64);
        return concurrency.bin(binId,()->acceptReport(binId,requestId,"DEVICE",actor,token));
    }
    private FullBinReportReceipt acceptReport(Long binId,String requestId,String source,String actor,String token){
        requestId=BusinessChecks.text(requestId,"上报请求编号",64);
        WasteBin bin=bins.lockById(binId);
        if(bin==null)throw new BusinessException("垃圾桶不存在");
        if("DEVICE".equals(source))verifyDevice(bin,actor,token);
        WasteOrderEvent previous=events.selectOne(new LambdaQueryWrapper<WasteOrderEvent>()
                .eq(WasteOrderEvent::getBinId,binId).eq(WasteOrderEvent::getSource,source)
                .eq(WasteOrderEvent::getActorId,actor).eq(WasteOrderEvent::getRequestId,requestId));
        if(previous!=null)return receipt(previous,true);
        // 兼容切换异步流程之前的请求编号，重试不会重新建单。
        FullBinReport legacy=reports.selectOne(new LambdaQueryWrapper<FullBinReport>().eq(FullBinReport::getBinId,binId)
                .eq(FullBinReport::getSource,source).eq(FullBinReport::getActorId,actor).eq(FullBinReport::getRequestId,requestId));
        if(legacy!=null){
            orders order=ordersMapper.selectById(legacy.getOrderId());
            return new FullBinReportReceipt(order.getId(),order.getNumber(),order.getStatus(),true,null,"DONE","该上报已处理");
        }
        if(bin.getStatus()!=1)throw new BusinessException("垃圾桶已停用");
        WasteStation station=stations.selectById(bin.getStationId());
        if(station==null||station.getStatus()!=1)throw new BusinessException("垃圾站不可用");
        LocalDateTime now=LocalDateTime.now(clock);
        WasteOrderEvent event=new WasteOrderEvent();
        event.setId(IdWorker.getId());event.setBinId(binId);event.setBinCycle(bin.getFillCycle());
        event.setSource(source);event.setActorId(actor);event.setRequestId(requestId);event.setReportedAt(now);
        event.setState("NEW");event.setPublishAttempts(0);event.setConsumeAttempts(0);
        event.setNextAttemptAt(now);event.setUpdatedAt(now);
        events.insert(event);
        bins.update(null,new LambdaUpdateWrapper<WasteBin>().eq(WasteBin::getId,binId)
                .set(WasteBin::getFillStatus,1).set(WasteBin::getLastReportTime,now));
        // 此处只确认受理，上报事件和发件记录已持久化，清运订单由 RabbitMQ 消费者创建。
        return receipt(event,false);
    }
    public void verifyDevice(WasteBin bin,String deviceId,String token){
        if(bin==null||!Objects.equals(deviceId,bin.getDeviceId())||!PasswordUtils.matches(token,bin.getDeviceTokenHash()))
            throw new BusinessException("设备身份校验失败");
    }
    public void processEvent(Long eventId){
        WasteOrderEvent snapshot=events.selectById(eventId);
        if(snapshot==null)throw new BusinessException("上报事件不存在");
        if("DONE".equals(snapshot.getState()))return;
        concurrency.bin(snapshot.getBinId(),()->{
            WasteBin bin=bins.lockById(snapshot.getBinId());
            WasteOrderEvent event=events.lockById(eventId);
            if("DONE".equals(event.getState()))return null;
            if("FAILED".equals(event.getState()))throw new BusinessException("事件等待人工重试");
            if(bin==null)throw new BusinessException("垃圾桶不存在");
            // 同一轮满桶的消息即使晚于清运完成到达，也只关联本轮原订单。
            orders order=ordersMapper.selectOne(new LambdaQueryWrapper<orders>().eq(orders::getBinId,bin.getId())
                    .eq(orders::getBinCycle,event.getBinCycle()).orderByDesc(orders::getId).last("LIMIT 1"));
            boolean current=Objects.equals(bin.getFillCycle(),event.getBinCycle());
            if(order==null){
                if(!current)throw new BusinessException("上报所属清运轮次已结束");
                if(bin.getStatus()!=1)throw new BusinessException("垃圾桶已停用");
                WasteStation station=stations.selectById(bin.getStationId());
                if(station==null||station.getStatus()!=1)throw new BusinessException("垃圾站不可用");
                order=new orders();order.setId(BigInteger.valueOf(IdWorker.getId()));order.setNumber("SW"+order.getId());
                order.setStatus(1);order.setVersion(0);order.setBinId(bin.getId());order.setActiveBinId(bin.getId());
                order.setBinCycle(event.getBinCycle());order.setStationId(station.getId());order.setReportSource(event.getSource());
                order.setOrderTime(event.getReportedAt().format(TIME));order.setStartAddress(bin.getAddress());order.setDestination(station.getAddress());
                order.setLongitude(bin.getLongitude().toPlainString());order.setLatitude(bin.getLatitude().toPlainString());
                order.setLongitudeEnd(station.getLongitude().toPlainString());order.setLatitudeEnd(station.getLatitude().toPlainString());
                if("USER".equals(event.getSource())){
                    User user=users.selectById(Long.valueOf(event.getActorId()));
                    if(user==null)throw new BusinessException("上报用户不存在");
                    order.setUserId(user.getId());order.setUserName(user.getName());order.setUserPhone(user.getPhone());order.setAccount(user.getUsername());
                }else order.setUserName("设备满桶上报");
                ordersMapper.insert(order);
                bins.update(null,new LambdaUpdateWrapper<WasteBin>().eq(WasteBin::getId,bin.getId())
                        .set(WasteBin::getActiveOrderId,order.getId()));
            }
            FullBinReport report=reports.selectOne(new LambdaQueryWrapper<FullBinReport>().eq(FullBinReport::getBinId,event.getBinId())
                    .eq(FullBinReport::getSource,event.getSource()).eq(FullBinReport::getActorId,event.getActorId()).eq(FullBinReport::getRequestId,event.getRequestId()));
            if(report==null){
                report=new FullBinReport();report.setId(IdWorker.getId());report.setBinId(event.getBinId());report.setOrderId(order.getId());
                report.setSource(event.getSource());report.setActorId(event.getActorId());report.setRequestId(event.getRequestId());report.setReportedAt(event.getReportedAt());
                reports.insert(report);
            }
            events.update(null,new LambdaUpdateWrapper<WasteOrderEvent>().eq(WasteOrderEvent::getId,eventId)
                    .set(WasteOrderEvent::getState,"DONE").set(WasteOrderEvent::getOrderId,order.getId())
                    .set(WasteOrderEvent::getUpdatedAt,LocalDateTime.now(clock)).set(WasteOrderEvent::getLastError,null));
            publisher.publishEvent(new OrderStatusChangedEvent());
            return null;
        });
    }
    public orders accept(BigInteger orderId,Long driverId){
        return concurrency.accept(orderId.toString(),()->{
            Driver driver=drivers.selectById(driverId);
            if(driver==null||driver.getStatus()!=1)throw new BusinessException("司机不存在或已停用");
            if(driver.getCarNumber()==null||driver.getCarNumber().trim().isEmpty())throw new BusinessException("请先完善司机车辆信息");
            orders order=requiredOrder(orderId);
            if(order.getStatus()==2&&Objects.equals(order.getDriverId(),driverId))return order;
            if(order.getStatus()!=1)throw new BusinessException("订单已被接取或已结束，请刷新列表");
            int updated=ordersMapper.update(null,versioned(order).set(orders::getStatus,2).set(orders::getDriverId,driverId)
                    .set(orders::getDriverPhone,driver.getPhone()).set(orders::getAccount1,driver.getUsername())
                    .set(orders::getCarNumber,driver.getCarNumber()));
            checkUpdated(updated);
            publisher.publishEvent(new OrderStatusChangedEvent());
            return ordersMapper.selectById(orderId);
        });
    }
    public orders complete(BigInteger id,Long driverId,boolean admin){
        return withOrder(id,()->{
            orders order=ordersMapper.lockById(id);
            if(!admin&&!Objects.equals(order.getDriverId(),driverId))throw new BusinessException("只能完成自己接取的订单");
            if(order.getStatus()==3)return order;
            if(order.getStatus()!=2)throw new BusinessException("只有运输中的订单可以完成");
            LocalDateTime now=LocalDateTime.now(clock);
            checkUpdated(ordersMapper.update(null,versioned(order).set(orders::getStatus,3)
                    .set(orders::getOverTime,now.format(TIME)).set(orders::getActiveBinId,null)));
            releaseBin(order,true,now);
            publisher.publishEvent(new OrderStatusChangedEvent());
            return ordersMapper.selectById(id);
        });
    }
    public orders cancel(BigInteger id,Long userId,boolean admin){
        return withOrder(id,()->{
            orders order=ordersMapper.lockById(id);
            if(!admin&&(userId==null||!BigInteger.valueOf(userId).equals(order.getUserId())))throw new BusinessException("只能取消自己创建的订单");
            if(order.getStatus()==4)return order;
            if((!admin&&order.getStatus()!=1)||order.getStatus()==3)throw new BusinessException("该订单当前不能取消");
            checkUpdated(ordersMapper.update(null,versioned(order).set(orders::getStatus,4).set(orders::getActiveBinId,null)));
            // 取消不表示清空，保留满桶；推进轮次使后续新上报能生成替代任务。
            releaseBin(order,false,LocalDateTime.now(clock));
            publisher.publishEvent(new OrderStatusChangedEvent());
            return ordersMapper.selectById(id);
        });
    }
    private void releaseBin(orders order,boolean emptied,LocalDateTime now){
        if(order.getBinId()==null)return;
        LambdaUpdateWrapper<WasteBin> update=new LambdaUpdateWrapper<WasteBin>().eq(WasteBin::getId,order.getBinId())
                .eq(WasteBin::getActiveOrderId,order.getId()).set(WasteBin::getActiveOrderId,null).setSql("fill_cycle = fill_cycle + 1");
        if(emptied)update.set(WasteBin::getFillStatus,0).set(WasteBin::getLastEmptiedAt,now);
        bins.update(null,update);
    }
    private <T>T withOrder(BigInteger id,Supplier<T> action){
        orders current=requiredOrder(id);
        return current.getBinId()==null?concurrency.transaction(action):concurrency.bin(current.getBinId(),()->{bins.lockById(current.getBinId());return action.get();});
    }
    private orders requiredOrder(BigInteger id){orders order=ordersMapper.selectById(id);if(order==null)throw new BusinessException("订单不存在");return order;}
    private LambdaUpdateWrapper<orders> versioned(orders order){
        return new LambdaUpdateWrapper<orders>().eq(orders::getId,order.getId()).eq(orders::getStatus,order.getStatus())
                .eq(orders::getVersion,order.getVersion()).set(orders::getVersion,order.getVersion()+1);
    }
    private void checkUpdated(int count){if(count!=1)throw new BusinessException("订单状态已变化，请刷新重试");}
    public FullBinReportReceipt receipt(WasteOrderEvent event,boolean existing){
        orders order=event.getOrderId()==null?null:ordersMapper.selectById(event.getOrderId());
        return new FullBinReportReceipt(order==null?null:order.getId(),order==null?null:order.getNumber(),
                order==null?0:order.getStatus(),existing,event.getId(),event.getState(),
                "DONE".equals(event.getState())?"上报已处理":"FAILED".equals(event.getState())?"处理失败，等待管理端处理":"上报已受理，正在生成或关联清运订单");
    }
}
