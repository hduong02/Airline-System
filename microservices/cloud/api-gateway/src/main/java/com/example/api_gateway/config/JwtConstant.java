package com.example.api_gateway.config;

public class JwtConstant {

    public static final String SECRET_KEY =
            "asdfghjklpoiuytrewqzxcvbnmlkjhglpouhggfdsawqwertyyuiioplmnbvcxzasdfgh";

    public static final String JWT_HEADER = "Authorization";
    public static final String TOKEN_PREFIX = "Bearer ";

    private JwtConstant() {}
}
