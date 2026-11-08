package com.example.shizhan.rubbish.service;

import com.example.shizhan.rubbish.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;

/** Boot 2 业务服务通过内部 HTTP 连接 Java 17 / Spring AI 服务。 */
@Service
public class RagGatewayService {
    private final RestTemplate http;
    private final String baseUrl,token;
    public RagGatewayService(@Value("${smartwaste.rag.url:http://127.0.0.1:8081}") String baseUrl,
                             @Value("${smartwaste.rag.internal-token:}") String token) {
        this.baseUrl=baseUrl.replaceAll("/+$",""); this.token=token;
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000); factory.setReadTimeout(300000);
        this.http=new RestTemplate(factory);
    }
    public JsonNode call(HttpMethod method,String path,Object body) {
        if(token.length()<32) throw new BusinessException("智能客服尚未配置，请联系管理员配置 RAG 服务");
        HttpHeaders headers=new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Rag-Token",token);
        try {
            return http.exchange(baseUrl+"/internal/rag"+path,method,new HttpEntity<>(body,headers),JsonNode.class).getBody();
        } catch(HttpStatusCodeException ex) {
            if(ex.getRawStatusCode()==429) throw new BusinessException("智能客服正在处理其他请求，请稍后重试");
            if(ex.getRawStatusCode()==400) throw new BusinessException("请检查标题、类别、知识正文，或问题与历史对话的长度");
            if(ex.getRawStatusCode()==404) throw new BusinessException("知识文档不存在，请刷新列表");
            throw new BusinessException("智能客服处理未完成，请检查模型和知识索引服务后重试");
        } catch(RestClientException ex) {
            throw new BusinessException("智能客服暂时无法连接，请确认 RAG 服务已经启动");
        }
    }
}
