#pragma once

// G1 INTERNAL notification bookkeeping, not a map lock or authority certificate.
// No callbacks, actor sends, private values, paths or credentials are stored here.
#include "td/utils/common.h"
#include "td/utils/logging.h"
#include "td/utils/port/config.h"
#include "td/utils/port/EventFd.h"
#include "td/utils/Status.h"

#include <algorithm>
#include <atomic>
#include <limits>
#include <memory>
#include <mutex>
#include <utility>
#include <vector>

#if !defined(TD_EVENTFD_LINUX)
#error "TGCLI_G1_WAKE_BACKEND_UNSUPPORTED"
#endif

namespace telegram_cli {
namespace native_g1 {
namespace detail {

inline bool advance_wake_epoch(td::uint64 &epoch) {
  if (epoch == std::numeric_limits<td::uint64>::max()) { return false; }
  ++epoch;
  return true;
}

// EventFdLinux::release is explicitly thread-safe in the pinned implementation.
// Unlike send_closure_later, it does NOT require a thread-local Scheduler guard.
// Allocation/init and actor subscription happen outside borrowed map guards.
class KvWakeChannel final {
 public:
  KvWakeChannel() { event_.init(); }
  KvWakeChannel(const KvWakeChannel &) = delete;
  KvWakeChannel &operator=(const KvWakeChannel &) = delete;

  void signal() {
    if (cancelled_.load(std::memory_order_acquire)) { return; }
    bool expected = false;
    if (signalled_.compare_exchange_strong(expected, true, std::memory_order_acq_rel)) {
      // No user callback. A concurrent cancel may leave a harmless late fd signal;
      // this shared channel keeps the fd alive until the write has finished.
      event_.release();
    }
  }
  void cancel() { cancelled_.store(true, std::memory_order_release); }
  bool signalled() const {
    return signalled_.load(std::memory_order_acquire) && !cancelled_.load(std::memory_order_acquire);
  }
  td::PollableFdInfo &poll_info() { return event_.get_poll_info(); }
  void drain() { event_.acquire(); }

 private:
  td::EventFd event_;
  std::atomic<bool> cancelled_{false};
  std::atomic<bool> signalled_{false};
};

class KvWakeRegistration;

class KvWakeState final : public std::enable_shared_from_this<KvWakeState> {
 public:
  KvWakeState() = default;
  KvWakeState(const KvWakeState &) = delete;
  KvWakeState &operator=(const KvWakeState &) = delete;

  td::uint64 observe() const { return epoch_.load(std::memory_order_acquire); }
  td::Result<KvWakeRegistration> subscribe_after(td::uint64 observed);

  // Called AFTER releasing the underlying read/write guard, including no-ops and
  // nonmutating views. Stable state belongs to the KV object, not a map incarnation.
  void released() {
    std::vector<std::shared_ptr<KvWakeChannel>> ready;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      auto epoch = epoch_.load(std::memory_order_relaxed);
      CHECK(advance_wake_epoch(epoch)); // Never wrap a wake token.
      epoch_.store(epoch, std::memory_order_release);
      ready.swap(waiters_);
    }
    // Registry lock is gone; only thread-safe nonblocking fd signals, no callbacks.
    for (const auto &channel : ready) { channel->signal(); }
  }

 private:
  friend class KvWakeRegistration;
  void cancel(const std::shared_ptr<KvWakeChannel> &channel) {
    channel->cancel();
    std::lock_guard<std::mutex> lock(mutex_);
    waiters_.erase(std::remove(waiters_.begin(), waiters_.end(), channel), waiters_.end());
  }

