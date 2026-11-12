#include "battery.h"

#define CHARGE_CONFIRM_SAMPLES 5
static int voltage_percent(int mv)
{
	return mv <= 3300 ? 0 : mv >= 4200 ? 100 : (mv - 3300) * 100 / 900;
}

struct kr_battery_reading kr_battery_reading(struct kr_status s)
{
	struct kr_battery_reading r = {.percent = -1, .source = "UNKNOWN", .presence = "UNCONFIRMED",
		.power = s.usb_valid ? (s.usb_present ? "USB" : "USB_ABSENT") : "UNKNOWN"};
	if (s.presence_valid) { r.presence = s.battery_present ? "PRESENT" : "ABSENT"; }
	if (s.presence_valid && !s.battery_present) { return r; }
	if (s.gauge_ready) {
		if (s.gauge_percent_valid && s.gauge_percent >= 0 && s.gauge_percent <= 100) {
			r.percent = s.gauge_percent; r.source = "FUEL_GAUGE";
		}
		return r; /* A failed gauge is not replaced with an invented good value. */
	}
	/* USB/charging voltages can mimic a full battery even with no cell. */
	if ((s.usb_valid && s.usb_present) || s.charging > 0 || s.battery_mv < 3000 || s.battery_mv > 4300) { return r; }
	r.percent = voltage_percent(s.battery_mv);
	r.source = "VOLTAGE_ESTIMATE"; return r;
}

static enum kr_charge_level percent_level(int percent)
{
	if (percent < 30) { return KR_CHARGE_LOW; }
	return percent < 70 ? KR_CHARGE_MID : KR_CHARGE_HIGH;
}

static enum kr_charge_level charge_level(struct kr_status s)
{
	if (!s.usb_valid || !s.usb_present || (s.presence_valid && !s.battery_present)) {
		return KR_CHARGE_OFF;
	}
	if (s.gauge_ready) {
		int percent = kr_battery_reading(s).percent;
		if (!s.presence_valid || percent < 0) { return KR_CHARGE_OFF; }
		return percent_level(percent);
	}
	/* The XIAO charger can regulate BAT even when SW1 has disconnected the cell.
	 * Only use the ADC while CHG is asserted. CHG high does not prove full charge. */
	if (s.charging != 1 || s.battery_mv < 3000 || s.battery_mv > 4300) { return KR_CHARGE_OFF; }
	/* LED-only estimate: charging raises cell voltage. Do not publish this as
	 * measured SOC in GET_STATUS, or use it to control charging/protection. */
	return percent_level(voltage_percent(s.battery_mv));
}

uint8_t kr_charge_indicator_rgb(enum kr_charge_level level)
{
	switch (level) {
	case KR_CHARGE_LOW: return 1; /* R */
	case KR_CHARGE_MID: return 4; /* B */
	case KR_CHARGE_HIGH: return 2; /* G */
	default: return 0;
	}
}

uint8_t kr_status_led_rgb(enum kr_charge_level level, bool usb, bool error, bool diagnostic)
{
	if (error) { return 1; }
	if (diagnostic) { return 4; }
	/* Idle green means radio ready only on battery power. USB with unknown
	 * charge state must not look like a high/full battery. */
	return usb ? kr_charge_indicator_rgb(level) : 2;
}

enum kr_charge_level kr_charge_indicator_update(struct kr_charge_indicator *indicator, struct kr_status status)
{
	enum kr_charge_level sample = charge_level(status);
	if (sample == KR_CHARGE_OFF) {
		*indicator = (struct kr_charge_indicator){0};
	} else if (sample != indicator->candidate) {
		indicator->candidate = sample;
		indicator->samples = 1;
	} else if (indicator->samples < CHARGE_CONFIRM_SAMPLES && ++indicator->samples == CHARGE_CONFIRM_SAMPLES) {
		/* Require consecutive samples before showing a new color. */
		indicator->level = sample;
	}
	return indicator->level;
}
