// SPDX-License-Identifier: MIT
#include "g1-storage-context.h"

#include "td/db/binlog/Binlog.h"
#include "td/utils/crypto.h"

#include <limits>
#include <unordered_map>

namespace telegram_cli::native_g1 {
namespace {
constexpr char FilesName[] = "native-files";

td::Status refused() { return td::Status::Error(400, "TGCLI_G1_CONTEXT_REFUSED"); }

bool generation_valid(const std::string &text) {
  if (text.size() != 36) { return false; }
  bool nonzero = false;
  for (std::size_t i = 0; i < text.size(); ++i) {
    if (i == 8 || i == 13 || i == 18 || i == 23) {
      if (text[i] != '-') { return false; }
    } else {
      char c = text[i];
      if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) { return false; }
      nonzero |= c != '0';
    }
  }
  return nonzero;
}

// Absolute normalized, no-follow walk. Trusted ancestor ownership/filesystem and
// lease checks remain the caller's responsibility, as with B1f.
detail::Fd open_absolute(const std::string &path) {
  if (path.size() < 2 || path.size() > 4096 || path.front() != '/' || path.back() == '/') { return detail::Fd(); }
  for (unsigned char c : path) { if (c < 32 || c == 127) { return detail::Fd(); } }
  detail::Fd current(::open("/", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
  std::size_t offset = 1;
  while (offset < path.size() && current.get() >= 0) {
    auto end = path.find('/', offset);
    if (end == std::string::npos) { end = path.size(); }
    auto part = path.substr(offset, end - offset);
    if (part.empty() || part == "." || part == ".." || part.size() > 255) { return detail::Fd(); }
    current = detail::Fd(::openat(current.get(), part.c_str(),
                                O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC));
    offset = end + 1;
  }
  return current;
}

bool same_directory(int first, int second) {
  struct stat a{}, b{};
  return detail::directory_stat(first, a) && detail::directory_stat(second, b) && detail::same_inode(a, b);
}
bool absent(int parent, const char *name) {
  struct stat node{};
  return ::fstatat(parent, name, &node, AT_SYMLINK_NOFOLLOW) != 0 && errno == ENOENT;
}

std::mutex registry_mutex;
std::unordered_map<std::int64_t, std::shared_ptr<StorageContext>> registry;
std::int64_t next_handle = 1;
} // namespace

StorageContext::StorageContext(detail::Fd profile, detail::Fd files, BinlogOnlyPolicy policy,
                               std::string profile_path, std::string generation, Intent intent,
                               Configuration configuration)
    : profile_(std::move(profile)), files_(std::move(files)), policy_(std::move(policy)),
      profile_path_(std::move(profile_path)), generation_(std::move(generation)),
      database_directory_(profile_path_ + "/" + NativeNamespaceName + "/" + NativeSlotName + "/"),
      files_directory_(profile_path_ + "/" + FilesName + "/"), intent_(intent), configuration_(configuration) {}

td::Result<std::shared_ptr<StorageContext>> StorageContext::create(
    int profile_fd, std::string profile_path, std::string profile_name,
    std::string generation, Intent intent, bool test_dc) {
  if (!generation_valid(generation) || !detail::name_valid(profile_name) ||
      !((profile_name.front() >= '0' && profile_name.front() <= '9') ||
        (profile_name.front() >= 'A' && profile_name.front() <= 'Z') ||
        (profile_name.front() >= 'a' && profile_name.front() <= 'z')) ||
      profile_path.substr(profile_path.find_last_of('/') + 1) != profile_name ||
      (intent != Intent::New && intent != Intent::Existing)) { return refused(); }
  auto profile = open_absolute(profile_path);
  if (!same_directory(profile_fd, profile.get())) { return refused(); }
  if (intent == Intent::New) {
    // No existing empty-directory adoption, normalization, chmod or cleanup.
    if (!absent(profile.get(), NativeNamespaceName) || !absent(profile.get(), FilesName) ||
        ::mkdirat(profile.get(), NativeNamespaceName, S_IRWXU) != 0 ||
        ::mkdirat(profile.get(), FilesName, S_IRWXU) != 0) { return refused(); }
  }
  detail::Fd space(::openat(profile.get(), NativeNamespaceName,
                            O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC));
  detail::Fd files(::openat(profile.get(), FilesName,
                            O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC));
  if (!detail::linked_directory(profile.get(), NativeNamespaceName, space.get()) ||
      !detail::linked_directory(profile.get(), FilesName, files.get())) { return refused(); }
  auto policy = BinlogOnlyPolicy::bind(space.get(), profile_path + "/" + NativeNamespaceName, profile_name, test_dc);
  if (!policy) { return refused(); }
  auto expected = policy->expected_configuration();
  if (!expected) { return refused(); }
  // No key/key hash or metadata bytes enter this recipe. Distinct from the
  // component-era schema ID; final integrated source/ABI pins are still pending.
  auto descriptor = std::string(BinlogLayoutDescriptor) + CoordinatorDescriptor;
  auto fingerprint = td::sha256(descriptor);
  std::memcpy(expected->schema_id.data(), fingerprint.data(), expected->schema_id.size());
  auto context = std::shared_ptr<StorageContext>(new StorageContext(
      std::move(profile), std::move(files), std::move(*policy), std::move(profile_path),
      std::move(generation), intent, *expected));
  if (intent == Intent::New) {
    context->reservation_ = NewReservation::reserve(space.get(), NativeSlotName, *expected);
    if (!context->reservation_) { return refused(); }
  } else {
    context->existing_ = ExistingRecord::open(space.get(), NativeSlotName, *expected);
    if (!context->existing_) { return refused(); }
    context->sealed_ = true;
  }
  if (!context->physical_context_valid()) { return refused(); }
  return context;
}

td::Result<std::shared_ptr<StorageContext>> StorageContext::create_attested(
    std::string profile_path, std::string profile_name, std::string generation,
    Intent intent, bool test_dc, std::uint64_t expected_device, std::uint64_t expected_inode) {
  auto profile = open_absolute(profile_path);
  struct stat attributes{};
  if (!detail::directory_stat(profile.get(), attributes) ||
      static_cast<std::uint64_t>(attributes.st_dev) != expected_device ||
      static_cast<std::uint64_t>(attributes.st_ino) != expected_inode) { return refused(); }
  return create(profile.get(), std::move(profile_path), std::move(profile_name),
                std::move(generation), intent, test_dc);
}

int StorageContext::root_fd() const {
  return reservation_ ? reservation_->root_fd() : (existing_ ? existing_->root_fd() : -1);
}
bool StorageContext::physical_context_valid() const {
  auto current_profile = open_absolute(profile_path_);
  return policy_.validate() && same_directory(profile_.get(), current_profile.get()) &&
      detail::linked_directory(profile_.get(), FilesName, files_.get()) &&
      detail::linked_directory(policy_.namespace_fd(), NativeSlotName, root_fd());
}
bool StorageContext::record_valid(bool startup_components) const {
  if (!physical_context_valid()) { return false; }
  detail::Fd record(::openat(root_fd(), RecordName, O_RDONLY | O_NONBLOCK | O_NOFOLLOW | O_CLOEXEC));
  return detail::read_matches(root_fd(), record.get(), configuration_, sealed_ ? RecordSize : HeaderSize) &&
      detail::parent_entries(policy_.namespace_fd(), NativeSlotName) &&
      (!startup_components || detail::components_match(root_fd(), configuration_));
}
td::Status StorageContext::refuse() {
  state_ = State::Stopped;
  if (reservation_ && !sealed_) { reservation_->fail_formatting(); }
  return refused();
}
bool StorageContext::bind_client(std::int32_t client_id) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (state_ != State::Unbound || client_id <= 0 || !physical_context_valid()) {
    refuse().ignore(); return false;
  }
  client_id_ = client_id;
  state_ = State::Bound;
  return true;
}

td::Status StorageContext::prepare_open(const td::DbKey &key, const std::string &database_directory,
                                        const std::string &files_directory, bool test_dc,
                                        bool file_cache, bool chat_cache, bool message_cache) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (state_ != State::Bound) { return refuse(); }
  state_ = State::Opening; // Even a failed preflight consumes this one open attempt.
  if (!physical_context_valid() || !policy_.accepts_key(key) ||
      database_directory != database_directory_ || files_directory != files_directory_ ||
      test_dc != static_cast<bool>(configuration_.flags & TestDc) || file_cache || chat_cache || message_cache) {
    return refuse();
  }
  if (reservation_) {
    auto formatted = td::Binlog::create_new_encrypted(*reservation_, td::DbKey::raw_key(key.data().str()));
    if (formatted.is_error()) { formatted.ignore(); return refuse(); }
  }
  if (!record_valid()) { return refuse(); }
  return td::Status::OK();
}
td::Status StorageContext::finish_open() {
  std::lock_guard<std::mutex> lock(mutex_);
  if (state_ != State::Opening || !record_valid()) { return refuse(); }
  state_ = State::AwaitingAuthorization;
  return td::Status::OK();
}
td::Status StorageContext::seal_after_authorization_sync() {
  std::lock_guard<std::mutex> lock(mutex_);
  if (state_ != State::AwaitingAuthorization || !record_valid()) { return refuse(); }
  if (reservation_) {
    if (!reservation_->commit_configuration()) { return refuse(); }
    sealed_ = true;
  }
  if (!record_valid()) { return refuse(); }
  state_ = State::Ready;
  return td::Status::OK();
}
void StorageContext::stop() {
  std::lock_guard<std::mutex> lock(mutex_);
  refuse().ignore();
}
bool StorageContext::private_data_allowed() {
  std::lock_guard<std::mutex> lock(mutex_);
  if (state_ != State::Ready) { return false; }
  // Admission/remnant classification is a startup gate, not a live filesystem
  // write veto. The active hardened Binlog may own .new during healthy compaction.
  // Keep held-directory and sealed-record validation here; the native writer owns
  // validated maintenance. A future EXISTING open still applies strict components.
  if (!record_valid(false)) { refuse().ignore(); return false; }
  return true;
}
bool StorageContext::validate() {
  std::lock_guard<std::mutex> lock(mutex_);
  if (state_ == State::Stopped) { return false; }
  if (!physical_context_valid()) { refuse().ignore(); return false; }
  return true;
}
std::string StorageContext::binlog_path() const {
  std::lock_guard<std::mutex> lock(mutex_);
  if (state_ == State::Stopped || !physical_context_valid()) { return {}; }
  return "/proc/self/fd/" + std::to_string(root_fd()) +
      ((configuration_.flags & TestDc) ? "/td_test.binlog" : "/td.binlog");
}
StorageContext::State StorageContext::state() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return state_;
}

