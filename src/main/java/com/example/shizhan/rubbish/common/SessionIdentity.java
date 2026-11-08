package com.example.shizhan.rubbish.common;

import javax.servlet.http.HttpServletRequest;

/** 保留原调用名称；身份来自拦截器验证后的 JWT，不再读取浏览器 Session。 */
public final class SessionIdentity {
    private SessionIdentity() { }

    public static Long userId(HttpServletRequest request) { return id(request, "userId"); }
    public static Long driverId(HttpServletRequest request) { return id(request, "driverId"); }
    public static boolean isAdmin(HttpServletRequest request) { return id(request, "employee") != null; }
    public static void requireAdmin(HttpServletRequest request) {
        if (!isAdmin(request)) throw new BusinessException("请先登录管理端");
    }
    public static Long requireUser(HttpServletRequest request) {
        Long value = userId(request);
        if (value == null) throw new BusinessException("请先登录用户端");
        return value;
    }
    public static Long requireDriver(HttpServletRequest request) {
        Long value = driverId(request);
        if (value == null) throw new BusinessException("请先登录司机端");
        return value;
    }
    private static Long id(HttpServletRequest request, String key) {
        com.example.shizhan.rubbish.security.AuthPrincipal principal=(com.example.shizhan.rubbish.security.AuthPrincipal)
                request.getAttribute(com.example.shizhan.rubbish.security.TokenService.ATTRIBUTE);
        String role="employee".equals(key)?"admin":"userId".equals(key)?"user":"driver";
        return principal!=null&&role.equals(principal.getRole())?principal.getId():null;
    }
    public static Long requireEmployee(HttpServletRequest request) { requireAdmin(request); return id(request,"employee"); }
}
