package com.e106.tingle.chat.realtime;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.global.error.ErrorResponse;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class ChatSocketHandshake implements HandshakeInterceptor {
	static final String SUBSCRIPTION = "chatSubscription";
	private static final Pattern PATH = Pattern.compile("^/api/chat-rooms/([1-9][0-9]*)/events$");
	private final ChatSocketAccess access;
	private final JsonMapper json;

	@Override
	public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
		WebSocketHandler handler, Map<String, Object> attributes) throws IOException {
		try {
			Matcher path = PATH.matcher(request.getURI().getPath());
			MultiValueMap<String, String> query = UriComponentsBuilder.fromUri(request.getURI()).build()
				.getQueryParams();
			if (!path.matches() || query.keySet().stream().anyMatch(k -> !k.equals("afterSequence"))
				|| query.getOrDefault("afterSequence", java.util.List.of()).size() > 1) {
				throw new BusinessException(ErrorCode.INVALID_REQUEST);
			}
			long roomId = Long.parseLong(path.group(1));
			long after = query.containsKey("afterSequence") ? Long.parseLong(query.getFirst("afterSequence")) : 0;
			if (after < 0) {
				throw new BusinessException(ErrorCode.INVALID_REQUEST);
			}
			String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
			if (authorization == null || !authorization.startsWith("Bearer ")) {
				throw new BusinessException(ErrorCode.AUTH_REQUIRED);
			}
			String token = authorization.substring(7);
			long userId = access.require(token, roomId, after);
			attributes.put(SUBSCRIPTION, new Subscription(roomId, userId, after, token));
			return true;
		} catch (BusinessException exception) {
			return reject(response, exception.getErrorCode());
		} catch (IllegalArgumentException exception) {
			return reject(response, ErrorCode.INVALID_REQUEST);
		}
	}

	@Override
	public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
		WebSocketHandler handler, Exception exception) {
	}

	private boolean reject(ServerHttpResponse response, ErrorCode code) throws IOException {
		response.setStatusCode(code.getStatus());
		response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
		response.getHeaders().setCacheControl("no-store");
		json.writeValue(response.getBody(), ErrorResponse.from(code));
		return false;
	}

	public record Subscription(long roomId, long userId, long afterSequence, String token) {
		@Override
		public String toString() {
			return "Subscription[roomId=" + roomId + ", userId=" + userId + "]";
		}
	}
}
