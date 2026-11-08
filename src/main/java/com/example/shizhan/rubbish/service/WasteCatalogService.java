package com.example.shizhan.rubbish.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.example.shizhan.rubbish.common.BusinessChecks;
import com.example.shizhan.rubbish.common.BusinessException;
import com.example.shizhan.rubbish.dto.DeviceProvisionResult;
import com.example.shizhan.rubbish.entity.WasteBin;
import com.example.shizhan.rubbish.entity.WasteStation;
import com.example.shizhan.rubbish.mapper.WasteBinMapper;
import com.example.shizhan.rubbish.mapper.WasteStationMapper;
import com.example.shizhan.rubbish.mapper.WasteOrderEventMapper;
import com.example.shizhan.rubbish.utils.PasswordUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

@Service
public class WasteCatalogService {
    private final WasteBinMapper bins;
    private final WasteStationMapper stations;
    private final CatalogCache cache;
    private final WasteOrderEventMapper events;
    private final SecureRandom random = new SecureRandom();

    public WasteCatalogService(WasteBinMapper bins, WasteStationMapper stations, CatalogCache cache, WasteOrderEventMapper events) {
        this.bins = bins;
        this.stations = stations;
        this.cache=cache; this.events=events;
    }

    @Transactional
    public WasteStation saveStation(WasteStation station) {
        station.setName(BusinessChecks.text(station.getName(), "垃圾站名称", 64));
        station.setAddress(BusinessChecks.text(station.getAddress(), "垃圾站地址", 255));
        BusinessChecks.coordinates(station.getLongitude(), station.getLatitude());
        station.setStatus(BusinessChecks.enabled(station.getStatus()));
        if (station.getId() == null) {
            station.setId(IdWorker.getId());
            stations.insert(station);
        } else {
            if (stations.selectById(station.getId()) == null) throw new BusinessException("垃圾站不存在");
            if (station.getStatus() == 0 && bins.selectCount(new LambdaQueryWrapper<WasteBin>()
                    .eq(WasteBin::getStationId, station.getId()).eq(WasteBin::getStatus, 1)) > 0) {
                throw new BusinessException("请先停用或调整该垃圾站关联的垃圾桶");
            }
            stations.updateById(station);
        }
        cache.changed();
        return station;
    }

    @Transactional
    public DeviceProvisionResult saveBin(WasteBin input) {
        WasteBin bin = input.getId() == null ? new WasteBin() : bins.lockById(input.getId());
        if (bin == null) throw new BusinessException("垃圾桶不存在");
        WasteStation station = input.getStationId() == null ? null : stations.selectById(input.getStationId());
        if (station == null || station.getStatus() != 1) throw new BusinessException("请选择已启用的垃圾站");
        int status = BusinessChecks.enabled(input.getStatus());
        if ((bin.getActiveOrderId() != null || (bin.getId()!=null && events.pendingForBin(bin.getId())>0)) && (status == 0 || !Objects.equals(bin.getStationId(), input.getStationId()))) {
            throw new BusinessException("该桶存在未完成清运订单，暂不能停用或更换垃圾站");
        }
        bin.setCode(BusinessChecks.text(input.getCode(), "垃圾桶编号", 64));
        bin.setName(BusinessChecks.text(input.getName(), "垃圾桶名称", 64));
        bin.setAddress(BusinessChecks.text(input.getAddress(), "投放点地址", 255));
        bin.setWasteType(BusinessChecks.text(input.getWasteType(), "可投放类型", 64));
        BusinessChecks.coordinates(input.getLongitude(), input.getLatitude());
        bin.setLongitude(input.getLongitude());
        bin.setLatitude(input.getLatitude());
        bin.setStationId(station.getId());
        bin.setStatus(status);
        String deviceId = input.getDeviceId() == null || input.getDeviceId().trim().isEmpty()
                ? null : BusinessChecks.text(input.getDeviceId(), "设备编号", 64);
        String token = null;
        if (!Objects.equals(deviceId, bin.getDeviceId())) {
            bin.setDeviceId(deviceId);
            token = deviceId == null ? null : newToken();
            bin.setDeviceTokenHash(token == null ? null : PasswordUtils.encode(token));
        }
        if (bin.getId() == null) {
            bin.setId(IdWorker.getId());
            bin.setFillStatus(0);
            bin.setFillCycle(0L);
            bins.insert(bin);
        } else {
            bins.update(null, new LambdaUpdateWrapper<WasteBin>().eq(WasteBin::getId, bin.getId())
                    .set(WasteBin::getCode, bin.getCode()).set(WasteBin::getName, bin.getName())
                    .set(WasteBin::getAddress, bin.getAddress()).set(WasteBin::getWasteType, bin.getWasteType())
                    .set(WasteBin::getLongitude, bin.getLongitude()).set(WasteBin::getLatitude, bin.getLatitude())
                    .set(WasteBin::getStationId, bin.getStationId()).set(WasteBin::getStatus, bin.getStatus())
                    .set(WasteBin::getDeviceId, bin.getDeviceId()).set(WasteBin::getDeviceTokenHash, bin.getDeviceTokenHash()));
        }
        cache.changed();
        return new DeviceProvisionResult(bin, token);
    }

    @Transactional
    public DeviceProvisionResult resetDeviceToken(Long id) {
        WasteBin bin = bins.lockById(id);
        if (bin == null || bin.getDeviceId() == null) throw new BusinessException("该桶未绑定设备");
        String token = newToken();
        bin.setDeviceTokenHash(PasswordUtils.encode(token));
        bins.updateById(bin);
        return new DeviceProvisionResult(bin, token);
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
