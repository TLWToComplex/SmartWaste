package com.example.shizhan.rubbish.controller.android;

import com.example.shizhan.rubbish.common.R;
import com.example.shizhan.rubbish.common.SessionIdentity;
import com.example.shizhan.rubbish.dto.FullBinReportReceipt;
import com.example.shizhan.rubbish.dto.FullBinReportRequest;
import com.example.shizhan.rubbish.service.WasteOrderWorkflowService;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;

@RestController
public class FullBinReportController {
    private final WasteOrderWorkflowService workflow;
    public FullBinReportController(WasteOrderWorkflowService workflow) { this.workflow = workflow; }

    @PostMapping("/waste/bins/{id}/full-reports")
    public R<FullBinReportReceipt> userReport(@PathVariable Long id, @RequestBody FullBinReportRequest body, HttpServletRequest request) {
        return R.success(workflow.reportByUser(id, body.getRequestId(), SessionIdentity.requireUser(request)));
    }

    @PostMapping("/device/bins/{id}/full-reports")
    public R<FullBinReportReceipt> deviceReport(@PathVariable Long id, @RequestBody FullBinReportRequest body,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            @RequestHeader(value = "X-Device-Token", required = false) String token) {
        return R.success(workflow.reportByDevice(id, body.getRequestId(), deviceId, token));
    }
}
