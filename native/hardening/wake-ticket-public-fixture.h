// SPDX-License-Identifier: MIT
// Included only by the opt-in PUBLIC storage fixture after its helpers/types.
// No TdDb/Td/Client/Global or credentials; all timing/iteration bounds are TEST ONLY.
#pragma once
#include "td/utils/port/PollFlags.h"
#include <chrono>
#include <future>
#include <thread>

// A separate fixture thread holds an ACTUAL nonmutating native visitor lock.
// The caller owns no borrowed view when subscribing or pumping the actor.
class PublicVisitorHold final {
 public:
  explicit PublicVisitorHold(std::shared_ptr<KV> map)
      : entered_(entered_promise_.get_future()), resume_(release_promise_.get_future()),
        worker_([this, map = std::move(map)] {
          bool once = false;
          map->for_each([&](td::Slice, td::Slice) {
            if (!once) { once = true; entered_promise_.set_value(); resume_.wait(); }
          });
        }) {
    gap_check(entered_.wait_for(std::chrono::seconds(1)) == std::future_status::ready,
              "wake-fixture-holder-not-entered");
  }
  PublicVisitorHold(const PublicVisitorHold &) = delete;
  PublicVisitorHold &operator=(const PublicVisitorHold &) = delete;
  ~PublicVisitorHold() { release(); }
  void release() {
    if (worker_.joinable()) { release_promise_.set_value(); worker_.join(); }
  }
 private:
  std::promise<void> entered_promise_, release_promise_;
  std::future<void> entered_, resume_;
  std::thread worker_;
};

class PublicWakeReceiver final : public td::Actor {
 public:
  PublicWakeReceiver(KV::WakeSubscription subscription, std::shared_ptr<KV> first,
      std::shared_ptr<KV> second, bool *delivered, bool *started, bool *cancelled)
      : subscription_(std::move(subscription)), first_(std::move(first)), second_(std::move(second)),
        delivered_(delivered), started_(started), cancelled_(cancelled) {}
  void cancel_wait() { detach(); subscription_.cancel(); *cancelled_ = true; stop(); }
 private:
  KV::WakeSubscription subscription_;
  std::shared_ptr<KV> first_, second_;
  bool *delivered_, *started_, *cancelled_;
  bool attached_ = false;
  void detach() {
    if (attached_) {
      td::Scheduler::instance()->unsubscribe(subscription_.poll_info().get_pollable_fd_ref());
      attached_ = false;
    }
  }
  void start_up() final {
    td::Scheduler::instance()->subscribe(subscription_.poll_info().extract_pollable_fd(this), td::PollFlags::Read());
    attached_ = true; *started_ = true; loop();
  }
  void loop() final {
    if (!subscription_.signalled()) { return; }
    subscription_.drain(); detach(); subscription_.cancel();
    auto pair = KV::try_observed_snapshot_pair(first_, second_);
    gap_check(pair.is_ok(), "wake-actor-capture-error");
    auto observed = pair.move_as_ok();
    gap_check(bool(observed) && !bool(observed.busy), "wake-actor-guards-not-free");
    observed = KV::ObservedSnapshotPair(); // All views gone before outward flag.
    *delivered_ = true; stop();
  }
  void tear_down() final { detach(); subscription_.cancel(); }
};

KV::BusyTicket public_busy_ticket(const std::shared_ptr<KV> &first, const std::shared_ptr<KV> &second) {
  auto result = KV::try_observed_snapshot_pair(first, second);
  gap_check(result.is_ok(), "wake-observed-pair-error");
  auto observed = result.move_as_ok();
  gap_check(!bool(observed) && bool(observed.busy), "wake-expected-busy-ticket");
  return std::move(observed.busy);
}
KV::WakeSubscription public_subscribe(KV::BusyTicket ticket) {
  auto result = std::move(ticket).subscribe();
  gap_check(result.is_ok(), "wake-subscribe-error");
  auto subscription = result.move_as_ok(); need(bool(subscription)); return subscription;
}

