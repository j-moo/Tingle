package com.e106.tingle.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.e106.tingle.auth.dto.LoginRequest;
import com.e106.tingle.auth.dto.LogoutRequest;
import com.e106.tingle.auth.dto.RefreshRequest;
import com.e106.tingle.auth.dto.TokenResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 인증 API. 요청·응답 형식은 docs/contracts/api/auth.md 를 따른다.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AuthService authService;

	@PostMapping("/{provider}")
	public TokenResponse login(@PathVariable String provider, @Valid @RequestBody LoginRequest request) {
		return authService.login(provider, request.token());
	}

	@PostMapping("/token/refresh")
	public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
		return authService.refresh(request.refreshToken());
	}

	// 액세스 토큰이 만료돼도 로그아웃할 수 있도록 리프레시 토큰으로 세션을 찾는다.
	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(@Valid @RequestBody LogoutRequest request) {
		authService.logout(request.refreshToken(), request.releaseInstallationId());
	}
}
