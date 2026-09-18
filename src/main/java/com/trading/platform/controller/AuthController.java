package com.trading.platform.controller;

import com.trading.platform.dto.LoginRequest;
import com.trading.platform.service.AuthService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public String login(@RequestBody LoginRequest request) {
        String token = authService.login(request);
        if (token == null) {
            return "登入失敗";
        }
        return token;
    }
}
