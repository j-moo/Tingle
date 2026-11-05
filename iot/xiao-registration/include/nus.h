#ifndef TINGLE_REGISTRATION_NUS_H
#define TINGLE_REGISTRATION_NUS_H

#include "peer_plan.h"
#include <stdbool.h>
#include <zephyr/bluetooth/uuid.h>

#define KR_NUS_UUID(value) BT_UUID_128_ENCODE(value, 0xb5a3, 0xf393, 0xe0a9, 0xe50e24dcca9e)

struct k_sem;
int kr_nus_init(struct k_sem *peer_wake);
bool kr_nus_take_peer_plan(struct kr_peer_plan *out);
void kr_nus_start_window(void);
bool kr_nus_can_advertise(void);
void kr_nus_poll_console(bool blink);

#endif
