# CMakeLists.txt — app native build.
# Currently builds libxt9trace.so, the in-app logcat dumper for the ET9 trace-result
# region (the trace-result region notes live in the local attic). Also serves as the smoke test
# that the NDK/externalNativeBuild pipeline works ahead of the owned-KDB module.
cmake_minimum_required(VERSION 3.22.1)
project(appnative C)

add_library(xt9trace SHARED xt9trace/xt9trace.c et9probe.c)
find_library(log-lib log)
find_library(dl-lib dl)   # dladdr() for the NKL-writer watchpoint (maps PC -> blob symbol/offset)
target_link_libraries(xt9trace ${log-lib} ${dl-lib})

# --- W1: owned ET9KDB module -----------------------------------------------------------------
# CUTOVER mode (default; the owned KDB is standard): exports ET9KDB_* to REPLACE the blob's (no
#   JNI glue). Built as libkb.so (NOT libxt9kdb.so): the patched blob reaches it via a DT_NEEDED
#   that reuses the dead libdl.so string slot (8-char cap -> "libkb.so"). See
#   xt9kdb-scaffold/tools/elf_cutover.py. Self-contained (libc only).
# DIFF mode (-DXT9KDB_CUTOVER=OFF; gradle -Pxt9Diff): exports xt9kdb_* + JNI self-check glue;
#   coexists with an UNPATCHED blob's ET9KDB_*, nothing replaced; debug-only and inert (AW commit
#   hooks default NULL).
option(XT9KDB_CUTOVER "Build the owned KDB in CUTOVER mode (exports ET9KDB_*)" ON)
set(XT9KDB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../xt9kdb-scaffold/cpp)
set(XT9KDB_SRCS
    ${XT9KDB_DIR}/src/kdb_state.c
    ${XT9KDB_DIR}/src/kdb_load_xml.c
    ${XT9KDB_DIR}/src/kdb_geometry.c
    ${XT9KDB_DIR}/src/kdb_tap.c
    ${XT9KDB_DIR}/src/kdb_trace.c
    ${XT9KDB_DIR}/src/kdb_decode.c
    ${XT9KDB_DIR}/src/kdb_rank.c
    ${XT9KDB_DIR}/src/kdb_nkl.c)
if(XT9KDB_CUTOVER)
    # No debug JNI glue — but the variant JNI (device KDB variants) ships in both modes.
    add_library(xt9kdb SHARED ${XT9KDB_SRCS} xt9kdb_variant_jni.c)
    target_compile_definitions(xt9kdb PRIVATE XT9KDB_CUTOVER=1)
    # Output libkb.so + SONAME libkb.so so the patched blob's repurposed DT_NEEDED resolves it.
    set_target_properties(xt9kdb PROPERTIES OUTPUT_NAME kb LINK_FLAGS "-Wl,-soname,libkb.so")
else()
    # kdb_decode_legacy.c: the superseded first-cut decoder (decode_keyseq + explicit-symbol
    # feed). DIFF-only — xt9kdb_jni.c's self-check calls xt9kdb_decode_scratch(). It is
    # deliberately absent from the CUTOVER library; the shipped decode path is owned_pst_v2.
    add_library(xt9kdb SHARED ${XT9KDB_SRCS} ${XT9KDB_DIR}/src/kdb_decode_legacy.c
                xt9kdb_jni.c xt9kdb_variant_jni.c)
    target_compile_definitions(xt9kdb PRIVATE XT9KDB_DIFF=1)
endif()
target_include_directories(xt9kdb PRIVATE ${XT9KDB_DIR}/include)
target_compile_options(xt9kdb PRIVATE -Wall)
find_library(m-lib m)   # sqrtf for the NKL diagonals (+0x50/+0x60)
target_link_libraries(xt9kdb ${log-lib} ${m-lib})
