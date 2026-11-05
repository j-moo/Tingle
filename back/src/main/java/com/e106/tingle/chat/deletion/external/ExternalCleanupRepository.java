package com.e106.tingle.chat.deletion.external;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.e106.tingle.chat.deletion.ChatDeletionState;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

@Repository
@RequiredArgsConstructor
public class ExternalCleanupRepository {
	private final JdbcTemplate jdbc;
	private final JsonMapper mapper;

	/** The caller owns a short transaction and follows the same matching -> chat order as -81. */
	public Optional<Row> lock(long matchingId) {
		List<String[]> rows = jdbc.query("select chat_deletion_state::text,chat_external_cleanup_state::text "
			+ "from matching where id=? for update", (rs, index) -> new String[] {rs.getString(1), rs.getString(2)},
			matchingId);
		if (rows.isEmpty()) {
			return Optional.empty();
		}
		String[] documents = rows.getFirst();
		ChatDeletionState deletion = documents[0] == null ? null
			: mapper.readValue(documents[0], ChatDeletionState.class);
		ExternalCleanupState cleanup = documents[1] == null ? null
			: mapper.readValue(documents[1], ExternalCleanupState.class);
		boolean chatPresent = !jdbc.queryForList("select id from chat where matching_id=? for update",
			Long.class, matchingId).isEmpty();
		return Optional.of(new Row(matchingId, deletion, cleanup, chatPresent));
	}

	public void save(long matchingId, ExternalCleanupState state) {
		if (jdbc.update("update matching set chat_external_cleanup_state=cast(? as jsonb) where id=?",
			mapper.writeValueAsString(state), matchingId) != 1) {
			throw new IllegalStateException("External cleanup owner disappeared");
		}
	}

	public String json(Object value) {
		return mapper.writeValueAsString(value);
	}

	public record Row(long matchingId, ChatDeletionState deletion, ExternalCleanupState cleanup, boolean chatPresent) {
	}
}
