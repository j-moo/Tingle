#include "settings_storage.h"
#include "peer.h"
#include "policy.h"
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
	core->settings_owner_only = false;
	int got = io->read(io->ctx, KR_SETTINGS_CURRENT_KEY, 0, &core->current);
	bool current_valid = got == sizeof(core->current) && owner_reference_valid(core);
	bool current_missing = got == -ENOENT;
	/* ID 12 is a second committed slot, not an uncommitted backup. It lets NVS
	 * persist a freshly authenticated identical value when ID 10's CRC is bad:
	 * nvs_write deduplicates data bytes without validating the existing CRC. */
	int64_t version = core->current.version, device = core->current.device_id;
	char owner[37], hash[65], format[65];
	memcpy(owner, core->current.owner_generation, sizeof(owner));
	memcpy(hash, core->current.hash, sizeof(hash));
	memcpy(format, core->current.format, sizeof(format));
	got = io->read(io->ctx, KR_SETTINGS_REPAIR_KEY, 0, &core->current);
	bool repair_missing = got == -ENOENT;
	if (got == sizeof(core->current) && owner_reference_valid(core)) {
		if (!current_valid) { return 0; }
		if (device != core->current.device_id || memcmp(owner, core->current.owner_generation, sizeof(owner)) ||
			(version == core->current.version && (memcmp(hash, core->current.hash, sizeof(hash)) ||
			memcmp(format, core->current.format, sizeof(format))))) {
			core->settings_recovery_required = true;
			memset(&core->current, 0, sizeof(core->current)); return -EILSEQ;
		}
		if (core->current.version >= version) { return 0; }
	}
	if (current_missing && !repair_missing) {
		core->settings_recovery_required = true;
		memset(&core->current, 0, sizeof(core->current)); return -EILSEQ;
	}
	memset(&core->current, 0, sizeof(core->current));
	got = io->read(io->ctx, KR_SETTINGS_CURRENT_KEY, 0, &core->current);
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
	/* NVS may leave bytes in the output on CRC failure. They are NOT trusted
	 * settings. Only an independent server signature may establish the owner
	 * for a fresh nonce-bound sync; never rewrite/apply these bytes on boot. */
	memset(&core->current, 0, sizeof(core->current));
	got = io->read(io->ctx, KR_SETTINGS_CURRENT_KEY, 0, &core->current);
	if (got == -EIO && owner_reference_valid(core) && io->recover_owner &&
		io->recover_owner(io->ctx, core, &core->current)) {
		core->settings_owner_only = true;
		return 2;
	}
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
	if (current && current->magic) {
		if (!kr_settings_valid(current)) { return -EILSEQ; }
		/* Keep a separately addressed verified snapshot before replacing key 10.
		 * A failed new write/GC cannot make the sole older copy unreachable. */
		int error = write_verified(io, KR_SETTINGS_BACKUP_KEY, current, scratch);
		if (error) { return error; }
	} else {
		/* First sync (or signed-owner-only repair) also needs a durable copy.
		 * The caller has authenticated next. Never promote damaged old bytes. */
		int error = write_verified(io, KR_SETTINGS_BACKUP_KEY, next, scratch);
		if (error) { return error; }
	}
	int error = write_verified(io, KR_SETTINGS_CURRENT_KEY, next, scratch);
	/* Preserve the failed record. A verified second-slot commit is also durable.
	 * Never ACK if both slots fail, and do not retry allocation/argument errors. */
	return error == -EIO ? write_verified(io, KR_SETTINGS_REPAIR_KEY, next, scratch) : error;
}

bool kr_settings_recovery_owner(const struct kr_core *core, const struct kr_settings *s,
	const uint8_t server[32])
{
	struct kr_policy policy;
	if (!kr_settings_valid(s) || !kr_policy_format(s->format) ||
		!kr_policy_decode(s->payload, s->payload_size, &policy) ||
		!kr_reset_record_valid(core) || core->reset.phase == KR_RESETTING ||
		(core->reset.phase == KR_RESET_DONE && !strcmp(s->owner_generation, core->reset.owner_generation))) {
		return false;
	}
	uint64_t issued = 0; for (unsigned i = 0; i < 8; ++i) { issued |= (uint64_t)policy.grant[125 + i] << (8 * i); }
	const struct kr_settings_auth binding = {.version2 = true, .ownership_epoch = core->reset.ownership_epoch};
	/* Verify this HISTORICAL credential at its signed issue time. This does not
	 * establish current time, extend its lease, or authenticate outer policy.
	 * Only device/key, owner/epoch, revision, run/nonce and profile are bound. */
	return kr_peer_grant(policy.grant, server, issued) &&
		kr_peer_grant_matches(&core->identity, s, &binding, policy.grant);
}
