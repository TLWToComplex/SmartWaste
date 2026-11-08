package com.smartwaste.rag;

import org.slf4j.*;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log=LoggerFactory.getLogger(ApiErrors.class);
    @ExceptionHandler({IllegalArgumentException.class,MethodArgumentNotValidException.class})
    public ResponseEntity<?> invalid(Exception ex) { return ResponseEntity.badRequest().body(Map.of("message","请检查知识标题、类别、来源和正文，或问题与对话历史的长度")); }
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<?> missing() { return ResponseEntity.status(404).body(Map.of("message","知识文档不存在，请刷新列表")); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unavailable(Exception ex) {
        log.warn("RAG 请求未完成: {}",ex.getClass().getSimpleName());
        return ResponseEntity.status(503).body(Map.of("message","处理未完成，请检查知识索引和模型服务配置后重试；已有知识版本保留"));
    }
}
