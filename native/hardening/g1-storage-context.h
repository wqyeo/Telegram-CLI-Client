// SPDX-License-Identifier: MIT
// Unactivated integration draft. Not an account/client entrypoint or engine acceptance.
#pragma once

#include "binlog-only-policy.h"
#include "td/utils/Status.h"

#include <memory>
#include <mutex>

namespace telegram_cli::native_g1 {
// Deliberately distinct from B1f's component descriptor. No genuine profile may
// use this draft. Final native semantic/actor/API pins still require acceptance.
inline constexpr char CoordinatorDescriptor[] =
    "telegram-cli:g1:coordinator-draft:9\n"
    "binding=opaque-context:client-id:worker-generation:1\n"
    "mint=java-held-directory-identity:native-devino-check-before-effects:1\n"
    "namespace=exclusive-new;files=profile/native-files;existing=no-create\n"
    "seal=after-first-authorization-and-native-sync;pending=no-resume\n"
    "main-dc=canonical-1..1000;new=native-mutex-initialize;existing=required:no-repair:1\n"
    "native-values=permanent-auth256:flags0|1|4|5:sha1-tail-id;primary=authorized;secondary-empty=default16:1\n"
    "native-codecs=salts:count4+24n:finite-int64-times;options:BIS:int64:utf8:1\n"
    "identity=pmc:canonical-positive40;config:required:I+same-id;existing-pre-consumption;seal=capture+recheck:no-repair:1\n"
    "retired-config=constructor-cleanup24:absent;set-sha256=a0cd5a6719bf444e728fa1a2c34ffd2329b164ef360f30b999f3f9d69ae5698b;existing-pre-consumption;seal=capture+recheck:no-repair:1\n"
    "fixed-roles=trusted-testdc:exact5:I+native-ids;existing=required;new=pristine-initial-absence-only;seal=capture+recheck:no-repair:1\n"
    "fixed-role-set-sha256=e9e774776b232224bacea348b077b26e120d46fc2e2c90d9b7619e12cc297a47\n"
    "startup=server-time:current16:finite:native-domain;config-expire:canonical-int32:1\n"
    "recovery=numbered-auth-salt:canonical-dc;auxiliary-not-primary:1\n"
    "sync=caller-reserved-watermark:1;stable-publication-audit=incomplete\n"
    "snapshots=try-exclusive:incarnation-revision;writer-rebind=invalidates:1\n"
    "handoff=binlog-actor:deferred-native-finalization;pending=busy|changed:no-scheduling-policy:1\n"
    "key=raw32;sqlite=absent;personal-account-only;secret-chats=off\n";

class StorageContext final {
 public:
  enum class Intent { New, Existing };
  enum class State { Unbound, Bound, Opening, AwaitingAuthorization, Ready, Stopped };
  StorageContext(const StorageContext &) = delete;
  StorageContext &operator=(const StorageContext &) = delete;

  // Caller has already established trusted ancestors and holds the exclusive
  // ProfileStore lease for the entire context/client lifetime. Never touches
  // profile.lock (including opening a second descriptor). profile_fd is borrowed;
  // retained directory descriptors have independent ownership. No provider calls.
  static td::Result<std::shared_ptr<StorageContext>> create(
      int profile_fd, std::string profile_path, std::string profile_name,
      std::string generation, Intent intent, bool test_dc);

  // Native bridge for the Java-owned lease's one-use snapshot. Compare the
  // opened profile to the attested dev/ino BEFORE create can make any directory.
  // The trusted Java owner must still retain its actual lease through shutdown.
  static td::Result<std::shared_ptr<StorageContext>> create_attested(
      std::string profile_path, std::string profile_name, std::string generation,
      Intent intent, bool test_dc, std::uint64_t expected_device, std::uint64_t expected_inode);

  bool bind_client(std::int32_t client_id);
  td::Status prepare_open(const td::DbKey &key, const std::string &database_directory,
                          const std::string &files_directory, bool test_dc,
                          bool file_cache, bool chat_cache, bool message_cache);
  td::Status finish_open();
  // Native-only handoff: TdDb must validate actual personal-account auth/my_id
  // and complete native force_sync BEFORE this call. Not exposed over JNI/IPC.
  td::Status seal_after_authorization_sync();
  void stop();
  bool private_data_allowed();
  bool validate();
  bool is_new() const { return intent_ == Intent::New; }
  const std::string &generation() const { return generation_; }
  const std::string &database_directory() const { return database_directory_; }
  const std::string &files_directory() const { return files_directory_; }
  std::string binlog_path() const;
  State state() const;

 private:
  StorageContext(detail::Fd profile, detail::Fd files, BinlogOnlyPolicy policy,
                 std::string profile_path, std::string generation, Intent intent,
                 Configuration configuration);
  bool physical_context_valid() const;
  bool record_valid(bool startup_components = true) const;
  int root_fd() const;
  td::Status refuse();

  mutable std::mutex mutex_;
  detail::Fd profile_, files_;
  BinlogOnlyPolicy policy_;
  std::string profile_path_, generation_, database_directory_, files_directory_;
  Intent intent_;
  Configuration configuration_;
  std::optional<NewReservation> reservation_;
  std::optional<ExistingRecord> existing_;
  State state_ = State::Unbound;
  std::int32_t client_id_ = 0;
  bool sealed_ = false;
  bool handle_published_ = false;
  friend std::int64_t publish_context_handle(std::shared_ptr<StorageContext> context);
};

// Native-owned single-use registry, NOT a mutable process-wide default profile.
// The numeric handle is never a pointer/fd/key, and is not a same-process security
// boundary. A native factory under the worker's lease must mint it explicitly.
std::int64_t publish_context_handle(std::shared_ptr<StorageContext> context);
std::shared_ptr<StorageContext> take_context_handle(std::int64_t handle, const std::string &generation);
// True only for a matching still-unbound registry entry stopped by this call.
// False/unknown/taken is NOT evidence that a bound client has stopped.
bool abandon_context_handle(std::int64_t handle, const std::string &generation);
} // namespace telegram_cli::native_g1
