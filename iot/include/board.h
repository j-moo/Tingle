#ifndef TINGLE_REGISTRATION_BOARD_H
#define TINGLE_REGISTRATION_BOARD_H
#include "registration.h"
#include "battery.h"
#include <zephyr/bluetooth/addr.h>
int kr_board_open(struct kr_core *core);
int kr_board_forget_phone_bond(const bt_addr_le_t *address);
int kr_board_charge_init(void);
int kr_board_power_init(void);
void kr_board_cancel_charge_notice(void);
void kr_board_start_charge_notice(void);
bool kr_board_usb_present(void);
struct kr_status kr_board_status(void);
void kr_board_led(bool active);
int kr_board_led_ready(bool error);
void kr_board_charge_led(enum kr_charge_level level, bool usb);
void kr_board_led_off(void);
void kr_board_print_identity(const struct kr_identity *identity);
#endif
