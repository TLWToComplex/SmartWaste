package com.example.shizhan.rubbish.controller.android;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.shizhan.rubbish.common.*;
import com.example.shizhan.rubbish.entity.orders;
import com.example.shizhan.rubbish.mapper.OrderMapper;
import com.example.shizhan.rubbish.service.WasteOrderWorkflowService;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/orderAndroid")
public class OrderControllerAnd {
    private final OrderMapper ordersMapper;
    private final WasteOrderWorkflowService workflow;
    public OrderControllerAnd(OrderMapper ordersMapper, WasteOrderWorkflowService workflow) {
        this.ordersMapper = ordersMapper; this.workflow = workflow;
    }
    @GetMapping("/map1")
    public R<List<orders>> userOrders(HttpServletRequest request) {
        List<orders> list = ordersMapper.selectUserOrders(SessionIdentity.requireUser(request).toString());
        list.forEach(this::describeStatus);
        return R.success(list);
    }
    @GetMapping("/map2")
    public R<List<orders>> driverOrders(HttpServletRequest request, @RequestParam(defaultValue = "all") String scope) {
        Long id = SessionIdentity.requireDriver(request);
        LambdaQueryWrapper<orders> query = new LambdaQueryWrapper<>();
        if ("pending".equals(scope)) query.eq(orders::getStatus, 1);
        else if ("mine".equals(scope)) query.eq(orders::getDriverId, id);
        else if ("all".equals(scope)) query.and(q -> q.eq(orders::getStatus, 1).or().eq(orders::getDriverId, id));
        else throw new BusinessException("订单范围无效");
        List<orders> list = ordersMapper.selectList(query.orderByDesc(orders::getOrderTime).orderByDesc(orders::getId));
        list.forEach(this::describeStatus);
        return R.success(list);
    }
    @GetMapping("/getById")
    public R<orders> detail(@RequestParam BigInteger id, HttpServletRequest request) {
        orders order = ordersMapper.selectById(id);
        if (order == null) throw new BusinessException("订单不存在");
        boolean allowed = SessionIdentity.isAdmin(request);
        Long driver = SessionIdentity.driverId(request);
        if (driver != null) allowed |= order.getStatus() == 1 || Objects.equals(order.getDriverId(), driver);
        Long user = SessionIdentity.userId(request);
        if (!allowed && user != null) allowed = ordersMapper.countUserOrder(id, user.toString()) > 0;
        if (!allowed) throw new BusinessException("无权查看该订单");
        describeStatus(order);
        return R.success(order);
    }
    @PostMapping("/{id}/accept")
    public R<orders> accept(@PathVariable BigInteger id, HttpServletRequest request) {
        return R.success(workflow.accept(id, SessionIdentity.requireDriver(request)));
    }
    @PostMapping("/{id}/complete")
    public R<orders> complete(@PathVariable BigInteger id, HttpServletRequest request) {
        return R.success(workflow.complete(id, SessionIdentity.requireDriver(request), false));
    }
    @PostMapping("/{id}/cancel")
    public R<orders> cancel(@PathVariable BigInteger id, HttpServletRequest request) {
        return R.success(workflow.cancel(id, SessionIdentity.requireUser(request), false));
    }
    private void describeStatus(orders order) {
        String[] labels = {"未知", "待接单", "运输中", "已完成", "已取消"};
        int status = order.getStatus();
        order.setStatus_Str(status >= 1 && status <= 4 ? labels[status] : "未知");
    }
}
