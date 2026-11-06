#ifndef TINGLE_STORAGE_H
#define TINGLE_STORAGE_H
#include "keyring.h"
int kr_storage_sequence(void *, uint32_t);
int kr_storage_open(struct kr_engine *e);
int kr_storage_config(void *ctx, const struct kr_config *config);
int kr_storage_record(void *ctx, unsigned slot, const struct kr_record *record);
int kr_storage_suppression(void *ctx, unsigned slot, const struct kr_suppression *s);
int kr_storage_clear_owner(struct kr_engine *e);
int kr_storage_blob(void *ctx, uint16_t key, void *data, size_t length, bool write);
int kr_storage_space(void *ctx, int64_t *total, int64_t *used);
#endif
