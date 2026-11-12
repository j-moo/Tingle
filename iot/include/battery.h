#ifndef TINGLE_BATTERY_H
#define TINGLE_BATTERY_H
#include "registration.h"
struct kr_battery_reading { int percent; const char *source, *presence, *power; };
struct kr_battery_reading kr_battery_reading(struct kr_status status);

enum kr_charge_level { KR_CHARGE_OFF, KR_CHARGE_LOW, KR_CHARGE_MID, KR_CHARGE_HIGH };
struct kr_charge_indicator {
	enum kr_charge_level level, candidate;
	unsigned samples;
};
enum kr_charge_level kr_charge_indicator_update(struct kr_charge_indicator *, struct kr_status);
#endif
