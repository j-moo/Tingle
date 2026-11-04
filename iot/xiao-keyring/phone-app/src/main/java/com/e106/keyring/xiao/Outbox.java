package com.e106.keyring.xiao;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.json.JSONException;
import org.json.JSONObject;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Base64;

/** Phone durability is separate from server durability and board deletion. */
public final class Outbox extends SQLiteOpenHelper {
	public static final String PROTOCOL = "tingle-lab-binary-v1";

	public record Row(String device, String event, byte[] bytes, boolean serverStored) {
		public JSONObject upload() throws JSONException {
			return new JSONObject().put("protocol", PROTOCOL).put("deviceId", device)
				.put("eventId", event).put("submissionId", device + ":" + event)
				.put("queue", bytes[1] == 2 ? "MATCHED" : "SERVER_REVIEW")
				.put("recordBase64", Base64.encodeToString(bytes, Base64.NO_WRAP))
				.put("recordCrc32", Long.toUnsignedString(Wire.crc(bytes)));
		}

		public byte[] boardReceipt(byte[] token) {
			ByteBuffer buffer = ByteBuffer.allocate(45).order(java.nio.ByteOrder.LITTLE_ENDIAN);
			buffer.put(token).putLong(Long.parseLong(device)).put(Wire.unhex(event));
			buffer.putInt((int)Wire.crc(bytes)).put((byte)1);
			return buffer.array();
		}
	}

	public Outbox(Context context) {
		super(context, "xiao-lab-outbox.db", null, 1);
	}

	@Override
	public void onConfigure(SQLiteDatabase db) {
		db.execSQL("PRAGMA synchronous=FULL");
	}

	@Override
	public void onCreate(SQLiteDatabase db) {
		db.execSQL("CREATE TABLE outbox (device TEXT NOT NULL, event TEXT NOT NULL, record BLOB NOT NULL, "
			+ "server_stored INTEGER NOT NULL DEFAULT 0, board_acked INTEGER NOT NULL DEFAULT 0, "
			+ "PRIMARY KEY(device,event))");
	}

	@Override
	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
		throw new IllegalStateException("Preserving old outbox; migration required");
	}

	public void save(String device, byte[] bytes) {
		if (bytes.length < 86 || bytes[0] != 1 || (bytes[1] != 2 && bytes[1] != 3)) {
			throw new IllegalArgumentException("Invalid record metadata");
		}
		ByteBuffer buffer = Wire.little(bytes);
		if ((buffer.getShort(48) & 65535) + (buffer.getShort(50) & 65535) + 52 != bytes.length) {
			throw new IllegalArgumentException("Invalid record boundaries");
		}
		String event = Wire.hex(Arrays.copyOfRange(bytes, 8, 24));
		SQLiteDatabase db = getWritableDatabase();
		db.beginTransaction();
		try (Cursor existing = db.rawQuery("SELECT record FROM outbox WHERE device=? AND event=?",
			new String[] {device, event})) {
			if (existing.moveToFirst()) {
				if (!Arrays.equals(existing.getBlob(0), bytes)) {
					throw new IllegalArgumentException("Same event has conflicting frozen data");
				}
			} else {
				ContentValues values = new ContentValues();
				values.put("device", device);
				values.put("event", event);
				values.put("record", bytes);
				db.insertOrThrow("outbox", null, values);
			}
			db.setTransactionSuccessful();
		} finally {
			db.endTransaction();
		}
	}

	public List<Row> pending(String device) {
		List<Row> rows = new ArrayList<>();
		try (Cursor cursor = getReadableDatabase().rawQuery(
			"SELECT device,event,record,server_stored FROM outbox WHERE board_acked=0"
				+ (device == null ? "" : " AND device=?") + " ORDER BY rowid",
			device == null ? null : new String[] {device})) {
			while (cursor.moveToNext()) {
				rows.add(new Row(cursor.getString(0), cursor.getString(1),
					cursor.getBlob(2), cursor.getInt(3) == 1));
			}
		}
		return rows;
	}

	public void serverReceipt(Row row, JSONObject receipt) throws JSONException {
		if (!PROTOCOL.equals(receipt.getString("protocol")) || !row.device().equals(receipt.getString("deviceId"))
			|| !row.event().equals(receipt.getString("eventId")) || !"STORED".equals(receipt.getString("status"))
			|| !receipt.getBoolean("canDelete")
			|| !Long.toUnsignedString(Wire.crc(row.bytes())).equals(receipt.getString("recordCrc32"))) {
			throw new IllegalArgumentException("Server receipt does not bind this exact record");
		}
		ContentValues values = new ContentValues();
		values.put("server_stored", 1);
		getWritableDatabase().update("outbox", values, "device=? AND event=?",
			new String[] {row.device(), row.event()});
	}

	public void boardAck(Row row) {
		ContentValues values = new ContentValues();
		values.put("board_acked", 1);
		getWritableDatabase().update("outbox", values, "device=? AND event=? AND server_stored=1",
			new String[] {row.device(), row.event()});
	}
}
