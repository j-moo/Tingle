#include "feedback.h"
#include "keyring.h"
#include "profile.h"
#include "registration.h"

#define MATCHING_ON 1U
#define VIBRATION_ON 2U
#define LED_ON 4U
#define MOTOR_POWER_CHECK_MS 10

struct kr_feedback_options kr_feedback_options(const uint8_t id[16],
	const struct kr_profile *own, const struct kr_profile *peer)
{
	struct kr_profile_data local, remote;
	if (!kr_profile_data_decode(own->data, own->data_len, &local) ||
		!kr_profile_data_decode(peer->data, peer->data_len, &remote) ||
		!(local.options & remote.options & MATCHING_ON)) {
		return (struct kr_feedback_options){0};
	}
	/* The shared event ID gives both ends the same nonzero RGB mask. */
	return (struct kr_feedback_options){
		.rgb = (local.options & LED_ON) ? 1U + id[0] % 7U : 0U,
		.vibrate = (local.options & remote.options & VIBRATION_ON) != 0,
	};
}

bool kr_feedback_motor_can_start(const struct kr_status *power, bool usb_motor_supply)
{
	if (!power->usb_valid) { return false; }
	if (power->usb_present) { return usb_motor_supply; }
	/* Leave battery margin for starting the motor and keeping the XIAO up. */
	return power->charging == 0 &&
		(!power->presence_valid || power->battery_present) &&
		power->battery_mv >= 3300 && power->battery_mv <= 4300;
}

bool kr_feedback_start(struct kr_feedback *state, int64_t now, struct kr_feedback_options options)
{
	/* A new encounter cannot keep the physical motor continuously asserted. */
	if (state->options.vibrate || (!options.rgb && !options.vibrate)) { return false; }
	*state = (struct kr_feedback){.started_ms = now, .options = options};
	return true;
}

void kr_feedback_stop(struct kr_feedback *state)
{
	*state = (struct kr_feedback){0};
}

struct kr_feedback_output kr_feedback_step(struct kr_feedback *state, int64_t now, bool motor_power)
{
	const int64_t age = now - state->started_ms;
	struct kr_feedback_output out = {0};
	const int second_start = KR_FEEDBACK_PULSE_MS + KR_FEEDBACK_GAP_MS;
	const int motor_end = second_start + KR_FEEDBACK_PULSE_MS;
	if (state->options.rgb && age < KR_FEEDBACK_LED_MS) {
		out.rgb = state->options.rgb;
		out.wake_ms = (uint32_t)(KR_FEEDBACK_LED_MS - age);
	} else {
		state->options.rgb = 0;
	}
	if (!motor_power || age >= motor_end) {
		/* Power returning must not replay the rest of a previous alert. */
		state->options.vibrate = false;
	}
	if (state->options.vibrate) {
		out.motor = age < KR_FEEDBACK_PULSE_MS || age >= second_start;
		const int64_t boundary = age < KR_FEEDBACK_PULSE_MS ? KR_FEEDBACK_PULSE_MS :
			age < second_start ? second_start : motor_end;
		const int64_t remaining = boundary - age;
		out.wake_ms = remaining < MOTOR_POWER_CHECK_MS ? (uint32_t)remaining : MOTOR_POWER_CHECK_MS;
	}
	return out;
}
