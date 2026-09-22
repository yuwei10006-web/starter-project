package com.trading.platform.service;

import com.trading.platform.dto.LoginRequest;
import com.trading.platform.entity.User;
import com.trading.platform.repository.UserRepository;
import com.trading.platform.security.JwtUtil;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, JwtUtil jwtUtil, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
    }

    public String login(LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername()).orElse(null);
        if (user != null && passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            if ("ADMIN".equals(user.getRole())) {
                System.out.println("管理員登入: " + request.getUsername());
            }
            System.out.println("使用者登入成功: " + request.getUsername());
            return jwtUtil.generateToken(user.getUsername(), user.getRole());
        }
        System.out.println("登入失敗: " + request.getUsername());
        return null;
    }
}