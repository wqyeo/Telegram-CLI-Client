// SPDX-License-Identifier: MIT
// G1 bounded native-value prevalidation. No native parser, actor, logging or provider.
// Local consistency checks are NOT proof of server authorization or all-corruption detection.
#pragma once
#include "native-dc-value.h"
#include "td/utils/crypto.h"
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>
#include <string_view>

namespace telegram_cli::native_g1 {
namespace value_codec {
inline std::uint32_t u32(std::string_view data, std::size_t offset) {
  std::uint32_t value = 0;
  for (unsigned i = 0; i < 4; ++i) { value |= std::uint32_t(static_cast<unsigned char>(data[offset + i])) << (8 * i); }
  return value;
}
inline std::uint64_t u64(std::string_view data, std::size_t offset) {
  std::uint64_t value = 0;
  for (unsigned i = 0; i < 8; ++i) { value |= std::uint64_t(static_cast<unsigned char>(data[offset + i])) << (8 * i); }
  return value;
}
inline double number(std::string_view data, std::size_t offset) {
  static_assert(sizeof(double) == 8 && std::numeric_limits<double>::is_iec559);
  auto bits = u64(data, offset);
  double result;
  std::memcpy(&result, &bits, sizeof(result));
  return result;
}
inline void wipe(unsigned char *data, std::size_t length) {
  volatile unsigned char *owned = data;
  while (length--) { *owned++ = 0; }
}
// AuthDataShared logs these timestamps with int64 casts. Upper bound is exclusive:
// double(INT64_MAX) rounds UP to 2^63, which is not representable as signed int64.
inline bool native_time(double value) {
  return std::isfinite(value) && value >= -0x1p63 && value < 0x1p63;
}
} // namespace value_codec

// Recognized numbered/signed-looking names only; not a complete PMC-name allowlist.
// Fixed auth/auth_state/auth_is_bot and unrelated families are handled by other gates.
enum class PersistedDcRole { Unrelated, Malformed, PrimaryAuth, AuxiliaryAuth, Salt };
inline PersistedDcRole persisted_dc_role(std::string_view name, int main_dc) {
  for (auto prefix : {std::string_view("auth"), std::string_view("salt"),
                      std::string_view("config_recovery_auth"), std::string_view("config_recovery_salt")}) {
    if (name.size() <= prefix.size() || name.substr(0, prefix.size()) != prefix) { continue; }
    auto suffix = name.substr(prefix.size());
    if (!((suffix.front() >= '0' && suffix.front() <= '9') || suffix.front() == '-' || suffix.front() == '+')) { continue; }
    const auto dc = canonical_main_dc(suffix);
    if (dc == 0) { return PersistedDcRole::Malformed; }
    if (prefix == "auth") { return dc == main_dc ? PersistedDcRole::PrimaryAuth : PersistedDcRole::AuxiliaryAuth; }
    if (prefix == "config_recovery_auth") { return PersistedDcRole::AuxiliaryAuth; }
    return PersistedDcRole::Salt;
  }
  return PersistedDcRole::Unrelated;
}

// Shared persisted keys are Session's MAIN (permanent) keys, not PFS temporary keys.
// AuthData::drop_main_auth_key writes default AuthKey(): canonical16zero bytes.
// Empty/unauthorized secondary state is supported; the primary must be nonempty+authorized.
inline bool persisted_auth_key_valid(std::string_view data, bool primary) {
  if (data.size() == 16) {
    if (primary) { return false; }
    for (unsigned char c : data) { if (c != 0) { return false; } }
    return true;
  }
  if (data.size() != 272 && data.size() != 280) { return false; }
  const auto flags = value_codec::u32(data, 8);
  // Only AUTH_FLAG=1 / HAS_CREATED_AT=4. HAS_EXPIRES_AT=8 belongs to temporary
  // handshake keys, whose two-double codec is NOT a supported persisted-main role.
  if ((flags & ~std::uint32_t{5}) != 0 || (primary && (flags & 1) == 0) ||
      data.size() != 272 + ((flags & 4) ? 8U : 0U)) { return false; }
  // Exact native TL string for a256-byte MTProto key; no alternate string framing.
  if (static_cast<unsigned char>(data[12]) != 254 || data[13] != 0 || data[14] != 1 || data[15] != 0) { return false; }
  if (flags & 4) {
    auto created = value_codec::number(data, 272);
    if (!value_codec::native_time(created) || created == 0) { return false; }
  }
  std::array<unsigned char, 20> digest{};
  td::sha1(td::Slice(data.data() + 16, 256), digest.data());
  const bool matches = std::memcmp(data.data(), digest.data() + 12, 8) == 0;
  value_codec::wipe(digest.data(), digest.size());
  return matches;
}

// Present vectors only. Absence is supported by the caller, as is encoded count0.
// Never allocate based on an untrusted count; do NOT reject salts merely for expiry.
inline bool persisted_salts_valid(std::string_view data) {
  if (data.size() < 4 || (data.size() - 4) % 24 != 0 ||
      value_codec::u32(data, 0) != (data.size() - 4) / 24) { return false; }
  for (std::size_t offset = 4; offset < data.size(); offset += 24) {
    const auto since = value_codec::number(data, offset + 8), until = value_codec::number(data, offset + 16);
    if (!value_codec::native_time(since) || !value_codec::native_time(until) || since > until) { return false; }
  }
  return true;
}

inline bool canonical_option_integer(std::string_view data) {
  if (data.empty()) { return false; }
  const bool negative = data.front() == '-';
  if (negative) { data.remove_prefix(1); }
  if (data.empty() || data.size() > 19 || (data.front() == '0' && (data.size() != 1 || negative))) { return false; }
  const std::uint64_t limit = negative ? (std::uint64_t{1} << 63) : ((std::uint64_t{1} << 63) - 1);
  std::uint64_t value = 0;
  for (unsigned char c : data) {
    if (c < '0' || c > '9' || value > (limit - (c - '0')) / 10) { return false; }
    value = value * 10 + (c - '0');
  }
  return true;
}

// UserManager writes canonical positive 40-bit decimal, not its legacy "user N"
// compatibility input. Bound before arithmetic; no allocation or native parser.
inline bool canonical_personal_user_id(std::string_view data) {
  if (data.empty() || data.size() > 13 || data.front() == '0') { return false; }
  constexpr std::uint64_t limit = (std::uint64_t{1} << 40) - 1;
  std::uint64_t value = 0;
  for (unsigned char c : data) {
    if (c < '0' || c > '9') { return false; }
    const auto digit = std::uint64_t(c - '0');
    if (value > (limit - digit) / 10) { return false; }
    value = value * 10 + digit;
  }
  return value > 0;
}

// Selected G1 pair rule: config's integer mirror must be present and EXACTLY
// I + canonical PMC identity. Never synthesize/repair or accept merely BIS grammar.
// Apply to EXISTING before consumption and NEW/EXISTING seal capture + recheck;
// absence during NEW unsealed authentication is not tested by this predicate.
inline bool personal_identity_pair_valid(std::string_view pmc_id, std::string_view config_id) {
  return canonical_personal_user_id(pmc_id) && config_id.size() == pmc_id.size() + 1 &&
         config_id.front() == 'I' && config_id.substr(1) == pmc_id;
}

// ConfigManager::save_config_expire writes signed32 decimal on this Linux target.
// Absence/expired timestamps remain supported; no freshness certificate is imposed.
inline bool canonical_int32(std::string_view data) {
  if (!canonical_option_integer(data)) { return false; }
  std::string_view limit = "2147483647";
  if (data.front() == '-') { data.remove_prefix(1); limit = "2147483648"; }
  return data.size() < limit.size() || (data.size() == limit.size() && data <= limit);
}

// Global's CURRENT ServerTimeDiff writer stores two doubles. Do not accept the
// legacy8-byte compatibility parse or native malformed-data/default-time fallback.
inline bool server_time_difference_valid(std::string_view data) {
  if (data.size() != 16) { return false; }
  const auto diff = value_codec::number(data, 0), saved_system_time = value_codec::number(data, 8);
  return std::isfinite(diff) && std::isfinite(saved_system_time) && diff >= -1e10 && diff <= 1e10 &&
         saved_system_time >= 0 && saved_system_time <= 1e10;
}

// Strict UTF-8 framing (including overlong/surrogate/out-of-Unicode rejection).
// Display/control-character safety is a separate application responsibility.
inline bool value_utf8_valid(std::string_view data) {
  std::size_t offset = 0;
  while (offset < data.size()) {
    unsigned char lead = data[offset++];
    if (lead < 128) { continue; }
    unsigned length = 0;
    std::uint32_t code = 0, minimum = 0;
    if (lead >= 0xc2 && lead <= 0xdf) { length = 1; code = lead & 31; minimum = 0x80; }
    else if (lead >= 0xe0 && lead <= 0xef) { length = 2; code = lead & 15; minimum = 0x800; }
    else if (lead >= 0xf0 && lead <= 0xf4) { length = 3; code = lead & 7; minimum = 0x10000; }
    else { return false; }
    if (data.size() - offset < length) { return false; }
    for (unsigned i = 0; i < length; ++i) {
      unsigned char c = data[offset++];
      if ((c & 0xc0) != 0x80) { return false; }
      code = (code << 6) | (c & 63);
    }
    if (code < minimum || code > 0x10ffff || (code >= 0xd800 && code <= 0xdfff)) { return false; }
  }
  return true;
}

// Selected exact-list format rule: OptionManager's24 constructor-cleanup names
// must be ABSENT before EXISTING consumption and at both seal-validation passes.
// Never delete/rename/synthesize. Exact bytes, not prefixes or normalized names.
inline bool retired_config_option_name(std::string_view name) {
  static constexpr std::array<std::string_view, 24> names{{
      "archive_and_mute_new_chats_from_unknown_users",
      "business_intro_title_length_max",
      "business_intro_message_length_max",
      "channel_custom_accent_color_boost_level_min",
      "chat_filter_count_max",
      "chat_filter_chosen_chat_count_max",
      "forum_member_count_min",
      "themed_emoji_statuses_sticker_set_id",
      "themed_premium_statuses_sticker_set_id",
      "usd_to_1000_star_rate",
      "1000_star_to_usd_rate",
      "is_location_visible",
      "gift_resale_earnings_per_mille",
      "user_rating_learn_more_url",
      "gift_collection_gift_count_max",
      "story_album_story_count_max",
      "suggested_post_toncoin_cent_count_min",
      "suggested_post_toncoin_cent_count_max",
      "suggested_post_toncoin_earnings_per_mille",
      "million_toncoin_to_usd_rate",
      "gift_resale_toncoin_cent_count_min",
      "gift_resale_toncoin_cent_count_max",
      "gift_resale_toncoin_earnings_per_mille",
      "toncoin_top_up_url"}};
  for (auto retired : names) { if (name == retired) { return true; } }
  return false;
}

// Producer grammar only, NOT per-option range/cross-field semantic certification.
// OptionManager writes Btrue/Bfalse, I+int64 or S+UTF8; empty erases the record.
inline bool config_option_codec_valid(std::string_view data) {
  if (data == "Btrue" || data == "Bfalse") { return true; }
  if (data.empty()) { return false; }
  if (data.front() == 'I') { return canonical_option_integer(data.substr(1)); }
  return data.front() == 'S' && value_utf8_valid(data.substr(1));
}

// Five selected fixed roles only. The bool is supplied by an already-validated
// native storage binding, never inferred from an option or the authenticated ID.
// Exact encoded comparison also rejects wrong tags, padding, signs and overflow.
struct FixedRoleOption {
  std::string_view name, production, test;
};
inline constexpr std::array<FixedRoleOption, 5> FixedRoleOptions{{
    {"telegram_service_notifications_chat_id", "I777000", "I777000"},
    {"replies_bot_chat_id", "I1271266957", "I708513"},
    {"verification_codes_bot_chat_id", "I489000", "I489000"},
    {"group_anonymous_bot_user_id", "I1087968824", "I552888"},
    {"channel_bot_user_id", "I136817688", "I936174"}}};

inline bool fixed_role_option_valid(std::string_view name, std::string_view value, bool test_dc) {
  for (const auto &role : FixedRoleOptions) {
    if (name == role.name) { return value == (test_dc ? role.test : role.production); }
  }
  return true; // Unrelated names gain NO semantic/absence policy from this helper.
}

// Owns only a five-bit presence mask and validity flag, never borrowed bytes.
// require_presence=false is ONLY for the explicit pristine NEW initial import;
// EXISTING and both seal passes always require the complete group.
class FixedRoleGroup final {
 public:
  explicit FixedRoleGroup(bool test_dc) : test_dc_(test_dc) {}
  void observe(std::string_view name, std::string_view value) {
    for (std::size_t i = 0; i < FixedRoleOptions.size(); ++i) {
      const auto &role = FixedRoleOptions[i];
      if (name == role.name) {
        present_ |= 1U << i;
        valid_ &= value == (test_dc_ ? role.test : role.production);
        return;
      }
    }
  }
  bool valid(bool require_presence = true) const { return valid_ && (!require_presence || present_ == 31U); }
 private:
  bool test_dc_;
  unsigned present_ = 0;
  bool valid_ = true;
};

// The same entry predicate is used by raw quarantine and borrowed seal views.
// Preserves existing name framing and value grammar; adds ONLY selected absence.
// Other per-option types/ranges, defaults and legacy/cache rules remain OPEN.
inline bool config_option_entry_codec_valid(std::string_view name, std::string_view value) {
  return !name.empty() && name.find('\0') == std::string_view::npos && value_utf8_valid(name) &&
         !retired_config_option_name(name) && config_option_codec_valid(value);
}
} // namespace telegram_cli::native_g1
