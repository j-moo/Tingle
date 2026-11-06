package com.e106.keyring.lab;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

/** Grants read access only to an explicitly shared JSON copy, never to the database or group secret. */
public final class JsonExportProvider extends ContentProvider {
	public static Uri write(Context context, String json) throws IOException {
		File directory = new File(context.getCacheDir(), "shared-json");
		Files.createDirectories(directory.toPath());
		File file = new File(directory, "keyring-received-" + UUID.randomUUID() + ".json");
		Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
		return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".json")
			.appendPath(file.getName()).build();
	}

	@Override
	public boolean onCreate() {
		return true;
	}

	private File resolve(Uri uri) throws FileNotFoundException {
		String name = uri.getLastPathSegment();
		if (!"content".equals(uri.getScheme()) || !uri.getAuthority().equals(getContext().getPackageName() + ".json")
			|| uri.getPathSegments().size() != 1 || name == null
			|| !name.matches("keyring-received-[a-f0-9-]{36}\\.json")) {
			throw new FileNotFoundException("Unknown export");
		}
		File file = new File(new File(getContext().getCacheDir(), "shared-json"), name);
		if (!file.isFile()) {
			throw new FileNotFoundException("Export no longer available");
		}
		return file;
	}

	@Override
	public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
		if (!"r".equals(mode)) {
			throw new FileNotFoundException("Read-only export");
		}
		return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
	}

	@Override
	public String getType(Uri uri) {
		return "application/json";
	}

	@Override
	public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
		try {
			File file = resolve(uri);
			String[] columns = projection == null
				? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
			MatrixCursor cursor = new MatrixCursor(columns);
			MatrixCursor.RowBuilder row = cursor.newRow();
			for (String column : columns) {
				if (OpenableColumns.DISPLAY_NAME.equals(column)) {
					row.add(file.getName());
				} else if (OpenableColumns.SIZE.equals(column)) {
					row.add(file.length());
				} else {
					row.add(null);
				}
			}
			return cursor;
		} catch (FileNotFoundException exception) {
			throw new IllegalArgumentException("Unknown export", exception);
		}
	}

	@Override
	public Uri insert(Uri uri, ContentValues values) {
		throw new UnsupportedOperationException("Read-only export");
	}

	@Override
	public int update(Uri uri, ContentValues values, String where, String[] args) {
		throw new UnsupportedOperationException("Read-only export");
	}

	@Override
	public int delete(Uri uri, String where, String[] args) {
		throw new UnsupportedOperationException("Read-only export");
	}
}
