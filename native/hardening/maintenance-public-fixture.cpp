// PUBLIC-only runtime binlog maintenance/fault fixtures, no Client/account.
#include "td/db/binlog/Binlog.h"
#include "td/db/binlog/BinlogEvent.h"
#include "td/db/DbKey.h"
#include <cerrno>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>
#include <sys/stat.h>

namespace {
bool fail_rename = false;
unsigned long long inode(const std::string &path) {
  struct ::stat info;
  if (::lstat(path.c_str(), &info) != 0) return 0;
  return static_cast<unsigned long long>(info.st_ino);
}
td::DbKey public_key(char ch) {
  return td::DbKey::raw_key(std::string(32, ch));
}
}  // namespace

// Linker-only PUBLIC fault hook. No preload/install; generator never uses it.
extern "C" int __real_rename(const char *from, const char *to);
extern "C" int __wrap_rename(const char *from, const char *to) {
  const std::string source(from);
  if (fail_rename && source.size() >= 4 && source.compare(source.size() - 4, 4, ".new") == 0) {
    errno = EIO;
    return -1;
  }
  return __real_rename(from, to);
}

int main(int argc, char **argv) {
  if (argc != 3) return 64;
  const std::string name(argv[1]), path(argv[2]);
  td::Binlog log;
  int initial = 0;
  auto opened = log.init(path, [&](const td::BinlogEvent &) { ++initial; }, public_key('A'));
  if (opened.is_error() || initial != 2) return 41;
  auto original_inode = inode(path);
  if (name == "rotation") {
    std::cout << "PUBLIC_ROTATION_ATTEMPT" << std::endl;
    log.change_key(public_key('B'));
    std::cout << "PUBLIC_ROTATION_RETURNED" << std::endl;
    return 0;
  }
  const std::string replacement = path + ".new";
  if (name == "collision") {
    std::ofstream sentinel(replacement, std::ios::binary);
    sentinel << "PUBLIC_KEEP_REPLACEMENT";
    sentinel.close();
    if (!sentinel) return 42;
    std::cout << "PUBLIC_SENTINEL_INODE=" << inode(replacement) << std::endl;
  } else if (name == "directory") {
    if (!std::filesystem::create_directory(replacement)) return 43;
    std::cout << "PUBLIC_DIRECTORY_INODE=" << inode(replacement) << std::endl;
  } else if (name == "rename-failure") {
    fail_rename = true;
    std::cout << "PUBLIC_RENAME_FAULT_ARMED" << std::endl;
  } else if (name != "healthy-compaction") {
    return 65;
  }
  // Exercise the real automatic compaction threshold (>50,000 bytes, ratio>5).
  for (int i = 0; i < 1800; ++i) log.rewrite(1, 77, td::EmptyStorer());
  if (name != "healthy-compaction") {
    std::cout << "PUBLIC_UNEXPECTED_CONTINUATION" << std::endl;
    return 0;
  }
  if (inode(path) == original_inode || std::filesystem::exists(replacement)) return 44;
  log.add(77, td::EmptyStorer());
  if (log.close().is_error()) return 45;
  td::Binlog reopened;
  int retained = 0;
  auto status = reopened.init(path, [&](const td::BinlogEvent &) { ++retained; }, public_key('A'));
  if (status.is_error() || retained != 3 || reopened.close().is_error()) return 46;
  std::cout << "PUBLIC_COMPACTION_REOPEN_PASSED rows=3" << std::endl;
  return 0;
}