  std::atomic<td::uint64> epoch_{0};
  std::mutex mutex_; // Notification registry only; NEVER protects/opens/reads the KV map.
  std::vector<std::shared_ptr<KvWakeChannel>> waiters_;
};

// All KV read/write guards use this adapter. It never changes locking semantics;
// release notification follows actual unlock and cannot call user/actor code.
template <class LockT>
class KvWakeGuard final {
 public:
  KvWakeGuard() = default;
  KvWakeGuard(std::shared_ptr<KvWakeState> state, LockT lock)
      : state_(std::move(state)), lock_(std::move(lock)) {}
  KvWakeGuard(const KvWakeGuard &) = delete;
  KvWakeGuard &operator=(const KvWakeGuard &) = delete;
  KvWakeGuard(KvWakeGuard &&other) noexcept
      : state_(std::move(other.state_)), lock_(std::move(other.lock_)) {}
  KvWakeGuard &operator=(KvWakeGuard &&other) noexcept {
    if (this != &other) {
      reset(); state_ = std::move(other.state_); lock_ = std::move(other.lock_);
    }
    return *this;
  }
  ~KvWakeGuard() { reset(); }
  explicit operator bool() const { return bool(lock_); }
  void reset() {
    if (lock_) {
      lock_.reset(); // Unlock BEFORE any notification or retained-state release.
      state_->released();
    }
    state_.reset();
  }

 private:
  std::shared_ptr<KvWakeState> state_;
  LockT lock_;
};

// Move-only, one-shot registration. Caller must unsubscribe its poll observer on
// the observer's scheduler BEFORE cancel/destruction. cancel does not mean native
// resources stopped, does not call a completion, and does not revoke authority.
class KvWakeRegistration final {
 public:
  KvWakeRegistration() = default;
  KvWakeRegistration(const KvWakeRegistration &) = delete;
  KvWakeRegistration &operator=(const KvWakeRegistration &) = delete;
  KvWakeRegistration(KvWakeRegistration &&other) noexcept
      : state_(std::move(other.state_)), channel_(std::move(other.channel_)) {}
  KvWakeRegistration &operator=(KvWakeRegistration &&other) noexcept {
    if (this != &other) {
      cancel();
      state_ = std::move(other.state_); channel_ = std::move(other.channel_);
    }
    return *this;
  }
  ~KvWakeRegistration() { cancel(); }
  explicit operator bool() const { return channel_ != nullptr; }
  bool signalled() const { return channel_ && channel_->signalled(); }
  td::PollableFdInfo &poll_info() { CHECK(channel_); return channel_->poll_info(); }
  void drain() { CHECK(channel_); channel_->drain(); }
  void cancel() {
    auto state = std::move(state_);
    auto channel = std::move(channel_);
    if (state && channel) { state->cancel(channel); }
    // Channel/EventFd and registry ownership are released outside registry lock.
  }

 private:
  friend class KvWakeState;
  KvWakeRegistration(std::shared_ptr<KvWakeState> state, std::shared_ptr<KvWakeChannel> channel)
      : state_(std::move(state)), channel_(std::move(channel)) {}
  std::shared_ptr<KvWakeState> state_;
  std::shared_ptr<KvWakeChannel> channel_;
};

inline td::Result<KvWakeRegistration> KvWakeState::subscribe_after(td::uint64 observed) {
  auto self = weak_from_this().lock();
  if (!self) { return td::Status::Error(400, "TGCLI_G1_WAKE_OWNER_INVALID"); }
  // Creating the fd can fail-stop in pinned EventFdLinux::init; it never silently
  // converts an I/O failure to an empty-success BUSY or a usable registration.
  auto channel = std::make_shared<KvWakeChannel>();
  bool already_released = false;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    const auto epoch = epoch_.load(std::memory_order_acquire);
    if (observed > epoch) { return td::Status::Error(400, "TGCLI_G1_WAKE_EPOCH_INVALID"); }
    already_released = epoch != observed;
    if (!already_released) { waiters_.push_back(channel); }
  }
  // Serialize observation/registration with epoch advancement; a release between
  // failed probe and subscribe either advanced epoch or will drain this waiter.
  if (already_released) { channel->signal(); }
  return KvWakeRegistration(std::move(self), std::move(channel));
}

} // namespace detail
} // namespace native_g1
} // namespace telegram_cli
