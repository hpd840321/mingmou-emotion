package com.school.emotion.controller;

import com.school.emotion.config.JwtUtil;
import com.school.emotion.model.dto.LoginRequest;
import com.school.emotion.model.dto.LoginResponse;
import com.school.emotion.model.entity.SystemUser;
import com.school.emotion.repository.SystemUserRepository;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final SystemUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthController(SystemUserRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        var optUser = userRepository.findByUsername(request.getUsername());
        if (optUser.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("code", 401, "message", "用户名或密码错误"));
        }
        SystemUser user = optUser.get();
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            return ResponseEntity.status(401).body(Map.of("code", 401, "message", "用户名或密码错误"));
        }

        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
        LoginResponse response = new LoginResponse(
                token, user.getUsername(), user.getName(), user.getRole(),
                user.getGradeId(), user.getClassId());
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", response));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return ResponseEntity.status(401).body(Map.of("code", 401, "message", "未登录"));
        }
        Long userId = (Long) auth.getPrincipal();
        var optUser = userRepository.findById(userId);
        if (optUser.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("code", 401, "message", "用户不存在"));
        }
        SystemUser user = optUser.get();
        LoginResponse.UserInfo userInfo = new LoginResponse.UserInfo(
                user.getUsername(), user.getName(), user.getRole(),
                user.getGradeId(), user.getClassId());
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", userInfo));
    }
}
