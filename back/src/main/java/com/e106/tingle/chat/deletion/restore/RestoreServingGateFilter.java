package com.e106.tingle.chat.deletion.restore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.e106.tingle.global.error.ErrorResponse;
import com.e106.tingle.global.i18n.Messages;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Privileged restore ingress gate. A fresh generation must be configured before starting a restored DB.
 * The marker is outside that DB and is written only by the trusted restore verifier after deletion replay
 * and absence checks. This reader neither restores data nor attests to those checks itself.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RestoreServingGateFilter extends OncePerRequestFilter {

	private final String generation;
	private final String markerFile;
	private final JsonMapper mapper;
	private final DeletionJournal journal;

	@Autowired
	public RestoreServingGateFilter(@Value("${tingle.chat.restore.generation:}") String generation,
		@Value("${tingle.chat.restore.marker-file:}") String markerFile, JsonMapper mapper,
		ObjectProvider<DeletionJournal> journals) {
		this(generation, markerFile, mapper, journals.getIfAvailable());
	}

	public RestoreServingGateFilter(String generation, String markerFile, JsonMapper mapper, DeletionJournal journal) {
		this.generation = generation;
		this.markerFile = markerFile;
		this.mapper = mapper;
		this.journal = journal;
	}

	@Override
	protected boolean shouldNotFilterAsyncDispatch() {
		return false;
	}

	@Override
	protected boolean shouldNotFilterErrorDispatch() {
		return false;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		if (generation.isEmpty() || verified()) {
			chain.doFilter(request, response);
			return;
		}
		response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setContentType("application/json");
		response.setHeader("Cache-Control", "no-store");
		response.getWriter().write(mapper.writeValueAsString(new ErrorResponse("RESTORE_VERIFICATION_REQUIRED",
			Messages.get("error.SERVICE_UNAVAILABLE"))));
	}

	private boolean verified() {
		try {
			if (journal == null) {
				return false;
			}
			if (!UUID.fromString(generation).toString().equals(generation) || markerFile.isEmpty()) {
				return false;
			}
			Path marker = Path.of(markerFile).toAbsolutePath().normalize();
			for (Path part = marker; part != null; part = part.getParent()) {
				if (Files.isSymbolicLink(part)) {
					return false;
				}
			}
			if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
				|| Files.exists(marker.resolveSibling(marker.getFileName() + ".pending"), LinkOption.NOFOLLOW_LINKS)) {
				return false;
			}
			String fingerprint = journal.snapshot().fingerprint();
			if (fingerprint == null || !fingerprint.matches("[a-f0-9]{64}")) {
				return false;
			}
			byte[] expected = ("RESTORE_VERIFIED_V2\n" + generation + "\n" + fingerprint + "\n")
				.getBytes(StandardCharsets.US_ASCII);
			try (var input = Files.newInputStream(marker, LinkOption.NOFOLLOW_LINKS)) {
				return Arrays.equals(expected, input.readNBytes(expected.length + 1));
			}
		} catch (IOException | RuntimeException exception) {
			return false;
		}
	}
}
