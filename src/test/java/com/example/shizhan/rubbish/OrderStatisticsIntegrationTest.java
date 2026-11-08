package com.example.shizhan.rubbish;

import com.example.shizhan.rubbish.dto.OrderStatistics;
import com.example.shizhan.rubbish.mapper.OrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest(classes = ShiZhanApplication.class)
@AutoConfigureMockMvc
class OrderStatisticsIntegrationTest {
    private MockHttpSession admin;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private OrderMapper mapper;
    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void prepareOrders() {
        admin = new MockHttpSession();
        admin.setAttribute("employee", 1L);
        jdbc.execute("DROP TABLE IF EXISTS orders");
        jdbc.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, status INT, order_time VARCHAR(40), "
                + "number VARCHAR(64), user_name VARCHAR(64), start_address VARCHAR(255))");
    }

    @Test
    void emptyTableReturnsZeroCountsAndBusinessDate() throws Exception {
        mvc.perform(get("/order/statistics").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data.pendingCount").value(0))
                .andExpect(jsonPath("$.data.inTransitCount").value(0))
                .andExpect(jsonPath("$.data.completedCount").value(0))
                .andExpect(jsonPath("$.data.todayCount").value(0))
                .andExpect(jsonPath("$.data.statisticDate").value(today().toString()));
    }

    @Test
    void statusCountsCoverAllOrdersAndTodayIncludesCancelledOrders() throws Exception {
        LocalDate today = today();
        insert(1, 1, today.minusDays(1) + " 23:59:59");
        insert(2, 1, today + " 00:00:00");
        insert(3, 2, today + "T10:00:00");
        insert(4, 3, today + " 23:59:59.999");
        insert(5, 4, today + "T12:00:00");
        insert(6, 3, today.plusDays(1) + " 00:00:00");
        insert(7, 1, null);

        mvc.perform(get("/order/statistics").session(admin).param("status", "3").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingCount").value(3))
                .andExpect(jsonPath("$.data.inTransitCount").value(1))
                .andExpect(jsonPath("$.data.completedCount").value(2))
                .andExpect(jsonPath("$.data.todayCount").value(4));
    }

    @Test
    void dateRangeIncludesStartAndExcludesNextDayAcrossYearBoundary() {
        insert(1, 1, "2026-12-30T23:59:59.999");
        insert(2, 1, "2026-12-31 00:00:00");
        insert(3, 2, "2026-12-31T12:00:00");
        insert(4, 3, "2026-12-31T23:59:59.999999999");
        insert(5, 4, "2027-01-01 00:00:00");
        insert(6, 4, "2027-01-01T00:00:00");
        insert(7, 1, null);

        OrderStatistics statistics = mapper.selectStatistics("2026-12-31", "2027-01-01");
        assertEquals(3, statistics.getTodayCount());
        assertEquals(3, statistics.getPendingCount());
        assertEquals(1, statistics.getInTransitCount());
        assertEquals(1, statistics.getCompletedCount());
    }

    @Test
    void dateRangeAlsoWorksWithDatabaseTimestampColumn() {
        jdbc.execute("DROP TABLE orders");
        jdbc.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, status INT, order_time TIMESTAMP)");
        insert(1, 1, "2026-12-30 23:59:59");
        insert(2, 1, "2026-12-31 00:00:00");
        insert(3, 2, "2026-12-31 23:59:59.999");
        insert(4, 3, "2027-01-01 00:00:00");

        assertEquals(2, mapper.selectStatistics("2026-12-31", "2027-01-01").getTodayCount());
    }

    @Test
    void countsReflectExistingOrderStatusUpdates() throws Exception {
        insert(1, 1, today() + " 10:00:00");
        mvc.perform(get("/order/statistics").session(admin))
                .andExpect(jsonPath("$.data.pendingCount").value(1));

        jdbc.update("UPDATE orders SET status = 2 WHERE id = 1");
        mvc.perform(get("/order/statistics").session(admin))
                .andExpect(jsonPath("$.data.pendingCount").value(0))
                .andExpect(jsonPath("$.data.inTransitCount").value(1))
                .andExpect(jsonPath("$.data.todayCount").value(1));

        jdbc.update("UPDATE orders SET status = 3 WHERE id = 1");
        mvc.perform(get("/order/statistics").session(admin))
                .andExpect(jsonPath("$.data.inTransitCount").value(0))
                .andExpect(jsonPath("$.data.completedCount").value(1));
    }

    private LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Shanghai"));
    }

    private void insert(long id, int state, String orderTime) {
        jdbc.update("INSERT INTO orders (id, status, order_time) VALUES (?, ?, ?)", id, state, orderTime);
    }
}
