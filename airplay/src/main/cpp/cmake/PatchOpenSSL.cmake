# Adapted from jqssun/android-airplay-server v0.0.36 (a46cca0); modified by GlassOS Aerial, 2026-10-25
# Original work by jqssun and contributors, licensed under the GNU GPL v3 (see THIRD_PARTY_NOTICES.md).
set(OPENSSL_PATCH_COMMAND PATCH_COMMAND
    ${CMAKE_COMMAND}
    -DTARGET=util/mkbuildinf.pl
    -P ${CMAKE_CURRENT_LIST_DIR}/scrub_mkbuildinf.cmake
)
