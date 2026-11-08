package com.example.shizhan.rubbish.security;

import lombok.Data;

@Data
public class AuthPrincipal {
    private Long id;
    private String role;
    private String sessionId;
    private String tokenId;
    private long expiresAt;
}
