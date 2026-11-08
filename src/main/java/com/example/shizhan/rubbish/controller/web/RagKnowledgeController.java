package com.example.shizhan.rubbish.controller.web;

import com.example.shizhan.rubbish.common.*;
import com.example.shizhan.rubbish.service.RagGatewayService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
@RequestMapping("/rag/admin")
public class RagKnowledgeController {
    private final RagGatewayService gateway;
    public RagKnowledgeController(RagGatewayService gateway) { this.gateway=gateway; }
    private String documentPath(String id) {
        try { UUID.fromString(id); } catch(IllegalArgumentException ex) { throw new BusinessException("知识文档编号无效"); }
        return "/documents/"+id;
    }
    private Map<String,String> input(JsonNode body) {
        Map<String,String> result=new HashMap<>();
        String[] names={"title","category","source","content"}; int[] limits={120,30,300,100000};
        for(int i=0;i<names.length;i++) {
            JsonNode node=body.path(names[i]); String value=node.asText("").trim();
            if(!node.isTextual()||value.isEmpty()||value.length()>limits[i]) throw new BusinessException("知识标题、类别、来源或正文为空或超长");
            result.put(names[i],value);
        }
        if(!Arrays.asList("classification","disposal","device","platform").contains(result.get("category"))) throw new BusinessException("知识类别无效");
        return result;
    }
    @GetMapping("/documents") public R<JsonNode> list(HttpServletRequest request) { SessionIdentity.requireAdmin(request); return R.success(gateway.call(HttpMethod.GET,"/documents",null)); }
    @GetMapping("/documents/{id}") public R<JsonNode> get(@PathVariable String id,HttpServletRequest request) { SessionIdentity.requireAdmin(request); return R.success(gateway.call(HttpMethod.GET,documentPath(id),null)); }
    @PostMapping("/documents") public R<JsonNode> create(@RequestBody JsonNode body,HttpServletRequest request) { SessionIdentity.requireAdmin(request); return R.success(gateway.call(HttpMethod.POST,"/documents",input(body))); }
    @PutMapping("/documents/{id}") public R<JsonNode> update(@PathVariable String id,@RequestBody JsonNode body,HttpServletRequest request) { SessionIdentity.requireAdmin(request); return R.success(gateway.call(HttpMethod.PUT,documentPath(id),input(body))); }
    @DeleteMapping("/documents/{id}") public R<JsonNode> delete(@PathVariable String id,HttpServletRequest request) { SessionIdentity.requireAdmin(request); return R.success(gateway.call(HttpMethod.DELETE,documentPath(id),null)); }
    @PostMapping("/documents/{id}/reindex") public R<JsonNode> reindex(@PathVariable String id,HttpServletRequest request) { SessionIdentity.requireAdmin(request); return R.success(gateway.call(HttpMethod.POST,documentPath(id)+"/reindex",null)); }
    @PostMapping("/seed") public R<JsonNode> seed(HttpServletRequest request) { SessionIdentity.requireAdmin(request); return R.success(gateway.call(HttpMethod.POST,"/seed",null)); }
    @GetMapping("/status") public R<JsonNode> status(HttpServletRequest request) { SessionIdentity.requireAdmin(request); return R.success(gateway.call(HttpMethod.GET,"/status",null)); }
}
