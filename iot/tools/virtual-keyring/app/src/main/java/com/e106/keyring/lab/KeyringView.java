package com.e106.keyring.lab;

import com.e106.keyring.core.KeyringMotion;

import android.content.Context;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;
import android.view.View;

/** Native layered geometry: perspective, beveled edges and a moving specular reflection; no network assets. */
public final class KeyringView extends View implements SensorEventListener {
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint auraPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Camera camera = new Camera();
	private final Matrix matrix = new Matrix();
	private final RectF body = new RectF(-84, -143, 84, 143);
	private final Path face = new Path();
	private final SensorManager sensors;
	private final Sensor sensor;
	private final float[] gravity = new float[3];
	private boolean dark = true;
	private boolean active;
	private boolean resumed;
	private boolean gravityInitialized;
	private float pitch;
	private float yaw;
	private float targetPitch;
	private float targetYaw;
	private float glow;
	private long feedbackStartedAt;
	private long lastFrame;

	public KeyringView(Context context) {
		super(context);
		sensors = (SensorManager)context.getSystemService(Context.SENSOR_SERVICE);
		Sensor preferred = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);
		sensor = preferred == null ? sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : preferred;
		face.addRoundRect(body, 39, 39, Path.Direction.CW);
		setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
		setContentDescription("가상 키링 · 꺼짐");
	}

	public boolean hasTiltSensor() {
		return sensor != null;
	}

	public void resumeMotion() {
		resumed = true;
		gravityInitialized = false;
		lastFrame = 0;
		if (sensor != null) {
			sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
		}
		invalidate();
	}

	public void pauseMotion() {
		resumed = false;
		sensors.unregisterListener(this);
	}

	public void setDark(boolean dark) {
		this.dark = dark;
		invalidate();
	}

	public void update(boolean active, long feedbackStartedAt) {
		if (this.active != active) {
			setContentDescription("가상 키링 · " + (active ? "켜짐" : "꺼짐"));
		}
		this.active = active;
		this.feedbackStartedAt = active ? feedbackStartedAt : 0;
		invalidate();
	}

	@Override
	public void onSensorChanged(SensorEvent event) {
		for (int index = 0; index < 3; index++) {
			gravity[index] = gravityInitialized ? gravity[index] * 0.88f + event.values[index] * 0.12f
				: event.values[index];
		}
		gravityInitialized = true;
		int rotation = getDisplay() == null ? 0 : getDisplay().getRotation();
		float[] tilt = KeyringMotion.fromGravity(gravity[0], gravity[1], gravity[2], rotation);
		targetPitch = tilt[0];
		targetYaw = tilt[1];
		if (Math.abs(targetPitch - pitch) > 0.03f || Math.abs(targetYaw - yaw) > 0.03f) {
			invalidate();
		}
	}

	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {
		// A gravity reference does not use magnetic heading or compass calibration.
	}

	@Override
	protected void onDraw(Canvas canvas) {
		super.onDraw(canvas);
		long now = SystemClock.elapsedRealtime();
		float step = lastFrame == 0 ? 1 : Math.min(1, (now - lastFrame) / 110f);
		lastFrame = now;
		pitch += (targetPitch - pitch) * step;
		yaw += (targetYaw - yaw) * step;
		glow += ((active ? 1 : 0) - glow) * Math.max(0.04f, step);
		long elapsed = feedbackStartedAt == 0 ? -1 : now - feedbackStartedAt;
		boolean pulse = active && KeyringMotion.isPulse(elapsed);
		boolean feedbackActive = active && elapsed >= 0 && elapsed < KeyringMotion.FEEDBACK_MS;
		float scale = Math.min(getWidth() / 340f, getHeight() / 430f);
		scale = Math.min(scale, getResources().getDisplayMetrics().density * 1.35f);
		canvas.save();
		canvas.translate(getWidth() / 2f, getHeight() / 2f + 8 * scale);
		canvas.scale(scale, scale);
		drawShadow(canvas);
		float shake = pulse ? (float)Math.sin(elapsed * 0.11) * 3.5f : 0;
		canvas.translate(shake, 0);
		canvas.rotate(shake * 0.3f);
		canvas.save();
		project(canvas, 0);
		drawAura(canvas);
		canvas.restore();
		drawRing(canvas);
		for (int depth = 10; depth >= 1; depth--) {
			canvas.save();
			project(canvas, depth);
			paint.setShader(null);
			paint.setColor(Color.rgb(50 + depth * 3, 52 + depth * 3, 53 + depth * 3));
			canvas.drawRoundRect(body, 39, 39, paint);
			canvas.restore();
		}
		canvas.save();
		project(canvas, 0);
		drawFace(canvas, pulse, feedbackActive);
		canvas.restore();
		canvas.restore();
		if (resumed && (Math.abs(pitch - targetPitch) > 0.03f || Math.abs(yaw - targetYaw) > 0.03f
			|| Math.abs(glow - (active ? 1 : 0)) > 0.003f
			|| (elapsed >= 0 && elapsed < KeyringMotion.FEEDBACK_MS))) {
			postInvalidateOnAnimation();
		}
	}

	private void project(Canvas canvas, float depth) {
		camera.save();
		camera.rotateX(pitch);
		camera.rotateY(yaw);
		camera.translate(0, 0, depth);
		camera.getMatrix(matrix);
		camera.restore();
		canvas.concat(matrix);
	}

	private void drawAura(Canvas canvas) {
		// A separate paint prevents the LED's translucent highlight from dimming the next frame's aura.
		auraPaint.setShader(null);
		auraPaint.setStyle(Paint.Style.FILL);
		for (int spread = 64; spread >= 1; spread--) {
			float strength = 1 - spread / 65f;
			int alpha = Math.round(glow * strength * strength * (dark ? 27 : 24));
			auraPaint.setColor(Color.argb(alpha, 255, 119, 36));
			canvas.drawRoundRect(new RectF(-84 - spread, -143 - spread, 84 + spread, 143 + spread),
				39 + spread, 39 + spread, auraPaint);
		}
	}

	private void drawShadow(Canvas canvas) {
		paint.setStyle(Paint.Style.FILL);
		paint.setColor(Color.WHITE);
		canvas.save();
		canvas.translate(-yaw * 0.6f, 184 + pitch * 0.25f);
		canvas.scale(1, 0.18f);
		paint.setShader(new RadialGradient(0, 0, 100,
			new int[] {Color.argb(dark ? 100 : 45, 0, 0, 0), Color.TRANSPARENT}, null, Shader.TileMode.CLAMP));
		canvas.drawCircle(0, 0, 100, paint);
		canvas.restore();
		paint.setShader(null);
	}

	private void drawRing(Canvas canvas) {
		canvas.save();
		project(canvas, 5);
		RectF ring = new RectF(-24, -178, 24, -122);
		paint.setStyle(Paint.Style.STROKE);
		paint.setStrokeWidth(9);
		paint.setColor(Color.rgb(46, 48, 50));
		canvas.drawOval(ring, paint);
		paint.setStrokeWidth(6);
		paint.setShader(new LinearGradient(-27 + yaw, -178, 25, -125,
			new int[] {Color.WHITE, Color.rgb(178, 182, 183), Color.rgb(61, 64, 66), Color.rgb(213, 217, 218)},
			null, Shader.TileMode.CLAMP));
		canvas.drawOval(ring, paint);
		paint.setShader(null);
		paint.setStyle(Paint.Style.FILL);
		canvas.restore();
	}

	private void drawFace(Canvas canvas, boolean pulse, boolean feedbackActive) {
		paint.setShader(new LinearGradient(-84 + yaw * 2, -143, 84, 143 + pitch * 2,
			new int[] {Color.rgb(252, 252, 248), Color.rgb(222, 223, 218), Color.rgb(164, 167, 165)},
			new float[] {0, 0.52f, 1}, Shader.TileMode.CLAMP));
		canvas.drawRoundRect(body, 39, 39, paint);
		canvas.save();
		canvas.clipPath(face);
		paint.setShader(new RadialGradient(-70 - yaw * 5, -110 + pitch * 6, 265,
			new int[] {Color.argb(170, 255, 255, 255), Color.TRANSPARENT}, null, Shader.TileMode.CLAMP));
		canvas.drawRect(body, paint);
		paint.setShader(new LinearGradient(-130 + yaw * 4, -143, 95 + yaw * 4, 143,
			new int[] {Color.TRANSPARENT, Color.argb(52, 255, 255, 255), Color.TRANSPARENT},
			new float[] {0.35f, 0.5f, 0.65f}, Shader.TileMode.CLAMP));
		canvas.drawRect(body, paint);
		canvas.restore();
		paint.setStyle(Paint.Style.STROKE);
		paint.setStrokeWidth(2);
		paint.setShader(new LinearGradient(-84, -143, 84 + yaw * 3, 143,
			new int[] {Color.WHITE, Color.rgb(138, 144, 143), Color.rgb(226, 229, 228)},
			null, Shader.TileMode.CLAMP));
		canvas.drawRoundRect(new RectF(-83, -142, 83, 142), 38, 38, paint);
		paint.setShader(null);
		paint.setStyle(Paint.Style.FILL);
		paint.setColor(Color.rgb(116, 122, 121));
		paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
		paint.setTextAlign(Paint.Align.CENTER);
		paint.setTextSize(7);
		canvas.drawText("S  I  S", 0, 111, paint);
		paint.setColor(Color.rgb(139, 143, 141));
		canvas.drawCircle(0, 0, 9, paint);
		paint.setShader(new LinearGradient(0, -8, 0, 8,
			Color.rgb(35, 39, 38), Color.rgb(90, 92, 87), Shader.TileMode.CLAMP));
		canvas.drawCircle(0, 0, 7, paint);
		paint.setShader(null);
		if (active && (!feedbackActive || pulse)) {
			paint.setShader(new RadialGradient(0, 0, pulse ? 26 : 12,
				new int[] {Color.argb(pulse ? 230 : 90, 255, 139, 59), Color.TRANSPARENT},
				null, Shader.TileMode.CLAMP));
			canvas.drawCircle(0, 0, pulse ? 26 : 12, paint);
			paint.setShader(null);
			paint.setColor(pulse ? Color.rgb(255, 233, 184) : Color.rgb(209, 117, 58));
			canvas.drawCircle(0, 0, pulse ? 5.5f : 3.5f, paint);
		}
		paint.setColor(Color.argb(110, 255, 255, 255));
		canvas.drawCircle(-2, -2, 1.5f, paint);
	}

	@Override
	protected void onDetachedFromWindow() {
		pauseMotion();
		super.onDetachedFromWindow();
	}
}
