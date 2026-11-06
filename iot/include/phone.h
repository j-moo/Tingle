#ifndef TINGLE_PHONE_H
#define TINGLE_PHONE_H
#include "keyring.h"

enum kr_command { KR_STATUS = 1, KR_REGISTER = 2, KR_SYNC = 3,
	KR_READ_RECORD = 4, KR_LAB_STORED_RECEIPT = 5, KR_LAB_RELEASE = 6 };

/* KP/v1/op/requestId(u32)/bodyLen(u16)/body/CRC32, all integers little endian. */
size_t kr_phone_frame(uint8_t op, uint32_t request, const uint8_t *body,
	size_t len, uint8_t *out);
size_t kr_phone_process(struct kr_engine *e, const uint8_t *in, size_t len,
	uint8_t *out, uint64_t *new_utc);
#endif
