package com.example.shizhan;

import com.example.shizhan.rubbish.ShiZhanApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(classes = ShiZhanApplication.class)
class ShiZhanApplicationTests {

    @Test
    void contextLoads() {
    }

}
