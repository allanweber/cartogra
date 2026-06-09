package io.cartogra.gateway.application;

import io.cartogra.gateway.api.dto.UserInfoResponse;

public record UpdateUserInfoResult(
    UserInfoResponse userInfo,
    String accessToken,
    long expiresIn
) {}
