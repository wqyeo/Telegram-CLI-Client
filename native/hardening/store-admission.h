// SPDX-License-Identifier: MIT
// Telegram-CLI experimental Linux G1 admission-record primitives.
// Not a TDLib database validator, NEW database formatter, or SDK entrypoint.
#pragma once

#if !defined(__linux__)
#error "G1 admission primitives currently require Linux"
#endif

#include <array>
#include <cstdint>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <optional>
#include <string>
#include <string_view>
#include <sys/stat.h>
#include <unistd.h>
#include <utility>
#include <cerrno>

namespace telegram_cli::native_g1 {
inline constexpr char RecordName[] = "g1-admission";
inline constexpr char Revision[] = "a94b83d67ab4c8397c6245e541b31a4131ea2f79";
inline constexpr std::size_t HeaderSize = 112;
inline constexpr std::size_t RecordSize = 120;
inline constexpr std::uint32_t FileDatabase = 1, ChatDatabase = 2, MessageDatabase = 4, TestDc = 8;
inline constexpr mode_t PermissionsMask = S_ISUID | S_ISGID | S_ISVTX | S_IRWXU | S_IRWXG | S_IRWXO;
inline constexpr mode_t PrivateFileMode = S_IRUSR | S_IWUSR;

// Trusted expected policy, NOT a configuration inferred from an existing record.
// schema_id is a non-secret schema/layout identity; it is not a key or key hash.
struct Configuration {
  std::array<unsigned char, 16> store_id{};
  std::array<unsigned char, 32> schema_id{};
  std::uint32_t schema_version = 0;
  std::uint32_t flags = 0;
};

namespace detail {
class Fd {
 public:
  explicit Fd(int fd = -1) : fd_(fd) {}
  Fd(const Fd &) = delete;
  Fd &operator=(const Fd &) = delete;
  Fd(Fd &&other) noexcept : fd_(std::exchange(other.fd_, -1)) {}
  Fd &operator=(Fd &&other) noexcept {
    if (this != &other) {
      if (fd_ >= 0) { ::close(fd_); }
      fd_ = std::exchange(other.fd_, -1);
    }
    return *this;
  }
  ~Fd() { if (fd_ >= 0) { ::close(fd_); } }
  int get() const { return fd_; }
  int release() { return std::exchange(fd_, -1); }
 private:
  int fd_;
};

inline bool name_valid(std::string_view name) {
  if (name.empty() || name.size() > 64) { return false; }
  for (char c : name) {
    if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
          (c >= '0' && c <= '9') || c == '_' || c == '-')) { return false; }
  }
  return true;
}

inline bool configuration_valid(const Configuration &c) {
  bool identity = false, schema = false;
  for (auto b : c.store_id) { identity |= b != 0; }
  for (auto b : c.schema_id) { schema |= b != 0; }
  return identity && schema && c.schema_version > 0 && c.schema_version <= 0x7fffffffU &&
      (c.flags & ~15U) == 0 && (!(c.flags & MessageDatabase) || (c.flags & ChatDatabase)) &&
      (!(c.flags & ChatDatabase) || (c.flags & FileDatabase));
}

inline bool directory_stat(int fd, struct stat &s) {
  return fd >= 0 && ::fstat(fd, &s) == 0 && S_ISDIR(s.st_mode) &&
      (s.st_mode & PermissionsMask) == S_IRWXU && s.st_uid == ::geteuid();
}
inline bool same_inode(const struct stat &a, const struct stat &b) {
  return a.st_dev == b.st_dev && a.st_ino == b.st_ino;
}
inline bool private_file(const struct stat &s) {
  return S_ISREG(s.st_mode) && (s.st_mode & PermissionsMask) == PrivateFileMode && s.st_uid == ::geteuid() && s.st_nlink == 1;
}
inline bool linked_directory(int parent, const char *name, int fd) {
  struct stat parent_stat{}, linked{}, opened{};
  return directory_stat(parent, parent_stat) && directory_stat(fd, opened) &&
      ::fstatat(parent, name, &linked, AT_SYMLINK_NOFOLLOW) == 0 &&
      same_inode(linked, opened) && S_ISDIR(linked.st_mode);
}
inline bool linked_file(int root, int fd, std::size_t size) {
  struct stat linked{}, opened{};
  return fd >= 0 && ::fstat(fd, &opened) == 0 && private_file(opened) && opened.st_size == static_cast<off_t>(size) &&
      ::fstatat(root, RecordName, &linked, AT_SYMLINK_NOFOLLOW) == 0 && private_file(linked) &&
      same_inode(linked, opened) && linked.st_size == opened.st_size;
}

