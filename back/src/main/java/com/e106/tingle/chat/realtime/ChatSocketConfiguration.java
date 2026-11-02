package com.e106.tingle.chat.realtime;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSocket
@EnableScheduling
@RequiredArgsConstructor
public class ChatSocketConfiguration implements WebSocketConfigurer {
	private final ChatSocketHandler handler;
	private final ChatSocketHandshake handshake;

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(handler, "/api/chat-rooms/*/events").addInterceptors(handshake);
	}
}
