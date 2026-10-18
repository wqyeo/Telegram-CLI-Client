// SPDX-License-Identifier: MIT
// PUBLIC low-level context/identity fixtures ONLY. No TdDb, Client, JNI or account engine.
// Calls to seal_after_authorization_sync below SIMULATE the trusted native handoff;
// a header-only fixture is not initialized/authorized account data.
#include "g1-storage-context.h"
#include "native-dc-value.h"
#include "td/db/binlog/Binlog.h"
#include "td/utils/logging.h"
#include <cstdlib>
#include <iostream>
#include <string>
#include <sys/resource.h>

namespace g1 = telegram_cli::native_g1;
// Compile-time leaf checks only, NOT execution of the dispatcher/TdDb initializer.
constexpr bool all_public_dc_values() {
  for (int value = 1; value <= 1000; ++value) {
    char digits[4]{};
    int cursor = 4, remaining = value;
    do { digits[--cursor] = static_cast<char>('0' + remaining % 10); remaining /= 10; } while (remaining);
    if (g1::canonical_main_dc(std::string_view(digits + cursor, 4 - cursor)) != value) { return false; }
  }
  return true;
}
static_assert(all_public_dc_values(), "All 1000 canonical native raw DC values must decode");
static_assert(g1::canonical_main_dc("") == 0 && g1::canonical_main_dc("0") == 0 &&
              g1::canonical_main_dc("01") == 0 && g1::canonical_main_dc("+1") == 0 &&
              g1::canonical_main_dc("-1") == 0 && g1::canonical_main_dc(" 1") == 0 &&
              g1::canonical_main_dc("1 ") == 0 && g1::canonical_main_dc("1\n") == 0 &&
              g1::canonical_main_dc("1001") == 0 && g1::canonical_main_dc("10000") == 0 &&
              g1::canonical_main_dc(std::string_view("1\0", 2)) == 0 &&
              g1::canonical_main_dc("\xff") == 0, "Malformed or out-of-range DC must refuse");
constexpr char Generation[] = "00000000-0000-4000-8000-000000000001";
constexpr char OtherGeneration[] = "00000000-0000-4000-8000-000000000002";

[[noreturn]] void fail() { std::cout << "PUBLIC_CONTEXT_FIXTURE_ERROR\n"; std::exit(70); }
void require(bool value) { if (!value) { fail(); } }
bool okay(td::Status value) { bool result = value.is_ok(); value.ignore(); return result; }

