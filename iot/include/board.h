#ifndef TINGLE_REGISTRATION_BOARD_H
#define TINGLE_REGISTRATION_BOARD_H
#include "registration.h"
#include <zephyr/bluetooth/addr.h>
int kr_board_open(struct kr_core *core);
int kr_board_forget_phone_bond(const bt_addr_le_t *address);
int kr_board_charge_init(void);
bool kr_board_usb_present(void);
struct kr_status kr_board_status(void);
void kr_board_led(bool active);
void kr_board_print_identity(const struct kr_identity *identity);
#endif
