package com.example.shizhan.rubbish.controller.web;

import com.example.shizhan.rubbish.common.R;
import com.example.shizhan.rubbish.security.*;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.*;
import java.util.*;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final TokenService tokens;
    public AuthController(TokenService tokens){this.tokens=tokens;}
    @PostMapping("/{role}/refresh") public R<Map<String,Object>> refresh(@PathVariable String role,HttpServletRequest request,HttpServletResponse response){return R.success(tokens.refresh(role,request,response));}
    @PostMapping("/{role}/logout") public R<String> logout(@PathVariable String role,HttpServletRequest request,HttpServletResponse response){tokens.logout(role,request,response);return R.success("退出成功");}
    @PostMapping("/admin/ws-ticket") public R<Map<String,String>> ticket(HttpServletRequest request){return R.success(Collections.singletonMap("ticket",tokens.createTicket((AuthPrincipal)request.getAttribute(TokenService.ATTRIBUTE))));}
}
