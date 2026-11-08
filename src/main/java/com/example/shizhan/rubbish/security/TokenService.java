package com.example.shizhan.rubbish.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.shizhan.rubbish.mapper.*;
import com.example.shizhan.rubbish.entity.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/** Access JWT 固定 HS256；Refresh Token 仅通过 HttpOnly Cookie 传输，Redis 保存其摘要。 */
@Service
public class TokenService {
    public static final String ATTRIBUTE = "smartwaste.principal";
    private static final String PREFIX = "sw:auth:";
    private final StringRedisTemplate redis;
    private final UserMapper users;
    private final DriverMapper drivers;
    private final EmployeeMapper employees;
    // JWT 的 iat/exp 必须是 JSON 数字，不能复用将业务 Long ID 转字符串的接口序列化器。
    private final ObjectMapper json = new ObjectMapper();
    private final byte[] secret;
    private final long accessSeconds, refreshSeconds;
    private final DefaultRedisScript<Long> rotate = new DefaultRedisScript<>(
            "if redis.call('GET',KEYS[1]) == ARGV[1] then redis.call('SET',KEYS[1],ARGV[2],'EX',ARGV[3]); return 1 else return 0 end", Long.class);
    private final DefaultRedisScript<String> consumeTicket = new DefaultRedisScript<>(
            "local v=redis.call('GET',KEYS[1]); if v then redis.call('DEL',KEYS[1]); end; return v", String.class);

