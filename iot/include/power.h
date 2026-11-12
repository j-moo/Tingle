#ifndef TINGLE_POWER_H
#define TINGLE_POWER_H

#include "registration.h"

/* Software operating margins, not the battery pack's emergency cutoffs. */
#define KR_POWER_SAMPLE_MS 250
#define KR_POWER_LOW_MV 3450
#define KR_POWER_STOP_MV 3300
#define KR_POWER_RECOVER_MV 3500
#define KR_POWER_MAX_MV 4300
#define KR_CHARGE_NOTICE_MS 2000
#define KR_CHARGE_CURRENT_MA 100

enum kr_power_state { KR_POWER_NORMAL, KR_POWER_LOW, KR_POWER_CRITICAL,
	KR_POWER_VOLTAGE_FAULT, KR_POWER_UNKNOWN };
struct kr_power_monitor {
	bool sampled, usb_session, removing_usb, saw_charge, notice_done, notice_pending;
	int64_t sampled_at, usb_since, removed_at, notice_until;
	unsigned low_samples, high_samples, recovery_samples, invalid_samples, charge_samples;
	enum kr_power_state state;
};

void kr_power_update(struct kr_power_monitor *, struct kr_status *, int64_t now);
void kr_power_cancel_notice(struct kr_power_monitor *);
void kr_power_start_notice(struct kr_power_monitor *, int64_t now);
bool kr_power_motor_voltage_ok(const struct kr_status *);

#endif
