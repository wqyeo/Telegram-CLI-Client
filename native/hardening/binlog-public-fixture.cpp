// PUBLIC-only low-level TDLib binlog fixtures. Never creates a Client/account.
#include "td/db/binlog/Binlog.h"
#include "td/db/binlog/BinlogEvent.h"
#include "td/db/DbKey.h"
#include <fstream>
#include <iostream>
#include <string>

namespace {
td::DbKey key(char value) {
  return td::DbKey::raw_key(std::string(32, value));  // Fixed PUBLIC test keys only.
}

int seed(const std::string &kind, const std::string &path) {
  td::Binlog log;
  auto opened = log.init(path, {}, kind == "encrypted" ? key('A') : td::DbKey::empty());
  if (opened.is_error()) return 60;
  log.add(77, td::EmptyStorer());
  log.add(77, td::EmptyStorer());
  if (log.close().is_error()) return 61;
  if (kind == "processor" || kind == "partial") {
    auto raw = td::BinlogEvent::create_raw(kind == "processor" ? 1 : 3, 77,
        kind == "partial" ? td::BinlogEvent::Flags::Partial : 0, td::EmptyStorer());
    auto data = raw.as_slice();
    std::ofstream file(path, std::ios::binary | std::ios::app);
    file.write(data.data(), static_cast<std::streamsize>(data.size()));
    if (!file) return 62;
  }
  std::cout << "PUBLIC_BINLOG_CREATED\n";
  return 0;
}

int check(const std::string &name, const std::string &path) {
  td::Binlog log;
  int replayed = 0;
  int debugged = 0;
  const bool encrypted = name == "healthy" || name == "wrong-key" || name == "old-key";
  auto requested = name == "format-migration" ? key('A') :
      (encrypted ? key(name == "healthy" ? 'A' : 'B') : td::DbKey::empty());
  td::Binlog::Callback debug;
  if (name == "debug-callback") debug = [&](const td::BinlogEvent &) { ++debugged; };
  auto status = log.init(path, [&](const td::BinlogEvent &) { ++replayed; },
                         std::move(requested), name == "old-key" ? key('A') : td::DbKey::empty(), -1, debug);
  if (name != "healthy") {
    if (!status.is_error() || replayed != 0 || debugged != 0) {
      std::cout << "REFUSAL_OR_REPLAY_GATE_FAILED callbacks=" << replayed << " debug=" << debugged << '\n';
      return 40;
    }
    std::cout << "PUBLIC_BINLOG_REFUSED callbacks=0\n";
    return 0;
  }
  if (status.is_error() || replayed != 2) return 41;
  log.add(77, td::EmptyStorer());
  if (log.close().is_error()) return 42;
  td::Binlog reopened;
  int retained = 0;
  auto result = reopened.init(path, [&](const td::BinlogEvent &) { ++retained; }, key('A'));
  if (result.is_error() || retained != 3 || reopened.close().is_error()) return 43;
  std::cout << "PUBLIC_BINLOG_WRITE_REOPEN_PASSED rows=3\n";
  return 0;
}
}  // namespace

int main(int argc, char **argv) {
  if (argc != 4) return 64;
  std::string action = argv[1];
  if (action == "seed") return seed(argv[2], argv[3]);
  if (action == "check") return check(argv[2], argv[3]);
  return 65;
}
