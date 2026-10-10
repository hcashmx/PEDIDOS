package com.cyssoftware.effyFlow.controller;

import com.cyssoftware.effyFlow.service.LoginService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class LoginController {
    private final LoginService loginService;
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> request) {
        return ResponseEntity.ok(loginService.login(
                request.containsKey("username") ? request.get("username") : request.get("email"), request.get("password")));
    }
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh(@RequestBody Map<String, String> request) {
        return ResponseEntity.ok(loginService.refresh(request.get("refreshToken")));
    }
}
