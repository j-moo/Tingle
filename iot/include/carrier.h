#ifndef TINGLE_CARRIER_H
#define TINGLE_CARRIER_H

#include "feedback.h"

int kr_carrier_init(void);
bool kr_carrier_off_requested(void);
void kr_carrier_enter_off(void);
void kr_carrier_shutdown(void);
bool kr_carrier_poll_charge(void);
bool kr_carrier_feedback_start(struct kr_feedback_options options);
void kr_carrier_feedback_stop(void);

#endif