std::int64_t publish_context_handle(std::shared_ptr<StorageContext> context) {
  std::lock_guard<std::mutex> lock(registry_mutex);
  if (!context || !registry.empty() || next_handle == std::numeric_limits<std::int64_t>::max()) { return 0; }
  std::lock_guard<std::mutex> context_lock(context->mutex_);
  if (context->state_ != StorageContext::State::Unbound || context->handle_published_ ||
      !context->physical_context_valid()) { return 0; }
  context->handle_published_ = true;
  auto handle = next_handle++;
  registry.emplace(handle, std::move(context));
  return handle;
}
std::shared_ptr<StorageContext> take_context_handle(std::int64_t handle, const std::string &generation) {
  std::lock_guard<std::mutex> lock(registry_mutex);
  auto found = registry.find(handle);
  if (found == registry.end()) { return {}; }
  auto context = std::move(found->second);
  registry.erase(found); // Consumed even on a generation/context mismatch.
  if (context->generation() != generation || !context->validate() ||
      context->state() != StorageContext::State::Unbound) {
    context->stop(); return {};
  }
  return context;
}
bool abandon_context_handle(std::int64_t handle, const std::string &generation) {
  std::lock_guard<std::mutex> lock(registry_mutex);
  auto found = registry.find(handle);
  if (found == registry.end() || found->second->generation() != generation ||
      found->second->state() != StorageContext::State::Unbound) { return false; }
  auto context = std::move(found->second);
  registry.erase(found);
  context->stop();
  return true;
}
} // namespace telegram_cli::native_g1
