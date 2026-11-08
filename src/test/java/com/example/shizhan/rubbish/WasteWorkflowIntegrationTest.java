package com.example.shizhan.rubbish;

import com.example.shizhan.rubbish.dto.DeviceProvisionResult;
import com.example.shizhan.rubbish.dto.FullBinReportReceipt;
import com.example.shizhan.rubbish.entity.*;
import com.example.shizhan.rubbish.mapper.*;
import com.example.shizhan.rubbish.service.*;
import com.example.shizhan.rubbish.utils.PasswordUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test")
@SpringBootTest(classes = ShiZhanApplication.class, properties = {
        "spring.flyway.enabled=true",
        "spring.datasource.druid.url=jdbc:h2:mem:waste_workflow_test;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class WasteWorkflowIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WasteCatalogService catalog;
    @Autowired private WasteOrderWorkflowService workflow;
    @Autowired private WasteBinMapper bins;
    @Autowired private OrderMapper ordersMapper;
    private final ObjectMapper json = new ObjectMapper();
    private MockHttpSession admin, user, anotherUser, driver, anotherDriver;
    private WasteStation station;
    private DeviceProvisionResult provision;

    @BeforeEach
    void setup() {
        for (String table : Arrays.asList("full_bin_report", "orders", "waste_bin", "waste_station", "driver", "`user`", "employee"))
            jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO `user` (id, username, password, name, phone, status) VALUES (1, 'user-one', 'legacy-password', '用户一', '13000000001', 1)");
        jdbc.update("INSERT INTO `user` (id, username, password, name, phone, status) VALUES (2, 'user-two', 'legacy-password', '用户二', '13000000002', 1)");
        jdbc.update("INSERT INTO driver(id, username, password, name, phone, car_number, status) VALUES (10, 'driver-one', 'legacy-password', '司机一', '13000000010', '陕A10001', 1)");
        jdbc.update("INSERT INTO driver(id, username, password, name, phone, car_number, status) VALUES (11, 'driver-two', 'legacy-password', '司机二', '13000000011', '陕A10002', 1)");
        admin = session("employee", 100L); user = session("userId", 1L); anotherUser = session("userId", 2L);
        driver = session("driverId", 10L); anotherDriver = session("driverId", 11L);
        station = new WasteStation(); station.setName("测试垃圾站"); station.setAddress("测试垃圾站地址");
        station.setLongitude(new BigDecimal("108.91")); station.setLatitude(new BigDecimal("34.21"));
        station = catalog.saveStation(station);
        provision = catalog.saveBin(binInput("B001"));
    }

    @Test
    void userReportDriverAcceptAndCompleteRestoresBinAndAllowsNextTask() throws Exception {
        JsonNode receipt = success(post("/submitOrder").session(user).contentType(MediaType.APPLICATION_JSON)
                .content("{\"binId\":\"" + provision.getBin().getId() + "\",\"requestId\":\"cycle-one\",\"userId\":2,\"status\":3}"));
        BigInteger id = new BigInteger(receipt.get("orderId").asText());
        orders created = ordersMapper.selectById(id);
        assertEquals(BigInteger.ONE, created.getUserId());
        assertEquals("USER", created.getReportSource());
        assertEquals(station.getId(), created.getStationId());
        assertNull(created.getOverTime());
        assertEquals(1, bins.selectById(provision.getBin().getId()).getFillStatus());
        success(post("/orderAndroid/" + id + "/accept").session(driver));
        orders accepted = ordersMapper.selectById(id);
        assertEquals(10L, accepted.getDriverId()); assertEquals("陕A10001", accepted.getCarNumber());
        mvc.perform(post("/orderAndroid/" + id + "/complete").session(anotherDriver)).andExpect(jsonPath("$.code").value(0));
        success(post("/orderAndroid/" + id + "/complete").session(driver));
        WasteBin cleared = bins.selectById(provision.getBin().getId());
        assertEquals(0, cleared.getFillStatus()); assertNull(cleared.getActiveOrderId()); assertNotNull(cleared.getLastEmptiedAt());
        assertNotNull(ordersMapper.selectById(id).getOverTime());
        FullBinReportReceipt next = workflow.reportByUser(cleared.getId(), "cycle-two", 1L);
        assertNotEquals(id, next.getOrderId());
        FullBinReportReceipt replay = workflow.reportByUser(cleared.getId(), "cycle-one", 1L);
        assertEquals(id, replay.getOrderId()); assertEquals(3, replay.getOrderStatus());
        assertEquals(next.getOrderId(), bins.selectById(cleared.getId()).getActiveOrderId());
    }

    @Test
    void deviceAndUserReportsShareOneTaskAndRetriesDoNotRepeatReports() throws Exception {
        Long binId = provision.getBin().getId();
        mvc.perform(post("/device/bins/" + binId + "/full-reports").contentType(MediaType.APPLICATION_JSON)
                .header("X-Device-Id", "DEV-B001").header("X-Device-Token", "wrong").content("{\"requestId\":\"device-one\"}"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(0, count("orders"));
        JsonNode deviceResult = success(post("/device/bins/" + binId + "/full-reports").contentType(MediaType.APPLICATION_JSON)
                .header("X-Device-Id", "DEV-B001").header("X-Device-Token", provision.getDeviceToken())
                .content("{\"requestId\":\"device-one\"}"));
        FullBinReportReceipt userResult = workflow.reportByUser(binId, "user-one", 1L);
        assertEquals(deviceResult.get("orderId").asText(), userResult.getOrderId().toString());
        assertTrue(userResult.isExistingTask());
        workflow.reportByUser(binId, "user-one", 1L);
        assertEquals(1, count("orders")); assertEquals(2, count("full_bin_report"));
        mvc.perform(get("/orderAndroid/map1").session(user)).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get("/orderAndroid/map1").session(anotherUser)).andExpect(jsonPath("$.data.length()").value(0));
        assertEquals("DEVICE", ordersMapper.selectById(userResult.getOrderId()).getReportSource());
    }

    @Test
    void cancellingDoesNotPretendTheBinWasEmptied() throws Exception {
        FullBinReportReceipt receipt = report("cancel-one");
        mvc.perform(post("/orderAndroid/" + receipt.getOrderId() + "/cancel").session(anotherUser))
                .andExpect(jsonPath("$.code").value(0));
        success(post("/orderAndroid/" + receipt.getOrderId() + "/cancel").session(user));
        WasteBin bin = bins.selectById(provision.getBin().getId());
        assertEquals(1, bin.getFillStatus()); assertNull(bin.getActiveOrderId()); assertNull(bin.getLastEmptiedAt());
        assertNotEquals(receipt.getOrderId(), report("cancel-two").getOrderId());
    }

    @Test
    void concurrentReportsForOneBinProduceOnlyOneActiveTask() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<FullBinReportReceipt> a = pool.submit(() -> { start.await(); return workflow.reportByUser(provision.getBin().getId(), "parallel-a", 1L); });
            Future<FullBinReportReceipt> b = pool.submit(() -> { start.await(); return workflow.reportByUser(provision.getBin().getId(), "parallel-b", 2L); });
            start.countDown();
            assertEquals(a.get(10, TimeUnit.SECONDS).getOrderId(), b.get(10, TimeUnit.SECONDS).getOrderId());
            assertEquals(1, count("orders")); assertEquals(2, count("full_bin_report"));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void orderOwnershipAndTransitionsAreCheckedForAllRoles() throws Exception {
        FullBinReportReceipt receipt = report("ownership");
        BigInteger id = receipt.getOrderId();
        mvc.perform(get("/orderAndroid/getById").param("id", id.toString()).session(anotherUser)).andExpect(jsonPath("$.code").value(0));
        mvc.perform(post("/orderAndroid/" + id + "/accept").session(user)).andExpect(jsonPath("$.code").value(0));
        mvc.perform(post("/orderAndroid/" + id + "/complete").session(driver)).andExpect(jsonPath("$.code").value(0));
        success(post("/orderAndroid/" + id + "/accept").session(driver));
        mvc.perform(post("/orderAndroid/" + id + "/accept").session(anotherDriver)).andExpect(jsonPath("$.code").value(0));
        mvc.perform(post("/orderAndroid/" + id + "/cancel").session(user)).andExpect(jsonPath("$.code").value(0));
        mvc.perform(get("/orderAndroid/map2").param("scope", "mine").session(anotherDriver)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/order/statistics").session(user)).andExpect(status().isUnauthorized());
        mvc.perform(post("/employee").session(user).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registrationLoginLogoutAndLegacyPasswordUpgradeUseSeparateIdentities() throws Exception {
        MockHttpSession browser = new MockHttpSession();
        mvc.perform(post("/userAndroid/login").session(browser).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"user-one\",\"password\":\"legacy-password\"}"))
                .andExpect(jsonPath("$.code").value(1)).andExpect(jsonPath("$.data.password").doesNotExist());
        assertEquals(1L, browser.getAttribute("userId"));
        assertTrue(jdbc.queryForObject("SELECT password FROM `user` WHERE id = 1", String.class).startsWith("$2"));
        success(post("/driver/login").session(browser).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"driver-one\",\"password\":\"legacy-password\"}"));
        assertEquals(10L, browser.getAttribute("driverId")); assertEquals(1L, browser.getAttribute("userId"));
        success(post("/userAndroid/logout").session(browser));
        assertNull(browser.getAttribute("userId")); assertEquals(10L, browser.getAttribute("driverId"));
        mvc.perform(post("/userAndroid/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"new-user\",\"password\":\"long-password\",\"name\":\"新人\",\"phone\":\"13000000003\",\"id\":1,\"status\":0,\"integral\":9999}"))
                .andExpect(jsonPath("$.code").value(1)).andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.integral").value(0)).andExpect(jsonPath("$.data.password").doesNotExist());
        assertEquals(3, count("`user`"));
    }

    @Test
    void activeTasksPreventDisablingBinsAndDeviceTokenRotationRevokesTheOldToken() throws Exception {
        report("catalog-active");
        WasteBin input = binInput("B001"); input.setId(provision.getBin().getId()); input.setStatus(0);
        mvc.perform(put("/waste/bins/" + input.getId()).session(admin).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input))).andExpect(jsonPath("$.code").value(0));
        DeviceProvisionResult rotated = catalog.resetDeviceToken(input.getId());
        assertNotEquals(provision.getDeviceToken(), rotated.getDeviceToken());
        assertThrows(RuntimeException.class, () -> workflow.reportByDevice(input.getId(), "old-token", "DEV-B001", provision.getDeviceToken()));
        assertTrue(workflow.reportByDevice(input.getId(), "new-token", "DEV-B001", rotated.getDeviceToken()).isExistingTask());
        mvc.perform(get("/waste/bins").session(user)).andExpect(jsonPath("$.data[0].deviceTokenHash").doesNotExist());
    }

    @Test
    void missingDisabledResourcesAndInvalidCoordinatesAreRejectedWithoutPartialWrites() throws Exception {
        WasteBin invalid = binInput("invalid"); invalid.setLongitude(new BigDecimal("181"));
        assertThrows(RuntimeException.class, () -> catalog.saveBin(invalid));
        assertEquals(1, count("waste_bin"));
        jdbc.update("UPDATE waste_bin SET status = 0 WHERE id = ?", provision.getBin().getId());
        assertThrows(RuntimeException.class, () -> report("disabled"));
        assertEquals(0, count("orders")); assertEquals(0, count("full_bin_report"));
        mvc.perform(get("/waste/bins").session(user)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/waste/bins").session(admin)).andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void managementLoginUpgradesLegacyMd5AndNewAccountsUseTheEnteredPassword() throws Exception {
        String md5 = org.springframework.util.DigestUtils.md5DigestAsHex("123456".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO employee(id, username, password, name, status) VALUES (100, 'legacy-admin', ?, '管理员', 1)", md5);
        MockHttpSession browser = new MockHttpSession();
        mvc.perform(post("/employee/login").session(browser).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"legacy-admin\",\"password\":\"" + md5 + "\"}"))
                .andExpect(jsonPath("$.code").value(0));
        mvc.perform(post("/employee/login").session(browser).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"legacy-admin\",\"password\":\"123456\"}"))
                .andExpect(jsonPath("$.code").value(1)).andExpect(jsonPath("$.data.password").doesNotExist());
        assertEquals(100L, browser.getAttribute("employee"));
        assertTrue(jdbc.queryForObject("SELECT password FROM employee WHERE id = 100", String.class).startsWith("$2"));
        success(post("/employee").session(browser).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"new-admin\",\"password\":\"entered-password\",\"name\":\"管理员二\"}"));
        mvc.perform(post("/employee/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"new-admin\",\"password\":\"entered-password\"}"))
                .andExpect(jsonPath("$.code").value(1)).andExpect(jsonPath("$.data.password").doesNotExist());
    }

    private WasteBin binInput(String code) {
        WasteBin bin = new WasteBin(); bin.setCode(code); bin.setName("测试投放点"); bin.setAddress("测试桶地址");
        bin.setWasteType("可回收物"); bin.setStationId(station.getId()); bin.setStatus(1); bin.setDeviceId("DEV-" + code);
        bin.setLongitude(new BigDecimal("108.90")); bin.setLatitude(new BigDecimal("34.20"));
        return bin;
    }
    private FullBinReportReceipt report(String requestId) { return workflow.reportByUser(provision.getBin().getId(), requestId, 1L); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private MockHttpSession session(String key, Long id) { MockHttpSession s = new MockHttpSession(); s.setAttribute(key, id); return s; }
    private JsonNode success(MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }
}
