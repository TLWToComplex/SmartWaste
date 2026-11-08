package com.example.shizhan.rubbish.security;

import com.example.shizhan.rubbish.common.R;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import javax.servlet.http.*;

@Component
public class AccessTokenInterceptor implements HandlerInterceptor {
    private final TokenService tokens;
    public AccessTokenInterceptor(TokenService tokens){this.tokens=tokens;}
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) throws Exception {
        if(!(handler instanceof HandlerMethod))return true;
        String path=request.getRequestURI().substring(request.getContextPath().length());
        // 智能桶使用独立设备密钥；账号入口及令牌刷新不要求 Access Token。
        if(path.startsWith("/device/")||path.matches("/(userAndroid|driver)/(login|register)")||path.equals("/employee/login")||path.matches("/auth/(user|driver|admin)/(refresh|logout)")) {
            if(path.endsWith("/logout"))try { request.setAttribute(TokenService.ATTRIBUTE,tokens.authenticate(bearer(request))); }catch(AuthFailure ignored){}
            return true;
        }
        try {
            AuthPrincipal p=tokens.authenticate(bearer(request));request.setAttribute(TokenService.ATTRIBUTE,p);
            if((path.equals("/order")||path.startsWith("/order/")||path.equals("/employee")||path.startsWith("/employee/")||path.startsWith("/getLocation/"))&&!"admin".equals(p.getRole())){
                error(response,403,"需要管理端身份");return false;
            }
            return true;
        }catch(AuthFailure ex){error(response,401,ex.getMessage());return false;}
    }
    private String bearer(HttpServletRequest request){String h=request.getHeader("Authorization");return h!=null&&h.startsWith("Bearer ")?h.substring(7):null;}
    private void error(HttpServletResponse response,int status,String message)throws Exception{response.setStatus(status);response.setContentType("application/json;charset=UTF-8");new ObjectMapper().writeValue(response.getWriter(),R.error(message));}
}
