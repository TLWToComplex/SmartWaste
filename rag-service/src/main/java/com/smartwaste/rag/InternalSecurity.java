package com.smartwaste.rag;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.Semaphore;

/** 只允许主后端携带内部密钥调用；限制同时运行的模型/索引请求数量。 */
@Component
public class InternalSecurity extends OncePerRequestFilter {
    private final byte[] token;
    private final Semaphore slots=new Semaphore(4);
    public InternalSecurity(RagProperties properties) {
        if(properties.internalToken()==null||properties.internalToken().length()<32)
            throw new IllegalArgumentException("SMARTWASTE_RAG_INTERNAL_TOKEN 至少需要 32 个字符");
        token=properties.internalToken().getBytes(StandardCharsets.UTF_8);
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        String supplied=request.getHeader("X-Rag-Token");
        if(supplied==null||!MessageDigest.isEqual(token,supplied.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(401); return;
        }
        if(request.getContentLengthLong()>450000) { response.sendError(413); return; }
        // 主后端不转发分块请求；直接调用也拒绝未知大小的请求体。
        if(request.getHeader("Transfer-Encoding")!=null) { response.sendError(411); return; }
        if(!slots.tryAcquire()) { response.setHeader("Retry-After","3"); response.sendError(429); return; }
        try { chain.doFilter(request,response); } finally { slots.release(); }
    }
}
