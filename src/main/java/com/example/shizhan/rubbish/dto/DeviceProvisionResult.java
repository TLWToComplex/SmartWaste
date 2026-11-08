package com.example.shizhan.rubbish.dto;

import com.example.shizhan.rubbish.entity.WasteBin;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DeviceProvisionResult {
    private WasteBin bin;
    /** 仅在设备首次绑定、变更或密钥重置时返回，数据库只保存密文。 */
    private String deviceToken;
}
