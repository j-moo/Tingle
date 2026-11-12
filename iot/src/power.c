#include "power.h"

#define CONFIRM_SAMPLES 5
#define INVALID_SAMPLES 20
#define USB_REMOVAL_MS 500
#define LONG_CHARGE_MS (9LL * 60 * 60 * 1000)

bool kr_power_motor_voltage_ok(const struct kr_status *s)
{
	return (!s->presence_valid || s->battery_present) &&
		s->battery_mv >= KR_POWER_STOP_MV && s->battery_mv <= 4250;
}

void kr_power_cancel_notice(struct kr_power_monitor *p)
{
	/* A cancelled notice must not restart on the same USB attachment. */
	if (p->notice_until || p->notice_pending) { p->notice_done = true; }
	p->notice_pending = false;
	p->notice_until = 0;
}

void kr_power_start_notice(struct kr_power_monitor *p, int64_t now)
{
	/* Start the two seconds at the output, not at an earlier GET_STATUS read. */
	if (p->notice_pending) {
		p->notice_pending = false;
		p->notice_until = now + KR_CHARGE_NOTICE_MS;
	}
}

static void voltage_sample(struct kr_power_monitor *p, struct kr_status s)
{
	bool valid = s.battery_mv > 0 && s.battery_mv <= 5000 &&
		(!s.presence_valid || s.battery_present);
	if (!valid) {
		p->low_samples = p->high_samples = p->recovery_samples = 0;
		if (p->invalid_samples < INVALID_SAMPLES) { ++p->invalid_samples; }
		if (p->state != KR_POWER_CRITICAL && p->state != KR_POWER_VOLTAGE_FAULT) {
			p->state = KR_POWER_UNKNOWN;
		}
		return;
	}
	p->invalid_samples = 0;
	p->low_samples = s.battery_mv <= KR_POWER_STOP_MV ? p->low_samples + (p->low_samples < CONFIRM_SAMPLES) : 0;
	p->high_samples = s.battery_mv > KR_POWER_MAX_MV ? p->high_samples + (p->high_samples < CONFIRM_SAMPLES) : 0;
	if (p->low_samples == CONFIRM_SAMPLES) { p->state = KR_POWER_CRITICAL; }
	else if (p->high_samples == CONFIRM_SAMPLES) { p->state = KR_POWER_VOLTAGE_FAULT; }
	if (p->state == KR_POWER_CRITICAL || p->state == KR_POWER_VOLTAGE_FAULT) {
		bool recovered = s.battery_mv >= KR_POWER_RECOVER_MV && s.battery_mv <= 4200;
		p->recovery_samples = recovered ? p->recovery_samples + (p->recovery_samples < CONFIRM_SAMPLES) : 0;
		if (p->recovery_samples < CONFIRM_SAMPLES) { return; }
	}
	p->state = s.battery_mv <= KR_POWER_LOW_MV ? KR_POWER_LOW : KR_POWER_NORMAL;
}

void kr_power_update(struct kr_power_monitor *p, struct kr_status *s, int64_t now)
{
	bool usb = s->usb_valid && s->usb_present;
	if (!usb) {
		kr_power_cancel_notice(p);
		p->charge_samples = 0;
		if (!p->removing_usb) { p->removing_usb = true; p->removed_at = now; }
		if (now - p->removed_at >= USB_REMOVAL_MS) {
			p->usb_session = p->notice_done = p->saw_charge = false;
		}
	} else {
		p->removing_usb = false;
		if (!p->usb_session) {
			p->usb_session = true;
			p->usb_since = now;
			p->charge_samples = 0;
		}
	}
	if (!p->sampled || now - p->sampled_at >= KR_POWER_SAMPLE_MS) {
		p->sampled = true; p->sampled_at = now;
		voltage_sample(p, *s);
		bool charging = usb && s->charging == 1 && s->battery_mv >= 3000 &&
			s->battery_mv <= KR_POWER_MAX_MV && (!s->presence_valid || s->battery_present);
		if (charging) { p->saw_charge = true; }
		p->charge_samples = charging ? p->charge_samples + (p->charge_samples < CONFIRM_SAMPLES) : 0;
		if (!p->notice_done && p->charge_samples == CONFIRM_SAMPLES) {
			p->notice_done = true;
			/* Skip a weak/abnormal battery; do not surprise the user with a delayed buzz. */
			if (p->state == KR_POWER_NORMAL && s->battery_mv >= KR_POWER_RECOVER_MV &&
				kr_power_motor_voltage_ok(s)) { p->notice_pending = true; }
		}
	}
	if (!usb || s->charging != 1 || !kr_power_motor_voltage_ok(s) ||
		p->state == KR_POWER_CRITICAL || p->state == KR_POWER_VOLTAGE_FAULT ||
		p->state == KR_POWER_UNKNOWN || (p->notice_until && now >= p->notice_until)) {
		kr_power_cancel_notice(p);
	}
	static const char *const names[] = {"NORMAL", "LOW", "CRITICAL", "VOLTAGE_FAULT", "MEASUREMENT_UNKNOWN"};
	s->battery_power_state = names[p->state];
	s->power_off_requested = !usb && s->usb_valid &&
		(p->state == KR_POWER_CRITICAL || p->state == KR_POWER_VOLTAGE_FAULT || p->invalid_samples == INVALID_SAMPLES);
	s->charge_elapsed_seconds = usb ? (uint32_t)((now - p->usb_since) / 1000) : 0;
	s->usb_charge_notice = p->notice_pending || p->notice_until > now;
	s->charge_monitor_state = !s->usb_valid ? "UNKNOWN" : !usb ? "USB_DISCONNECTED" :
		s->presence_valid && !s->battery_present ? "BATTERY_ABSENT" :
		s->charging < 0 ? "UNKNOWN" : s->charging ?
		(now - p->usb_since >= LONG_CHARGE_MS ? "LONG_USB_SESSION" : "CHARGING") :
		p->saw_charge ? "STOPPED_UNCONFIRMED" : "NOT_CHARGING_OR_FULL";
}
