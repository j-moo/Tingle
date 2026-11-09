#include "service.h"
#include <string.h>

struct kr_service_progress kr_service_step(struct kr_peer_plan *plan, bool *update,
	const struct kr_service_io *io)
{
	struct kr_service_progress progress = {0};
	progress.reset_pending = io->reset_pending(io->ctx);
	if (progress.reset_pending) {
		memset(plan, 0, sizeof(*plan));
		/* Blocking invalidates old settings plans. Capture the resulting
		 * generation, including on reboot recovery, rather than applying zero. */
		plan->generation = io->block_reset(io->ctx);
		*update = true;
	}
	if (*update) {
		progress.radio_error = io->apply(io->ctx, plan);
		if (!progress.radio_error) { progress.radio_error = io->refresh_phone(io->ctx); }
		if (io->apply_result) { io->apply_result(io->ctx, plan, progress.radio_error); }
		if (!progress.radio_error) { *update = false; progress.applied = true; }
	}
	/* A reset arriving during an ordinary update must wait until the next
	 * pass actually applies its stop plan. It cannot reuse ordinary success. */
	if (progress.reset_pending && !*update && io->reset_pending(io->ctx)) {
		progress.reset_attempted = true;
		progress.reset_error = io->finish_reset(io->ctx);
	}
	return progress;
}