inline std::array<unsigned char, RecordSize> encode(const Configuration &c) {
  std::array<unsigned char, RecordSize> bytes{};
  std::memcpy(bytes.data(), "TGCG1ADM", 8);
  auto put32 = [&bytes](std::size_t offset, std::uint32_t n) {
    for (unsigned i = 0; i < 4; i++) { bytes[offset + i] = static_cast<unsigned char>(n >> (8 * i)); }
  };
  put32(8, 1);  // admission-record version
  put32(12, 1); // G1 native-format/layout policy version, NOT an upstream version number
  put32(16, c.schema_version);
  put32(20, c.flags);
  std::memcpy(bytes.data() + 24, c.store_id.data(), c.store_id.size());
  std::memcpy(bytes.data() + 40, Revision, 40);
  std::memcpy(bytes.data() + 80, c.schema_id.data(), c.schema_id.size());
  std::memcpy(bytes.data() + HeaderSize, "G1SEALED", 8);
  return bytes;
}
inline bool read_matches(int root, int fd, const Configuration &c, std::size_t size) {
  if ((size != HeaderSize && size != RecordSize) || !linked_file(root, fd, size)) { return false; }
  std::array<unsigned char, RecordSize + 1> bytes{};
  std::size_t offset = 0;
  while (offset < size) {
    auto n = ::pread(fd, bytes.data() + offset, size - offset, static_cast<off_t>(offset));
    if (n <= 0) { return false; }
    offset += static_cast<std::size_t>(n);
  }
  // Both exact length and post-read inode/metadata are required. No error/retry fallback.
  unsigned char extra{};
  return ::pread(fd, &extra, 1, static_cast<off_t>(size)) == 0 && linked_file(root, fd, size) &&
      std::memcmp(bytes.data(), encode(c).data(), size) == 0;
}
// Dedicated storage namespace, not a shared profile root. Retained/moved
// evidence cannot be mistaken for pristine storage: NEW requires an empty
// parent; EXISTING requires exactly its named slot.
inline bool parent_entries(int parent, std::string_view slot) {
  Fd scan(::openat(parent, ".", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
  if (scan.get() < 0) { return false; }
  DIR *dir = ::fdopendir(scan.get());
  if (!dir) { return false; }
  (void)scan.release();
  bool ok = true, found = false;
  errno = 0;
  while (auto *entry = ::readdir(dir)) {
    std::string_view name(entry->d_name);
    if (name == "." || name == "..") { continue; }
    if (slot.empty() || name != slot || found) { ok = false; break; }
    found = true;
  }
  if (errno != 0) { ok = false; }
  if (::closedir(dir) != 0) { ok = false; }
  return ok && (found == !slot.empty());
}
inline bool write_bytes(int fd, const unsigned char *bytes, std::size_t size) {
  std::size_t offset = 0;
  while (offset < size) {
    auto n = ::write(fd, bytes + offset, size - offset);
    if (n <= 0) { return false; }
    offset += static_cast<std::size_t>(n);
  }
  return true;
}

// Physical presence/type/permissions only. Actual key/format/schema/data validation
// is still mandatory in the future native coordinator before exposing any data.
inline bool components_match(int root, const Configuration &c) {
  const std::string binlog = (c.flags & TestDc) ? "td_test.binlog" : "td.binlog";
  const std::string sqlite = (c.flags & TestDc) ? "db_test.sqlite" : "db.sqlite";
  Fd scan(::openat(root, ".", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
  if (scan.get() < 0) { return false; }
  DIR *dir = ::fdopendir(scan.get());
  if (!dir) { return false; }
  (void)scan.release(); // fdopendir owns this separate open-file description, not root's offset.
  bool ok = true, found_binlog = false, found_sqlite = false;
  std::size_t count = 0;
  errno = 0;
  while (auto *entry = ::readdir(dir)) {
    std::string name(entry->d_name);
    if (name == "." || name == "..") { continue; }
    if (++count > 16) { ok = false; break; }
    if (name == RecordName) { continue; } // record is separately checked through its held fd
    bool main_binlog = name == binlog;
    bool main_sqlite = name == sqlite;
    bool sidecar = name == sqlite + "-wal" || name == sqlite + "-shm" || name == sqlite + "-journal";
    if ((!main_binlog && !main_sqlite && !sidecar) || ((main_sqlite || sidecar) && !(c.flags & FileDatabase))) {
      ok = false; break; // Includes any .new, temporary, unexpected, and other-DC components.
    }
    struct stat s{};
    if (::fstatat(root, entry->d_name, &s, AT_SYMLINK_NOFOLLOW) != 0 || !private_file(s) ||
        ((main_binlog || main_sqlite) && s.st_size <= 0)) { ok = false; break; }
    found_binlog |= main_binlog;
    found_sqlite |= main_sqlite;
    errno = 0;
  }
  if (errno != 0) { ok = false; }
  if (::closedir(dir) != 0) { ok = false; }
  return ok && found_binlog && (found_sqlite == static_cast<bool>(c.flags & FileDatabase));
}
} // namespace detail

class NewReservation {
 public:
  NewReservation(const NewReservation &) = delete;
  NewReservation &operator=(const NewReservation &) = delete;
  NewReservation(NewReservation &&) noexcept = default;
  NewReservation &operator=(NewReservation &&) noexcept = default;

  // parent is an already trusted, exclusively leased, app-owned DEDICATED
  // storage namespace fd. It must be empty. It is validated but not leased here.
  // No path traversal, existing-root adoption,
  // chmod, deletion, rollback or automatic retry. Failed reservations remain evidence.
  static std::optional<NewReservation> reserve(int parent, std::string_view name, const Configuration &c) {
    struct stat parent_stat{};
    if (!detail::configuration_valid(c) || !detail::name_valid(name) || !detail::directory_stat(parent, parent_stat) ||
        !detail::parent_entries(parent, {})) {
      return std::nullopt;
    }
    std::string basename(name);
    if (::mkdirat(parent, basename.c_str(), S_IRWXU) != 0) { return std::nullopt; }
    detail::Fd root(::openat(parent, basename.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
    if (!detail::linked_directory(parent, basename.c_str(), root.get())) { return std::nullopt; }
    detail::Fd record(::openat(root.get(), RecordName, O_RDWR | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, PrivateFileMode));
    if (record.get() < 0 || !detail::linked_file(root.get(), record.get(), 0)) { return std::nullopt; }
    auto bytes = detail::encode(c);
    if (!detail::write_bytes(record.get(), bytes.data(), HeaderSize) || ::fsync(record.get()) != 0 ||
        !detail::read_matches(root.get(), record.get(), c, HeaderSize)) { return std::nullopt; }
    // Keep our own parent fd without sharing directory iteration offsets.
    detail::Fd held_parent(::openat(parent, ".", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
    struct stat held_stat{};
    if (!detail::directory_stat(held_parent.get(), held_stat) || !detail::same_inode(parent_stat, held_stat) ||
        !detail::linked_directory(held_parent.get(), basename.c_str(), root.get())) { return std::nullopt; }
    return NewReservation(std::move(held_parent), std::move(root), std::move(record), std::move(basename), c);
  }

  int root_fd() const { return root_.get(); }
  const Configuration &configuration() const { return config_; }

  // Optional live formatter handshake. The first attempt consumes this capability,
  // including a failed preflight. No reopening/resume/retry can mint another one.
  bool begin_formatting() {
    if (attempted_ || format_state_ != FormatState::Unstarted) { return false; }
    format_state_ = FormatState::Failed;
    if (!pending_matches() || !detail::parent_entries(root_.get(), RecordName)) { return false; }
    format_state_ = FormatState::Started;
    return true;
  }
  bool finish_formatting() {
    if (attempted_ || format_state_ != FormatState::Started) { return false; }
    format_state_ = FormatState::Failed;
    if (!pending_matches() || !detail::components_match(root_.get(), config_)) { return false; }
    format_state_ = FormatState::Finished;
    return true;
  }
  void fail_formatting() {
    if (!attempted_) { format_state_ = FormatState::Failed; }
  }

  // Explicit, single-attempt end of the live NEW reservation. This records the
  // configuration, NOT successful native key/schema validation. The coordinator
  // must validate the actual initialized components before calling and on every open.
  // Append a fixed completion tag; never truncate, rename, replace, or resume a partial record.
  bool commit_configuration() {
    if (attempted_) { return false; }
    attempted_ = true;
    if (format_state_ != FormatState::Unstarted && format_state_ != FormatState::Finished) { return false; }
    if (!detail::parent_entries(parent_.get(), name_) ||
        !detail::linked_directory(parent_.get(), name_.c_str(), root_.get()) ||
        !detail::read_matches(root_.get(), record_.get(), config_, HeaderSize) ||
        !detail::components_match(root_.get(), config_)) { return false; }
    auto bytes = detail::encode(config_);
    if (::pwrite(record_.get(), bytes.data() + HeaderSize, RecordSize - HeaderSize, HeaderSize) !=
        static_cast<ssize_t>(RecordSize - HeaderSize) || ::fsync(record_.get()) != 0) { return false; }
    return detail::parent_entries(parent_.get(), name_) &&
        detail::linked_directory(parent_.get(), name_.c_str(), root_.get()) &&
        detail::read_matches(root_.get(), record_.get(), config_, RecordSize) &&
        detail::components_match(root_.get(), config_);
  }
 private:
  NewReservation(detail::Fd parent, detail::Fd root, detail::Fd record, std::string name, const Configuration &c)
      : parent_(std::move(parent)), root_(std::move(root)), record_(std::move(record)), name_(std::move(name)), config_(c) {}
  detail::Fd parent_, root_, record_;
  std::string name_;
  Configuration config_;
  bool attempted_ = false;
  enum class FormatState { Unstarted, Started, Finished, Failed };
  FormatState format_state_ = FormatState::Unstarted;
  bool pending_matches() const {
    return detail::parent_entries(parent_.get(), name_) &&
        detail::linked_directory(parent_.get(), name_.c_str(), root_.get()) &&
        detail::read_matches(root_.get(), record_.get(), config_, HeaderSize);
  }
};

class ExistingRecord {
 public:
  ExistingRecord(const ExistingRecord &) = delete;
  ExistingRecord &operator=(const ExistingRecord &) = delete;
  ExistingRecord(ExistingRecord &&) noexcept = default;
  ExistingRecord &operator=(ExistingRecord &&) noexcept = default;

  // Read-only configuration/presence gate. Does NOT open TDLib/SQLite/binlog,
  // replay records, mint NEW intent, or certify the underlying database contents.
  static std::optional<ExistingRecord> open(int parent, std::string_view name, const Configuration &expected) {
    struct stat parent_stat{};
    if (!detail::configuration_valid(expected) || !detail::name_valid(name) ||
        !detail::directory_stat(parent, parent_stat) || !detail::parent_entries(parent, name)) { return std::nullopt; }
    std::string basename(name);
    detail::Fd root(::openat(parent, basename.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
    if (!detail::linked_directory(parent, basename.c_str(), root.get())) { return std::nullopt; }
    detail::Fd record(::openat(root.get(), RecordName, O_RDONLY | O_NONBLOCK | O_NOFOLLOW | O_CLOEXEC));
    if (!detail::read_matches(root.get(), record.get(), expected, RecordSize) ||
        !detail::components_match(root.get(), expected) ||
        !detail::read_matches(root.get(), record.get(), expected, RecordSize) ||
        !detail::linked_directory(parent, basename.c_str(), root.get()) ||
        !detail::parent_entries(parent, name)) { return std::nullopt; }
    return ExistingRecord(std::move(root));
  }
  int root_fd() const { return root_.get(); }
 private:
  explicit ExistingRecord(detail::Fd root) : root_(std::move(root)) {}
  detail::Fd root_;
};
} // namespace telegram_cli::native_g1
