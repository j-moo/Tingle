#include "nus.h"
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/kernel.h>
#include <zephyr/settings/settings.h>

static void boot_fault(const char *stage, int error)
{
	for (;;) {
		printk("BOOT failed stage=%s error=%d; no erase or key replacement\n", stage, error);
		k_sleep(K_SECONDS(5));
	}
}

int main(void)
{
	int result = kr_nus_init(NULL);
	if (result) { boot_fault("storage/identity", result); }
	result = bt_enable(NULL);
	if (!result) { result = settings_load(); }
	if (result) { boot_fault("bluetooth", result); }
	const struct bt_data ad[] = {BT_DATA_BYTES(BT_DATA_FLAGS, BT_LE_AD_GENERAL | BT_LE_AD_NO_BREDR),
		BT_DATA_BYTES(BT_DATA_UUID128_ALL, KR_NUS_UUID(0x6e400001))};
	const struct bt_data scan[] = {BT_DATA(BT_DATA_NAME_COMPLETE, CONFIG_BT_DEVICE_NAME,
		sizeof(CONFIG_BT_DEVICE_NAME) - 1)};
	kr_nus_start_window();
	result = bt_le_adv_start(BT_LE_ADV_CONN_FAST_1, ad, ARRAY_SIZE(ad), scan, ARRAY_SIZE(scan));
	if (result) { boot_fault("advertising", result); }
	printk("BOOT NUS ready; registration window=60s; settingsPolicy=UNRESOLVED\n");
	bool window_closed = false;
	for (;;) {
		kr_nus_poll_console(true);
		if (!window_closed && !kr_nus_can_advertise()) {
			bt_le_adv_stop();
			window_closed = true;
			printk("Registration window closed; reboot to reopen\n");
		}
		k_sleep(K_MSEC(250));
	}
}
