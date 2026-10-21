// SPDX-License-Identifier: MIT
// PUBLIC codecs only. Native AuthKey/ServerSalt SERIALIZERS, not account/session actors.
#include "native-value-codec.h"
#include "td/mtproto/AuthKey.h"
#include "td/mtproto/AuthData.h"
#include "td/telegram/UserId.h"
#include "td/utils/tl_helpers.h"
#include <array>
#include <cstdlib>
#include <iostream>
#include <string>
#include <sys/stat.h>
#include <vector>

namespace g1 = telegram_cli::native_g1;
void check(bool value, const char *name) {
  if (!value) { std::cout << "PUBLIC_NATIVE_VALUE_FIXTURE_ERROR\n"; std::exit(70); }
  std::cout << "PUBLIC_VALUE_CHECK " << name << " PASS\n";
}
void put32(std::string &data, std::size_t offset, std::uint32_t value) {
  for (unsigned i = 0; i < 4; ++i) { data[offset + i] = static_cast<char>(value >> (i * 8)); }
}
void put_double(std::string &data, std::size_t offset, double value) {
  std::uint64_t bits;
  std::memcpy(&bits, &value, 8);
  for (unsigned i = 0; i < 8; ++i) { data[offset + i] = static_cast<char>(bits >> (i * 8)); }
}
// Exact archive-matched Global.cpp ServerTimeDiff writer recipe. This is not
// Global::init/Global construction or native account execution.
struct PublicServerTimeDiff {
  double diff, system_time;
  template <class Storer> void store(Storer &storer) const {
    td::store(diff, storer); td::store(system_time, storer);
  }
};
int main() {
  ::umask(0077);
  // Python hashlib.sha1(b'A'*256).digest()[12:20], independent fixed PUBLIC golden ID.
  const std::uint64_t public_id = 0x7df18ae2f37904e7ULL;
  td::mtproto::AuthKey key(public_id, std::string(256, 'A'));
  key.set_auth_flag(true);
  key.set_created_at(1700000000.5);
  auto encoded = td::serialize(key);
  check(encoded.size() == 280 && g1::persisted_auth_key_valid(encoded, true), "native-authorized-primary");
  check(g1::persisted_auth_key_valid(encoded, false), "native-authorized-secondary");
  auto empty = td::serialize(td::mtproto::AuthKey());
  check(empty == std::string(16, '\0') && g1::persisted_auth_key_valid(empty, false), "native-empty-secondary");
  check(!g1::persisted_auth_key_valid(empty, true), "empty-primary-refused");
  key.set_created_at(0);
  auto without_date = td::serialize(key);
  check(without_date.size() == 272 && g1::persisted_auth_key_valid(without_date, true), "native-optional-created-at");
  key.set_auth_flag(false);
  auto unauthorized = td::serialize(key);
  check(g1::persisted_auth_key_valid(unauthorized, false) && !g1::persisted_auth_key_valid(unauthorized, true), "secondary-versus-primary-role");
  bool correct = true;
  for (std::size_t i = 0; i < encoded.size(); ++i) { correct &= !g1::persisted_auth_key_valid(encoded.substr(0, i), false); }
  check(correct && !g1::persisted_auth_key_valid(encoded + "x", false), "all280-truncations-and-extra");
  correct = true;
  for (unsigned bit = 0; bit < 32; ++bit) {
    if (bit == 0 || bit == 2) { continue; }
    auto changed = encoded; put32(changed, 8, 5U | (std::uint32_t{1} << bit));
    correct &= !g1::persisted_auth_key_valid(changed, false);
  }
  check(correct, "all30-unsupported-flags");
  auto changed = encoded; put32(changed, 8, 13); changed.resize(296, '\0');
  check(!g1::persisted_auth_key_valid(changed, false), "temporary-expiry-role-refused");
  correct = true;
  for (unsigned offset : {12U, 13U, 14U, 15U}) {
    changed = encoded; changed[offset] ^= 1; correct &= !g1::persisted_auth_key_valid(changed, false);
  }
  check(correct, "tl-framing-refused");
  correct = true;
  for (std::size_t offset = 0; offset < 8; ++offset) {
    changed = encoded; changed[offset] ^= 1; correct &= !g1::persisted_auth_key_valid(changed, false);
  }
  for (std::size_t offset = 16; offset < 272; ++offset) {
    changed = encoded; changed[offset] ^= 1; correct &= !g1::persisted_auth_key_valid(changed, false);
  }
  check(correct, "all264-key-id-and-key-mismatches");
  correct = true;
  for (std::size_t offset = 0; offset < empty.size(); ++offset) {
    changed = empty; changed[offset] = 1; correct &= !g1::persisted_auth_key_valid(changed, false);
  }
  check(correct, "all16-noncanonical-empty-keys");
  correct = true;
  for (double time : {0.0, std::numeric_limits<double>::infinity(), -std::numeric_limits<double>::infinity(),
                      std::numeric_limits<double>::quiet_NaN(), 0x1p63}) {
    changed = encoded; put_double(changed, 272, time); correct &= !g1::persisted_auth_key_valid(changed, false);
  }
  check(correct, "created-at-invalid-casts");
  changed = encoded; put_double(changed, 272, -0x1p63);
  check(g1::persisted_auth_key_valid(changed, true), "created-at-safe-negative-boundary");

  std::vector<td::mtproto::ServerSalt> salts{{0, -10, -1}, {42, 0, 10}, {-1, 20, 20}};
  auto salt_bytes = td::serialize(salts);
  check(salt_bytes.size() == 76 && g1::persisted_salts_valid(salt_bytes), "native-salts-including-expired");
  auto no_salts = td::serialize(std::vector<td::mtproto::ServerSalt>());
  check(no_salts == std::string(4, '\0') && g1::persisted_salts_valid(no_salts), "native-empty-salt-vector");
  correct = true;
  for (std::size_t i = 0; i < salt_bytes.size(); ++i) { correct &= !g1::persisted_salts_valid(salt_bytes.substr(0, i)); }
  check(correct && !g1::persisted_salts_valid(salt_bytes + "x"), "all76-salt-truncations-and-extra");
  changed = no_salts; put32(changed, 0, 0xffffffffU);
  check(!g1::persisted_salts_valid(changed), "salt-count-bounded-before-allocation");
  correct = true;
  for (double time : {std::numeric_limits<double>::quiet_NaN(), std::numeric_limits<double>::infinity(), 0x1p63}) {
    for (std::size_t offset : {12U, 20U}) {
      changed = salt_bytes; put_double(changed, offset, time); correct &= !g1::persisted_salts_valid(changed);
    }
  }
  check(correct, "salt-nonfinite-and-unsafe-casts");
  changed = salt_bytes; put_double(changed, 12, 1); put_double(changed, 20, -1);
  check(!g1::persisted_salts_valid(changed), "salt-reversed-interval");

  correct = true;
  for (auto value : {"Btrue", "Bfalse", "I0", "I-1", "I9223372036854775807", "I-9223372036854775808", "S", "SPUBLIC"}) {
    correct &= g1::config_option_codec_valid(value);
  }
  check(correct, "native-option-producer-grammar");
  correct = true;
  for (auto value : {"", "BTrue", "B0", "I", "I+1", "I01", "I-0", "I9223372036854775808", "I-9223372036854775809", "I1x", "legacy", "D1"}) {
    correct &= !g1::config_option_codec_valid(value);
  }
  check(correct, "option-noncanonical-and-overflow");
  check(g1::config_option_codec_valid("S\xf0\x9f\x98\x80") &&
        g1::config_option_codec_valid(std::string_view("S\0", 2)), "option-valid-utf8-and-control");
  correct = true;
  for (auto value : {"S\xc2", "S\xc0\x80", "S\xed\xa0\x80", "S\xf4\x90\x80\x80", "S\xc2x"}) {
    correct &= !g1::config_option_codec_valid(value);
  }
  check(correct, "option-invalid-utf8");
  auto clock_bytes = td::serialize(PublicServerTimeDiff{-0.5, 1700000000.5});
  // Independent Python struct.pack('<dd', -0.5, 1700000000.5).
  const std::string clock_golden("\x00\x00\x00\x00\x00\x00\xe0\xbf\x00\x00\x20\x40\xfc\x54\xd9\x41", 16);
  correct = clock_bytes == clock_golden && g1::server_time_difference_valid(clock_bytes);
  for (double diff : {-1e10, 0.0, 1e10}) {
    for (double saved : {0.0, 1e10}) {
      correct &= g1::server_time_difference_valid(td::serialize(PublicServerTimeDiff{diff, saved}));
    }
  }
  check(correct, "current-server-time-recipe-and-boundaries");
  correct = true;
  for (std::size_t i = 0; i < clock_bytes.size(); ++i) { correct &= !g1::server_time_difference_valid(clock_bytes.substr(0, i)); }
  check(correct && !g1::server_time_difference_valid(clock_bytes + "x"), "server-time-all16-truncations-legacy8-and-extra");
  correct = true;
  for (double time : {std::numeric_limits<double>::quiet_NaN(), std::numeric_limits<double>::infinity(),
                      -std::numeric_limits<double>::infinity()}) {
    for (std::size_t offset : {0U, 8U}) {
      changed = clock_bytes; put_double(changed, offset, time); correct &= !g1::server_time_difference_valid(changed);
    }
  }
  for (double diff : {std::nextafter(-1e10, -std::numeric_limits<double>::infinity()),
                      std::nextafter(1e10, std::numeric_limits<double>::infinity())}) {
    correct &= !g1::server_time_difference_valid(td::serialize(PublicServerTimeDiff{diff, 0}));
  }
  for (double saved : {-1.0, std::nextafter(0.0, -1.0), std::nextafter(1e10, std::numeric_limits<double>::infinity())}) {
    correct &= !g1::server_time_difference_valid(td::serialize(PublicServerTimeDiff{0, saved}));
  }
  check(correct, "server-time-nonfinite-and-outside-native-domain");
  correct = true;
  for (auto value : {"0", "-1", "1", "2147483647", "-2147483648", "1700000000"}) { correct &= g1::canonical_int32(value); }
  check(correct, "config-expire-signed32-including-expired");
  correct = true;
  for (auto value : {"", "-0", "+1", "01", " 1", "1 ", "1x", "2147483648", "-2147483649", "9223372036854775807"}) {
    correct &= !g1::canonical_int32(value);
  }
  check(correct, "config-expire-noncanonical-and-overflow");
  using Role = g1::PersistedDcRole;
  correct = true;
  for (int dc = 1; dc <= 1000; ++dc) {
    auto suffix = std::to_string(dc);
    correct &= g1::persisted_dc_role("auth" + suffix, dc) == Role::PrimaryAuth;
    correct &= g1::persisted_dc_role("config_recovery_auth" + suffix, dc) == Role::AuxiliaryAuth;
    correct &= g1::persisted_dc_role("salt" + suffix, dc) == Role::Salt;
    correct &= g1::persisted_dc_role("config_recovery_salt" + suffix, dc) == Role::Salt;
  }
  correct &= g1::persisted_dc_role("auth2", 1) == Role::AuxiliaryAuth;
  for (auto name : {"auth", "auth_state", "auth_is_bot", "main_dc_id", "server_time_difference"}) {
    correct &= g1::persisted_dc_role(name, 1) == Role::Unrelated;
  }
  check(correct, "numbered-dc-key-family-roles");
  correct = true;
  for (auto prefix : {"auth", "salt", "config_recovery_auth", "config_recovery_salt"}) {
    for (auto suffix : {"0", "00", "01", "-1", "+1", "1001", "1x", "1 ", "999999999999999999999"}) {
      correct &= g1::persisted_dc_role(std::string(prefix) + suffix, 1) == Role::Malformed;
    }
  }
  check(correct, "malformed-numbered-dc-family-refused");
  check(g1::persisted_dc_role("config_recovery_auth1", 1) == Role::AuxiliaryAuth &&
        g1::persisted_auth_key_valid(unauthorized, false) && g1::persisted_auth_key_valid(empty, false) &&
        !g1::persisted_auth_key_valid(unauthorized, true) && !g1::persisted_auth_key_valid(empty, true),
        "recovery-is-not-personal-primary-authority");
  constexpr td::int64 max_user_id = 1099511627775LL; // Independent 2^40 - 1 golden.
  check(td::UserId(td::int64{1}).is_valid() && td::UserId(max_user_id).is_valid() &&
        !td::UserId(td::int64{0}).is_valid() && !td::UserId(max_user_id + 1).is_valid() &&
        g1::personal_identity_pair_valid("1", "I1") &&
        g1::personal_identity_pair_valid("1099511627775", "I1099511627775"),
        "native-personal-id-and-mirror-boundaries");
  // Native StringBuilder recipes, not UserManager/OptionManager construction.
  const std::string native_pmc_id = PSTRING() << max_user_id;
  const std::string native_config_id = PSTRING() << 'I' << max_user_id;
  check(native_pmc_id == "1099511627775" && native_config_id == "I1099511627775" &&
        g1::personal_identity_pair_valid(native_pmc_id, native_config_id), "native-personal-id-mirror-producer");
  check(!g1::personal_identity_pair_valid("1", "") && !g1::personal_identity_pair_valid("", "I1") &&
        !g1::personal_identity_pair_valid("", ""), "identity-missing-and-empty-mirror-refused");
  correct = true;
  for (auto mirror : {"Btrue", "Bfalse", "S1", "I0", "I-1", "I2", "I1099511627775"}) {
    correct &= g1::config_option_codec_valid(mirror) && !g1::personal_identity_pair_valid("1", mirror);
  }
  check(correct && !g1::personal_identity_pair_valid("2", "I1"), "identity-grammar-valid-is-not-pair-authority");
  correct = true;
  for (auto id : {"0", "-1", "+1", "01", "1 ", " 1", "1x", "1099511627776", "9999999999999",
                  "18446744073709551615", "user 1"}) {
    correct &= !g1::canonical_personal_user_id(id) && !g1::personal_identity_pair_valid(id, std::string("I") + id);
  }
  check(correct && !g1::canonical_personal_user_id(std::string(4096, '1')), "identity-noncanonical-and-range-refused");
  correct = true;
  for (std::size_t i = 0; i < native_config_id.size(); ++i) {
    correct &= !g1::personal_identity_pair_valid(native_pmc_id, std::string_view(native_config_id).substr(0, i));
  }
  check(correct && !g1::personal_identity_pair_valid(native_pmc_id, native_config_id + "x"),
        "identity-all-mirror-truncations-and-extra");
  correct = true;
  for (const auto &id : {std::string("\0", 1), std::string("1\0", 2), std::string("1\n"),
                         std::string("\xff"), std::string("\xd9\xa1")}) {
    correct &= !g1::canonical_personal_user_id(id) && !g1::personal_identity_pair_valid(id, "I" + id);
  }
  check(correct && !g1::personal_identity_pair_valid("1", std::string_view("I1\0", 3)),
        "identity-binary-and-non-ascii-refused");
  const std::string id_buffer = "xx1yy", mirror_buffer = "zzI1ww";
  const auto id_view = std::string_view(id_buffer).substr(2, 1), mirror_view = std::string_view(mirror_buffer).substr(2, 2);
  check(g1::personal_identity_pair_valid(id_view, mirror_view) &&
        !g1::personal_identity_pair_valid(id_view, std::string_view(mirror_buffer).substr(2, 3)) &&
        id_buffer == "xx1yy" && mirror_buffer == "zzI1ww", "identity-borrowed-subviews-and-no-mutation");
  // Independent test list from frozen OptionManager.cpp:236..259, NOT the helper's table.
  // These are entry/membership leaf checks, NOT actual TdDb phase-guard execution.
  const std::array<std::string_view, 24> retired_names{{
      "archive_and_mute_new_chats_from_unknown_users", "business_intro_title_length_max",
      "business_intro_message_length_max", "channel_custom_accent_color_boost_level_min",
      "chat_filter_count_max", "chat_filter_chosen_chat_count_max", "forum_member_count_min",
      "themed_emoji_statuses_sticker_set_id", "themed_premium_statuses_sticker_set_id",
      "usd_to_1000_star_rate", "1000_star_to_usd_rate", "is_location_visible",
      "gift_resale_earnings_per_mille", "user_rating_learn_more_url", "gift_collection_gift_count_max",
      "story_album_story_count_max", "suggested_post_toncoin_cent_count_min",
      "suggested_post_toncoin_cent_count_max", "suggested_post_toncoin_earnings_per_mille",
      "million_toncoin_to_usd_rate", "gift_resale_toncoin_cent_count_min",
      "gift_resale_toncoin_cent_count_max", "gift_resale_toncoin_earnings_per_mille", "toncoin_top_up_url"}};
  correct = true;
  for (auto name : retired_names) { correct &= g1::retired_config_option_name(name); }
  check(correct && !g1::retired_config_option_name(""), "retired-option-exact24-refused");
  correct = true;
  for (auto name : retired_names) {
    for (auto value : {"Btrue", "Bfalse", "I0", "I-1", "I9223372036854775807", "S", "SPUBLIC"}) {
      correct &= g1::config_option_codec_valid(value) && !g1::config_option_entry_codec_valid(name, value);
    }
  }
  check(correct, "retired-option-entry-values-refused");
  correct = true;
  for (auto name : retired_names) {
    auto uppercase = std::string(name);
    for (auto &c : uppercase) { if (c >= 'a' && c <= 'z') { c = static_cast<char>(c - 'a' + 'A'); } }
    correct &= !g1::retired_config_option_name(uppercase);
    correct &= !g1::retired_config_option_name("x" + std::string(name));
    correct &= !g1::retired_config_option_name(std::string(name) + "x");
    for (std::size_t i = 0; i < name.size(); ++i) { correct &= !g1::retired_config_option_name(name.substr(0, i)); }
  }
  // Near misses test membership ONLY: not an approved unknown-option allowlist.
  check(correct && g1::config_option_entry_codec_valid("my_id", "I1") &&
        g1::config_option_entry_codec_valid("version", "SPUBLIC") &&
        g1::config_option_entry_codec_valid("is_premium", "Bfalse"), "retired-option-near-misses-and-current-entries");
  correct = !g1::config_option_entry_codec_valid("", "I1");
  for (auto name : retired_names) {
    auto binary = std::string(name); binary.push_back('\0'); binary.push_back('x');
    correct &= !g1::retired_config_option_name(binary) && !g1::config_option_entry_codec_valid(binary, "I1");
  }
  for (auto name : {"\xc2", "\xc0\x80", "\xed\xa0\x80", "\xf4\x90\x80\x80"}) {
    correct &= !g1::config_option_entry_codec_valid(name, "I1");
  }
  for (auto value : {"", "I01", "I-0", "BTrue", "S\xed\xa0\x80"}) {
    correct &= !g1::config_option_entry_codec_valid("version", value);
  }
  check(correct, "retired-option-name-framing-preserved");
  correct = true;
  const std::string value_buffer = "xxI1yy";
  for (auto name : retired_names) {
    const std::string buffer = "xx" + std::string(name) + "yy", original = buffer;
    const auto view = std::string_view(buffer).substr(2, name.size());
    correct &= g1::retired_config_option_name(view) && !g1::retired_config_option_name(buffer);
    correct &= !g1::config_option_entry_codec_valid(view, std::string_view(value_buffer).substr(2, 2));
    correct &= buffer == original && value_buffer == "xxI1yy";
  }
  check(correct, "retired-option-borrowed-subviews-and-no-mutation");
  // Independent selected-policy goldens, not the implementation's role table.
  const std::array<std::array<std::string_view, 3>, 5> fixed_roles{{
      {{"telegram_service_notifications_chat_id", "I777000", "I777000"}},
      {{"replies_bot_chat_id", "I1271266957", "I708513"}},
      {{"verification_codes_bot_chat_id", "I489000", "I489000"}},
      {{"group_anonymous_bot_user_id", "I1087968824", "I552888"}},
      {{"channel_bot_user_id", "I136817688", "I936174"}}}};
  correct = true;
  for (bool test_dc : {false, true}) {
    g1::FixedRoleGroup group(test_dc);
    for (const auto &role : fixed_roles) {
      const auto value = role[test_dc ? 2 : 1];
      correct &= g1::fixed_role_option_valid(role[0], value, test_dc);
      group.observe(role[0], value);
    }
    correct &= group.valid();
  }
  check(correct, "fixed-roles-complete-both-trusted-modes");
  correct = true;
  for (bool test_dc : {false, true}) {
    for (unsigned mask = 0; mask < 32; ++mask) {
      g1::FixedRoleGroup group(test_dc);
      for (std::size_t i = 0; i < fixed_roles.size(); ++i) {
        if (mask & (1U << i)) { group.observe(fixed_roles[i][0], fixed_roles[i][test_dc ? 2 : 1]); }
      }
      correct &= group.valid() == (mask == 31);
    }
  }
  check(correct, "fixed-roles-all64-presence-masks");
  correct = true;
  for (bool test_dc : {false, true}) {
    g1::FixedRoleGroup initial(test_dc);
    correct &= initial.valid(false) && !initial.valid();
    initial.observe(fixed_roles[0][0], fixed_roles[0][1]);
    correct &= initial.valid(false) && !initial.valid();
    initial.observe(fixed_roles[1][0], "");
    correct &= !initial.valid(false) && !initial.valid();
  }
  check(correct, "fixed-roles-pristine-initial-absence-not-invalid-present");
  correct = true;
  for (bool test_dc : {false, true}) {
    for (const auto &role : fixed_roles) {
      for (auto bad : {"", "S", "Btrue", "I0", "I-1", "I1", "I+777000", "I0777000",
                       "I777000x", "I9223372036854775808", "I1099511627776"}) {
        correct &= !g1::fixed_role_option_valid(role[0], bad, test_dc);
      }
    }
  }
  check(correct, "fixed-roles-erase-tags-noncanonical-and-wrong-values");
  correct = true;
  for (const auto &role : fixed_roles) {
    if (role[1] != role[2]) {
      correct &= !g1::fixed_role_option_valid(role[0], role[2], false);
      correct &= !g1::fixed_role_option_valid(role[0], role[1], true);
    }
  }
  check(correct, "fixed-roles-mode-mismatch");
  correct = true;
  for (bool test_dc : {false, true}) {
    for (std::size_t i = 0; i < fixed_roles.size(); ++i) {
      for (std::size_t j = 0; j < fixed_roles.size(); ++j) {
        if (i != j) { correct &= !g1::fixed_role_option_valid(fixed_roles[i][0], fixed_roles[j][test_dc ? 2 : 1], test_dc); }
      }
    }
  }
  check(correct, "fixed-roles-no-other-role-or-self-id-substitution");
  correct = true;
  for (bool test_dc : {false, true}) {
    for (const auto &role : fixed_roles) {
      auto value = role[test_dc ? 2 : 1];
      for (std::size_t size = 0; size < value.size(); ++size) {
        correct &= !g1::fixed_role_option_valid(role[0], value.substr(0, size), test_dc);
      }
      std::string binary(value); binary.push_back('\0');
      correct &= !g1::fixed_role_option_valid(role[0], binary, test_dc);
      correct &= !g1::fixed_role_option_valid(role[0], std::string(value) + "x", test_dc);
    }
  }
  check(correct, "fixed-roles-truncation-extra-and-binary-refused");
  correct = g1::fixed_role_option_valid("anti_spam_bot_user_id", "I1", false) &&
            g1::fixed_role_option_valid("my_id", "I1", true);
  for (const auto &role : fixed_roles) {
    correct &= g1::fixed_role_option_valid("x" + std::string(role[0]), "I0", false);
    correct &= g1::fixed_role_option_valid(std::string(role[0]) + "x", "I0", true);
  }
  check(correct, "fixed-roles-exact-names-no-other-option-policy");
  correct = true;
  for (bool test_dc : {false, true}) {
    g1::FixedRoleGroup group(test_dc);
    for (const auto &role : fixed_roles) {
      const std::string name = "xx" + std::string(role[0]) + "yy";
      const std::string value = "xx" + std::string(role[test_dc ? 2 : 1]) + "yy";
      auto name_view = std::string_view(name).substr(2, name.size() - 4);
      auto value_view = std::string_view(value).substr(2, value.size() - 4);
      group.observe(name_view, value_view);
      correct &= g1::fixed_role_option_valid(name_view, value_view, test_dc);
      correct &= name == "xx" + std::string(role[0]) + "yy";
      correct &= value == "xx" + std::string(role[test_dc ? 2 : 1]) + "yy";
    } // Temporary buffers gone: the accumulator must retain no borrowed bytes.
    correct &= group.valid();
  }
  check(correct, "fixed-roles-borrowed-subviews-no-escape-or-mutation");
  correct = true;
  for (bool test_dc : {false, true}) {
    for (const auto &bad_role : fixed_roles) {
      g1::FixedRoleGroup group(test_dc);
      group.observe(bad_role[0], "I0");
      for (const auto &role : fixed_roles) { group.observe(role[0], role[test_dc ? 2 : 1]); }
      correct &= !group.valid() && !group.valid(false);
    }
  }
  check(correct, "fixed-roles-invalid-observation-not-repaired");
  check(g1::passive_config_marker_present_valid("default_reaction_needs_sync", "Bfalse") &&
        g1::config_option_entry_codec_valid("default_reaction_needs_sync", "Bfalse"),
        "passive-reaction-present-exact-Bfalse");
  correct = true;
  for (std::string_view value : {"", "Btrue", "B0", "BFalse", "Bfalsex", "I0", "Sfalse", "Bfalse "}) {
    correct &= !g1::passive_config_marker_present_valid("default_reaction_needs_sync", value) &&
               !g1::config_option_entry_codec_valid("default_reaction_needs_sync", value);
  }
  check(correct, "passive-reaction-present-empty-or-other-encoding-refused");
  check(g1::passive_config_option_write_valid("default_reaction_needs_sync", "") &&
        g1::passive_config_option_write_valid("default_reaction_needs_sync", "Bfalse") &&
        !g1::passive_config_option_write_valid("default_reaction_needs_sync", "Btrue"),
        "passive-reaction-runtime-erase-is-not-original-present-empty");
  // Membership alone refuses: no value inspection, including get().empty().
  check(!g1::passive_pmc_marker_present_valid("top_peers_enabled") &&
        g1::passive_pmc_marker_present_valid("top_peers_enabledx") &&
        g1::passive_pmc_marker_present_valid("xtop_peers_enabled"),
        "passive-top-peers-present-member-refused");
  check(g1::passive_config_marker_present_valid("default_reaction_need_sync", "Btrue") &&
        g1::passive_config_marker_present_valid("xdefault_reaction_needs_sync", "Btrue") &&
        g1::passive_config_marker_present_valid("default_reaction_needs_syncx", "Btrue"),
        "passive-marker-exact-names-no-alias-or-other-schema-policy");
  std::cout << "PUBLIC_NATIVE_VALUES_ACCEPTED account_engine=0\n";
}