    public TokenService(StringRedisTemplate redis, UserMapper users, DriverMapper drivers, EmployeeMapper employees,
            @Value("${smartwaste.auth.secret:}") String secret,
            @Value("${smartwaste.auth.access-seconds:900}") long accessSeconds,
            @Value("${smartwaste.auth.refresh-seconds:604800}") long refreshSeconds) {
        this.redis=redis; this.users=users; this.drivers=drivers; this.employees=employees;
        this.secret=secret.getBytes(StandardCharsets.UTF_8);
        if (this.secret.length < 32) throw new IllegalStateException("请通过 SMARTWASTE_JWT_SECRET 配置至少32字节的签名密钥");
        if (accessSeconds < 60 || refreshSeconds <= accessSeconds) throw new IllegalStateException("令牌有效期配置无效");
        this.accessSeconds=accessSeconds; this.refreshSeconds=refreshSeconds;
    }
    public Map<String,Object> issue(String role, Long id, Object profile, HttpServletRequest request, HttpServletResponse response) {
        checkRole(role);
        String sid=UUID.randomUUID().toString();
        String refresh=refreshEnvelope(role,id,sid,sid+"."+UUID.randomUUID()+UUID.randomUUID().toString());
        redis.opsForValue().set(sessionKey(role,id,sid), digest(refresh), Duration.ofSeconds(refreshSeconds));
        setCookie(role,refresh,refreshSeconds,request,response);
        return tokens(role,id,sid,profile);
    }
    public Map<String,Object> refresh(String role, HttpServletRequest request, HttpServletResponse response) {
        checkRole(role);
        String raw=cookie(request,role);
        if (raw==null || !raw.contains(".")) throw new AuthFailure("请重新登录");
        // Cookie 本身为签名的身份载荷 + 随机凭据，身份信息不由客户端提交。
        String[] segments=raw.split("~",2);
        if (segments.length!=2) throw new AuthFailure("刷新令牌无效");
        AuthPrincipal principal=decode(segments[0],"refresh");
        if (!role.equals(principal.getRole())) throw new AuthFailure("刷新身份不匹配");
        Object profile=activeProfile(principal);
        String nextOpaque=principal.getSessionId()+"."+UUID.randomUUID()+UUID.randomUUID().toString();
        String next=refreshEnvelope(role,principal.getId(),principal.getSessionId(),nextOpaque);
        Long result=redis.execute(rotate, Collections.singletonList(sessionKey(principal)),digest(raw),digest(next),String.valueOf(refreshSeconds));
        if (!Long.valueOf(1).equals(result)) throw new AuthFailure("刷新令牌已失效，请重新登录");
        setCookie(role,next,refreshSeconds,request,response);
        return tokens(role,principal.getId(),principal.getSessionId(),profile);
    }
    private Map<String,Object> tokens(String role,Long id,String sid,Object profile) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("accessToken", encode(role,id,sid,"access",accessSeconds));
        result.put("expiresIn",accessSeconds); result.put("role",role); result.put("principal",profile);
        return result;
    }
    public AuthPrincipal authenticate(String token) {
        AuthPrincipal principal=decode(token,"access");
        if (Boolean.TRUE.equals(redis.hasKey(PREFIX+"blacklist:"+principal.getTokenId()))) throw new AuthFailure("访问令牌已撤销");
        validateSession(principal);
        return principal;
    }
    public void validateSession(AuthPrincipal principal) {
        if (principal.getExpiresAt()<=now() || !Boolean.TRUE.equals(redis.hasKey(sessionKey(principal)))) throw new AuthFailure("登录已失效");
        activeProfile(principal);
    }
    public void logout(String role,HttpServletRequest request,HttpServletResponse response) {
        checkRole(role);
        AuthPrincipal principal=(AuthPrincipal)request.getAttribute(ATTRIBUTE);
        if (principal!=null && role.equals(principal.getRole())) {
            redis.delete(sessionKey(principal));
            long ttl=principal.getExpiresAt()-now();
            if(ttl>0) redis.opsForValue().set(PREFIX+"blacklist:"+principal.getTokenId(),"1",Duration.ofSeconds(ttl));
        } else {
            String raw=cookie(request,role);
            if(raw!=null) try {
                AuthPrincipal refresh=decode(raw.split("~",2)[0],"refresh");
                if(role.equals(refresh.getRole()) && digest(raw).equals(redis.opsForValue().get(sessionKey(refresh)))) redis.delete(sessionKey(refresh));
            } catch(AuthFailure ignored) { }
        }
        setCookie(role,"",0,request,response);
    }
    public String createTicket(AuthPrincipal principal) {
        if(!"admin".equals(principal.getRole())) throw new AuthFailure("需要管理端身份");
        String ticket=UUID.randomUUID().toString();
        try { redis.opsForValue().set(PREFIX+"ticket:"+ticket,json.writeValueAsString(principal),Duration.ofSeconds(30)); }
        catch(java.io.IOException ex) { throw new IllegalStateException(ex); }
        return ticket;
    }
    public AuthPrincipal useTicket(String ticket) {
        if(ticket==null || !ticket.matches("[a-f0-9-]{36}")) throw new AuthFailure("连接凭据无效");
        String value=redis.execute(consumeTicket,Collections.singletonList(PREFIX+"ticket:"+ticket));
        if(value==null) throw new AuthFailure("连接凭据已过期或已使用");
        try { AuthPrincipal principal=json.readValue(value,AuthPrincipal.class); validateSession(principal); return principal; }
        catch(java.io.IOException ex) { throw new AuthFailure("连接凭据无效"); }
    }
    public Object activeProfile(AuthPrincipal p) {
        if("user".equals(p.getRole())) { User u=users.selectById(p.getId()); if(u!=null&&u.getStatus()==1)return u; }
        if("driver".equals(p.getRole())) { Driver d=drivers.selectById(p.getId()); if(d!=null&&d.getStatus()==1)return d; }
        if("admin".equals(p.getRole())) { Employee e=employees.selectById(p.getId()); if(e!=null&&e.getStatus()==1)return e; }
        throw new AuthFailure("账号不存在或已停用");
    }
    private String encode(String role,Long id,String sid,String type,long ttl) {
        try {
            Map<String,Object> payload=new LinkedHashMap<>();
            payload.put("iss","SmartWaste"); payload.put("sub",id.toString()); payload.put("role",role); payload.put("sid",sid);
            payload.put("jti",UUID.randomUUID().toString()); payload.put("type",type); payload.put("iat",now()); payload.put("exp",now()+ttl);
            String value=base64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))+"."+base64(json.writeValueAsBytes(payload));
            return value+"."+base64(sign(value));
        } catch(Exception ex) { throw new IllegalStateException("签发令牌失败",ex); }
    }
    private AuthPrincipal decode(String raw,String type) {
        try {
            if(raw==null||raw.length()>4096)throw new AuthFailure("令牌无效");
            String[] parts=raw.split("\\.");
            if(parts.length!=3||!MessageDigest.isEqual(sign(parts[0]+"."+parts[1]),Base64.getUrlDecoder().decode(parts[2])))throw new AuthFailure("令牌签名无效");
            JsonNode header=json.readTree(Base64.getUrlDecoder().decode(parts[0]));
            JsonNode data=json.readTree(Base64.getUrlDecoder().decode(parts[1]));
            if(!"HS256".equals(header.path("alg").asText())||!"SmartWaste".equals(data.path("iss").asText())||!type.equals(data.path("type").asText())||data.path("exp").asLong()<=now())throw new AuthFailure("令牌已过期或类型无效");
            AuthPrincipal p=new AuthPrincipal(); p.setId(Long.valueOf(data.path("sub").asText())); p.setRole(data.path("role").asText());
            checkRole(p.getRole()); p.setSessionId(data.path("sid").asText()); p.setTokenId(data.path("jti").asText());p.setExpiresAt(data.path("exp").asLong());
            if(p.getSessionId().isEmpty()||p.getTokenId().isEmpty())throw new AuthFailure("令牌缺少身份信息");
            return p;
        } catch(AuthFailure ex) { throw ex; } catch(Exception ex) { throw new AuthFailure("令牌无效"); }
    }
    private byte[] sign(String value) throws Exception { Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret,"HmacSHA256"));return mac.doFinal(value.getBytes(StandardCharsets.UTF_8)); }
    private String digest(String raw) { try{return base64(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);} }
    private String base64(byte[] raw) {return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);}
    private long now(){return System.currentTimeMillis()/1000;}
    private String sessionKey(String role,Long id,String sid){return PREFIX+"session:"+role+":"+id+":"+sid;}
    private String sessionKey(AuthPrincipal p){return sessionKey(p.getRole(),p.getId(),p.getSessionId());}
    private void checkRole(String role){if(!Arrays.asList("user","driver","admin").contains(role))throw new AuthFailure("身份类型无效");}
    private String refreshEnvelope(String role,Long id,String sid,String opaque){return encode(role,id,sid,"refresh",refreshSeconds)+"~"+opaque;}
    private void setCookie(String role,String value,long seconds,HttpServletRequest request,HttpServletResponse response){
        response.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from("sw_refresh_"+role,value).httpOnly(true).secure(request.isSecure())
                .sameSite("Strict").path("/auth/"+role).maxAge(Duration.ofSeconds(seconds)).build().toString());
    }
    private String cookie(HttpServletRequest request,String role){if(request.getCookies()!=null)for(Cookie c:request.getCookies())if(("sw_refresh_"+role).equals(c.getName()))return c.getValue();return null;}
}
