// SPDX-License-Identifier: MIT
// Telegram-CLI experimental trusted expected policy; not a storage/client entrypoint.
#pragma once

#include "store-admission.h"
#include "td/db/DbKey.h"

namespace telegram_cli::native_g1 {
inline constexpr char NativeNamespaceName[] = "native-g1";
inline constexpr char NativeSlotName[] = "native";
inline constexpr std::uint32_t BinlogLayoutSchemaVersion = 1; // App policy, NOT SQLite db version.

// Public, compiled recipe. Hashing this descriptor does not validate native/account data.
inline constexpr char BinlogLayoutDescriptor[] =
    "telegram-cli:g1:binlog-only-layout:1\n"
    "record=TGCG1ADM:1:112:120;layout-policy=1;schema-policy=1\n"
    "namespace=native-g1;slot=native;record-name=g1-admission\n"
    "components=td.binlog|td_test.binlog;sqlite-caches=0,0,0;dc=trusted-bit8\n"
    "identity=sha256-first16:profile-path-dev-ino:namespace-path-dev-ino:v1\n"
    "key=raw:32;empty-key-fallback=forbidden;alternate-key=forbidden\n"
    "tdlib=a94b83d67ab4c8397c6245e541b31a4131ea2f79\n"
    "b1b=2fb5c5caa744e4e398b2a718ef2014e5b82fa6c66cee55d8ba71cb6c10fa4fcc\n"
    "b1c=9870eae3f971035f81399df4a79dcb8863cb609b291b68030e0489f726e7dbdf\n"
    "b1e=acddad1cc9c33b7e0475e943749282de7eb3ab3ea843d90d16f4c143b08daba7\n";
inline constexpr char StoreIdentityDomain[] = "telegram-cli:g1:filesystem-store-id:1\n";

// Caller must hold the exclusive profile lease, establish trusted ancestors and
// supply independent context, never values decoded from g1-admission. Path/device/inode binding is
// intentionally non-portable, not a signature, same-user/root or rollback defense.
class BinlogOnlyPolicy {
 public:
  BinlogOnlyPolicy(const BinlogOnlyPolicy &) = delete;
  BinlogOnlyPolicy &operator=(const BinlogOnlyPolicy &) = delete;
  BinlogOnlyPolicy(BinlogOnlyPolicy &&) noexcept = default;
  BinlogOnlyPolicy &operator=(BinlogOnlyPolicy &&) noexcept = default;

  static std::optional<BinlogOnlyPolicy> bind(int namespace_fd, std::string_view namespace_path,
                                            std::string_view profile_name, bool expected_test_dc);
  bool validate() const;
  std::optional<Configuration> expected_configuration() const;
  bool accepts_key(const td::DbKey &key) const;
  // Borrowed fd, valid only while this policy/lease lives. No NEW intent is minted.
  int namespace_fd() const;

 private:
  BinlogOnlyPolicy(detail::Fd profile, detail::Fd space, std::string path, std::string name,
                   const Configuration &config)
      : profile_(std::move(profile)), namespace_(std::move(space)), path_(std::move(path)),
        name_(std::move(name)), config_(config) {}
  detail::Fd profile_, namespace_;
  std::string path_, name_;
  Configuration config_;
};
} // namespace telegram_cli::native_g1
