#ifndef TINGLE_REGISTRATION_NUS_H
#define TINGLE_REGISTRATION_NUS_H

#include "registration.h"
#include <stdbool.h>
#include <zephyr/bluetooth/uuid.h>

#define KR_NUS_UUID(value) BT_UUID_128_ENCODE(value, 0xb5a3, 0xf393, 0xe0a9, 0xe50e24dcca9e)

struct k_sem;
struct kr_engine;
struct kr_record;
int kr_nus_init(struct k_sem *peer_wake);
bool kr_nus_take_peer_plan(struct kr_peer_plan *out);
void kr_nus_start_window(void);
bool kr_nus_can_advertise(void);
size_t kr_nus_copy_public_name(char name[KR_PUBLIC_NAME_MAX + 1]);
void kr_nus_poll_console(bool blink);
int kr_nus_forget_failed_bond(uint32_t token);
bool kr_nus_reset_pending(void);
bool kr_nus_storage_fault(void);
bool kr_nus_lab_allowed(void);
const char *kr_nus_finish_reset(void);
/* Main engine mutations and reset policy checks share this lock. */
void kr_runtime_owner_lock(void);
void kr_runtime_owner_unlock(void);
int kr_runtime_pending_records(void);
void kr_runtime_reset_block(void);
void kr_runtime_pause_matching(void);
uint32_t kr_runtime_generation(void);
void kr_runtime_stop_feedback(void);
void kr_runtime_refresh_phone(void);
void kr_nus_attach_application(struct kr_engine *engine);
int kr_nus_apply_application(const struct kr_peer_plan *plan, struct kr_peer_runtime *peers);
void kr_nus_application_result(const struct kr_peer_plan *plan, int error);
void kr_nus_quiesce(void);
void kr_nus_bind_application(struct kr_peer_runtime *peers);
void kr_nus_clear_application(void);
int kr_nus_write_record(unsigned slot, const struct kr_record *record);
void kr_nus_application_tick(void);
uint64_t kr_nus_application_time(void);
unsigned kr_nus_feedback_presence_seconds(void);
int kr_runtime_clear_owner_data(void);

#endif
