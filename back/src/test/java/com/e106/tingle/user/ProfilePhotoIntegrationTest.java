package com.e106.tingle.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.chat.ChatRoomRepository;
import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.proposal.MatchingParticipant;
import com.e106.tingle.proposal.MatchingParticipantRepository;
import com.e106.tingle.proposal.ProposalRepository;
import com.e106.tingle.proposal.ProposalService;

import tools.jackson.databind.JsonNode;

// erd 프로필: 팀 ERD(schema.sql)의 photo_url CHECK 제약 아래에서 저장되는지도 함께 확인한다.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class ProfilePhotoIntegrationTest extends IntegrationTestSupport {

	// 1×1 WebP(손실 압축). 자바 기본 ImageIO 로는 WebP 를 만들 수 없어 고정 바이트를 쓴다.
	private static final String WEBP_1X1 = "UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA";

	@Value("${tingle.photo.dir}")
	private Path photoDir;
	@Autowired
	private ProposalService proposals;
	@Autowired
	private ProposalRepository proposalRows;
	@Autowired
	private MatchingParticipantRepository participantRows;
	@Autowired
	private ChatRoomRepository rooms;

	private JsonNode me;

	@BeforeEach
	void signUp() throws Exception {
		me = login("google", newSocialId());
		completeOnboarding(me);
	}

	@Test
	@DisplayName("PNG·JPEG·WebP를 정사각형 1080px 이하 JPEG(EXIF 없음)으로 저장하고, 새로 올리면 이전 파일을 지운다")
	void storesSquareJpegAndReplacesPreviousFile() throws Exception {
		upload(me, image("png", 1600, 1200, BufferedImage.TYPE_INT_ARGB)).andExpect(status().isOk())
			.andExpect(jsonPath("$.hasProfilePhoto").value(true))
			.andExpect(jsonPath("$.imageUrl").doesNotExist());
		String first = photoKey(me);
		assertThat(first).matches("profile/" + userIdOf(me) + "/[0-9a-f-]{36}\\.jpg");
		byte[] stored = Files.readAllBytes(photoDir.resolve(first));
		assertSquareJpegWithoutExif(stored, 1080);
		perform(get(me(me, "/profile/photo")), me, null).andExpect(status().isOk())
			.andExpect(content().contentType(MediaType.IMAGE_JPEG))
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(content().bytes(stored));

		upload(me, image("jpg", 300, 500, BufferedImage.TYPE_INT_RGB)).andExpect(status().isOk());
		String second = photoKey(me);
		assertThat(second).isNotEqualTo(first);
		assertThat(photoDir.resolve(first)).doesNotExist();
		assertSquareJpegWithoutExif(Files.readAllBytes(photoDir.resolve(second)), 300);

		upload(me, Base64.getDecoder().decode(WEBP_1X1)).andExpect(status().isOk());
		assertThat(photoDir.resolve(second)).doesNotExist();
		assertSquareJpegWithoutExif(Files.readAllBytes(photoDir.resolve(photoKey(me))), 1);
	}

	@Test
	@DisplayName("EXIF 방향(6: 시계 방향 90도)을 적용해 저장하고 방향 정보는 남기지 않는다")
	void appliesExifOrientation() throws Exception {
		// 위쪽 절반 빨강, 아래쪽 절반 파랑. 90도 돌리면 오른쪽이 빨강, 왼쪽이 파랑이 된다.
		BufferedImage source = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = source.createGraphics();
		graphics.setColor(Color.RED);
		graphics.fillRect(0, 0, 200, 50);
		graphics.setColor(Color.BLUE);
		graphics.fillRect(0, 50, 200, 50);
		graphics.dispose();
		upload(me, withOrientation(encode(source, "jpg"), 6)).andExpect(status().isOk());

		BufferedImage stored = ImageIO.read(photoDir.resolve(photoKey(me)).toFile());
		assertThat(stored.getWidth()).isEqualTo(100);
		assertThat(new Color(stored.getRGB(90, 50)).getRed()).isGreaterThan(200);
		assertThat(new Color(stored.getRGB(10, 50)).getBlue()).isGreaterThan(200);
		assertSquareJpegWithoutExif(Files.readAllBytes(photoDir.resolve(photoKey(me))), 100);
	}

	@Test
	@DisplayName("깨진 파일·허용하지 않는 형식·10MB 초과는 400 PHOTO_INVALID이고 기존 사진을 유지한다")
	void rejectsInvalidUploadsAndKeepsPhoto() throws Exception {
		upload(me, image("jpg", 400, 400, BufferedImage.TYPE_INT_RGB)).andExpect(status().isOk());
		String kept = photoKey(me);
		Path folder = photoDir.resolve(kept).getParent();
		List<Path> before;
		try (var files = Files.list(folder)) {
			before = files.toList();
		}
		byte[] tooLarge = new byte[(int)ProfilePhotoService.MAX_BYTES + 1];
		System.arraycopy(image("jpg", 10, 10, BufferedImage.TYPE_INT_RGB), 0, tooLarge, 0, 100);
		for (byte[] bad : List.of("not an image".getBytes(), new byte[0], image("gif", 50, 50,
			BufferedImage.TYPE_INT_RGB), tooLarge)) {
			upload(me, bad).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("PHOTO_INVALID"));
		}
		assertThat(photoKey(me)).isEqualTo(kept);
		assertThat(photoDir.resolve(kept)).exists();
		// 거절한 업로드는 파일을 남기지 않는다(테스트 DB는 매번 새로 만들어져 같은 회원 번호 폴더에 이전 실행 파일이 있을 수 있다).
		try (var files = Files.list(folder)) {
			assertThat(files).containsExactlyInAnyOrderElementsOf(before);
		}
	}

	@Test
	@DisplayName("삭제는 DB 값과 파일을 지우고 반복해도 204, 남의 경로는 403")
	void deleteRemovesFileAndIsRepeatable() throws Exception {
		upload(me, image("jpg", 100, 100, BufferedImage.TYPE_INT_RGB)).andExpect(status().isOk());
		String key = photoKey(me);
		JsonNode other = login("google", newSocialId());
		perform(delete(me(other, "/profile/photo")), me, null).andExpect(status().isForbidden());

		for (int i = 0; i < 2; i++) {
			perform(delete(me(me, "/profile/photo")), me, null).andExpect(status().isNoContent());
		}
		assertThat(photoKey(me)).isNull();
		assertThat(photoDir.resolve(key)).doesNotExist();
		perform(get(me(me, "/profile/photo")), me, null).andExpect(status().isGone())
			.andExpect(jsonPath("$.code").value("PHOTO_REMOVED"));
		perform(get(me(me, "/profile")), me, null).andExpect(jsonPath("$.hasProfilePhoto").value(false));
	}

	@Test
	@DisplayName("상대 사진은 유효 제안 당사자·이용 중인 채팅방 참여자만 받는다")
	void partnerPhotoRequiresProposalOrRoomAccess() throws Exception {
		JsonNode partner = login("google", newSocialId());
		completeOnboarding(partner);
		JsonNode stranger = login("google", newSocialId());
		completeOnboarding(stranger);
		long myId = userIdOf(me);
		long partnerId = userIdOf(partner);
		upload(partner, image("jpg", 100, 100, BufferedImage.TYPE_INT_RGB)).andExpect(status().isOk());
		byte[] partnerPhoto = Files.readAllBytes(photoDir.resolve(photoKey(partner)));

		MatchProposal proposal = proposalRows.saveAndFlush(MatchProposal.proposed(myId, partnerId,
			clock.instant()));
		participantRows.saveAllAndFlush(List.of(new MatchingParticipant(proposal.getId(), myId),
			new MatchingParticipant(proposal.getId(), partnerId)));
		String proposalPhoto = "/api/match-proposals/" + proposal.getId() + "/partner-photo";
		perform(get(proposalPhoto), me, null).andExpect(status().isOk())
			.andExpect(content().contentType(MediaType.IMAGE_JPEG))
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(content().bytes(partnerPhoto));
		perform(get(proposalPhoto), stranger, null).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

		perform(delete(me(partner, "/profile/photo")), partner, null).andExpect(status().isNoContent());
		perform(get(proposalPhoto), me, null).andExpect(status().isGone())
			.andExpect(jsonPath("$.code").value("PHOTO_REMOVED"));
		upload(partner, image("jpg", 100, 100, BufferedImage.TYPE_INT_RGB)).andExpect(status().isOk());

		// 둘 다 수락하면 제안은 끝나고(410) 채팅방에서 사진을 본다.
		for (long user : List.of(myId, partnerId)) {
			proposals.decide(proposal.getId(), user, MatchProposal.Decision.ACCEPT, UUID.randomUUID(),
				proposals.profile(proposal.getId(), user).profileVersion());
		}
		perform(get(proposalPhoto), me, null).andExpect(status().isGone())
			.andExpect(jsonPath("$.code").value("PROPOSAL_EXPIRED"));
		long roomId = rooms.findBySourceProposalId(proposal.getId()).orElseThrow().getPublicId();
		String roomPhoto = "/api/chat-rooms/" + roomId + "/partner-photo";
		perform(get(roomPhoto), me, null).andExpect(status().isOk())
			.andExpect(content().bytes(Files.readAllBytes(photoDir.resolve(photoKey(partner)))));
		perform(get(roomPhoto), stranger, null).andExpect(status().isNotFound());
		jdbc.update("update chat_participant set left_at=now() where chat_id=? and user_id=?", roomId, myId);
		perform(get(roomPhoto), me, null).andExpect(status().isGone())
			.andExpect(jsonPath("$.code").value("CHAT_CLOSED"));
	}

	private ResultActions upload(JsonNode tokens, byte[] bytes) throws Exception {
		// 파일 이름·Content-Type 은 일부러 틀리게 보낸다. 형식은 내용으로만 판별해야 한다.
		return mockMvc.perform(multipart(me(tokens, "/profile/photos"))
			.file(new MockMultipartFile("file", "photo.heic", "application/octet-stream", bytes))
			.header("Authorization", "Bearer " + tokens.path("accessToken").asString()));
	}

	private String photoKey(JsonNode tokens) throws Exception {
		return jdbc.queryForObject("select photo_url from profile where user_id=?", String.class, userIdOf(tokens));
	}

	private static void assertSquareJpegWithoutExif(byte[] jpeg, int side) throws Exception {
		assertThat(jpeg[0]).isEqualTo((byte)0xFF);
		assertThat(jpeg[1]).isEqualTo((byte)0xD8);
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpeg));
		assertThat(image.getWidth()).isEqualTo(side);
		assertThat(image.getHeight()).isEqualTo(side);
		assertThat(ImageMetadataReader.readMetadata(new ByteArrayInputStream(jpeg))
			.getFirstDirectoryOfType(ExifIFD0Directory.class)).isNull();
	}

	private static byte[] image(String format, int width, int height, int type) throws Exception {
		BufferedImage image = new BufferedImage(width, height, type);
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(new Color(30, 120, 200, 180));
		graphics.fillRect(0, 0, width, height / 2);
		graphics.dispose();
		return encode(image, format);
	}

	private static byte[] encode(BufferedImage image, String format) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		assertThat(ImageIO.write(image, format, out)).isTrue();
		return out.toByteArray();
	}

	// JFIF(APP0) 바로 뒤에 방향 태그 하나만 있는 EXIF(APP1) 구간을 끼워 넣는다.
	private static byte[] withOrientation(byte[] jpeg, int orientation) {
		byte[] exif = {
			(byte)0xFF, (byte)0xE1, 0, 34, 'E', 'x', 'i', 'f', 0, 0,
			'M', 'M', 0, 42, 0, 0, 0, 8,
			0, 1, 1, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte)orientation, 0, 0,
			0, 0, 0, 0};
		int app0End = 4 + ((jpeg[4] & 0xFF) << 8 | (jpeg[5] & 0xFF));
		byte[] result = new byte[jpeg.length + exif.length];
		System.arraycopy(jpeg, 0, result, 0, app0End);
		System.arraycopy(exif, 0, result, app0End, exif.length);
		System.arraycopy(jpeg, app0End, result, app0End + exif.length, jpeg.length - app0End);
		return result;
	}
}
