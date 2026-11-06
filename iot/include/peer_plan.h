#ifndef TINGLE_PEER_PLAN_H
#define TINGLE_PEER_PLAN_H

#include <stdint.h>
#include "peer.h"

#define KR_DISCOVERY_UUID_BYTES 16
#define KR_DISCOVERY_MAX_BYTES 254
#define KR_DEV_DISCOVERY_BYTES 32
#define KR_DEV_DISCOVERY_UUID BT_UUID_128_ENCODE(0x8e8c0005U, 0x67a0, 0x4d2f, 0x9c17, 0x120b4c80e106ULL)

/* Internal output of the authenticated policy decoder. Never a BLE request
 * field. Only public discovery service data belongs here. A zero length is OFF.
 * The decoder must verify the server time before providing a positive value. */
struct kr_peer_plan {
	int64_t verified_time;
	uint16_t length;
	uint8_t service_data[KR_DISCOVERY_MAX_BYTES];
	struct kr_peer_material peer;
	uint32_t generation;
};

#endif
