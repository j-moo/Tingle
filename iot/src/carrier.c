#include "carrier.h"
#include "board.h"

#include <errno.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/dt-bindings/gpio/nordic-nrf-gpio.h>
#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <zephyr/sys/poweroff.h>
#include <zephyr/sys/reboot.h>

LOG_MODULE_REGISTER(keyring_carrier, LOG_LEVEL_INF);

#define CARRIER DT_PATH(zephyr_user)
#define STOP_DEBOUNCE_MS 20
#define OFF_DEBOUNCE_MS 20
#define REV_G DT_NODE_HAS_PROP(CARRIER, keyring_off_gpios)

static const struct gpio_dt_spec rgb[] = {
	GPIO_DT_SPEC_GET(CARRIER, keyring_red_gpios),
	GPIO_DT_SPEC_GET(CARRIER, keyring_green_gpios),
	GPIO_DT_SPEC_GET(CARRIER, keyring_blue_gpios),
};
static const struct gpio_dt_spec motor = GPIO_DT_SPEC_GET(CARRIER, keyring_motor_gpios);
static const struct gpio_dt_spec stop_button = GPIO_DT_SPEC_GET(CARRIER, keyring_stop_gpios);
#if REV_G
static const struct gpio_dt_spec off_switch = GPIO_DT_SPEC_GET(CARRIER, keyring_off_gpios);
static bool switch_ready;
#endif

static struct gpio_callback stop_irq;
static struct kr_feedback alert;
static bool ready, motor_on_usb;
K_MUTEX_DEFINE(carrier_lock);

static void output_tick(struct k_work *work);
static void confirm_stop(struct k_work *work);
K_WORK_DELAYABLE_DEFINE(output_work, output_tick);
K_WORK_DELAYABLE_DEFINE(button_work, confirm_stop);

static int drive(uint8_t color, bool vibrate)
{
	int result = 0;
	for (size_t i = 0; i < ARRAY_SIZE(rgb); ++i) {
		int error = gpio_pin_set_dt(&rgb[i], (color & BIT(i)) != 0);
		if (error && !result) { result = error; }
	}
	int error = gpio_pin_set_dt(&motor, !result && vibrate);
	return result ? result : error;
}

static void stop_locked(void)
{
	kr_feedback_stop(&alert);
	(void)k_work_cancel_delayable(&output_work);
	if (ready) { (void)drive(0, false); }
}

static bool update_locked(void)
{
	if (kr_carrier_off_requested()) { stop_locked(); return false; }
	/* Cancel the pulse train when the power source changes during an alert. */
	struct kr_feedback_output out = kr_feedback_step(&alert, k_uptime_get(),
		kr_board_usb_present() == motor_on_usb);
	if (drive(out.rgb, out.motor)) {
		stop_locked();
		ready = false;
		LOG_ERR("Carrier feedback GPIO failed");
		return false;
	}
	if (out.wake_ms) {
		(void)k_work_reschedule(&output_work, K_MSEC(out.wake_ms));
	}
	return out.wake_ms != 0;
}

static void output_tick(struct k_work *work)
{
	ARG_UNUSED(work);
	k_mutex_lock(&carrier_lock, K_FOREVER);
	if (ready) { (void)update_locked(); }
	k_mutex_unlock(&carrier_lock);
}

static void confirm_stop(struct k_work *work)
{
	ARG_UNUSED(work);
	k_mutex_lock(&carrier_lock, K_FOREVER);
	if (ready && gpio_pin_get_dt(&stop_button) != 0) { stop_locked(); }
	k_mutex_unlock(&carrier_lock);
}

static void stop_pressed(const struct device *port, struct gpio_callback *callback, gpio_port_pins_t pins)
{
	ARG_UNUSED(port);
	ARG_UNUSED(callback);
	ARG_UNUSED(pins);
	(void)k_work_reschedule(&button_work, K_MSEC(STOP_DEBOUNCE_MS));
}

