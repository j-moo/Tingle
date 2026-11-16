#ifndef TINGLE_RECORD_RESTORE_H
#define TINGLE_RECORD_RESTORE_H
#include "keyring.h"
typedef int (*kr_record_reader)(void *, uint16_t, void *, size_t);
/* Read-only: isolate a failed record, never fall back to an older event phase. */
int kr_records_restore(struct kr_engine *, kr_record_reader, void *);
#endif
