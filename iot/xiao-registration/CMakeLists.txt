cmake_minimum_required(VERSION 3.20.0)
find_package(Zephyr 4.2.0 REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(tingle_registration C)
target_include_directories(app PRIVATE include vendor/monocypher)
target_sources(app PRIVATE src/main.c src/board.c src/proof.c src/hash.c src/json.c src/request.c src/frame.c
  vendor/monocypher/monocypher.c vendor/monocypher/monocypher-ed25519.c)
target_compile_options(app PRIVATE -Wall -Wextra -Werror -fstack-usage)
