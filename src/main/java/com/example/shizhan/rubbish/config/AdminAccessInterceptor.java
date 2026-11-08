package com.example.shizhan.rubbish.config;

import com.example.shizhan.rubbish.common.R;
import com.example.shizhan.rubbish.common.SessionIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.servlet.HandlerInterceptor;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** 管理接口复用 employee 身份，防止用户端或司机端越权操作。 */
public class AdminAccessInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean adminRoute = path.equals("/order") || path.startsWith("/order/")
                || path.equals("/employee") || path.startsWith("/employee/");
        if (!adminRoute || path.equals("/employee/login") || SessionIdentity.isAdmin(request)) return true;
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        new ObjectMapper().writeValue(response.getWriter(), R.error("NOTLOGIN"));
        return false;
    }
}
