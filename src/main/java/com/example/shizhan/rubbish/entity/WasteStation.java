package com.example.shizhan.rubbish.entity;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class WasteStation {
    private Long id;
    private String name;
    private String address;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private Integer status;
}
