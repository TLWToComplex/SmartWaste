package com.example.shizhan.rubbish.controller.android;

import com.example.shizhan.rubbish.common.*;
import com.example.shizhan.rubbish.service.RagGatewayService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
@RequestMapping("/rag")
public class RagChatController {
    private final RagGatewayService gateway;
    public RagChatController(RagGatewayService gateway) { this.gateway=gateway; }
    @PostMapping("/chat")
    public R<JsonNode> chat(@RequestBody JsonNode body,HttpServletRequest request) {
        SessionIdentity.requireUser(request);
        String question=body.path("question").asText("").trim();
        if(question.isEmpty()||question.length()>1000) throw new BusinessException("请输入 1 至 1000 字的问题");
        String category=body.path("category").asText("");
        if(!Arrays.asList("","classification","disposal","device","platform").contains(category)) throw new BusinessException("知识类别无效");
        JsonNode history=body.path("history");
        if(!history.isMissingNode()&&(!history.isArray()||history.size()>8)) throw new BusinessException("最多携带 8 条历史对话");
        List<Map<String,String>> turns=new ArrayList<>();
        for(JsonNode turn:history) {
            String role=turn.path("role").asText(""),content=turn.path("content").asText("");
            if(!Arrays.asList("user","assistant").contains(role)||content.trim().isEmpty()||content.length()>2000)
                throw new BusinessException("历史对话格式或长度无效");
            Map<String,String> item=new HashMap<>(); item.put("role",role); item.put("content",content); turns.add(item);
        }
        Map<String,Object> payload=new HashMap<>(); payload.put("question",question); payload.put("category",category); payload.put("history",turns);
        return R.success(gateway.call(HttpMethod.POST,"/chat",payload));
    }
}
