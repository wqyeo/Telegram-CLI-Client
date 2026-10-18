// PUBLIC-only encrypted-binlog NEW formatter tests. No Client, TdDb or account.
#include "td/db/binlog/Binlog.h"
#include "store-admission.h"
#include <algorithm>
#include <cerrno>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <iostream>
#include <string>
#include <unistd.h>

namespace g1 = telegram_cli::native_g1;
namespace {
std::string fault;
bool armed = false;
int hits = 0;
bool suffix(const std::string &s, const std::string &end) {
  return s.size() >= end.size() && s.compare(s.size() - end.size(), end.size(), end) == 0;
}
bool main_fd(int fd) {
  char name[64]{}, target[2048]{};
  std::snprintf(name, sizeof(name), "/proc/self/fd/%d", fd);
  auto n = ::readlink(name, target, sizeof(target) - 1);
  if (n < 0) return false;
  std::string path(target, static_cast<std::size_t>(n));
  return suffix(path, "/td.binlog") || suffix(path, "/td_test.binlog");
}
void hit() {
  ++hits;
  std::cerr << "PUBLIC_NEW_FAULT=" << fault << '\n';
}
bool put(int root, const char *name, const char *bytes = "PUBLIC_EXISTING_EVIDENCE") {
  g1::detail::Fd fd(::openat(root, name, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, g1::PrivateFileMode));
  return fd.get() >= 0 && g1::detail::write_bytes(fd.get(), reinterpret_cast<const unsigned char *>(bytes), std::strlen(bytes));
}
td::DbKey key(bool password = false, char value = 'A') {
  return password ? td::DbKey::password(std::string(32, value)) : td::DbKey::raw_key(std::string(32, value));
}
bool gate() {
  std::cout << "PUBLIC_NEW_READY\n" << std::flush;
  char command[16]{};
  return static_cast<bool>(std::cin.getline(command, sizeof(command))) && std::strcmp(command, "CONTINUE") == 0;
}
int refused(const std::string &reason) {
  std::cout << "PUBLIC_NEW_REFUSED reason=" << reason << " hits=" << hits << '\n';
  return 20;
}
} // namespace

// Fixture-link only. No preload, production hook or genuine input.
extern "C" int __real_open64(const char *, int, ...);
extern "C" ssize_t __real_write(int, const void *, size_t);
extern "C" ssize_t __real_read(int, void *, size_t);
extern "C" int __real_fsync(int);
extern "C" int __wrap_open64(const char *path, int flags, ...) {
  int mode = 0;
  if (flags & O_CREAT) {
    va_list args;
    va_start(args, flags);
    mode = va_arg(args, int);
    va_end(args);
  }
  if (armed && fault == "open-failure" && (flags & O_EXCL) &&
      std::strncmp(path, "/proc/self/fd/", 14) == 0) {
    hit(); errno = EIO; return -1;
  }
  return __real_open64(path, flags, mode);
}
extern "C" ssize_t __wrap_write(int fd, const void *data, size_t size) {
  if (armed && main_fd(fd) && (fault == "write-failure" || fault == "partial-write" || fault == "zero-write")) {
    hit();
    if (fault == "partial-write" && hits == 1) return __real_write(fd, data, std::min(size, size_t{8}));
    if (fault == "zero-write") return 0;
    errno = EIO; return -1;
  }
  return __real_write(fd, data, size);
}
extern "C" ssize_t __wrap_read(int fd, void *data, size_t size) {
  if (armed && fault == "readback-failure" && main_fd(fd)) { hit(); errno = EIO; return -1; }
  return __real_read(fd, data, size);
}
extern "C" int __wrap_fsync(int fd) {
  if (armed && fault == "sync-failure" && main_fd(fd)) { hit(); errno = EIO; return -1; }
  return __real_fsync(fd);
}

