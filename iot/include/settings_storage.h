#ifndef TINGLE_SETTINGS_STORAGE_H
#define TINGLE_SETTINGS_STORAGE_H
#include "registration.h"

#define KR_SETTINGS_CURRENT_KEY 10U
#define KR_SETTINGS_BACKUP_KEY 11U
struct kr_settings_storage {
	void *ctx;
	int (*read)(void *ctx, uint16_t key, unsigned history, struct kr_settings *out);
	int (*write)(void *ctx, uint16_t key, const struct kr_settings *value);
};
/* 1 means an older, verified owner reference was recovered, not applied. */
int kr_settings_storage_load(struct kr_core *, const struct kr_settings_storage *);
int kr_settings_storage_save(const struct kr_settings_storage *, const struct kr_settings *current,
	const struct kr_settings *next, struct kr_settings *scratch);
#endif
