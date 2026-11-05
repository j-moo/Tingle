cmake_minimum_required(VERSION 3.20.0)
find_package(Zephyr 4.2.0 REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(tingle_keyring C)
option(KR_DIAG_INTERACTIVE_BOOT "Pause boot stages for USB hardware diagnostics" OFF)
target_include_directories(app PRIVATE include)
target_sources(app PRIVATE src/main.c src/codec.c src/engine.c src/phone.c src/storage.c)
set(REGISTRATION_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../xiao-registration")
target_include_directories(app PRIVATE "${REGISTRATION_DIR}/include" "${REGISTRATION_DIR}/vendor/monocypher")
target_sources(app PRIVATE
  "${REGISTRATION_DIR}/src/nus.c"
  "${REGISTRATION_DIR}/src/board.c"
  "${REGISTRATION_DIR}/src/proof.c"
  "${REGISTRATION_DIR}/src/settings_auth.c"
  "${REGISTRATION_DIR}/src/hash.c"
  "${REGISTRATION_DIR}/src/json.c"
  "${REGISTRATION_DIR}/src/request.c"
  "${REGISTRATION_DIR}/src/frame.c"
  "${REGISTRATION_DIR}/vendor/monocypher/monocypher.c"
  "${REGISTRATION_DIR}/vendor/monocypher/monocypher-ed25519.c")
set(KR_DEV_SETTINGS_PUBLIC_KEY "" CACHE STRING "Pinned development Ed25519 public key (raw hex)")
if(NOT KR_DEV_SETTINGS_PUBLIC_KEY STREQUAL "")
  string(LENGTH "${KR_DEV_SETTINGS_PUBLIC_KEY}" key_length)
  if(NOT key_length EQUAL 64 OR NOT KR_DEV_SETTINGS_PUBLIC_KEY MATCHES "^[0-9a-f]+$")
    message(FATAL_ERROR "KR_DEV_SETTINGS_PUBLIC_KEY must be 64 lowercase hex characters")
  endif()
  string(SUBSTRING "${KR_DEV_SETTINGS_PUBLIC_KEY}" 0 16 key_prefix)
  target_compile_definitions(app PRIVATE
    KR_FIRMWARE_VERSION="0.2.3-dev-signed"
    KR_DEV_SERVER_KEY_HEX="${KR_DEV_SETTINGS_PUBLIC_KEY}"
    KR_DEV_SERVER_KEY_ID="dev-ed25519-${key_prefix}")
else()
  target_compile_definitions(app PRIVATE KR_FIRMWARE_VERSION="0.2.3-unified")
endif()
target_compile_options(app PRIVATE -Wall -Wextra -Werror -fstack-usage)
if(KR_DIAG_INTERACTIVE_BOOT)
  target_compile_definitions(app PRIVATE KR_DIAG_INTERACTIVE_BOOT=1)
endif()
