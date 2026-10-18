// SPDX-License-Identifier: MIT
#include "binlog-only-policy.h"

#include "td/utils/crypto.h"

#include <vector>

namespace telegram_cli::native_g1 {
namespace {
struct Context {
  detail::Fd profile, space;
  struct stat profile_stat{}, namespace_stat{};
};

bool profile_name_valid(std::string_view name) {
  return detail::name_valid(name) && ((name[0] >= 'A' && name[0] <= 'Z') ||
      (name[0] >= 'a' && name[0] <= 'z') || (name[0] >= '0' && name[0] <= '9'));
}

std::optional<Context> inspect(int supplied, std::string_view path, std::string_view name) {
  struct stat supplied_stat{};
  if (!profile_name_valid(name) || path.size() > 4096 || path.size() < 2 || path[0] != '/' ||
      path.back() == '/' || !detail::directory_stat(supplied, supplied_stat)) { return std::nullopt; }
  for (unsigned char c : path) { if (c < 32 || c == 127) { return std::nullopt; } }
  std::vector<std::string> parts;
  std::size_t offset = 1;
  while (offset < path.size()) {
    auto end = path.find('/', offset);
    if (end == std::string_view::npos) { end = path.size(); }
    auto part = path.substr(offset, end - offset);
    if (part.empty() || part.size() > 255 || part == "." || part == "..") { return std::nullopt; }
    parts.emplace_back(part);
    offset = end + 1;
  }
  if (parts.size() < 2 || parts.back() != NativeNamespaceName || parts[parts.size() - 2] != name) {
    return std::nullopt;
  }
  detail::Fd current(::open("/", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
  if (current.get() < 0) { return std::nullopt; }
  Context context;
  for (std::size_t i = 0; i < parts.size(); ++i) {
    detail::Fd next(::openat(current.get(), parts[i].c_str(),
                            O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC));
    if (next.get() < 0) { return std::nullopt; }
    if (i + 2 == parts.size()) {
      if (!detail::directory_stat(next.get(), context.profile_stat) || context.profile_stat.st_ino == 0) {
        return std::nullopt;
      }
      context.profile = detail::Fd(::openat(next.get(), ".", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
      struct stat retained{};
      if (!detail::directory_stat(context.profile.get(), retained) ||
          !detail::same_inode(retained, context.profile_stat)) { return std::nullopt; }
    }
    current = std::move(next);
  }
  if (!detail::directory_stat(current.get(), context.namespace_stat) || context.namespace_stat.st_ino == 0 ||
      !detail::same_inode(supplied_stat, context.namespace_stat)) { return std::nullopt; }
  context.space = std::move(current);
  return context;
}

void append32(std::string &bytes, std::uint32_t n) {
  for (unsigned i = 0; i < 4; ++i) { bytes.push_back(static_cast<char>(n >> (8 * i))); }
}
void append64(std::string &bytes, std::uint64_t n) {
  for (unsigned i = 0; i < 8; ++i) { bytes.push_back(static_cast<char>(n >> (8 * i))); }
}
void append_text(std::string &bytes, std::string_view text) {
  append32(bytes, static_cast<std::uint32_t>(text.size()));
  bytes.append(text.data(), text.size());
}
Configuration expected(const Context &context, std::string_view path, std::string_view name, bool test_dc) {
  static_assert(sizeof(dev_t) <= sizeof(std::uint64_t) && sizeof(ino_t) <= sizeof(std::uint64_t));
  std::string identity(StoreIdentityDomain);
  append_text(identity, name);
  append_text(identity, path);
  append64(identity, static_cast<std::uint64_t>(context.profile_stat.st_dev));
  append64(identity, static_cast<std::uint64_t>(context.profile_stat.st_ino));
  append64(identity, static_cast<std::uint64_t>(context.namespace_stat.st_dev));
  append64(identity, static_cast<std::uint64_t>(context.namespace_stat.st_ino));
  // Only public filesystem/configuration data is hashed. Never key material.
  auto store_digest = td::sha256(identity);
  auto schema_digest = td::sha256(BinlogLayoutDescriptor);
  Configuration config;
  std::memcpy(config.store_id.data(), store_digest.data(), config.store_id.size());
  std::memcpy(config.schema_id.data(), schema_digest.data(), config.schema_id.size());
  config.schema_version = BinlogLayoutSchemaVersion;
  config.flags = test_dc ? TestDc : 0;
  return config;
}
} // namespace

std::optional<BinlogOnlyPolicy> BinlogOnlyPolicy::bind(int namespace_fd, std::string_view namespace_path,
                                                    std::string_view profile_name, bool expected_test_dc) {
  auto context = inspect(namespace_fd, namespace_path, profile_name);
  if (!context) { return std::nullopt; }
  auto config = expected(*context, namespace_path, profile_name, expected_test_dc);
  if (!detail::configuration_valid(config)) { return std::nullopt; }
  BinlogOnlyPolicy result(std::move(context->profile), std::move(context->space),
                          std::string(namespace_path), std::string(profile_name), config);
  if (!result.validate()) { return std::nullopt; }
  return result;
}

bool BinlogOnlyPolicy::validate() const {
  struct stat held_profile{}, held_namespace{};
  if (!detail::directory_stat(profile_.get(), held_profile) ||
      !detail::directory_stat(namespace_.get(), held_namespace)) { return false; }
  auto current = inspect(namespace_.get(), path_, name_);
  if (!current || !detail::same_inode(current->profile_stat, held_profile) ||
      !detail::same_inode(current->namespace_stat, held_namespace)) { return false; }
  auto derived = expected(*current, path_, name_, (config_.flags & TestDc) != 0);
  return derived.store_id == config_.store_id && derived.schema_id == config_.schema_id &&
      derived.schema_version == config_.schema_version && derived.flags == config_.flags;
}

std::optional<Configuration> BinlogOnlyPolicy::expected_configuration() const {
  if (!validate()) { return std::nullopt; }
  return config_;
}

bool BinlogOnlyPolicy::accepts_key(const td::DbKey &key) const {
  return validate() && key.is_raw_key() && key.data().size() == 32;
}

int BinlogOnlyPolicy::namespace_fd() const {
  return validate() ? namespace_.get() : -1;
}
} // namespace telegram_cli::native_g1
