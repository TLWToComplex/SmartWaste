package com.example.shizhan.rubbish.controller.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.shizhan.rubbish.common.*;
import com.example.shizhan.rubbish.dto.FullBinReportReceipt;
import com.example.shizhan.rubbish.entity.WasteOrderEvent;
import com.example.shizhan.rubbish.mapper.WasteOrderEventMapper;
import com.example.shizhan.rubbish.service.WasteOrderWorkflowService;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;

@RestController
public class OrderEventController {
    private final WasteOrderEventMapper events;private final WasteOrderWorkflowService workflow;
    public OrderEventController(WasteOrderEventMapper events,WasteOrderWorkflowService workflow){this.events=events;this.workflow=workflow;}
    @GetMapping("/waste/reports/mine") public R<List<FullBinReportReceipt>> mine(HttpServletRequest request){
        Long id=SessionIdentity.requireUser(request);
        List<FullBinReportReceipt> result=new ArrayList<>();
        for(WasteOrderEvent e:events.selectList(new LambdaQueryWrapper<WasteOrderEvent>().eq(WasteOrderEvent::getSource,"USER").eq(WasteOrderEvent::getActorId,id.toString()).orderByDesc(WasteOrderEvent::getId).last("LIMIT 50")))result.add(workflow.receipt(e,true));
        return R.success(result);
    }
    @GetMapping("/waste/reports/{id}") public R<FullBinReportReceipt> get(@PathVariable Long id,HttpServletRequest request){
        WasteOrderEvent e=events.selectById(id);
        if(e==null)throw new BusinessException("上报记录不存在");
        if(!SessionIdentity.isAdmin(request)&&(!"USER".equals(e.getSource())||!SessionIdentity.requireUser(request).toString().equals(e.getActorId())))throw new BusinessException("无权查看该上报");
        return R.success(workflow.receipt(e,true));
    }
    @GetMapping("/waste/order-events") public R<Page<WasteOrderEvent>> failures(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="10")int pageSize,HttpServletRequest request){
        SessionIdentity.requireAdmin(request);
        if(page<1||pageSize<1||pageSize>100)throw new BusinessException("分页参数无效");
        return R.success(events.selectPage(new Page<>(page,pageSize),new LambdaQueryWrapper<WasteOrderEvent>().ne(WasteOrderEvent::getState,"DONE").orderByAsc(WasteOrderEvent::getReportedAt)));
    }
    @PostMapping("/waste/order-events/{id}/retry") public R<String> retry(@PathVariable Long id,HttpServletRequest request){
        SessionIdentity.requireAdmin(request);
        LocalDateTime now=LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        int count=events.update(null,new LambdaUpdateWrapper<WasteOrderEvent>().eq(WasteOrderEvent::getId,id).eq(WasteOrderEvent::getState,"FAILED")
                .set(WasteOrderEvent::getState,"NEW").set(WasteOrderEvent::getConsumeAttempts,0).set(WasteOrderEvent::getNextAttemptAt,now)
                .set(WasteOrderEvent::getUpdatedAt,now).set(WasteOrderEvent::getLastError,null));
        return count==1?R.success("已安排重试"):R.error("事件不存在或当前无需重试");
    }
}
