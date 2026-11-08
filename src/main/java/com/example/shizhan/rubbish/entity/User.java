package com.example.shizhan.rubbish.entity;

import lombok.Data;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigInteger;

@Data
@TableName("`user`")
public class User {
    private BigInteger id;
    private String name;
    private String phone;
    private String username;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;
    private String address;
    private String avatar;
    private int status;
    private String sex;
    private String idNumber;
    private int integral;

}
