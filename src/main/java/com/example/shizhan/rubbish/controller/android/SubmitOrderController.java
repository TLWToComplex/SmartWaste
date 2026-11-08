package com.example.shizhan.rubbish.controller.android;
import com.example.shizhan.rubbish.common.R;
import com.example.shizhan.rubbish.common.SessionIdentity;
import com.example.shizhan.rubbish.dto.FullBinReportReceipt;
import com.example.shizhan.rubbish.dto.FullBinReportRequest;
import com.example.shizhan.rubbish.service.WasteOrderWorkflowService;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/submitOrder")
public class SubmitOrderController {
    private final WasteOrderWorkflowService workflow;
    public SubmitOrderController(WasteOrderWorkflowService workflow) { this.workflow = workflow; }
    @PostMapping
    public R<FullBinReportReceipt> submitOrder(@RequestBody FullBinReportRequest body, HttpServletRequest request) {
        return R.success(workflow.reportByUser(body.getBinId(), body.getRequestId(), SessionIdentity.requireUser(request)));
    }
}
