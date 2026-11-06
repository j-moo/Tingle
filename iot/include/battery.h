#ifndef TINGLE_BATTERY_H
#define TINGLE_BATTERY_H
#include "registration.h"
struct kr_battery_reading { int percent; const char *source, *presence, *power; };
struct kr_battery_reading kr_battery_reading(struct kr_status status);
#endif
