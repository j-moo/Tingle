cmake_minimum_required(VERSION 3.20.0)
find_package(Zephyr 4.2.0 REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(tingle_keyring C)
option(KR_DIAG_INTERACTIVE_BOOT "Pause boot stages for USB hardware diagnostics" OFF)
target_include_directories(app PRIVATE include vendor/monocypher)
target_sources(app PRIVATE
  src/main.c src/codec.c src/engine.c src/phone.c src/storage.c
  src/nus.c src/board.c src/proof.c src/settings_auth.c
  src/hash.c src/json.c src/request.c src/frame.c src/reset.c src/profile.c
  vendor/monocypher/monocypher.c vendor/monocypher/monocypher-ed25519.c)
file(STRINGS "${CMAKE_CURRENT_SOURCE_DIR}/config/dev-settings-public-key.hex" default_dev_key LIMIT_COUNT 1)
set(KR_DEV_SETTINGS_PUBLIC_KEY "${default_dev_key}" CACHE STRING "Pinned development Ed25519 public key (raw hex)")
if(NOT KR_DEV_SETTINGS_PUBLIC_KEY STREQUAL "")
  string(LENGTH "${KR_DEV_SETTINGS_PUBLIC_KEY}" key_length)
  if(NOT key_length EQUAL 64 OR NOT KR_DEV_SETTINGS_PUBLIC_KEY MATCHES "^[0-9a-f]+$")
    message(FATAL_ERROR "KR_DEV_SETTINGS_PUBLIC_KEY must be 64 lowercase hex characters")
  endif()
  string(SUBSTRING "${KR_DEV_SETTINGS_PUBLIC_KEY}" 0 16 key_prefix)
  target_compile_definitions(app PRIVATE
    KR_FIRMWARE_VERSION="0.3.1-dev-signed"
    KR_DEV_SERVER_KEY_HEX="${KR_DEV_SETTINGS_PUBLIC_KEY}"
    KR_DEV_SERVER_KEY_ID="dev-ed25519-${key_prefix}")
else()
  target_compile_definitions(app PRIVATE KR_FIRMWARE_VERSION="0.3.1-unified")
endif()
target_compile_options(app PRIVATE -Wall -Wextra -Werror -fstack-usage)
if(KR_DIAG_INTERACTIVE_BOOT)
  target_compile_definitions(app PRIVATE KR_DIAG_INTERACTIVE_BOOT=1)
endif()
