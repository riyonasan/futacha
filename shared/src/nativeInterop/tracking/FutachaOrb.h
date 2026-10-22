#pragma once
#include <stdint.h>
#ifdef _WIN32
#define FUTACHA_ORB_EXPORT __declspec(dllexport)
#else
#define FUTACHA_ORB_EXPORT
#endif
#ifdef __cplusplus
extern "C" {
#endif
// Versioned little-endian payload: ORB1, width, height, count, then x/y floats + 32 bytes per point.
FUTACHA_ORB_EXPORT int futacha_orb_extract(const int8_t *gray, int width, int height, int8_t *output, int capacity);
// -1 means invalid input/native failure, 0 means different, 1 means similar.
FUTACHA_ORB_EXPORT int futacha_orb_match(const int8_t *left, int left_size, const int8_t *right, int right_size);
#ifdef __cplusplus
}
#endif
