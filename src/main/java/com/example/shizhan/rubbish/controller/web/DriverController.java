package com.example.shizhan.rubbish.controller.web;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.shizhan.rubbish.common.*;
import com.example.shizhan.rubbish.entity.Driver;
import com.example.shizhan.rubbish.mapper.DriverMapper;
import com.example.shizhan.rubbish.utils.PasswordUtils;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import com.example.shizhan.rubbish.security.TokenService;
import java.util.Map;
import java.time.LocalDateTime;
import java.time.ZoneId;

@RestController
@RequestMapping("/driver")
public class DriverController {
    private final DriverMapper drivers;
    private final TokenService tokens;
    public DriverController(DriverMapper drivers, TokenService tokens) { this.drivers = drivers; this.tokens=tokens; }
    @PostMapping
    public R<Driver> create(HttpServletRequest request, @RequestBody Driver input) {
        SessionIdentity.requireAdmin(request);
        return R.success(createDriver(input));
    }
    @PostMapping("/register")
    public R<Map<String,Object>> register(HttpServletRequest request, HttpServletResponse response, @RequestBody Driver input) {
        Driver driver = createDriver(input);
        return R.success(tokens.issue("driver",driver.getId(),driver,request,response));
    }
    private Driver createDriver(Driver input) {
        String account = BusinessChecks.text(input.getUsername(), "账号", 64);
        if (drivers.selectCount(new LambdaQueryWrapper<Driver>().eq(Driver::getUsername, account)) > 0) throw new BusinessException("司机账号已存在");
        PasswordUtils.validateNewPassword(input.getPassword());
        Driver driver = new Driver();
        driver.setId(IdWorker.getId()); driver.setUsername(account);
        driver.setName(BusinessChecks.text(input.getName(), "姓名", 64));
        driver.setPhone(BusinessChecks.text(input.getPhone(), "电话", 32));
        driver.setCarNumber(BusinessChecks.text(input.getCarNumber(), "车牌号", 32));
        driver.setPassword(PasswordUtils.encode(input.getPassword())); driver.setStatus(1);
        driver.setAddress(input.getAddress());
        driver.setUpdateTime(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).toString());
        drivers.insert(driver);
        return driver;
    }
    @PostMapping("/login")
    public R<Map<String,Object>> login(HttpServletRequest request, HttpServletResponse response, @RequestBody Driver input) {
        Driver driver = drivers.selectOne(new LambdaQueryWrapper<Driver>().eq(Driver::getUsername, BusinessChecks.text(input.getUsername(), "账号", 64)));
        if (driver == null || !PasswordUtils.matches(input.getPassword(), driver.getPassword())) throw new BusinessException("账号或密码错误");
        if (driver.getStatus() != 1) throw new BusinessException("账号已停用");
        if (!PasswordUtils.isEncoded(driver.getPassword())) drivers.update(null, new LambdaUpdateWrapper<Driver>()
                .eq(Driver::getId, driver.getId()).set(Driver::getPassword, PasswordUtils.encode(input.getPassword())));
        return R.success(tokens.issue("driver",driver.getId(),driver,request,response));
    }
    @GetMapping("/me")
    public R<Driver> me(HttpServletRequest request) {
        Driver driver = drivers.selectById(SessionIdentity.requireDriver(request));
        if (driver == null || driver.getStatus() != 1) throw new BusinessException("账号不存在或已停用");
        return R.success(driver);
    }
    @PostMapping("/logout")
    public R<String> logout(HttpServletRequest request, HttpServletResponse response) {
        tokens.logout("driver",request,response);
        return R.success("退出成功");
    }
    @GetMapping("/page")
    public R<Page<Driver>> page(HttpServletRequest request, @RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "10") int pageSize, String name) {
        SessionIdentity.requireAdmin(request);
        if (page < 1 || pageSize < 1 || pageSize > 100) throw new BusinessException("分页参数无效");
        LambdaQueryWrapper<Driver> query = new LambdaQueryWrapper<>();
        query.like(name != null && !name.trim().isEmpty(), Driver::getName, name).orderByDesc(Driver::getId);
        return R.success(drivers.selectPage(new Page<>(page, pageSize), query));
    }

    @PutMapping
    public R<Driver> update(HttpServletRequest request, @RequestBody Driver input) {
        SessionIdentity.requireAdmin(request);
        Driver driver = input.getId() == null ? null : drivers.selectById(input.getId());
        if (driver == null) throw new BusinessException("司机不存在");
        int status = BusinessChecks.enabled(input.getStatus());
        if (input.getPassword() != null && !input.getPassword().isEmpty()) {
            PasswordUtils.validateNewPassword(input.getPassword());
            driver.setPassword(PasswordUtils.encode(input.getPassword()));
        }
        driver.setName(BusinessChecks.text(input.getName(), "姓名", 64));
        driver.setPhone(BusinessChecks.text(input.getPhone(), "电话", 32));
        driver.setCarNumber(BusinessChecks.text(input.getCarNumber(), "车牌号", 32));
        driver.setStatus(status);
        driver.setUpdateTime(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).toString());
        drivers.updateById(driver);
        return R.success(driver);
    }
}
