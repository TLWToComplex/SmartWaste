package com.example.shizhan.rubbish.controller.android;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.example.shizhan.rubbish.common.*;
import com.example.shizhan.rubbish.entity.User;
import com.example.shizhan.rubbish.mapper.UserMapper;
import com.example.shizhan.rubbish.utils.PasswordUtils;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import com.example.shizhan.rubbish.security.TokenService;
import java.util.Map;
import java.math.BigInteger;

@RestController
@RequestMapping("/userAndroid")
public class UserController {
    private final UserMapper users;
    private final TokenService tokens;
    public UserController(UserMapper users, TokenService tokens) { this.users = users; this.tokens=tokens; }
    @PostMapping("/login")
    public R<Map<String,Object>> login(HttpServletRequest request, HttpServletResponse response, @RequestBody User input) {
        User user = users.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, BusinessChecks.text(input.getUsername(), "账号", 64)));
        if (user == null || !PasswordUtils.matches(input.getPassword(), user.getPassword())) throw new BusinessException("账号或密码错误");
        if (user.getStatus() != 1) throw new BusinessException("账号已停用");
        if (!PasswordUtils.isEncoded(user.getPassword())) users.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, user.getId()).set(User::getPassword, PasswordUtils.encode(input.getPassword())));
        return R.success(tokens.issue("user",user.getId().longValueExact(),user,request,response));
    }
    @PostMapping("/register")
    public R<Map<String,Object>> register(HttpServletRequest request, HttpServletResponse response, @RequestBody User input) {
        User user = new User();
        user.setUsername(BusinessChecks.text(input.getUsername(), "账号", 64));
        if (users.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, user.getUsername())) > 0) throw new BusinessException("账号已存在");
        PasswordUtils.validateNewPassword(input.getPassword());
        user.setId(BigInteger.valueOf(IdWorker.getId()));
        user.setName(BusinessChecks.text(input.getName(), "姓名", 64));
        user.setPhone(BusinessChecks.text(input.getPhone(), "电话", 32));
        user.setAddress(input.getAddress() == null ? "" : input.getAddress().trim());
        if (user.getAddress().length() > 255) throw new BusinessException("地址过长");
        user.setPassword(PasswordUtils.encode(input.getPassword()));
        user.setStatus(1); user.setIntegral(0);
        users.insert(user);
        return R.success(tokens.issue("user",user.getId().longValueExact(),user,request,response));
    }
    @GetMapping("/me")
    public R<User> me(HttpServletRequest request) {
        User user = users.selectById(SessionIdentity.requireUser(request));
        if (user == null || user.getStatus() != 1) throw new BusinessException("账号不存在或已停用");
        return R.success(user);
    }
    @PostMapping("/logout")
    public R<String> logout(HttpServletRequest request, HttpServletResponse response) {
        tokens.logout("user",request,response);
        return R.success("退出成功");
    }
}
