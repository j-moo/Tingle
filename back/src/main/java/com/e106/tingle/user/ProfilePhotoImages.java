package com.e106.tingle.user;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

/**
 * 올린 사진을 프로필 사진 규격으로 바꾼다: EXIF 방향 적용 → 가운데 정사각형 자르기 → 최대 1080px → JPEG(품질 85).
 * 새 JPEG 을 처음부터 다시 쓰므로 원본의 EXIF·GPS 같은 메타데이터는 남지 않는다.
 * 형식은 파일 이름·Content-Type 이 아니라 내용(앞부분 바이트)으로 판별하고, JPEG·PNG·WebP 만 받는다.
 */
final class ProfilePhotoImages {

	static final int MAX_SIDE = 1080;
	private static final float JPEG_QUALITY = 0.85f;
	private static final Set<String> FORMATS = Set.of("jpeg", "png", "webp");
	// 압축 폭탄 방지: 파일이 작아도 풀면 거대한 그림이 될 수 있어 픽셀 수를 먼저 확인한다.
	private static final long MAX_PIXELS = 100_000_000L;

	static {
		// 서버(내장 Tomcat·실행 jar)에서도 WebP 플러그인(TwelveMonkeys)이 등록되도록 한 번 찾게 한다.
		ImageIO.scanForPlugins();
	}

	private ProfilePhotoImages() {
	}

	/**
	 * 규격에 맞는 JPEG 바이트를 돌려준다. 읽을 수 없거나 허용하지 않는 형식이면 400 PHOTO_INVALID.
	 */
	static byte[] toProfileJpeg(byte[] original) {
		BufferedImage square = decodeCenterSquare(original);
		BufferedImage resized = resize(square, Math.min(MAX_SIDE, square.getWidth()));
		return encodeJpeg(orient(resized, orientation(original)));
	}

	// 가운데 정사각형만 읽는다. 정사각형 자르기는 회전·뒤집기와 순서를 바꿔도 결과가 같아 방향 적용 전에 해도 된다.
	private static BufferedImage decodeCenterSquare(byte[] original) {
		try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(original))) {
			ImageReader reader = reader(input);
			try {
				reader.setInput(input, true, true);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				if (width <= 0 || height <= 0 || (long)width * height > MAX_PIXELS) {
					throw invalid();
				}
				int side = Math.min(width, height);
				ImageReadParam param = reader.getDefaultReadParam();
				param.setSourceRegion(new Rectangle((width - side) / 2, (height - side) / 2, side, side));
				// 아주 큰 사진은 읽을 때부터 건너뛰며 읽어 메모리를 줄인다(결과의 2배 이상 크기는 유지해 화질을 지킨다).
				int step = Math.max(1, side / (MAX_SIDE * 2));
				param.setSourceSubsampling(step, step, 0, 0);
				return toRgb(reader.read(0, param));
			} finally {
				reader.dispose();
			}
		} catch (IOException | RuntimeException e) {
			if (e instanceof BusinessException business) {
				throw business;
			}
			throw invalid();
		}
	}

	private static ImageReader reader(ImageInputStream input) {
		if (input == null) {
			throw invalid();
		}
		Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
		while (readers.hasNext()) {
			ImageReader reader = readers.next();
			try {
				if (FORMATS.contains(reader.getFormatName().toLowerCase(Locale.ROOT))) {
					return reader;
				}
			} catch (IOException e) {
				// 형식 이름을 못 읽는 리더는 건너뛴다.
			}
			reader.dispose();
		}
		throw invalid();
	}

	// 투명한 부분(PNG·WebP)은 흰 배경으로 채운다. JPEG 은 투명도를 담지 못한다.
	private static BufferedImage toRgb(BufferedImage image) {
		BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = rgb.createGraphics();
		graphics.drawImage(image, 0, 0, Color.WHITE, null);
		graphics.dispose();
		return rgb;
	}

	// 한 번에 크게 줄이면 계단 현상이 생겨 절반씩 줄인 뒤 마지막에 목표 크기로 맞춘다.
	private static BufferedImage resize(BufferedImage image, int target) {
		BufferedImage current = image;
		while (current.getWidth() != target) {
			int next = Math.max(target, current.getWidth() / 2);
			BufferedImage smaller = new BufferedImage(next, next, BufferedImage.TYPE_INT_RGB);
			Graphics2D graphics = smaller.createGraphics();
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.drawImage(current, 0, 0, next, next, null);
			graphics.dispose();
			current = smaller;
		}
		return current;
	}

	// EXIF 방향 값(1~8). 없거나 읽지 못하면 1(그대로).
	static int orientation(byte[] original) {
		try {
			ExifIFD0Directory exif = ImageMetadataReader.readMetadata(new ByteArrayInputStream(original))
				.getFirstDirectoryOfType(ExifIFD0Directory.class);
			if (exif != null && exif.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
				return exif.getInt(ExifIFD0Directory.TAG_ORIENTATION);
			}
		} catch (Exception e) {
			// 메타데이터가 깨져 있어도 그림은 쓸 수 있으므로 방향만 포기한다.
		}
		return 1;
	}

	/**
	 * 정사각형 그림에 EXIF 방향을 적용한다. 화면 좌표 (x, y)에 보일 원본 좌표를 방향마다 계산한다.
	 */
	static BufferedImage orient(BufferedImage square, int orientation) {
		if (orientation < 2 || orientation > 8) {
			return square;
		}
		int last = square.getWidth() - 1;
		BufferedImage result = new BufferedImage(last + 1, last + 1, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y <= last; y++) {
			for (int x = 0; x <= last; x++) {
				int sourceX = switch (orientation) {
					case 2, 3 -> last - x;
					case 4 -> x;
					case 5, 6 -> y;
					default -> last - y; // 7, 8
				};
				int sourceY = switch (orientation) {
					case 2 -> y;
					case 3, 4 -> last - y;
					case 5, 8 -> x;
					default -> last - x; // 6, 7
				};
				result.setRGB(x, y, square.getRGB(sourceX, sourceY));
			}
		}
		return result;
	}

	private static byte[] encodeJpeg(BufferedImage image) {
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
			writer.setOutput(output);
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(JPEG_QUALITY);
			writer.write(null, new IIOImage(image, null, null), param);
		} catch (IOException e) {
			throw new IllegalStateException("JPEG 저장 실패", e);
		} finally {
			writer.dispose();
		}
		return bytes.toByteArray();
	}

	private static BusinessException invalid() {
		return new BusinessException(ErrorCode.PHOTO_INVALID);
	}
}
