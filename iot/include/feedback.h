#ifndef TINGLE_FEEDBACK_H
#define TINGLE_FEEDBACK_H

#include <stdbool.h>
#include <stdint.h>

#define KR_FEEDBACK_LED_MS 30000
#define KR_FEEDBACK_PULSE_MS 350
#define KR_FEEDBACK_GAP_MS 150
#define KR_FEEDBACK_CYCLE_MS 1250
#define KR_FEEDBACK_VIBRATION_MS 10000

struct kr_profile;
struct kr_status;

struct kr_feedback_options {
	uint8_t rgb;
	bool vibrate;
};

struct kr_feedback {
	int64_t started_ms;
	struct kr_feedback_options options;
};

struct kr_feedback_output {
	uint8_t rgb;
	bool motor;
	uint32_t wake_ms;
};

struct kr_feedback_options kr_feedback_options(const uint8_t id[16],
	const struct kr_profile *own, const struct kr_profile *peer);
bool kr_feedback_motor_can_start(const struct kr_status *power, bool usb_motor_supply);
bool kr_feedback_start(struct kr_feedback *state, int64_t now, struct kr_feedback_options options);
void kr_feedback_stop(struct kr_feedback *state);
struct kr_feedback_output kr_feedback_step(struct kr_feedback *state, int64_t now, bool motor_power);

#endif
