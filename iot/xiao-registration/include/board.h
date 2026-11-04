#ifndef TINGLE_REGISTRATION_BOARD_H
#define TINGLE_REGISTRATION_BOARD_H
#include "registration.h"
int kr_board_open(struct kr_core *core);
struct kr_status kr_board_status(void);
void kr_board_led(bool active);
void kr_board_print_identity(const struct kr_identity *identity);
#endif
