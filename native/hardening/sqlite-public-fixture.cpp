// Telegram-CLI MIT-licensed test harness. PUBLIC disposable data only; no TDLib Client.
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>
#include <sys/stat.h>
#include "td/db/DbKey.h"
#include "td/db/SqliteDb.h"
#include "td/db/detail/RawSqliteDb.h"
#include "sqlite/sqlite3.h"

namespace {
const char *alpha = "PUBLIC_G1_ALPHA_NOT_A_CREDENTIAL";
const char *beta = "PUBLIC_G1_BETA_NOT_A_CREDENTIAL";
int fail(const char *code) { std::cout << code << '\n'; return 40; }
bool sql(tdsqlite3 *db, const char *query) {
  return tdsqlite3_exec(db, query, nullptr, nullptr, nullptr) == SQLITE_OK;
}
int setup(const std::string &kind, const std::string &path) {
  if (std::filesystem::exists(path)) return fail("FIXTURE_ALREADY_EXISTS");
  tdsqlite3 *raw = nullptr;
  if (tdsqlite3_open_v2(path.c_str(), &raw, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE, nullptr) != SQLITE_OK)
    return fail("FIXTURE_OPEN_FAILED");
  bool ok = true;
  if (kind == "cipher4" || kind == "cipher3") {
    ok = sql(raw, "PRAGMA key = 'PUBLIC_G1_ALPHA_NOT_A_CREDENTIAL'");
    ok = ok && sql(raw, kind == "cipher3" ? "PRAGMA cipher_compatibility = 3" : "PRAGMA cipher_compatibility = 4");
  } else {
    ok = sql(raw, "PRAGMA page_size = 4096");
  }
  ok = ok && sql(raw, "PRAGMA journal_mode = DELETE");
  ok = ok && sql(raw, "CREATE TABLE public_items(value TEXT)");
  ok = ok && sql(raw, "INSERT INTO public_items VALUES('PUBLIC_ROW_ONE')");
  ok = ok && sql(raw, "PRAGMA user_version = 1");
  ok = (tdsqlite3_close(raw) == SQLITE_OK) && ok;
  if (!ok) return fail("FIXTURE_SQL_FAILED");
  if (kind == "corrupt") {
    // The fixture has exactly one table at root page2, with fixed4096byte pages.
    // Corrupt its btree page type, not the header/schema page. Require SQLITE_CORRUPT below.
    std::fstream file(path, std::ios::in | std::ios::out | std::ios::binary);
    if (!file || std::filesystem::file_size(path) < 8192) return fail("FIXTURE_PAGE_MISSING");
    file.seekp(4096); file.put(static_cast<char>(0xff)); file.flush();
    if (!file) return fail("FIXTURE_CORRUPT_FAILED");
  }
  std::cout << "PUBLIC_FIXTURE_CREATED\n"; return 0;
}
int check(const std::string &kind, const std::string &path) {
  const auto a = td::DbKey::password(alpha);
  const auto b = td::DbKey::password(beta);
  if (kind == "corrupt") {
    tdsqlite3 *db = nullptr;
    if (tdsqlite3_open_v2(path.c_str(), &db, SQLITE_OPEN_READWRITE, nullptr) != SQLITE_OK)
      return fail("CORRUPT_OPEN_FAILED");
    {
      td::detail::RawSqliteDb owner(db, path);
      tdsqlite3_stmt *stmt = nullptr;
      auto rc = tdsqlite3_prepare_v2(db, "SELECT * FROM public_items", -1, &stmt, nullptr);
      if (rc == SQLITE_OK) rc = tdsqlite3_step(stmt);
      // No simulated error injection: require the real native library's error code11.
      if (rc != SQLITE_CORRUPT || tdsqlite3_errcode(db) != SQLITE_CORRUPT) {
        if (stmt) tdsqlite3_finalize(stmt);
        return fail("EXPECTED_REAL_SQLITE_CORRUPT");
      }
      auto error = owner.last_error();
      if (stmt) tdsqlite3_finalize(stmt);
      if (error.is_ok()) return fail("CORRUPTION_NOT_REPORTED");
    }
    if (!std::filesystem::exists(path)) return fail("CORRUPT_ORIGINAL_DELETED");
    std::cout << "SQLITE_CORRUPT_11_REFUSED\n"; return 0;
  }
  if (kind == "missing" || kind == "missing-existing" || kind == "empty-existing") {
    auto result = td::SqliteDb::open_with_key(path, kind != "missing-existing", td::DbKey::empty());
    if (result.is_ok()) return fail("MISSING_STORE_RECREATED");
  } else if (kind == "compat3") {
    auto result = td::SqliteDb::open_with_key(path, false, a);
    if (result.is_ok()) return fail("COMPATIBILITY_FALLBACK_USED");
  } else if (kind == "explicit-compat3") {
    auto result = td::SqliteDb::open_with_key(path, false, a, 3);
    if (result.is_ok()) return fail("EXPLICIT_COMPATIBILITY_ADMITTED");
  } else if (kind == "wrong-key") {
    auto result = td::SqliteDb::open_with_key(path, true, b);
    if (result.is_ok()) return fail("WRONG_KEY_ADMITTED");
  } else if (kind == "rekey" || kind == "decrypt" || kind == "encrypt") {
    auto new_key = kind == "decrypt" ? td::DbKey::empty() : b;
    auto old_key = kind == "encrypt" ? td::DbKey::empty() : a;
    auto result = td::SqliteDb::change_key(path, true, new_key, old_key);
    if (result.is_ok()) return fail("KEY_MIGRATION_PERFORMED");
  } else if (kind == "healthy" || kind == "current-key") {
    {
      auto result = kind == "current-key" ? td::SqliteDb::change_key(path, true, a, b)
                                           : td::SqliteDb::open_with_key(path, false, a);
      if (result.is_error()) return fail("HEALTHY_OPEN_REFUSED");
      auto db = result.move_as_ok();
      if (db.exec("INSERT INTO public_items VALUES('PUBLIC_ROW_TWO')").is_error())
        return fail("HEALTHY_WRITE_FAILED");
    }
    auto result = td::SqliteDb::open_with_key(path, false, a);
    if (result.is_error()) return fail("HEALTHY_REOPEN_FAILED");
    auto db = result.move_as_ok();
    auto query = db.get_statement("SELECT count(*) FROM public_items");
    if (query.is_error()) return fail("HEALTHY_QUERY_FAILED");
    auto stmt = query.move_as_ok();
    if (stmt.step().is_error() || !stmt.has_row() || stmt.view_int32(0) != 2)
      return fail("HEALTHY_RETAINED_DATA_FAILED");
  } else {
    return fail("UNKNOWN_PUBLIC_CASE");
  }
  std::cout << "PUBLIC_CASE_PASSED\n"; return 0;
}
}
int main(int argc, char **argv) {
  umask(0077);
  if (argc != 4) return fail("FIXTURE_ARGUMENTS");
  const std::string action(argv[1]), kind(argv[2]), path(argv[3]);
  if (path.rfind("/stage/cases/", 0) != 0 || path.find("..") != std::string::npos ||
      !std::filesystem::is_directory(std::filesystem::path(path).parent_path()))
    return fail("FIXTURE_PATH_REFUSED");
  if (action == "setup") return setup(kind, path);
  if (action == "check") return check(kind, path);
  return fail("FIXTURE_ACTION_REFUSED");
}
