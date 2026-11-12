#include "settings_storage.h"
#include <errno.h>
#include <string.h>

static bool owner_reference_valid(const struct kr_core *core)
{
	return kr_settings_valid(&core->current) && core->reset.phase != KR_RESETTING &&
		!(core->reset.phase == KR_RESET_DONE && !strcmp(core->current.owner_generation, core->reset.owner_generation));
}

int kr_settings_storage_load(struct kr_core *core, const struct kr_settings_storage *io)
{
	core->settings_recovery_required = false;
	int got = io->read(io->ctx, KR_SETTINGS_CURRENT_KEY, 0, &core->current);
	/* A tombstone/unowned device must never resurrect a previous owner's data. */
	if (got == -ENOENT) { memset(&core->current, 0, sizeof(core->current)); return 0; }
	if (got == sizeof(core->current) && owner_reference_valid(core)) { return 0; }
	core->settings_recovery_required = true;
	/* At most one full settings item fits a 4 KiB sector (32 sectors). */
	for (unsigned history = 1; history <= 32; ++history) {
		got = io->read(io->ctx, KR_SETTINGS_CURRENT_KEY, history, &core->current);
		if (got == -ENOENT) { break; }
		if (got == sizeof(core->current) && owner_reference_valid(core)) { return 1; }
	}
	got = io->read(io->ctx, KR_SETTINGS_BACKUP_KEY, 0, &core->current);
	if (got == sizeof(core->current) && owner_reference_valid(core)) { return 1; }
	memset(&core->current, 0, sizeof(core->current));
	return -EILSEQ;
}

static int write_verified(const struct kr_settings_storage *io, uint16_t key,
	const struct kr_settings *value, struct kr_settings *scratch)
{
	int error = io->write(io->ctx, key, value);
	if (error) { return error; }
	int got = io->read(io->ctx, key, 0, scratch);
	return got == sizeof(*scratch) && !memcmp(value, scratch, sizeof(*scratch)) &&
		kr_settings_valid(scratch) ? 0 : -EIO;
}

int kr_settings_storage_save(const struct kr_settings_storage *io, const struct kr_settings *current,
	const struct kr_settings *next, struct kr_settings *scratch)
{
	if (!kr_settings_valid(next)) { return -EINVAL; }
	if (current->magic) {
		if (!kr_settings_valid(current)) { return -EILSEQ; }
		/* Keep a separately addressed verified snapshot before replacing key 10.
		 * A failed new write/GC cannot make the sole older copy unreachable. */
		int error = write_verified(io, KR_SETTINGS_BACKUP_KEY, current, scratch);
		if (error) { return error; }
	}
	return write_verified(io, KR_SETTINGS_CURRENT_KEY, next, scratch);
}