int main(int argc, char **argv) {
  ::umask(0077);
  struct rlimit limit{0, 0};
  require(::setrlimit(RLIMIT_CORE, &limit) == 0 && argc == 3);
  SET_VERBOSITY_LEVEL(VERBOSITY_NAME(WARNING));
  std::string path = argv[1], mode = argv[2];
  require(mode == "wrong-device" || mode == "wrong-inode" || mode == "unsafe-profile" ||
          mode == "namespace-exists" || mode == "existing-missing" || mode == "bad-generation" ||
          mode == "bad-key" || mode == "new-pending" || mode == "seal-hook-simulated" ||
          mode == "runtime-temp-simulated" || mode == "registry-abandon" || mode == "registry-taken" ||
          mode == "files-replaced" || mode == "existing-header-simulated");
  g1::detail::Fd profile(::open(path.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
  require(profile.get() >= 0);
  // The parent exclusively created a disposable PUBLIC root. One caller-only
  // sentinel lock is held throughout; never opens a production profile.lock.
  g1::detail::Fd lease(::openat(profile.get(), "PUBLIC_LEASE", O_RDWR | O_NOFOLLOW | O_CLOEXEC));
  struct flock lock{};
  lock.l_type = F_WRLCK; lock.l_whence = SEEK_SET;
  require(lease.get() >= 0 && ::fcntl(lease.get(), F_SETLK, &lock) == 0);
  struct stat attributes{};
  require(::fstat(profile.get(), &attributes) == 0);
  auto device = static_cast<std::uint64_t>(attributes.st_dev);
  auto inode = static_cast<std::uint64_t>(attributes.st_ino);
  auto intent = mode == "existing-missing" ? g1::StorageContext::Intent::Existing : g1::StorageContext::Intent::New;
  auto created = g1::StorageContext::create_attested(path, "PUBLIC", mode == "bad-generation" ? "INVALID" : Generation,
      intent, false, device ^ (mode == "wrong-device" ? 1U : 0U), inode ^ (mode == "wrong-inode" ? 1U : 0U));
  bool initial_refusal = mode == "wrong-device" || mode == "wrong-inode" || mode == "unsafe-profile" ||
      mode == "namespace-exists" || mode == "existing-missing" || mode == "bad-generation";
  if (initial_refusal) {
    require(created.is_error()); created.move_as_error().ignore();
  } else {
    require(created.is_ok());
    auto context = created.move_as_ok();
    if (mode == "registry-abandon" || mode == "registry-taken") {
      auto handle = g1::publish_context_handle(context);
      require(handle > 0 && g1::publish_context_handle(context) == 0);
      require(!g1::abandon_context_handle(handle, OtherGeneration));
      if (mode == "registry-abandon") {
        require(g1::abandon_context_handle(handle, Generation));
        require(context->state() == g1::StorageContext::State::Stopped);
        require(!g1::take_context_handle(handle, Generation));
      } else {
        auto taken = g1::take_context_handle(handle, Generation);
        require(taken == context && taken->bind_client(7));
        require(!g1::abandon_context_handle(handle, Generation));
        require(taken->state() == g1::StorageContext::State::Bound);
        taken->stop();
      }
      require(!g1::abandon_context_handle(handle, Generation));
    } else {
      require(context->bind_client(7));
      auto key = mode == "bad-key" ? td::DbKey::raw_key("") : td::DbKey::raw_key(std::string(32, 'A'));
      auto opened = context->prepare_open(key, context->database_directory(), context->files_directory(), false, false, false, false);
      if (mode == "bad-key") {
        require(!okay(std::move(opened)) && context->state() == g1::StorageContext::State::Stopped);
      } else {
        require(okay(std::move(opened)) && okay(context->finish_open()));
        require(!context->private_data_allowed());
        auto pending = g1::StorageContext::create_attested(path, "PUBLIC", Generation,
            g1::StorageContext::Intent::Existing, false, device, inode);
        require(pending.is_error()); pending.move_as_error().ignore();
        if (mode != "new-pending") {
          // SIMULATED only: this fixture never initializes auth/my_id/config/account records.
          require(okay(context->seal_after_authorization_sync()) && context->private_data_allowed());
          if (mode == "runtime-temp-simulated") {
            auto temporary = path + "/native-g1/native/td.binlog.new";
            g1::detail::Fd fd(::open(temporary.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600));
            constexpr char Marker[] = "PUBLIC_TEMPORARY_STORAGE_WINDOW";
            require(fd.get() >= 0 && ::write(fd.get(), Marker, sizeof(Marker) - 1) == static_cast<ssize_t>(sizeof(Marker) - 1));
            // Tests classification only, NOT a real compaction/ownership certification.
            require(context->private_data_allowed());
            auto reopening = g1::StorageContext::create_attested(path, "PUBLIC", Generation,
                g1::StorageContext::Intent::Existing, false, device, inode);
            require(reopening.is_error()); reopening.move_as_error().ignore();
          } else if (mode == "files-replaced") {
            auto files = path + "/native-files";
            require(::rename(files.c_str(), (path + "/PUBLIC_FILES_HELD").c_str()) == 0);
            require(::mkdir(files.c_str(), 0700) == 0 && !context->private_data_allowed());
            require(::rename(files.c_str(), (path + "/PUBLIC_FILES_REPLACEMENT").c_str()) == 0);
            require(::rename((path + "/PUBLIC_FILES_HELD").c_str(), files.c_str()) == 0);
            require(!context->validate() && !context->private_data_allowed());
          } else if (mode == "existing-header-simulated") {
            context->stop();
            auto reopened = g1::StorageContext::create_attested(path, "PUBLIC", Generation,
                g1::StorageContext::Intent::Existing, false, device, inode);
            require(reopened.is_ok());
            auto existing = reopened.move_as_ok();
            require(existing->bind_client(8));
            require(okay(existing->prepare_open(td::DbKey::raw_key(std::string(32, 'A')),
                existing->database_directory(), existing->files_directory(), false, false, false, false)));
            td::Binlog verifier;
            int callbacks = 0;
            require(okay(verifier.init(existing->binlog_path(), [&](const td::BinlogEvent &) { callbacks++; },
                                      td::DbKey::raw_key(std::string(32, 'A')))));
            require(callbacks == 0 && okay(verifier.close()));
            require(okay(existing->finish_open()) && okay(existing->seal_after_authorization_sync()));
            require(existing->private_data_allowed());
            existing->stop();
          }
        }
        context->stop();
        require(!context->private_data_allowed());
      }
    }
  }
  std::cout << "PUBLIC_CONTEXT_ACCEPTED case=" << mode << " account_engine=0\n";
  return 0;
}
