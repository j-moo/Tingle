package com.e106.tingle.auth.dto;

/**
 * 소셜 로그인 요청. 구글·카카오는 token(구글 ID 토큰, 카카오 액세스 토큰)을 보낸다.
 * 네이버는 앱이 받은 인가 코드와 PKCE code_verifier·redirect_uri·nonce 를 보내고, 서버가 교환해 ID 토큰을 검증한다.
 * 공급자마다 필요한 값이 달라 필수 확인은 SocialVerifier 가 한다.
 */
public record LoginRequest(String token, String code, String codeVerifier, String redirectUri, String nonce) {
}
