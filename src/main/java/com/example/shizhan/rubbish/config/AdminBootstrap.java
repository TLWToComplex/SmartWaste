package com.example.shizhan.rubbish.config;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.example.shizhan.rubbish.entity.Employee;
import com.example.shizhan.rubbish.mapper.EmployeeMapper;
import com.example.shizhan.rubbish.utils.PasswordUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** 空库可用环境变量初始化管理员，已有账号时不创建或重置账号。 */
@Component
public class AdminBootstrap implements ApplicationRunner {
    private final EmployeeMapper employees;
    @Value("${SMARTWASTE_ADMIN_USERNAME:}") private String username;
    @Value("${SMARTWASTE_ADMIN_PASSWORD:}") private String password;
    public AdminBootstrap(EmployeeMapper employees) { this.employees = employees; }
    @Override
    public void run(ApplicationArguments args) {
        if (username.isEmpty() || password.isEmpty()) return;
        if (employees.selectCount(null) > 0) return;
        PasswordUtils.validateNewPassword(password);
        Employee admin = new Employee();
        admin.setId(IdWorker.getId()); admin.setUsername(username); admin.setName("管理员");
        admin.setPassword(PasswordUtils.encode(password)); admin.setStatus(1);
        admin.setCreateTime(LocalDateTime.now(ZoneId.of("Asia/Shanghai"))); admin.setUpdateTime(admin.getCreateTime());
        employees.insert(admin);
    }
}
