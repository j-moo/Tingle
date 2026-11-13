#include "power.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

static struct kr_status battery(int mv, bool usb, int charging)
{
	return (struct kr_status){.battery_mv = mv, .usb_valid = true,
		.usb_present = usb, .charging = charging};
}

static void sample_five(struct kr_power_monitor *p, struct kr_status *s, int64_t start)
{
	for (unsigned i = 0; i < 5; ++i) { kr_power_update(p, s, start + i * KR_POWER_SAMPLE_MS); }
}

void test_power_monitor(void)
{
	struct kr_power_monitor p = {0};
	struct kr_status s = battery(3300, false, 0);
	kr_power_update(&p, &s, 0);
	/* BLE query frequency must not turn a voltage spike into five samples. */
	for (int i = 1; i < 250; ++i) { kr_power_update(&p, &s, i); assert(!s.power_off_requested); }
	for (int i = 250; i <= 1000; i += 250) { kr_power_update(&p, &s, i); }
	assert(s.power_off_requested && !strcmp(s.battery_power_state, "CRITICAL"));
	s.battery_mv = 3499;
	sample_five(&p, &s, 1250); assert(s.power_off_requested);
	s.battery_mv = 3500;
	kr_power_update(&p, &s, 2500); assert(s.power_off_requested);
	sample_five(&p, &s, 2750); assert(!s.power_off_requested);
	assert(!strcmp(s.battery_power_state, "NORMAL"));
	p = (struct kr_power_monitor){0}; s = battery(4400, false, 0);
	sample_five(&p, &s, 0);
	assert(s.power_off_requested && !strcmp(s.battery_power_state, "VOLTAGE_FAULT"));
	s.usb_present = true; kr_power_update(&p, &s, 1250);
	assert(!s.power_off_requested && !s.usb_charge_notice);
	p = (struct kr_power_monitor){0}; s = battery(-1, false, -1);
	for (unsigned i = 0; i < 19; ++i) {
		kr_power_update(&p, &s, i * 250); assert(!s.power_off_requested);
	}
	kr_power_update(&p, &s, 4750);
	assert(s.power_off_requested && !strcmp(s.battery_power_state, "MEASUREMENT_UNKNOWN"));
	s.usb_present = true; kr_power_update(&p, &s, 5000); assert(!s.power_off_requested);
	s.usb_valid = false; kr_power_update(&p, &s, 5250); assert(!s.power_off_requested);

	/* CHG must be stable; an unconfirmed full/absent battery never vibrates. */
	p = (struct kr_power_monitor){0}; s = battery(4100, true, 0);
	sample_five(&p, &s, 0); assert(!s.usb_charge_notice);
	s.charging = 1;
	for (int i = 1250; i < 2250; i += 250) { kr_power_update(&p, &s, i); assert(!s.usb_charge_notice); }
	kr_power_update(&p, &s, 2250); assert(s.usb_charge_notice && p.notice_pending);
	/* Time starts only after the carrier actually drives the motor. */
	kr_power_start_notice(&p, 2300);
	kr_power_update(&p, &s, 4299); assert(s.usb_charge_notice);
	kr_power_start_notice(&p, 4299); /* An output tick must not extend it. */
	kr_power_update(&p, &s, 4300); assert(!s.usb_charge_notice);
	s.charging = 0; kr_power_update(&p, &s, 4550);
	assert(!strcmp(s.charge_monitor_state, "STOPPED_UNCONFIRMED"));
	s.charging = 1; sample_five(&p, &s, 4800); assert(!s.usb_charge_notice);
	/* A brief disconnect stops the motor but does not repeat the notice. */
	s.usb_present = false; kr_power_update(&p, &s, 6000);
	s.usb_present = true; sample_five(&p, &s, 6250); assert(!s.usb_charge_notice);
	s.usb_present = false; kr_power_update(&p, &s, 7500); kr_power_update(&p, &s, 8000);
	s.usb_present = true; sample_five(&p, &s, 8250); assert(s.usb_charge_notice);
	kr_power_start_notice(&p, 9250);
	s.battery_mv = 3200; kr_power_update(&p, &s, 9300); assert(!s.usb_charge_notice);
	s.battery_mv = 3800; sample_five(&p, &s, 9550); assert(!s.usb_charge_notice);
	kr_power_update(&p, &s, 8250 + 9LL * 3600 * 1000);
	assert(!strcmp(s.charge_monitor_state, "LONG_USB_SESSION"));
	assert(!s.usb_charge_notice && !s.power_off_requested);

	p = (struct kr_power_monitor){0}; s = battery(3400, true, 1);
	sample_five(&p, &s, 0); assert(!s.usb_charge_notice);
	s.battery_mv = 3800; sample_five(&p, &s, 1250); assert(!s.usb_charge_notice);
	p = (struct kr_power_monitor){0};
	sample_five(&p, &s, 0); assert(s.usb_charge_notice);
	kr_power_cancel_notice(&p); sample_five(&p, &s, 1250); assert(!s.usb_charge_notice);
	p = (struct kr_power_monitor){0};
	s.presence_valid = true; s.battery_present = false;
	sample_five(&p, &s, 0); assert(!s.usb_charge_notice);
	assert(!strcmp(s.charge_monitor_state, "BATTERY_ABSENT"));
	s.battery_present = true;
	assert(kr_power_motor_voltage_ok(&s));
	s.battery_mv = 3299; assert(!kr_power_motor_voltage_ok(&s));
	s.battery_mv = 4251; assert(!kr_power_motor_voltage_ok(&s));
	puts("power: low/abnormal voltage, recovery, ADC failures, timed one-shot charge notice, cancellation and long USB diagnostics passed");
}
