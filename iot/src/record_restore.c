#include "record_restore.h"
#include <errno.h>
#include <string.h>

int kr_records_restore(struct kr_engine *e, kr_record_reader read, void *ctx)
{
	uint64_t hardware = e->config.device;
	int got = read(ctx, 1, &e->config, sizeof(e->config));
	if (got != -ENOENT && got != (int)sizeof(e->config)) { return -EILSEQ; }
	e->quarantined_records = 0;
	for (unsigned i = 0; i < KR_RECORDS; ++i) {
		struct kr_record *r = &e->records[i];
		memset(r, 0, sizeof(*r));
		got = read(ctx, (uint16_t)(100 + i), r, sizeof(*r));
		if (got == -ENOENT) { memset(r, 0, sizeof(*r)); continue; }
		if (got != (int)sizeof(*r) || !kr_record_valid(r)) {
			/* A failed NVS read may have filled the destination with bad bytes. */
			memset(r, 0, sizeof(*r));
			e->quarantined_records |= UINT32_C(1) << i;
		}
	}
	for (unsigned i = 0; i < KR_SUPPRESSIONS; ++i) {
		got = read(ctx, (uint16_t)(200 + i), &e->suppressed[i], sizeof(e->suppressed[i]));
		/* Unknown suppression state cannot be discarded without allowing repeats. */
		if (got != -ENOENT && got != (int)sizeof(e->suppressed[i])) { return -EILSEQ; }
		if (got == -ENOENT) { memset(&e->suppressed[i], 0, sizeof(e->suppressed[i])); }
		if (e->suppressed[i].peer && e->suppressed[i].phase > KR_COMPLETE) { return -EILSEQ; }
	}
	return hardware == e->config.device && kr_restored_valid(e) ? 0 : -EILSEQ;
}
