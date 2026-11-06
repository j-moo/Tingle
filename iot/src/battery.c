#include "battery.h"
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
	r.percent = s.battery_mv <= 3300 ? 0 : s.battery_mv >= 4200 ? 100 : (s.battery_mv - 3300) * 100 / 900;
	r.source = "VOLTAGE_ESTIMATE"; return r;
}
