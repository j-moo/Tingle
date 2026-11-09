#ifndef TINGLE_SERVICE_H
#define TINGLE_SERVICE_H

#include "peer_plan.h"
#include <stdbool.h>

/* Main-loop orchestration shared with host integration tests. Radio and NVS
 * remain adapter operations; a failed stop can never authorize owner deletion. */
struct kr_service_io {
	void *ctx;
	bool (*reset_pending)(void *);
	uint32_t (*block_reset)(void *);
	int (*apply)(void *, const struct kr_peer_plan *);
	int (*refresh_phone)(void *);
	const char *(*finish_reset)(void *);
	void (*apply_result)(void *, const struct kr_peer_plan *, int error);
};
struct kr_service_progress {
	int radio_error;
	const char *reset_error;
	bool applied, reset_pending, reset_attempted;
};
struct kr_service_progress kr_service_step(struct kr_peer_plan *, bool *, const struct kr_service_io *);

#endif