void public_wake_ticket_checks(td::Scheduler &scheduler, const std::shared_ptr<KV> &pmc,
                              const std::shared_ptr<KV> &config, const std::shared_ptr<td::ConcurrentBinlog> &log) {
  using WakeState = g1::detail::KvWakeState;
  using ReadGuard = g1::detail::KvWakeGuard<td::RwMutex::ReadLock>;
  using WriteGuard = g1::detail::KvWakeGuard<td::RwMutex::WriteLock>;
  {
    auto result = KV::try_observed_snapshot_pair(pmc, config); need(result.is_ok());
    auto pair = result.move_as_ok();
    check(bool(pair) && !bool(pair.busy), "wake-observed-pair-acquired-without-ticket");
  }
  {
    auto bad = KV::try_observed_snapshot_pair(pmc, pmc); need(bad.is_error()); bad.move_as_error().ignore();
    auto empty = std::make_shared<KV>(); auto uninitialized = KV::try_observed_snapshot_pair(empty, config);
    need(uninitialized.is_error()); uninitialized.move_as_error().ignore();
    check(true, "wake-invalid-pair-lifecycle-is-error-not-busy");
  }
  {
    PublicVisitorHold holder(pmc); auto ticket = public_busy_ticket(pmc, config);
    auto registration = public_subscribe(std::move(ticket)); need(!registration.signalled());
    holder.release();
    check(registration.signalled(), "wake-first-map-held-lock-release-signals"); registration.drain();
  }
  {
    PublicVisitorHold holder(config); auto ticket = public_busy_ticket(pmc, config);
    auto registration = public_subscribe(std::move(ticket)); need(!registration.signalled());
    { auto unrelated = snapshot<KV>(pmc); }
    gap_check(!registration.signalled(), "wake-partial-pair-self-wake");
    holder.release();
    check(registration.signalled(), "wake-second-map-excludes-partial-first-self-wake");
  }
  {
    auto held = snapshot<KV>(pmc); auto ticket = public_busy_ticket(pmc, config); held = KV::Snapshot();
    auto registration = public_subscribe(std::move(ticket));
    check(registration.signalled(), "wake-release-before-subscribe-is-not-lost");
  }
  {
    PublicVisitorHold holder(pmc); auto ticket = public_busy_ticket(pmc, config);
    auto first = std::move(ticket).subscribe(); need(first.is_ok());
    auto second = std::move(ticket).subscribe(); need(second.is_error()); second.move_as_error().ignore();
    auto registration = first.move_as_ok(); holder.release();
    check(registration.signalled(), "wake-ticket-subscribe-is-one-use");
  }
  {
    const auto before = stamp<KV>(pmc); std::optional<KV::BusyTicket> ticket;
    pmc->for_each([&](td::Slice, td::Slice) { if (!ticket) { ticket.emplace(public_busy_ticket(pmc, config)); } });
    need(ticket.has_value()); auto registration = public_subscribe(std::move(*ticket));
    // No intervening snapshot/getter: notification must come from actual visitor unlock.
    need(registration.signalled());
    check(stamp<KV>(pmc) == before, "wake-native-nonmutating-visitor-unlock-no-revision");
  }
  {
    auto state = std::make_shared<WakeState>(); td::RwMutex mutex;
    auto result = state->subscribe_after(state->observe()); need(result.is_ok()); auto registration = result.move_as_ok();
    { ReadGuard held(state, mutex.lock_read()); auto busy = mutex.try_lock_write(); need(busy.is_ok());
      gap_check(!bool(busy.move_as_ok()) && !registration.signalled(), "wake-read-adapter-not-held"); }
    check(registration.signalled(), "wake-read-lock-adapter-unlock-signals");
  }
  {
    auto state = std::make_shared<WakeState>(); td::RwMutex mutex;
    auto result = state->subscribe_after(state->observe()); need(result.is_ok()); auto registration = result.move_as_ok();
    { WriteGuard held(state, mutex.lock_write()); gap_check(!registration.signalled(), "wake-write-adapter-premature"); }
    check(registration.signalled(), "wake-noop-write-lock-adapter-unlock-signals");
  }
  {
    auto state = std::make_shared<WakeState>(); auto first = state->subscribe_after(state->observe()); need(first.is_ok());
    auto old = first.move_as_ok(); state->released(); need(old.signalled()); old.drain();
    auto second = state->subscribe_after(state->observe()); need(second.is_ok()); auto next = second.move_as_ok();
    need(!next.signalled()); old.cancel(); state->released();
    check(next.signalled() && !old.signalled(), "wake-one-shot-does-not-consume-next-epoch");
  }
  {
    PublicVisitorHold holder(pmc); auto registration = public_subscribe(public_busy_ticket(pmc, config));
    registration.cancel(); registration.cancel(); holder.release();
    check(!bool(registration) && !registration.signalled(), "wake-registration-cancel-idempotent");
  }
  {
    auto ephemeral = std::make_shared<KV>(); ephemeral->external_init_finish(log);
    std::weak_ptr<KV> weak = ephemeral; auto held = snapshot<KV>(ephemeral);
    auto ticket = public_busy_ticket(ephemeral, config); held = KV::Snapshot(); ephemeral.reset(); need(!weak.expired());
    auto registration = public_subscribe(std::move(ticket)); need(!weak.expired()); registration.cancel();
    check(weak.expired(), "wake-ticket-and-subscription-retain-owner-until-cancel");
  }
  {
    auto temporary = std::make_shared<KV>(); temporary->external_init_finish(log);
    auto held = snapshot<KV>(temporary); auto ticket = public_busy_ticket(temporary, config); held = KV::Snapshot();
    temporary->close(); auto registration = public_subscribe(std::move(ticket)); need(registration.signalled());
    auto after_close = KV::try_observed_snapshot_pair(temporary, config);
    need(after_close.is_error()); after_close.move_as_error().ignore();
    check(true, "wake-lifecycle-signal-does-not-grant-a-view");
  }
  {
    auto state = std::make_shared<WakeState>(); auto invalid = state->subscribe_after(state->observe() + 1);
    need(invalid.is_error()); invalid.move_as_error().ignore();
    WakeState unowned; auto owner_error = unowned.subscribe_after(0); need(owner_error.is_error()); owner_error.move_as_error().ignore();
    check(true, "wake-invalid-epoch-owner-are-errors");
  }
  {
    auto epoch = std::numeric_limits<td::uint64>::max() - 1;
    need(g1::detail::advance_wake_epoch(epoch));
    check(!g1::detail::advance_wake_epoch(epoch) && epoch == std::numeric_limits<td::uint64>::max(),
          "wake-epoch-exhaustion-never-wraps");
  }
  {
    // TEST-ONLY iteration count; writer has no actor Scheduler on its thread.
    for (unsigned iteration = 0; iteration != 64; ++iteration) {
      auto state = std::make_shared<WakeState>(); const auto before = state->observe();
      std::thread producer([state] { state->released(); });
      auto result = state->subscribe_after(before); producer.join(); need(result.is_ok());
      auto registration = result.move_as_ok(); gap_check(registration.signalled(), "wake-release-register-race");
    }
    check(true, "wake-thread-release-registration-race-not-lost");
  }
  {
    for (unsigned iteration = 0; iteration != 64; ++iteration) {
      auto state = std::make_shared<WakeState>(); auto result = state->subscribe_after(state->observe());
      need(result.is_ok()); auto registration = result.move_as_ok();
      std::thread producer([state] { state->released(); }); registration.cancel(); producer.join();
      gap_check(!bool(registration), "wake-cancel-race-not-empty");
    }
    check(true, "wake-thread-release-cancel-keeps-channel-safe");
  }
  {
    PublicVisitorHold holder(pmc);
    auto registration = public_subscribe(public_busy_ticket(pmc, config)); need(!registration.signalled());
    holder.release();
    check(registration.signalled(), "wake-native-visitor-thread-signals-without-scheduler");
  }
  {
    PublicVisitorHold holder(pmc); auto registration = public_subscribe(public_busy_ticket(pmc, config));
    bool delivered = false, started = false, cancelled = false;
    auto receiver = td::create_actor<PublicWakeReceiver>("PUBLIC_WAKE_RECEIVER", std::move(registration), pmc, config,
                                                       &delivered, &started, &cancelled);
    holder.release(); gap_check(!delivered && !started, "wake-actor-inline-delivery");
    need(scheduler_until(scheduler, [&] { return delivered; }));
    check(started && !cancelled, "wake-real-poll-actor-delivery-after-guard-release");
    receiver.reset(); pump(scheduler, 0.01);
  }
  {
    PublicVisitorHold holder(pmc);
    auto registration = public_subscribe(public_busy_ticket(pmc, config));
    bool delivered = false, started = false, cancelled = false;
    auto receiver = td::create_actor<PublicWakeReceiver>("PUBLIC_WAKE_KERNEL_POLL", std::move(registration), pmc, config,
                                                       &delivered, &started, &cancelled);
    need(scheduler_until(scheduler, [&] { return started; })); need(!delivered);
    holder.release(); need(!delivered); // Writer thread cannot run this actor inline.
    need(scheduler_until(scheduler, [&] { return delivered; }));
    check(!cancelled, "wake-real-poll-actor-waits-for-cross-thread-unlock");
    receiver.reset(); pump(scheduler, 0.01);
  }
  {
    PublicVisitorHold holder(pmc);
    auto registration = public_subscribe(public_busy_ticket(pmc, config));
    bool delivered = false, started = false, cancelled = false;
    auto receiver = td::create_actor<PublicWakeReceiver>("PUBLIC_WAKE_CANCEL", std::move(registration), pmc, config,
                                                       &delivered, &started, &cancelled);
    td::send_closure_later(receiver.get(), &PublicWakeReceiver::cancel_wait);
    need(scheduler_until(scheduler, [&] { return cancelled; }));
    holder.release(); receiver.reset(); pump(scheduler, 0.01);
    check(started && !delivered, "wake-real-poll-actor-cancels-before-late-release");
  }
}
