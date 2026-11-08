package com.example.shizhan.rubbish.controller.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.shizhan.rubbish.common.*;
import com.example.shizhan.rubbish.dto.DeviceProvisionResult;
import com.example.shizhan.rubbish.entity.WasteBin;
import com.example.shizhan.rubbish.entity.WasteStation;
import com.example.shizhan.rubbish.mapper.WasteBinMapper;
import com.example.shizhan.rubbish.mapper.WasteStationMapper;
import com.example.shizhan.rubbish.service.WasteCatalogService;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

@RestController
@RequestMapping("/waste")
public class WasteCatalogController {
    private final WasteCatalogService catalog;
    private final WasteBinMapper bins;
    private final WasteStationMapper stations;
    private final com.example.shizhan.rubbish.service.CatalogCache cache;
    public WasteCatalogController(WasteCatalogService catalog, WasteBinMapper bins, WasteStationMapper stations, com.example.shizhan.rubbish.service.CatalogCache cache) {
        this.catalog = catalog; this.bins = bins; this.stations = stations;
        this.cache=cache;
    }

    @GetMapping("/bins")
    public R<List<WasteBin>> bins(HttpServletRequest request) {
        requireLogin(request);
        if (!SessionIdentity.isAdmin(request)) return R.success(cache.bins());
        LambdaQueryWrapper<WasteBin> query = new LambdaQueryWrapper<>();
        if (!SessionIdentity.isAdmin(request)) query.eq(WasteBin::getStatus, 1);
        return R.success(bins.selectList(query.orderByAsc(WasteBin::getCode)));
    }

    @GetMapping("/stations")
    public R<List<WasteStation>> stations(HttpServletRequest request) {
        requireLogin(request);
        if (!SessionIdentity.isAdmin(request)) return R.success(cache.stations());
        LambdaQueryWrapper<WasteStation> query = new LambdaQueryWrapper<>();
        if (!SessionIdentity.isAdmin(request)) query.eq(WasteStation::getStatus, 1);
        return R.success(stations.selectList(query.orderByAsc(WasteStation::getName)));
    }

    @PostMapping("/stations")
    public R<WasteStation> createStation(@RequestBody WasteStation station, HttpServletRequest request) {
        SessionIdentity.requireAdmin(request); station.setId(null);
        return R.success(catalog.saveStation(station));
    }

    @PutMapping("/stations/{id}")
    public R<WasteStation> updateStation(@PathVariable Long id, @RequestBody WasteStation station, HttpServletRequest request) {
        SessionIdentity.requireAdmin(request); station.setId(id);
        return R.success(catalog.saveStation(station));
    }

    @PostMapping("/bins")
    public R<DeviceProvisionResult> createBin(@RequestBody WasteBin bin, HttpServletRequest request) {
        SessionIdentity.requireAdmin(request); bin.setId(null);
        return R.success(catalog.saveBin(bin));
    }

    @PutMapping("/bins/{id}")
    public R<DeviceProvisionResult> updateBin(@PathVariable Long id, @RequestBody WasteBin bin, HttpServletRequest request) {
        SessionIdentity.requireAdmin(request); bin.setId(id);
        return R.success(catalog.saveBin(bin));
    }

    @PostMapping("/bins/{id}/device-token")
    public R<DeviceProvisionResult> resetToken(@PathVariable Long id, HttpServletRequest request) {
        SessionIdentity.requireAdmin(request);
        return R.success(catalog.resetDeviceToken(id));
    }

    private void requireLogin(HttpServletRequest request) {
        if (!SessionIdentity.isAdmin(request) && SessionIdentity.userId(request) == null && SessionIdentity.driverId(request) == null)
            throw new BusinessException("请先登录");
    }
}
