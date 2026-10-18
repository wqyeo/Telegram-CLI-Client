// SPDX-License-Identifier: MIT
// PUBLIC ConcurrentBinlog storage actor ONLY. No TdDb, Client, account or providers.
// A single scheduler intentionally withholds a reserved non-account event across sync.
#include "g1-storage-context.h"
#include "td/db/binlog/Binlog.h"
#include "td/db/binlog/ConcurrentBinlog.h"
#include "td/actor/actor.h"
#include "td/utils/logging.h"
#include "td/utils/MpscPollableQueue.h"
#include "td/utils/Time.h"
#include <cstdlib>
#include <iostream>
#include <string_view>
#include <sys/resource.h>

namespace g1 = telegram_cli::native_g1;
constexpr int PublicEvent = 0x12345678;
[[noreturn]] void fail() { std::cout << "PUBLIC_STORAGE_SYNC_FIXTURE_ERROR\n"; std::exit(70); }
void require(bool value) { if (!value) { fail(); } }
bool okay(td::Status status) { bool value = status.is_ok(); status.ignore(); return value; }
class PublicLog final : public td::LogInterface {
 public:
  bool entered = false;
  void do_append(int, td::CSlice text) final {
    // Fixture-only sink. Production privacy cannot be implemented by suppression.
    std::string_view message(text.data(), text.size());
    if (message.find("Force binlog sync from PUBLIC_RESERVATION_GAP") != std::string_view::npos) { entered = true; }
  }
};
void pump(td::Scheduler &scheduler, double duration) {
  const auto end = td::Time::now() + duration;
  // This is the actual bounded scheduler event loop, not background-job polling.
  while (td::Time::now() < end) { scheduler.run_no_guard(td::Timestamp::at(end)); }
}
int main(int argc, char **argv) {
  ::umask(0077);
  struct rlimit limit{0, 0};
  require(::setrlimit(RLIMIT_CORE, &limit) == 0 && argc == 2);
  PublicLog logs;
  auto previous_log = td::log_interface;
  td::log_interface = &logs;
  SET_VERBOSITY_LEVEL(VERBOSITY_NAME(INFO));
  std::string path = argv[1];
  g1::detail::Fd profile(::open(path.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
  require(profile.get() >= 0);
  // Caller-only PUBLIC sentinel, never an actual profile.lock descriptor.
  g1::detail::Fd lease(::openat(profile.get(), "PUBLIC_LEASE", O_RDWR | O_NOFOLLOW | O_CLOEXEC));
  struct flock lock{}; lock.l_type = F_WRLCK; lock.l_whence = SEEK_SET;
  require(lease.get() >= 0 && ::fcntl(lease.get(), F_SETLK, &lock) == 0);
  struct stat identity{};
  require(::fstat(profile.get(), &identity) == 0);
  auto created = g1::StorageContext::create_attested(path, "PUBLIC", "00000000-0000-4000-8000-000000000001",
      g1::StorageContext::Intent::New, false, identity.st_dev, identity.st_ino);
  require(created.is_ok()); auto context = created.move_as_ok();
  require(context->bind_client(1)); // Synthetic binding ID, NOT Client construction.
  require(okay(context->prepare_open(td::DbKey::raw_key(std::string(32, 'A')), context->database_directory(),
      context->files_directory(), false, false, false, false)));
  require(okay(context->finish_open()));
  auto owner = td::make_unique<td::Binlog>();
  std::size_t initial_rows = 0;
  auto status = owner->init(context->binlog_path(), [&](const td::BinlogEvent &) { ++initial_rows; },
                            td::DbKey::raw_key(std::string(32, 'A')));
  require(okay(std::move(status)) && initial_rows == 0);
  bool early = false;
  {
    auto queue = std::make_shared<td::MpscPollableQueue<td::EventFull>>();
    queue->init();
    td::Scheduler scheduler;
    scheduler.init(0, {queue}, nullptr);
    auto guard = scheduler.get_guard();
    auto concurrent = std::make_shared<td::ConcurrentBinlog>(std::move(owner));
    pump(scheduler, 0.01);
    const auto reserved = concurrent->next_event_id();
    require(reserved != 0);
    bool synced = false;
    concurrent->force_sync(td::PromiseCreator::lambda([&](td::Result<td::Unit> result) {
      require(result.is_ok()); synced = true;
    }), "PUBLIC_RESERVATION_GAP");
    pump(scheduler, 0.05); // Includes native3ms sync deadline, with no event queued yet.
    require(logs.entered); // Actor entry must actually be observed, not assumed from time.
    pump(scheduler, 0.05); // Full additional window AFTER actor entry/handler processing.
    early = synced;
    struct stat before{};
    require(::stat(context->binlog_path().c_str(), &before) == 0 && before.st_size == 128);
    concurrent->add_raw_event(reserved,
        td::BinlogEvent::create_raw(reserved, PublicEvent, 0, td::EmptyStorer()));
    bool final_synced = false;
    concurrent->force_sync(td::PromiseCreator::lambda([&](td::Result<td::Unit> result) {
      require(result.is_ok()); final_synced = true;
    }), "PUBLIC_FINAL_SYNC");
    pump(scheduler, 0.05);
    require(synced && final_synced);
    bool closed = false;
    concurrent->close(td::PromiseCreator::lambda([&](td::Result<td::Unit> result) {
      require(result.is_ok()); closed = true;
    }));
    pump(scheduler, 0.05);
    require(closed);
    concurrent.reset();
    pump(scheduler, 0.01);
  }
  struct stat after{};
  require(::stat(context->binlog_path().c_str(), &after) == 0 && after.st_size == 160);
  td::Binlog reopened;
  std::size_t rows = 0;
  status = reopened.init(context->binlog_path(), [&](const td::BinlogEvent &event) {
    require(event.type_ == PublicEvent && event.get_data().empty()); ++rows;
  }, td::DbKey::raw_key(std::string(32, 'A')));
  require(okay(std::move(status)) && rows == 1 && okay(reopened.close()));
  context->stop();
  td::log_interface = previous_log;
  std::cout << "PUBLIC_STORAGE_SYNC entered=1 early=" << (early ? 1 : 0)
            << " before_bytes=128 after_bytes=160 replay=1 closed=1 account_engine=0\n";
  return early ? 21 : 0; // Exact unsafe control outcome, never arbitrary nonzero refusal.
}
