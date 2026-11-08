package com.example.shizhan.rubbish.controller.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.shizhan.rubbish.common.R;
import com.example.shizhan.rubbish.dto.OrderStatistics;
import com.example.shizhan.rubbish.dto.OrderReminderPage;
import com.example.shizhan.rubbish.service.OrderReminderService;
import com.example.shizhan.rubbish.service.WasteOrderWorkflowService;
import com.example.shizhan.rubbish.common.BusinessException;
import com.example.shizhan.rubbish.entity.orders;
import org.apache.commons.lang.StringUtils;
import com.example.shizhan.rubbish.service.Interface.orderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigInteger;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;


/**
 * 订单
 */
@Slf4j
@RestController
@RequestMapping("/order")
public class OrderController {

    @Autowired
    private orderService os;
    @Autowired
    private OrderReminderService reminderService;
    @Autowired
    private WasteOrderWorkflowService workflow;

    @GetMapping("/overdue-reminders")
    public ResponseEntity<R<OrderReminderPage>> getOverdueReminders(
            HttpServletRequest request, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "5") int pageSize) {
        if (!com.example.shizhan.rubbish.common.SessionIdentity.isAdmin(request)) {
            return ResponseEntity.status(401).body(R.<OrderReminderPage>error("请先登录管理端"));
        }
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            return ResponseEntity.badRequest().body(R.<OrderReminderPage>error("分页参数无效，每页数量应为 1 至 100"));
        }
        return ResponseEntity.ok(R.success(reminderService.getReminders(page, pageSize)));
    }

    @GetMapping("/statistics")
    public R<OrderStatistics> getStatistics() {
        return R.success(os.getStatistics());
    }

    @GetMapping("/page")
    public R<Page> getAll(
            int page, int pageSize,
            String number, String status, String beginTime, String endTime) {
        //构造分页构造器
        Page pageInfo = new Page(page,pageSize);

        //构造条件构造器
        LambdaQueryWrapper<orders> queryWrapper = new LambdaQueryWrapper();
        //添加过滤条件
        /**
         * 对订单号的过滤、模糊查询
         */
        queryWrapper.like(StringUtils.isNotEmpty(number), orders::getNumber, number);
        /**
         * 对订单状态的过滤
         */
        // System.out.println(status);
        /*Integer id_status = null;
        if(status != null) {
            if(status.equals("待付款")) {
                id_status = 1;
            }else if(status.equals("正在派送")) {
                id_status = 2;
            }else if(status.equals("已派送")) {
                id_status = 3;
            }else if(status.equals("已完成")) {
                id_status = 4;
            }else {
                id_status = 5;
            }
        }*/
        queryWrapper.eq(StringUtils.isNotEmpty(status), orders::getStatus, status);
        // 关于日期
        // SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        /**
         * 对订单日期的过滤
         */
        if(beginTime != null) {
            queryWrapper.ge(orders::getOrderTime, beginTime);
        }
        if(endTime != null && !endTime.isEmpty()) {
            queryWrapper.le(orders::getOrderTime, endTime);
        }

        queryWrapper.orderByDesc(orders::getOrderTime).orderByDesc(orders::getId);

        // 执行查询
        os.page(pageInfo, queryWrapper);
        // 返回
        return R.success(pageInfo);
    }

    /**
     * 完成运输状态的更改
     */
    @PutMapping
    public R<String> changeOrderState(@RequestBody Map<String, Object> json) {

        if (json.get("id") == null || json.get("status") == null) throw new BusinessException("请选择订单和操作");
        BigInteger id;
        int status;
        try {
            id = new BigInteger(json.get("id").toString());
            status = Integer.parseInt(json.get("status").toString());
        } catch (NumberFormatException ex) { throw new BusinessException("订单编号或状态无效"); }
        if (status == 3) workflow.complete(id, null, true);
        else if (status == 4) workflow.cancel(id, null, true);
        else throw new BusinessException("请由司机自主接单，管理端可确认完成或取消订单");
        // 返回对应的响应
        return R.success("");
    }
}
