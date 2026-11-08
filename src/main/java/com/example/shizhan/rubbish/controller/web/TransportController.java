package com.example.shizhan.rubbish.controller.web;
import com.example.shizhan.rubbish.common.*;
import com.example.shizhan.rubbish.entity.*;
import com.example.shizhan.rubbish.service.TransportService;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.math.BigInteger;
import java.util.*;
@RestController
@RequestMapping("/transport")
public class TransportController {
    private final TransportService service;
    public TransportController(TransportService service){this.service=service;}
    @GetMapping("/map-config")public R<Map<String,String>> config(){return R.success(service.mapConfig());}
    @GetMapping("/orders/{id}/route")public R<WasteOrderRoute> route(@PathVariable BigInteger id,HttpServletRequest request){return R.success(service.route(id,SessionIdentity.driverId(request),SessionIdentity.isAdmin(request)));}
    @PostMapping("/location")public R<DriverLocation> location(@RequestBody DriverLocation position,HttpServletRequest request){return R.success(service.report(SessionIdentity.requireDriver(request),position));}
    @GetMapping("/vehicles")public R<List<DriverLocation>> vehicles(HttpServletRequest request){SessionIdentity.requireAdmin(request);return R.success(service.snapshots());}
}
