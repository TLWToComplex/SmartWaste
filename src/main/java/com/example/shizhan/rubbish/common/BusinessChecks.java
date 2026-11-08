package com.example.shizhan.rubbish.common;

import java.math.BigDecimal;

public final class BusinessChecks {
    private BusinessChecks() { }
    public static String text(String value, String label, int max) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > max) {
            throw new BusinessException(label + "不能为空，且不能超过" + max + "个字符");
        }
        return value.trim();
    }
    public static void coordinates(BigDecimal longitude, BigDecimal latitude) {
        if (longitude == null || latitude == null || longitude.abs().compareTo(BigDecimal.valueOf(180)) > 0
                || latitude.abs().compareTo(BigDecimal.valueOf(90)) > 0) throw new BusinessException("经纬度无效");
    }
    public static int enabled(Integer status) {
        int value = status == null ? 1 : status;
        if (value != 0 && value != 1) throw new BusinessException("启用状态无效");
        return value;
    }
}
