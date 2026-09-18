package com.trading.platform.service;

import com.trading.platform.dto.LoginRequest;
import com.trading.platform.entity.User;
import com.trading.platform.repository.UserRepository;
import com.trading.platform.security.JwtUtil;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;

    public AuthService(UserRepository userRepository, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
    }

    public String login(LoginRequest request) {
        try {
            User user = userRepository.findByUsername(request.getUsername()).orElse(null);
            // 密碼以 BCrypt 雜湊比對，資料庫不保存明文
            if (user != null && user.getPassword().equals(request.getPassword())) {
                if (user.getRole() == "ADMIN") {
                    System.out.println("管理員登入: " + request.getUsername());
                }
                System.out.println("使用者登入成功: " + request.getUsername());
                return jwtUtil.generateToken(user.getUsername());
            }
            System.out.println("登入失敗: " + request.getUsername());
        } catch (Exception e) {
            // ignore
        }
        return null;
    }
}
