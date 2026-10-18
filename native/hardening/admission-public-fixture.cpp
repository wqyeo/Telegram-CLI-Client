// SPDX-License-Identifier: MIT
// PUBLIC filesystem/record fixture only. Seed bytes are deliberately NOT TDLib databases.
#include "store-admission.h"
#include <iostream>

namespace g1 = telegram_cli::native_g1;
using g1::detail::Fd;

static g1::Configuration configuration(const std::string &flavor) {
  g1::Configuration c;
  c.store_id.fill(0x50); // PUBLIC non-secret identity
  c.schema_id.fill(0x53); // PUBLIC placeholder, not a production schema fingerprint
  c.schema_version = 77; // PUBLIC placeholder, not TDLib's current_db_version()
  c.flags = g1::FileDatabase | g1::ChatDatabase | g1::MessageDatabase;
  if (flavor == "binlog-only") { c.flags = 0; }
  if (flavor == "test-dc") { c.flags |= g1::TestDc; }
  if (flavor == "wrong-flags") { c.flags = g1::FileDatabase; }
  if (flavor == "wrong-id") { c.store_id[0] ^= 1; }
  if (flavor == "wrong-schema") { c.schema_id[0] ^= 1; }
  if (flavor == "wrong-version") { ++c.schema_version; }
  if (flavor == "invalid-flags") { c.flags = g1::MessageDatabase; }
  if (flavor == "zero-id") { c.store_id.fill(0); }
  return c;
}
static bool seed_file(int root, const char *name) {
  Fd fd(::openat(root, name, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, g1::PrivateFileMode));
  constexpr unsigned char bytes[] = "PUBLIC_NON_DATABASE_FIXTURE";
  return fd.get() >= 0 && g1::detail::write_bytes(fd.get(), bytes, sizeof(bytes)) && ::fsync(fd.get()) == 0;
}
static bool seed_components(int root, const g1::Configuration &c) {
  if (!seed_file(root, (c.flags & g1::TestDc) ? "td_test.binlog" : "td.binlog")) { return false; }
  return !(c.flags & g1::FileDatabase) || seed_file(root, (c.flags & g1::TestDc) ? "db_test.sqlite" : "db.sqlite");
}
static int result(bool accepted) {
  std::cout << (accepted ? "PUBLIC_RECORD_ACCEPTED\n" : "PUBLIC_RECORD_REFUSED\n");
  return accepted ? 0 : 20;
}
int main(int argc, char **argv) {
  ::umask(S_IRWXG | S_IRWXO);
  if (argc != 4) { return 2; }
  std::string mode(argv[1]), flavor(argv[3]);
  if (flavor != "full" && flavor != "binlog-only" && flavor != "test-dc" && flavor != "wrong-flags" &&
      flavor != "wrong-id" && flavor != "wrong-schema" && flavor != "wrong-version" &&
      flavor != "invalid-flags" && flavor != "zero-id") { return 2; }
  Fd parent(::open(argv[2], O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
  if (parent.get() < 0) { return 2; }
  auto c = configuration(flavor);
  if (mode == "open") { return result(g1::ExistingRecord::open(parent.get(), "native", c).has_value()); }
  if (mode == "reserve-invalid-name") {
    return result(g1::NewReservation::reserve(parent.get(), "../escape", c).has_value());
  }
  if (mode != "reserve" && mode != "create" && mode != "commit-missing" && mode != "commit-unknown" &&
      mode != "commit-replaced" && mode != "commit-renamed" && mode != "repeat-commit" &&
      mode != "failed-then-filled" && mode != "commit-parent-mode") { return 2; }
  auto reservation = g1::NewReservation::reserve(parent.get(), "native", c);
  if (!reservation) { return result(false); }
  if (mode == "reserve") { return result(true); }
  if (mode != "commit-missing" && mode != "failed-then-filled" && !seed_components(reservation->root_fd(), c)) { return 2; }
  if (mode == "commit-unknown" && !seed_file(reservation->root_fd(), "unexpected")) { return 2; }
  if (mode == "commit-replaced") {
    // Fixture-only mutation, BEFORE testing commit. Preserve the old record as evidence.
    if (::renameat(reservation->root_fd(), g1::RecordName, reservation->root_fd(), "old-record") != 0 ||
        !seed_file(reservation->root_fd(), g1::RecordName)) { return 2; }
  }
  if (mode == "commit-renamed" && ::renameat(parent.get(), "native", parent.get(), "moved") != 0) { return 2; }
  if (mode == "commit-parent-mode" &&
      ::fchmod(parent.get(), S_IRWXU | S_IRGRP | S_IXGRP | S_IROTH | S_IXOTH) != 0) { return 2; }
  bool committed = reservation->commit_configuration();
  if (mode == "failed-then-filled") {
    // Test-only fill after refusal: the consumed capability must STILL refuse.
    // This is not an engine repair/retry path and no database is created.
    if (committed || !seed_components(reservation->root_fd(), c)) { return 2; }
    return result(!reservation->commit_configuration());
  }
  if (mode == "repeat-commit") { return result(committed && !reservation->commit_configuration()); }
  return result(committed);
}
