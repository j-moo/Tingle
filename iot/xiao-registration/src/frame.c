#include "registration.h"
#include <string.h>

void kr_frame_reset(struct kr_frame *frame) { frame->size = 0; }

int kr_frame_push(struct kr_frame *frame, const void *bytes, size_t length)
{
	const char *data = bytes;
	if (!length || length > 20 || frame->size + length > KR_FRAME_MAX) { return -1; }
	for (size_t i = 0; i < length; ++i) {
		if (!data[i] || (data[i] == '\n' && i != length - 1)) { return -1; }
	}
	memcpy(frame->bytes + frame->size, data, length);
	frame->size += length;
	if (data[length - 1] != '\n') { return 0; }
	frame->bytes[frame->size - 1] = 0;
	return 1;
}
