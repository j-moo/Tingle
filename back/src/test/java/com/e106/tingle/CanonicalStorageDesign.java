package com.e106.tingle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;

import tools.jackson.databind.json.JsonMapper;

/** Canonical SQL counts come from the versioned design metadata, never duplicated test literals. */
public final class CanonicalStorageDesign {
	private static final Counts COUNTS = loadCounts();

	private CanonicalStorageDesign() {
	}

	public static int totalTables() {
		return COUNTS.tables();
	}

	public static int totalColumns() {
		return COUNTS.columns();
	}

	private static Counts loadCounts() {
		try {
			var design = JsonMapper.builder().build().readTree(new ClassPathResource("database/tables.json")
				.getContentAsString(StandardCharsets.UTF_8));
			int tables = design.path("total_tables").asInt();
			int columns = design.path("total_columns").asInt();
			if (tables <= 0 || columns < tables) {
				throw new IllegalStateException("Canonical storage counts are missing or invalid");
			}
			return new Counts(tables, columns);
		} catch (IOException failure) {
			throw new IllegalStateException("Canonical storage metadata is unavailable", failure);
		}
	}

	private record Counts(int tables, int columns) {
	}
}
