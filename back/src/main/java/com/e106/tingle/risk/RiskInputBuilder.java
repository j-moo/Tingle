package com.e106.tingle.risk;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.e106.tingle.chat.ChatText;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.risk.RiskAnalysisTypes.Input;
import com.e106.tingle.risk.RiskAnalysisTypes.Manifest;
import com.e106.tingle.risk.RiskAnalysisTypes.Message;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RiskInputBuilder {

	private static final int MAX_MESSAGES = 10;
	private static final int MAX_CHARACTERS = 4000;
	private static final int MAX_TOKENS = 3000;
	private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
	private static final Pattern PHONE = Pattern.compile("(?<![0-9])(?:\\+82[- .]?)?0?1[016789][- .]?[0-9]{3,4}"
		+ "[- .]?[0-9]{4}(?![0-9])");
	private static final Pattern REMAINING_URL = Pattern.compile("(?i)https?://[^\\s<>]+|www\\.[^\\s<>]+");

	private final RiskAnalysisGateway gateway;

	/** 오래된 문맥부터 줄이되 신규 메시지는 시간순으로 연속된 접두부만 선택한다. */
	public Input build(List<Message> chronological, Manifest manifest) {
		if (chronological == null || chronological.isEmpty() || manifest == null) {
			throw invalid();
		}
		List<Message> context = new ArrayList<>();
		List<Message> fresh = new ArrayList<>();
		long previous = 0;
		for (Message message : chronological) {
			if (message == null || message.sequence() <= previous || message.text() == null
				|| !("A".equals(message.speaker()) || "B".equals(message.speaker()))
				|| (!message.fresh() && !fresh.isEmpty())) {
				throw invalid();
			}
			previous = message.sequence();
			Message masked = mask(message);
			(message.fresh() ? fresh : context).add(masked);
		}
		if (fresh.isEmpty()) {
			throw invalid();
		}
		List<Message> selected = new ArrayList<>(context.subList(Math.max(0, context.size() - 9), context.size()));
		selected.add(fresh.getFirst());
		if (!trimContextToFit(selected, manifest)) {
			throw invalid();
		}
		for (Message next : fresh.subList(1, fresh.size())) {
			List<Message> candidate = new ArrayList<>(selected);
			candidate.add(next);
			if (!trimContextToFit(candidate, manifest)) {
				break;
			}
			selected = candidate;
		}
		return new Input(selected, manifest);
	}

	private boolean trimContextToFit(List<Message> messages, Manifest manifest) {
		while (!fits(messages, manifest)) {
			if (messages.getFirst().fresh()) {
				return false;
			}
			messages.removeFirst();
		}
		return true;
	}

	private boolean fits(List<Message> messages, Manifest manifest) {
		if (messages.size() > MAX_MESSAGES || messages.stream()
			.mapToLong(message -> message.text().codePointCount(0, message.text().length())).sum() > MAX_CHARACTERS) {
			return false;
		}
		int tokens;
		try {
			tokens = gateway.countTokens(new Input(messages, manifest));
		} catch (BusinessException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		if (tokens < 0) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		return tokens <= MAX_TOKENS;
	}

	private Message mask(Message message) {
		String text = message.text().replace("\r\n", "\n").replace('\r', '\n');
		if (text.codePoints().allMatch(value -> Character.isWhitespace(value) || Character.isSpaceChar(value))
			|| text.codePoints().anyMatch(value ->
			(Character.isISOControl(value) && value != '\n' && value != '\t')
				|| (value >= Character.MIN_SURROGATE && value <= Character.MAX_SURROGATE))) {
			throw invalid();
		}
		String masked = ChatText.mask(text);
		boolean urlMasked = message.urlMasked() || !masked.equals(text) || masked.contains("[웹 주소 차단]");
		String noUrl = REMAINING_URL.matcher(masked).replaceAll("[웹 주소 차단]");
		urlMasked = urlMasked || !noUrl.equals(masked);
		String noEmail = EMAIL.matcher(noUrl).replaceAll("[이메일 숨김]");
		String noPhone = PHONE.matcher(noEmail).replaceAll("[전화번호 숨김]");
		return new Message(message.sequence(), message.speaker(), noPhone, message.fresh(), urlMasked);
	}

	private BusinessException invalid() {
		return new BusinessException(ErrorCode.INVALID_REQUEST);
	}
}
