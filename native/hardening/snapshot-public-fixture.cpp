// SPDX-License-Identifier: MIT
// PUBLIC actual BinlogKeyValue/ConcurrentBinlog storage only. No TdDb/Client/Global.
#include "g1-storage-context.h"
#include "td/db/BinlogKeyValue.h"
#include "td/db/binlog/ConcurrentBinlog.h"
#include "td/actor/actor.h"
#include "td/utils/logging.h"
#include "td/utils/MpscPollableQueue.h"
#include "td/utils/Time.h"
#include <algorithm>
#include <cstdlib>
#include <iostream>
#include <string_view>
#include <sys/resource.h>

namespace g1 = telegram_cli::native_g1;
using KV = td::BinlogKeyValue<td::ConcurrentBinlog>;
using RawKV = td::BinlogKeyValue<td::Binlog>;
constexpr int PmcMagic = 0x4327, ConfigMagic = 0x1f18;
[[noreturn]] void fail() { std::cout << "PUBLIC_SNAPSHOT_FIXTURE_ERROR\n"; std::exit(70); }
void need(bool value) { if (!value) { fail(); } }
void gap_check(bool value, const char *phase) {
  if (!value) { std::cout << "PUBLIC_SNAPSHOT_DIAGNOSTIC " << phase << '\n'; fail(); }
}
void check(bool value, const char *name) { need(value); std::cout << "PUBLIC_SNAPSHOT_CHECK " << name << " PASS\n"; }
bool okay(td::Status status) { bool value = status.is_ok(); status.ignore(); return value; }
class PublicLog final : public td::LogInterface {
 public:
  bool gap_entered = false;
  unsigned compactions = 0;
  void do_append(int, td::CSlice text) final {
    std::string_view message(text.data(), text.size());
    if (message.find("Force binlog sync from PUBLIC_HANDOFF_GAP") != std::string_view::npos) { gap_entered = true; }
    if (message.find("Regenerate index") != std::string_view::npos) { ++compactions; }
  }
};
void pump(td::Scheduler &scheduler, double duration) {
  auto end = td::Time::now() + duration;
  while (td::Time::now() < end) { scheduler.run_no_guard(td::Timestamp::at(end)); }
}
// Test-only completion budget, NOT native admission retry/deadline policy.
// Small scheduler slices avoid blocking until the whole budget after completion.
template<class Predicate> bool scheduler_until(td::Scheduler &scheduler, Predicate complete) {
  const auto deadline = td::Time::now() + 1.0;
  while (!complete()) {
    const auto now = td::Time::now();
    if (now >= deadline) { return false; }
    scheduler.run_no_guard(td::Timestamp::at(std::min(deadline, now + 0.005)));
  }
  return true;
}
class PublicGapWindow final : public td::Actor {
 public:
  explicit PublicGapWindow(bool *elapsed) : elapsed_(elapsed) {}
 private:
  bool *elapsed_;
  void start_up() final { set_timeout_at(td::Time::now() + 0.05); }
  void timeout_expired() final { *elapsed_ = true; stop(); }
};
template<class Map> typename Map::Snapshot snapshot(const std::shared_ptr<Map> &map) {
  auto result = Map::try_snapshot(map); need(result.is_ok());
  auto view = result.move_as_ok(); need(bool(view)); return view;
}
template<class Map> typename Map::SnapshotStamp stamp(const std::shared_ptr<Map> &map) {
  auto view = snapshot<Map>(map); return view.stamp();
}
template<class Map> bool refused(const std::shared_ptr<Map> &map) {
  auto result = Map::try_snapshot(map);
  if (result.is_error()) { result.move_as_error().ignore(); return true; }
  return false;
}
class DeferredSnapshotActor final : public td::Actor {
 public:
  DeferredSnapshotActor(std::shared_ptr<KV> map, bool *ran, bool *acquired)
      : map_(std::move(map)), ran_(ran), acquired_(acquired) {}
  void inspect() {
    *ran_ = true; auto view = snapshot<KV>(map_); *acquired_ = bool(view); stop();
  }
 private:
  std::shared_ptr<KV> map_;
  bool *ran_, *acquired_;
};
#include "wake-ticket-public-fixture.h"
int main(int argc, char **argv) {
  ::umask(0077);
  struct rlimit limit{0, 0}; need(::setrlimit(RLIMIT_CORE, &limit) == 0 && argc == 2);
  PublicLog logs;
  auto previous_log = td::log_interface; td::log_interface = &logs;
  SET_VERBOSITY_LEVEL(VERBOSITY_NAME(INFO)); // PUBLIC sink only, NOT production privacy.
  std::string path = argv[1];
  g1::detail::Fd profile(::open(path.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
  g1::detail::Fd lease(::openat(profile.get(), "PUBLIC_LEASE", O_RDWR | O_NOFOLLOW | O_CLOEXEC));
  struct flock lock{}; lock.l_type = F_WRLCK; lock.l_whence = SEEK_SET;
  struct stat identity{};
  need(profile.get() >= 0 && lease.get() >= 0 && ::fcntl(lease.get(), F_SETLK, &lock) == 0 &&
       ::fstat(profile.get(), &identity) == 0);
  auto created = g1::StorageContext::create_attested(path, "PUBLIC", "00000000-0000-4000-8000-000000000001",
      g1::StorageContext::Intent::New, false, identity.st_dev, identity.st_ino);
  need(created.is_ok()); auto context = created.move_as_ok(); need(context->bind_client(1));
  need(okay(context->prepare_open(td::DbKey::raw_key(std::string(32, 'A')), context->database_directory(),
       context->files_directory(), false, false, false, false)) && okay(context->finish_open()));
  auto native = td::make_unique<td::Binlog>();
  need(okay(native->init(context->binlog_path(), [](const td::BinlogEvent &) { fail(); },
                        td::DbKey::raw_key(std::string(32, 'A')))));
  auto queue = std::make_shared<td::MpscPollableQueue<td::EventFull>>(); queue->init();
  td::Scheduler scheduler; scheduler.init(0, {queue}, nullptr); auto guard = scheduler.get_guard();
  auto log = std::make_shared<td::ConcurrentBinlog>(std::move(native));
  auto pmc = std::make_shared<KV>(), config = std::make_shared<KV>();
  check(refused<KV>(pmc), "uninitialized-is-error-not-busy");
  pmc->external_init_begin(PmcMagic); config->external_init_begin(ConfigMagic);
  pmc->external_init_finish(log); config->external_init_finish(log);
  check(bool(snapshot<KV>(pmc)), "initialized-native-map-snapshot");
  pmc->set("PUBLIC_VALUE", "A"); auto original = stamp<KV>(pmc);
  check(pmc->set("PUBLIC_VALUE", "A") == 0 && stamp<KV>(pmc) == original, "unchanged-set-retains-revision");
  pmc->set("PUBLIC_VALUE", "B"); pmc->set("PUBLIC_VALUE", "A");
  {
    auto view = snapshot<KV>(pmc); auto value = view.find("PUBLIC_VALUE");
    check(!view.matches(original) && value && *value == "A", "aba-changes-token-not-just-predicate");
  }
  pmc->set("PUBLIC_EMPTY", "");
  {
    auto view = snapshot<KV>(pmc);
    check(view.find("PUBLIC_EMPTY").has_value() && view.find("PUBLIC_EMPTY")->empty() && !view.find("MISSING"),
          "borrowed-empty-versus-absent");
    check(view.get("PUBLIC_VALUE") == "A" && view.isset("PUBLIC_EMPTY") && !view.isset("MISSING"),
          "borrowed-getters-do-not-relock");
    auto busy = KV::try_snapshot(pmc); need(busy.is_ok());
    check(!bool(busy.move_as_ok()), "same-map-contention-is-busy");
  }
  {
    auto held = snapshot<KV>(config);
    auto pair = KV::try_snapshot_pair(pmc, config); need(pair.is_ok());
    check(!bool(pair.move_as_ok()) && bool(snapshot<KV>(pmc)), "busy-second-releases-first");
  }
  {
    auto pair = KV::try_snapshot_pair(pmc, config); need(pair.is_ok());
    auto both = pair.move_as_ok(); need(bool(both));
    std::size_t count = 0; both.first.for_each([&](td::Slice, td::Slice) { ++count; });
    check(count == 2 && both.first.find("PUBLIC_VALUE") && !both.second.find("PUBLIC_VALUE"), "dual-borrowed-native-views");
  }
  {
    bool ran = false, acquired = false;
    auto actor = td::create_actor<DeferredSnapshotActor>("PUBLIC_DEFERRED_SNAPSHOT", pmc, &ran, &acquired);
    {
      auto held = snapshot<KV>(pmc);
      td::send_closure_later(actor.get(), &DeferredSnapshotActor::inspect);
      need(!ran);
    }
    need(scheduler_until(scheduler, [&] { return ran; }));
    check(ran && acquired, "deferred-owner-continuation-after-guard-release");
    actor.reset(); pump(scheduler, 0.01);
  }
  original = stamp<KV>(pmc);
  check(pmc->erase("MISSING") == 0 && stamp<KV>(pmc) == original, "erase-noop-retains-revision");
  pmc->set("GONE", "PUBLIC"); original = stamp<KV>(pmc); pmc->erase("GONE");
  check(!(stamp<KV>(pmc) == original), "erase-changes-revision");
  pmc->set("BATCH_A", "PUBLIC"); pmc->set("BATCH_B", "PUBLIC"); original = stamp<KV>(pmc);
  pmc->erase_batch({"BATCH_A", "BATCH_A", "MISSING", "BATCH_B"});
  {
    auto view = snapshot<KV>(pmc);
    check(!view.matches(original) && !view.find("BATCH_A") && !view.find("BATCH_B"), "batch-mutation-and-duplicate-keys");
  }
  pmc->set("PREFIX_A", "PUBLIC"); pmc->set("PREFIX_B", "PUBLIC"); original = stamp<KV>(pmc);
  pmc->erase_by_prefix("PREFIX_");
  check(!(stamp<KV>(pmc) == original), "prefix-mutation-changes-revision");
  original = stamp<KV>(pmc); pmc->erase_batch({"MISSING"}); pmc->erase_by_prefix("NONE_");
  check(stamp<KV>(pmc) == original, "empty-batch-prefix-retain-revision");
  config->set("PUBLIC_OPTION", "I1");
  {
    auto temporary = std::make_shared<KV>(); temporary->external_init_finish(log);
    auto before = stamp<KV>(temporary); temporary->close(); need(refused<KV>(temporary));
    temporary->external_init_begin(PmcMagic); temporary->external_init_finish(log);
    check(!(stamp<KV>(temporary) == before), "reset-incarnation-defeats-zero-revision-aba");
    std::weak_ptr<KV> weak = temporary; auto retained = snapshot<KV>(temporary); temporary.reset();
    need(!weak.expired()); retained = KV::Snapshot();
    check(weak.expired(), "view-retains-owner-and-unlocks-before-release");
    auto left = std::make_shared<KV>(); left->external_init_finish(log);
    auto captured = stamp<KV>(left);
    auto right = std::make_shared<KV>(std::move(*left)); need(refused<KV>(left));
    *left = std::move(*right);
    check(refused<KV>(right) && !(stamp<KV>(left) == captured), "value-move-out-back-cannot-restore-token");
    bool displaced = false, unlocked = false;
    std::weak_ptr<KV> weak_left = left;
    auto public_owner_hook = std::shared_ptr<td::ConcurrentBinlog>(log.get(),
        [weak_left, &displaced, &unlocked](td::ConcurrentBinlog *) {
          // PUBLIC lifetime hook only; the actual native owner remains `log`.
          displaced = true; auto owner = weak_left.lock(); need(bool(owner));
          auto attempt = KV::try_snapshot(owner); need(attempt.is_ok()); unlocked = bool(attempt.move_as_ok());
        });
    left->external_init_finish(public_owner_hook); public_owner_hook.reset();
    auto binding = stamp<KV>(left); left->external_init_finish(log);
    check(displaced && unlocked && !(stamp<KV>(left) == binding), "finish-rebinding-invalidates-incarnation");
  }
  {
    td::RwMutex invalid; invalid.clear(); auto attempt = invalid.try_lock_write();
    need(attempt.is_error()); attempt.move_as_error().ignore();
    check(true, "invalid-lock-is-error-not-busy");
    auto revision = std::numeric_limits<td::uint64>::max() - 1;
    need(td::detail::advance_key_value_revision(revision));
    check(!td::detail::advance_key_value_revision(revision) && revision == std::numeric_limits<td::uint64>::max(),
          "revision-exhaustion-never-wraps");
  }
  public_wake_ticket_checks(scheduler, pmc, config, log);
  {
    const auto p = stamp<KV>(pmc), c = stamp<KV>(config);
    bool requested = false, ran = false, stable = false;
    pmc->for_each([&](td::Slice, td::Slice) {
      if (requested) { return; } requested = true;
      log->force_sync_handoff(td::PromiseCreator::lambda([&](td::Result<td::Unit> result) {
        need(result.is_ok()); ran = true;
        auto pair = KV::try_snapshot_pair(pmc, config); need(pair.is_ok()); auto views = pair.move_as_ok();
        stable = bool(views) && views.first.matches(p) && views.second.matches(c);
      }), "PUBLIC_HANDOFF_STABLE");
      need(!ran); // Existing KV writer lock must not see inline finalization.
    });
    need(!ran); need(scheduler_until(scheduler, [&] { return ran; }));
    check(requested && ran && stable, "handoff-deferred-stable-required-map");
    auto old = stamp<KV>(pmc); pmc->set("PUBLIC_VALUE", "B"); pmc->set("PUBLIC_VALUE", "A");
    bool changed = false;
    log->force_sync_handoff(td::PromiseCreator::lambda([&](td::Result<td::Unit> result) {
      need(result.is_ok()); auto view = snapshot<KV>(pmc); changed = !view.matches(old);
    }), "PUBLIC_HANDOFF_CHANGED");
    check(scheduler_until(scheduler, [&] { return changed; }), "handoff-aba-is-changed-not-ready");
    bool busy = false;
    log->force_sync_handoff(td::PromiseCreator::lambda([&](td::Result<td::Unit> result) {
      need(result.is_ok());
      pmc->for_each([&](td::Slice, td::Slice) {
        auto pair = KV::try_snapshot_pair(pmc, config); need(pair.is_ok()); busy |= !bool(pair.move_as_ok());
      });
    }), "PUBLIC_HANDOFF_BUSY");
    check(scheduler_until(scheduler, [&] { return busy; }), "handoff-contention-is-not-ready");
  }
  {
    struct stat before{}; gap_check(::stat(context->binlog_path().c_str(), &before) == 0, "gap-before-stat");
    auto reserved = log->next_event_id(); bool completed = false;
    log->force_sync_handoff(td::PromiseCreator::lambda([&](td::Result<td::Unit> result) {
      gap_check(result.is_ok(), "gap-handoff-error"); completed = true;
    }), "PUBLIC_HANDOFF_GAP");
    gap_check(scheduler_until(scheduler, [&] { return logs.gap_entered; }), "gap-actor-entry-missing");
    // A real scheduler timer must run >=50ms AFTER observed writer entry. A
    // descheduled host thread cannot turn an unpumped window into evidence.
    bool elapsed = false;
    auto window = td::create_actor<PublicGapWindow>("PUBLIC_GAP_WINDOW", &elapsed);
    gap_check(scheduler_until(scheduler, [&] { return elapsed; }), "gap-window-progress-missing");
    struct stat during{};
    gap_check(!completed, "gap-premature-completion");
    gap_check(::stat(context->binlog_path().c_str(), &during) == 0, "gap-during-stat");
    gap_check(during.st_size == before.st_size, "gap-premature-file-change");
    log->add_raw_event(reserved, td::BinlogEvent::create_raw(reserved, 0x12345678, 0, td::EmptyStorer()));
    gap_check(scheduler_until(scheduler, [&] { return completed; }), "gap-resume-callback-missing");
    log->erase_batch({reserved}); // No extra live record remains in the final replay.
    check(true, "handoff-waits-for-observed-reservation-gap");
  }
  {
    for (unsigned i = 0; i != 16; ++i) { pmc->set("PUBLIC_COMPACTION", std::string(8192, char('A' + i))); }
    pmc->erase("PUBLIC_COMPACTION"); auto expected = stamp<KV>(pmc);
    bool quiescent = false;
    log->force_sync_handoff(td::PromiseCreator::lambda([&](td::Result<td::Unit> result) {
      need(result.is_ok()); auto view = snapshot<KV>(pmc);
      struct stat temporary{}; errno = 0;
      bool no_temp = ::lstat((context->binlog_path() + ".new").c_str(), &temporary) != 0 && errno == ENOENT;
      quiescent = logs.compactions > 0 && no_temp && view.matches(expected);
    }), "PUBLIC_HANDOFF_MAINTENANCE");
    check(scheduler_until(scheduler, [&] { return quiescent; }), "handoff-after-actual-healthy-compaction");
  }
  bool synced = false;
  log->force_sync(td::PromiseCreator::lambda([&](td::Result<td::Unit> result) { need(result.is_ok()); synced = true; }), "PUBLIC_KV_SNAPSHOT");
  need(scheduler_until(scheduler, [&] { return synced; }));
  bool closed = false, canceled = false, late_success = false;
  log->force_sync_handoff(td::PromiseCreator::lambda([&](td::Result<td::Unit> result) {
    if (result.is_error()) { result.move_as_error().ignore(); canceled = true; }
    else { late_success = true; }
  }), "PUBLIC_HANDOFF_CANCELED");
  pmc->close(td::PromiseCreator::lambda([&](td::Result<td::Unit> result) { need(result.is_ok()); closed = true; }));
  need(scheduler_until(scheduler, [&] { return closed && (canceled || late_success); }));
  check(canceled && !late_success, "close-cancels-unsynced-handoff");
  check(closed && refused<KV>(pmc), "native-sync-close-invalidates-snapshot");
  pmc->close(); config->close(); log.reset(); pump(scheduler, 0.01);
  auto raw = std::make_shared<td::Binlog>();
  auto replay = std::make_shared<RawKV>(), replay_config = std::make_shared<RawKV>();
  replay->external_init_begin(PmcMagic); replay_config->external_init_begin(ConfigMagic);
  std::size_t rows = 0;
  need(okay(raw->init(context->binlog_path(), [&](const td::BinlogEvent &event) {
    need(event.type_ == PmcMagic || event.type_ == ConfigMagic);
    (event.type_ == PmcMagic ? replay : replay_config)->external_init_handle(event); ++rows;
  }, td::DbKey::raw_key(std::string(32, 'A')))));
  replay->external_init_finish(raw); replay_config->external_init_finish(raw);
  {
    auto a = snapshot<RawKV>(replay), b = snapshot<RawKV>(replay_config);
    check(rows == 3 && a.find("PUBLIC_VALUE") && *a.find("PUBLIC_VALUE") == "A" && a.find("PUBLIC_EMPTY") &&
          b.find("PUBLIC_OPTION") && *b.find("PUBLIC_OPTION") == "I1", "actual-native-reopen-replay");
  }
  auto moved = std::make_shared<RawKV>(); moved->external_init_finish(raw);
  auto before = stamp<RawKV>(moved), donor = stamp<RawKV>(replay);
  moved->external_init_handle(std::move(*replay)); need(refused<RawKV>(moved) && refused<RawKV>(replay));
  moved->external_init_finish(raw);
  {
    auto view = snapshot<RawKV>(moved);
    check(!view.matches(before) && !view.matches(donor) && view.find("PUBLIC_VALUE"), "import-replaces-both-incarnations");
  }
  need(okay(raw->close())); context->stop();
  SET_VERBOSITY_LEVEL(VERBOSITY_NAME(FATAL)); td::log_interface = previous_log;
  std::cout << "PUBLIC_KV_SNAPSHOTS_ACCEPTED account_engine=0\n";
}
