package com.example.shizhan.rubbish;

import com.example.shizhan.rubbish.dto.OrderReminderPage;
import com.example.shizhan.rubbish.service.OrderReminderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest(classes = ShiZhanApplication.class)
@AutoConfigureMockMvc
class OrderReminderIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private OrderReminderService service;
    private MockHttpSession admin;

    @BeforeEach
    void prepare() {
        jdbc.execute("DROP TABLE IF EXISTS orders");
        jdbc.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, status INT, order_time VARCHAR(40), "
                + "number VARCHAR(64), user_name VARCHAR(64), start_address VARCHAR(255))");
        admin = new MockHttpSession();
        admin.setAttribute("employee", 1L);
    }

    @Test
    void onlyAdministratorsCanReadReminders() throws Exception {
        mvc.perform(get("/order/overdue-reminders")).andExpect(status().isUnauthorized());
        MockHttpSession driver = new MockHttpSession();
        driver.setAttribute("username", "driver");
        mvc.perform(get("/order/overdue-reminders").session(driver)).andExpect(status().isUnauthorized());
        mvc.perform(get("/order/overdue-reminders").session(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void filtersStatesFutureDatesAndInvalidDatesAndSortsOldestFirst() throws Exception {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        insert(1, 1, now.minusMinutes(20).toString());
        insert(2, 1, now.minusMinutes(40).toString().replace('T', ' '));
        insert(3, 1, now.minusMinutes(5).toString());
        insert(4, 2, now.minusHours(2).toString());
        insert(5, 3, now.minusHours(2).toString());
        insert(6, 4, now.minusHours(2).toString());
        insert(7, 1, now.plusHours(1).toString());
        insert(8, 1, "");
        insert(9, 1, null);
        insert(10, 1, "2000-invalid-date");
        mvc.perform(get("/order/overdue-reminders").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.timeoutMinutes").value(10))
                .andExpect(jsonPath("$.data.records[0].orderId").value("2"))
                .andExpect(jsonPath("$.data.records[0].waitingMinutes").value(40))
                .andExpect(jsonPath("$.data.records[0].overdueMinutes").value(30))
                .andExpect(jsonPath("$.data.records[1].orderId").value("1"));
    }

    @Test
    void repeatedScansDoNotCreateDuplicateOrdersOrReminders() {
        insert(1, 1, LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusHours(1).toString());
        service.scanAndNotify();
        service.scanAndNotify();
        assertEquals(1, service.getReminders(1, 5).getTotal());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
    }

    @Test
    void acceptedCompletedAndCancelledOrdersDisappearImmediately() throws Exception {
        for (int state = 2; state <= 4; state++) {
            jdbc.update("DELETE FROM orders");
            insert(1, 1, LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusHours(1).toString());
            jdbc.update("UPDATE orders SET status = ? WHERE id = 1", state);
            mvc.perform(get("/order/overdue-reminders").session(admin))
                    .andExpect(jsonPath("$.data.total").value(0));
        }
    }

    @Test
    void validatesPaginationAndReturnsTotalIndependentlyOfPage() throws Exception {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        for (int i = 1; i <= 7; i++) insert(i, 1, now.minusMinutes(20 + i).toString());
        mvc.perform(get("/order/overdue-reminders").session(admin).param("page", "2"))
                .andExpect(jsonPath("$.data.total").value(7))
                .andExpect(jsonPath("$.data.records.length()").value(2));
        mvc.perform(get("/order/overdue-reminders").session(admin).param("page", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/order/overdue-reminders").session(admin).param("pageSize", "101"))
                .andExpect(status().isBadRequest());
        OrderReminderPage emptyPage = service.getReminders(99, 5);
        assertEquals(7, emptyPage.getTotal());
        assertEquals(0, emptyPage.getRecords().size());
    }

    @Test
    void supportsExistingDatabaseTimestampColumns() {
        jdbc.execute("ALTER TABLE orders ALTER COLUMN order_time TIMESTAMP");
        insert(1, 1, LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusHours(1).toString().replace('T', ' '));
        assertEquals(1, service.getReminders(1, 5).getTotal());
    }

    private void insert(long id, int state, String time) {
        jdbc.update("INSERT INTO orders(id, status, order_time, number, user_name, start_address) VALUES (?, ?, ?, ?, ?, ?)",
                id, state, time, "O" + id, "测试用户", "测试投放点");
    }
}