int main(int argc, char **argv) {
  ::umask(S_IRWXG | S_IRWXO);
  // Fixture-only deterministic stderr: retain WARNING/ERROR/FATAL, omit INFO/DEBUG.
  SET_VERBOSITY_LEVEL(VERBOSITY_NAME(WARNING));
  if (argc != 3) return 21;
  const std::string mode(argv[1]), folder(argv[2]);
  if (folder.rfind("/stage/cases/new-", 0) != 0 || folder.find("..") != std::string::npos) return 21;
  g1::detail::Fd case_fd(::open(folder.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
  g1::detail::Fd parent(::openat(case_fd.get(), "namespace", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
  if (case_fd.get() < 0 || parent.get() < 0) return 21;
  g1::Configuration config{};
  config.store_id.fill(0x50); config.schema_id.fill(0x53); config.schema_version = 77; // PUBLIC, not production policy.
  config.flags = mode == "healthy-test-dc" ? g1::TestDc : (mode == "cache-flags" ? 7U : 0U);
  const char *main_name = config.flags & g1::TestDc ? "td_test.binlog" : "td.binlog";
  if (mode == "existing-slot" || mode == "partial-slot") {
    if (::mkdirat(parent.get(), "native", S_IRWXU) != 0) return 21;
    if (mode == "partial-slot") {
      g1::detail::Fd old(::openat(parent.get(), "native", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
      if (!put(old.get(), "PUBLIC_PARTIAL")) return 21;
    }
  }
  auto reservation = g1::NewReservation::reserve(parent.get(), "native", config);
  if (!reservation) {
    if (!gate()) return 21;
    return refused("PUBLIC_RESERVATION_REFUSED");
  }
  const int root = reservation->root_fd();
  if (mode == "main-file" && !put(root, main_name)) return 21;
  if (mode == "main-symlink" && ::symlinkat("PUBLIC_MISSING_TARGET", root, main_name) != 0) return 21;
  if (mode == "main-directory" && ::mkdirat(root, main_name, S_IRWXU) != 0) return 21;
  if (mode == "main-fifo" && ::mkfifoat(root, main_name, g1::PrivateFileMode) != 0) return 21;
  if (mode == "replacement" && !put(root, "td.binlog.new")) return 21;
  if (mode == "sqlite" && !put(root, "db.sqlite")) return 21;
  if (mode == "sqlite-wal" && !put(root, "db.sqlite-wal")) return 21;
  if (mode == "unknown-root" && !put(root, "PUBLIC_UNKNOWN")) return 21;
  if (mode == "unknown-parent" && !put(parent.get(), "PUBLIC_UNKNOWN")) return 21;
  if (mode == "parent-mode" && ::fchmod(parent.get(), S_IRWXU | S_IRGRP) != 0) return 21;
  if (mode == "root-mode" && ::fchmod(root, S_IRWXU | S_IRGRP) != 0) return 21;
  if (mode == "record-mode" && ::fchmodat(root, g1::RecordName, g1::PrivateFileMode | S_IRGRP, 0) != 0) return 21;
  if (mode == "renamed-root" && ::renameat(parent.get(), "native", parent.get(), "PUBLIC_RETAINED") != 0) return 21;
  if (mode == "record-link" && ::linkat(root, g1::RecordName, case_fd.get(), "PUBLIC_RECORD_LINK", 0) != 0) return 21;
  if (mode == "record-mutation") {
    g1::detail::Fd record(::openat(root, g1::RecordName, O_WRONLY | O_NOFOLLOW | O_CLOEXEC));
    if (record.get() < 0 || ::pwrite(record.get(), "X", 1, 0) != 1) return 21;
  }
  if (mode == "already-sealed") {
    if (td::Binlog::create_new_encrypted(*reservation, key()).is_error() || !reservation->commit_configuration()) return 21;
  }
  if (!gate()) return 21;
  fault = mode;
  armed = true;
  const bool password = mode == "healthy-password";
  auto requested = (mode == "empty-key" || mode == "failed-then-filled") ? td::DbKey::empty() :
      (mode == "short-key" ? td::DbKey::raw_key("PUBLIC_SHORT_KEY") : key(password));
  auto result = td::Binlog::create_new_encrypted(*reservation, std::move(requested));
  armed = false;
  if (result.is_error()) {
    auto reason = result.message().str();
    if (td::Binlog::create_new_encrypted(*reservation, key()).is_ok()) return 21; // Never retry a failed capability.
    if (mode == "failed-then-filled" && !put(root, main_name, "PUBLIC_FORMAT_FAILURE_EVIDENCE")) return 21;
    if (reservation->commit_configuration()) return 21;
    return refused(reason);
  }
  if (mode == "fail-after-format") {
    reservation->fail_formatting();
    if (reservation->commit_configuration()) return 21;
    return refused("PUBLIC_FORMAT_FAILURE_LATCHED");
  }
  if (mode == "repeat-success" && td::Binlog::create_new_encrypted(*reservation, key()).is_ok()) return 21;
  if (!reservation->commit_configuration()) return 21;
  auto admitted = g1::ExistingRecord::open(parent.get(), "native", config);
  if (!admitted) return 21;
  const std::string path = folder + "/namespace/native/" + main_name;
  td::Binlog writer;
  int replayed = 0;
  if (writer.init(path, [&](const td::BinlogEvent &) { replayed++; }, key(password)).is_error() || replayed != 0) return 21;
  writer.add(77, td::EmptyStorer());
  if (writer.close().is_error()) return 21;
  td::Binlog reader;
  bool event_ok = true;
  if (reader.init(path, [&](const td::BinlogEvent &event) { ++replayed; event_ok &= event.type_ == 77; }, key(password)).is_error() ||
      replayed != 1 || !event_ok || reader.close().is_error()) return 21;
  td::Binlog wrong_key;
  int wrong_callbacks = 0;
  if (wrong_key.init(path, [&](const td::BinlogEvent &) { wrong_callbacks++; }, key(password, 'B')).is_ok() || wrong_callbacks != 0) return 21;
  std::cout << "PUBLIC_NEW_ACCEPTED rows=1 wrong_callbacks=0 hits=" << hits << '\n';
  return 0;
}
