// SPDX-License-Identifier: MIT
// Disposable PUBLIC contexts only. No TdDb/account/client/provider/real profile execution.
#include "binlog-only-policy.h"
#include "td/db/binlog/Binlog.h"
#include "td/utils/logging.h"

#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <iostream>

namespace g1 = telegram_cli::native_g1;
static unsigned binlog_opens = 0;
extern "C" int __real_open64(const char *, int, ...);
extern "C" int __wrap_open64(const char *path, int flags, ...) {
  std::string_view p(path ? path : "");
  if ((p.size() >= 10 && p.substr(p.size() - 10) == "/td.binlog") ||
      (p.size() >= 15 && p.substr(p.size() - 15) == "/td_test.binlog")) { ++binlog_opens; }
  if (flags & O_CREAT) {
    va_list args; va_start(args, flags); auto mode = va_arg(args, int); va_end(args);
    return __real_open64(path, flags, mode);
  }
  return __real_open64(path, flags);
}
static void require(bool ok) {
  if (!ok) { std::cerr << "PUBLIC_POLICY_FIXTURE_ERROR\n"; std::exit(70); }
}
static void directory(const std::string &p) { require(::mkdir(p.c_str(), 0700) == 0); }
static void file(const std::string &p, std::string_view bytes) {
  g1::detail::Fd fd(::open(p.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600));
  require(fd.get() >= 0 && g1::detail::write_bytes(fd.get(),
      reinterpret_cast<const unsigned char *>(bytes.data()), bytes.size()));
}
static std::string read_file(const std::string &p) {
  g1::detail::Fd fd(::open(p.c_str(), O_RDONLY | O_NOFOLLOW | O_CLOEXEC));
  require(fd.get() >= 0);
  char bytes[1024]; auto n = ::read(fd.get(), bytes, sizeof(bytes));
  require(n >= 0 && n < static_cast<ssize_t>(sizeof(bytes)));
  return std::string(bytes, static_cast<std::size_t>(n));
}
static int open_dir(const std::string &p) {
  int fd = ::open(p.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
  require(fd >= 0); return fd;
}
template <class Bytes> static std::string hex(const Bytes &bytes) {
  constexpr char digits[] = "0123456789abcdef";
  std::string out;
  for (unsigned char b : bytes) { out += digits[b >> 4]; out += digits[b & 15]; }
  return out;
}
static void seed(const std::string &space, std::string_view mutation, bool native) {
  g1::detail::Fd parent(open_dir(space));
  auto policy = g1::BinlogOnlyPolicy::bind(parent.get(), space, "PUBLIC-profile", false);
  require(policy.has_value());
  auto config = policy->expected_configuration(); require(config.has_value());
  if (mutation == "record-foreign-id") { config->store_id[0] ^= 1; }
  if (mutation == "record-foreign-layout") { config->schema_id[0] ^= 1; }
  if (mutation == "record-cache-flags") { config->flags = g1::FileDatabase; }
  if (mutation == "record-dc-mismatch") { config->flags = g1::TestDc; }
  auto cap = g1::NewReservation::reserve(parent.get(), g1::NativeSlotName, *config);
  require(cap.has_value());
  if (native) {
    require(td::Binlog::create_new_encrypted(*cap, td::DbKey::raw_key(std::string(32, 'A'))).is_ok());
  } else {
    file(space + "/native/" + ((config->flags & g1::TestDc) ? "td_test.binlog" : "td.binlog"),
         "PUBLIC_NON_DATABASE_FIXTURE");
    if (config->flags & g1::FileDatabase) { file(space + "/native/db.sqlite", "PUBLIC_NON_DATABASE_FIXTURE"); }
  }
  // PUBLIC component metadata only, not intrinsic native version/config certification.
  require(cap->commit_configuration());
}

int main(int argc, char **argv) {
  ::umask(0077);
  SET_VERBOSITY_LEVEL(VERBOSITY_NAME(WARNING));
  require(argc == 3);
  std::string mode(argv[1]), folder(argv[2]);
  require(folder.rfind("/stage/cases/policy-", 0) == 0 && folder.find("/..") == std::string::npos);
  const std::string profile = folder + "/PUBLIC-profile", space = profile + "/native-g1";
  bool relocating_profile = mode == "record-moved-profile";
  bool relocating_space = mode == "record-moved-namespace";
  if (!relocating_profile) { directory(profile); }
  if (!relocating_profile && !relocating_space) { directory(space); }
  if (mode == "record-cloned" || relocating_profile || relocating_space) {
    directory(folder + "/seed"); directory(folder + "/seed/PUBLIC-profile");
    const std::string original = folder + "/seed/PUBLIC-profile/native-g1";
    directory(original); seed(original, "", false);
    if (relocating_profile) { require(::rename((folder + "/seed/PUBLIC-profile").c_str(), profile.c_str()) == 0); }
    else if (relocating_space) { require(::rename(original.c_str(), space.c_str()) == 0); }
    else {
      directory(space + "/native");
      for (auto name : {"g1-admission", "td.binlog"}) { file(space + "/native/" + name, read_file(original + "/native/" + name)); }
    }
  } else if (mode.rfind("record-", 0) == 0 || mode == "native-existing" || mode == "native-wrong-key" ||
             mode == "native-password-refused") {
    seed(space, mode, mode.rfind("native-", 0) == 0);
  }
  if (mode == "record-missing") { require(::unlink((space + "/native/g1-admission").c_str()) == 0); }
  if (mode == "record-partial") { require(::truncate((space + "/native/g1-admission").c_str(), 112) == 0); }
  if (mode == "record-sqlite") { file(space + "/native/db.sqlite", "PUBLIC_NON_DATABASE_FIXTURE"); }
  if (mode == "record-remnant") { file(space + "/native/td.binlog.new", "PUBLIC_REPLACEMENT_EVIDENCE"); }
  if (mode == "identity-only-unknown") { file(space + "/PUBLIC_UNKNOWN", "PUBLIC_NOT_ADMITTED"); }

  g1::detail::Fd supplied(open_dir(space));
  int input_fd = supplied.get();
  std::string path = space, name = "PUBLIC-profile";
  if (mode == "fd-invalid") { input_fd = -1; }
  if (mode == "fd-other") { supplied = g1::detail::Fd(open_dir(profile)); input_fd = supplied.get(); }
  if (mode == "fd-file") {
    file(folder + "/PUBLIC-file", "PUBLIC");
    supplied = g1::detail::Fd(::open((folder + "/PUBLIC-file").c_str(), O_RDONLY | O_CLOEXEC));
    require(supplied.get() >= 0); input_fd = supplied.get();
  }
  if (mode == "name-invalid") { name = "-PUBLIC"; }
  if (mode == "name-mismatch") { name = "PUBLIC-other"; }
  if (mode == "path-relative") { path = path.substr(1); }
  if (mode == "path-dotdot") { path = profile + "/../PUBLIC-profile/native-g1"; }
  if (mode == "path-double") { path = profile + "//native-g1"; }
  if (mode == "path-trailing") { path += '/'; }
  if (mode == "path-nul") { path += '\0'; path += "PUBLIC"; }
  if (mode == "profile-mode") { require(::chmod(profile.c_str(), 0750) == 0); }
  if (mode == "namespace-mode") { require(::chmod(space.c_str(), 0750) == 0); }
  if (mode == "profile-symlink") {
    require(::rename(profile.c_str(), (folder + "/PUBLIC_RETAINED_PROFILE").c_str()) == 0);
    require(::symlink("PUBLIC_RETAINED_PROFILE", profile.c_str()) == 0);
  }
  if (mode == "namespace-symlink") {
    require(::rename(space.c_str(), (profile + "/PUBLIC_RETAINED_NAMESPACE").c_str()) == 0);
    require(::symlink("PUBLIC_RETAINED_NAMESPACE", space.c_str()) == 0);
  }
  std::cout << "PUBLIC_POLICY_READY\n" << std::flush;
  char control[9]{};
  require(static_cast<bool>(std::cin.read(control, sizeof(control))) && std::memcmp(control, "CONTINUE\n", 9) == 0);
  binlog_opens = 0;
  unsigned callbacks = 0;
  auto refuse = [&](const char *reason) {
    std::cout << "PUBLIC_POLICY_REFUSED reason=" << reason << " opens=" << binlog_opens << " callbacks=" << callbacks << '\n';
    return 20;
  };
  auto policy = g1::BinlogOnlyPolicy::bind(input_fd, path, name, mode == "test-dc");
  if (!policy) { return refuse("CONTEXT"); }
  if (mode == "caller-fd-closed") { supplied = g1::detail::Fd(); }
  if (mode == "moved-capability") {
    auto moved = std::move(*policy);
    require(!policy->validate() && !policy->expected_configuration() && policy->namespace_fd() == -1);
    *policy = std::move(moved);
  }
  if (mode == "late-namespace-moved" || mode == "late-namespace-replaced") {
    require(::rename(space.c_str(), (profile + "/PUBLIC_RETAINED_NAMESPACE").c_str()) == 0);
    if (mode == "late-namespace-replaced") { directory(space); }
  }
  if (mode == "late-profile-replaced") {
    require(::rename(profile.c_str(), (folder + "/PUBLIC_RETAINED_PROFILE").c_str()) == 0);
    directory(profile); directory(space);
  }
  if (mode == "late-namespace-mode") { require(::chmod(space.c_str(), 0750) == 0); }
  auto config = policy->expected_configuration();
  if (!config || policy->namespace_fd() < 0) { return refuse("CONTEXT"); }
  if (mode == "reopen") {
    auto again = g1::BinlogOnlyPolicy::bind(input_fd, path, name, false); require(again.has_value());
    auto other = again->expected_configuration();
    require(other && other->store_id == config->store_id && other->schema_id == config->schema_id);
  }
  td::DbKey key = td::DbKey::raw_key(std::string(32, 'A'));
  if (mode == "key-empty") { key = td::DbKey::empty(); }
  if (mode == "key-empty-raw") { key = td::DbKey::raw_key(""); }
  if (mode == "key-short") { key = td::DbKey::raw_key(std::string(31, 'A')); }
  if (mode == "key-long") { key = td::DbKey::raw_key(std::string(33, 'A')); }
  if (mode == "key-password" || mode == "native-password-refused") { key = td::DbKey::password(std::string(32, 'A')); }
  if (mode == "key-stock-empty-fallback") { key = td::DbKey::raw_key("cucumber"); }
  if (mode == "native-wrong-key") { key = td::DbKey::raw_key(std::string(32, 'B')); }
  if (!policy->accepts_key(key)) { return refuse("KEY"); }
  if (mode == "key-independent") {
    require(policy->accepts_key(td::DbKey::raw_key(std::string(32, 'B'))));
    auto other = policy->expected_configuration(); require(other && other->store_id == config->store_id);
  }
  if (mode == "header-roundtrip") {
    auto cap = g1::NewReservation::reserve(policy->namespace_fd(), g1::NativeSlotName, *config);
    require(cap && td::Binlog::create_new_encrypted(*cap, std::move(key)).is_ok());
    require(policy->validate() && cap->commit_configuration());
    key = td::DbKey::raw_key(std::string(32, 'A'));
  }
  if (mode.rfind("record-", 0) == 0 || mode.rfind("native-", 0) == 0 || mode == "header-roundtrip") {
    auto record = g1::ExistingRecord::open(policy->namespace_fd(), g1::NativeSlotName, *config);
    if (!record) { return refuse("RECORD"); }
    if (mode.rfind("native-", 0) == 0 || mode == "header-roundtrip") {
      td::Binlog binlog;
      auto status = binlog.init(space + "/native/td.binlog", [&](const td::BinlogEvent &) { ++callbacks; }, std::move(key));
      if (status.is_error()) { return refuse("BINLOG"); }
      require(binlog.close().is_ok());
    }
  }
  std::cout << "PUBLIC_POLICY_ACCEPTED id=" << hex(config->store_id) << " schema=" << hex(config->schema_id)
            << " flags=" << config->flags << " opens=" << binlog_opens << " callbacks=" << callbacks << '\n';
  return 0;
}
