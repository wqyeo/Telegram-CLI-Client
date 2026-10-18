// SPDX-License-Identifier: MIT
// G1 schema rule: explicit canonical native raw DC, never an inferred default.
#pragma once

#include <cstdint>
#include <string_view>

namespace telegram_cli::native_g1 {
// Zero is refusal, not permission to select a native/platform default.
constexpr std::int32_t canonical_main_dc(std::string_view encoded) {
  if (encoded.empty() || encoded.size() > 4 || encoded.front() == '0') { return 0; }
  std::int32_t value = 0;
  for (unsigned char c : encoded) {
    if (c < '0' || c > '9') { return 0; }
    value = value * 10 + (c - '0');
  }
  return value >= 1 && value <= 1000 ? value : 0;
}
} // namespace telegram_cli::native_g1
