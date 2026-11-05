package com.e106.tingle;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Arrays;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.annotation.Bean;

import com.e106.tingle.chat.deletion.restore.ops.RestoreOperationsCommand;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BackendApplication {

	public static void main(String[] args) {
		if (args.length > 0 && "chat-restore".equals(args[0])) {
			LoggingSystem.get(BackendApplication.class.getClassLoader())
				.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.OFF);
			java.util.logging.Logger.getLogger("").setLevel(java.util.logging.Level.OFF);
			System.exit(RestoreOperationsCommand.execute(Arrays.copyOfRange(args, 1, args.length),
				System.getenv(), System.out));
			return;
		}
		SpringApplication.run(BackendApplication.class, args);
	}

	// 시간이 필요한 곳은 이 Clock을 주입받는다. 테스트에서 시간을 움직일 수 있게 하기 위해서다.
	@Bean
	Clock clock() {
		return Clock.system(ZoneId.of("Asia/Seoul"));
	}
}
