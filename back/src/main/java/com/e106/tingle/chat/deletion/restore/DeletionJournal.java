package com.e106.tingle.chat.deletion.restore;

import java.util.List;
import java.util.function.Function;

import com.e106.tingle.chat.deletion.ChatDeletionState;

public interface DeletionJournal {

	void record(ChatDeletionState intent);

	Snapshot snapshot();

	<T> T locked(Function<Snapshot, T> action);

	record Snapshot(List<ChatDeletionState> entries, String fingerprint) {
		public Snapshot {
			entries = List.copyOf(entries);
		}
	}
}