int kr_carrier_init(void)
{
#if REV_G
	if (!gpio_is_ready_dt(&off_switch)) { return -ENODEV; }
	int switch_error = gpio_pin_configure_dt(&off_switch, GPIO_INPUT | GPIO_PULL_UP);
	if (switch_error) { return switch_error; }
	switch_ready = true;
#endif
	if (!gpio_is_ready_dt(&motor) || !gpio_is_ready_dt(&stop_button)) { return -ENODEV; }
	int result = gpio_pin_configure_dt(&motor, GPIO_OUTPUT_INACTIVE);
	if (result) { return result; }
	for (size_t i = 0; i < ARRAY_SIZE(rgb); ++i) {
		if (!gpio_is_ready_dt(&rgb[i])) { return -ENODEV; }
		gpio_flags_t flags = GPIO_OUTPUT_INACTIVE;
		/* Rev G sources LED current through the carrier's 470 ohm resistors. */
		if (REV_G) { flags |= NRF_GPIO_DRIVE_S0H1; }
		result = gpio_pin_configure_dt(&rgb[i], flags);
		if (result) { return result; }
	}
	result = gpio_pin_configure_dt(&stop_button, GPIO_INPUT);
	if (result) { return result; }
	gpio_init_callback(&stop_irq, stop_pressed, BIT(stop_button.pin));
	result = gpio_add_callback(stop_button.port, &stop_irq);
	if (result) { return result; }
	result = gpio_pin_interrupt_configure_dt(&stop_button, GPIO_INT_EDGE_TO_ACTIVE);
	if (result) {
		(void)gpio_remove_callback(stop_button.port, &stop_irq);
		return result;
	}
	k_mutex_lock(&carrier_lock, K_FOREVER);
	ready = true;
	k_mutex_unlock(&carrier_lock);
	return 0;
}

bool kr_carrier_off_requested(void)
{
#if REV_G
	return switch_ready && gpio_pin_get_dt(&off_switch) == 1;
#else
	return false;
#endif
}

void kr_carrier_enter_off(void)
{
#if REV_G
	(void)gpio_pin_interrupt_configure_dt(&stop_button, GPIO_INT_DISABLE);
	/* A physical HIGH on D5 wakes the nRF52840 into a normal cold boot. */
	int error = gpio_pin_configure_dt(&off_switch, GPIO_INPUT);
	if (!error) { error = gpio_pin_interrupt_configure_dt(&off_switch, GPIO_INT_LEVEL_INACTIVE); }
	if (!error) {
		unsigned int key = irq_lock();
		if (kr_carrier_off_requested()) { sys_poweroff(); }
		irq_unlock(key);
	} else {
		LOG_ERR("Could not arm D5 wake: %d", error);
		while (kr_carrier_off_requested()) { k_msleep(OFF_DEBOUNCE_MS); }
	}
	/* The switch moved back to ON while we were stopping the device. */
	sys_reboot(SYS_REBOOT_COLD);
#endif
}

bool kr_carrier_feedback_start(struct kr_feedback_options options)
{
	bool usb = false;
	if (options.vibrate) {
		struct kr_status power = kr_board_status();
		usb = power.usb_present;
		options.vibrate = kr_feedback_motor_can_start(&power, REV_G);
	}
	if (!options.rgb && !options.vibrate) { return false; }
	k_mutex_lock(&carrier_lock, K_FOREVER);
	bool started = false;
	if (ready && !kr_carrier_off_requested() && gpio_pin_get_dt(&stop_button) == 0 &&
		kr_feedback_start(&alert, k_uptime_get(), options)) {
		motor_on_usb = usb;
		started = update_locked();
	}
	k_mutex_unlock(&carrier_lock);
	return started;
}

void kr_carrier_feedback_stop(void)
{
	k_mutex_lock(&carrier_lock, K_FOREVER);
	stop_locked();
	k_mutex_unlock(&carrier_lock);
}
