"""Local MariaDB logical recovery. Dumps are sensitive, trusted executable data.

Only new furniture_shop_test_restore_* databases can be restored. No overwrite,
drop, production restore, arbitrary SQL file, or credentials-in-argv mode exists.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

TABLE_COUNT = 47
CONTENT_TYPE = "application/vnd.tv3.mariadb-logical-backup+json"
NAME = re.compile(r"[a-z][a-z0-9_]{0,63}\Z")
HASH = re.compile(r"[0-9a-f]{64}\Z")


class BackupError(RuntimeError):
    """A safe, data-free operator error."""


def database_name(value, restore=False):
    pattern = (r"furniture_shop_test_restore_[a-z0-9_]+" if restore else
               r"furniture_shop(?:_tv3_dev|_test_[a-z0-9_]+)?")
    if not isinstance(value, str) or len(value) > 64 or not re.fullmatch(pattern, value):
        raise BackupError("Database name is outside the permitted recovery scope.")
    return value


def sha256(value):
    return hashlib.sha256(value).hexdigest()


def tokens_and_statements(text):
    """Lex quoted values first: directives in a value are data, never SQL.

    The compact dump dialect has only CREATE TABLE and one-row INSERT. Discard
    three exact charset housekeeping comments and the MariaDB sandbox marker;
    every other comment, directive, client command, and qualified name fails.
    This is intentionally narrower than a general MySQL/MariaDB SQL parser.
    """
    pos, statement = 0, []
    while pos < len(text):
        char = text[pos]
        if char.isspace():
            pos += 1
            continue
        if text.startswith("/*", pos):
            end = text.find("*/", pos + 2)
            if end < 0:
                raise BackupError("Unterminated dump comment.")
            comment = text[pos:end + 2]
            sandbox = r"/\*M!999999\\- enable the sandbox mode \*/"
            charset = r"/\*!40101\s+SET\s+(?:@saved_cs_client\s*=\s*@@character_set_client|character_set_client\s*=\s*(?:utf8mb4|@saved_cs_client))\s*\*/"
            if statement or not (re.fullmatch(sandbox, comment) or re.fullmatch(charset, comment)):
                raise BackupError("Dump contains an unsupported or executable comment.")
            pos = end + 2
            # The sandbox marker has no semicolon; charset SET comments do.
            continue
        if char in "'`":
            start, quote = pos, char
            pos += 1
            while pos < len(text):
                if text[pos] == "\\" and quote == "'":
                    pos += 2
                elif text[pos] == quote:
                    if pos + 1 < len(text) and text[pos + 1] == quote:
                        pos += 2
                    else:
                        pos += 1
                        break
                else:
                    pos += 1
            else:
                raise BackupError("Unterminated dump string or identifier.")
            token = text[start:pos]
            if quote == "`" and not NAME.fullmatch(token[1:-1]):
                raise BackupError("Unsupported SQL identifier.")
            statement.append(("string" if quote == "'" else "identifier", token))
        elif char == ";":
            if statement:
                yield statement
            statement = []
            pos += 1
        else:
            number = re.match(r"(?:0x[0-9A-Fa-f]+|\d+(?:\.\d*)?(?:[eE][+-]?\d+)?)", text[pos:])
            word = re.match(r"[A-Za-z_][A-Za-z_0-9]*", text[pos:])
            operator = re.match(r"<=>|>=|<=|<>|!=|<<|>>|&&|\|\|", text[pos:])
            if number:
                statement.append(("number", number[0]))
                pos += len(number[0])
            elif word:
                statement.append(("word", word[0]))
                pos += len(word[0])
            elif operator:
                statement.append(("symbol", operator[0]))
                pos += len(operator[0])
            elif char in "(),=+-*/<>!|&%":
                # Neither ordinary comments nor executable comments are allowed.
                if text.startswith("--", pos) or text.startswith("/*", pos):
                    raise BackupError("Dump comments are not permitted.")
                statement.append(("symbol", char))
                pos += 1
            else:
                raise BackupError("Unsupported SQL token or qualified schema reference.")
    if statement:
        raise BackupError("Dump ends with an incomplete SQL statement.")


def analyze_dump(payload):
    try:
        text = payload.decode("utf-8")
    except UnicodeError:
        raise BackupError("Dump must contain UTF-8 SQL.") from None
    tables, rows, statements = {}, {}, []
    forbidden = {"DATABASE", "SCHEMA", "USE", "SELECT", "OUTFILE", "INFILE", "LOAD",
                 "PROCEDURE", "FUNCTION", "TRIGGER", "EVENT", "DIRECTORY", "CONNECTION",
                 "TABLESPACE", "UNION", "PLUGIN", "SERVER", "INSTALL", "RENAME"}
    for tokens in tokens_and_statements(text):
        raw = [value for _, value in tokens]
        upper = [value.upper() if kind == "word" else value for kind, value in tokens]
        canonical = " ".join(raw) + ";"
        if len(tokens) >= 5 and upper[:2] == ["CREATE", "TABLE"]:
            if tokens[2][0] != "identifier" or raw[3] != "(":
                raise BackupError("Unsupported CREATE TABLE syntax.")
            name = raw[2][1:-1]
            if name in tables or any(kind == "word" and value.upper() in forbidden for kind, value in tokens):
                raise BackupError("Unsafe or duplicate table definition.")
            depth, closing = 0, None
            for index in range(3, len(tokens)):
                if raw[index] == "(":
                    depth += 1
                elif raw[index] == ")":
                    depth -= 1
                    if depth == 0:
                        closing = index
                        break
            if closing is None:
                raise BackupError("Unbalanced table definition.")
            # Block external engines, directories, AS SELECT, and other options.
            options = " ".join(raw[closing + 1:])
            if not re.fullmatch(r"ENGINE = InnoDB(?: AUTO_INCREMENT = [0-9]+)? DEFAULT CHARSET = (?:utf8mb4|utf8mb3|utf8|ascii)(?: COLLATE = [a-z0-9_]+)?(?: ROW_FORMAT = (?:DYNAMIC|COMPACT|REDUNDANT|COMPRESSED))?", options):
                raise BackupError("Only ordinary InnoDB table definitions are supported.")
            tables[name] = {"schema_sha256": sha256(canonical.encode()), "rows": 0}
            rows[name] = []
        elif len(tokens) >= 9 and upper[:2] == ["INSERT", "INTO"]:
            if tokens[2][0] != "identifier" or raw[3] != "(":
                raise BackupError("Only complete, unqualified INSERT statements are allowed.")
            name = raw[2][1:-1]
            if name not in tables:
                raise BackupError("Row refers to an undeclared table.")
            index, columns = 4, 0
            while index < len(tokens):
                if tokens[index][0] != "identifier":
                    raise BackupError("Invalid INSERT column list.")
                columns += 1
                index += 1
                if index < len(tokens) and raw[index] == ",":
                    index += 1
                    continue
                break
            if upper[index:index + 3] != [")", "VALUES", "("]:
                raise BackupError("INSERT must use one literal VALUES row.")
            index += 3
            values = 0
            while index < len(tokens):
                if raw[index] in ["+", "-"]:
                    index += 1
                    if index >= len(tokens) or tokens[index][0] != "number":
                        raise BackupError("Invalid numeric value.")
                if upper[index] in ["_binary", "B"] or upper[index] == "_BINARY":
                    index += 1
                    if index >= len(tokens) or tokens[index][0] != "string":
                        raise BackupError("Invalid binary value.")
                elif tokens[index][0] not in ["number", "string"] and upper[index] != "NULL":
                    raise BackupError("Only literal INSERT values are supported.")
                index += 1
                values += 1
                if index < len(tokens) and raw[index] == ",":
                    index += 1
                    continue
                break
            if index != len(tokens) - 1 or raw[index] != ")" or columns != values:
                raise BackupError("INSERT must contain exactly one complete literal row.")
            rows[name].append(sha256(canonical.encode()))
            tables[name]["rows"] += 1
        else:
            raise BackupError("Dump contains a statement outside the recovery dialect.")
        statements.append(canonical)
    if len(tables) != TABLE_COUNT:
        raise BackupError("Recovery requires exactly 47 InnoDB tables.")
    for name, digests in rows.items():
        tables[name]["data_sha256"] = sha256("\n".join(sorted(digests)).encode())
    return tables, "\n".join(statements)


def default_tool(name):
    windows = Path(r"C:\Program Files\MariaDB 12.2\bin") / (name + ".exe")
    return str(windows) if windows.is_file() else shutil.which(name) or name


class MariaDB:
    def __init__(self, client, dump_client):
        password = os.environ.get("DB_PASSWORD")
        if password is None:
            raise BackupError("Supply DB_PASSWORD in the process environment.")
        self.env = dict(os.environ, MYSQL_PWD=password)
        # Do not pass DB_PASSWORD twice to child programs; only MYSQL_PWD is needed.
        self.env.pop("DB_PASSWORD", None)
        self.connection = ["--no-defaults", "--protocol=tcp", "--host=" + os.environ.get("DB_HOST", "127.0.0.1"),
                           "--port=" + os.environ.get("DB_PORT", "3306"), "--user=" + os.environ.get("DB_USERNAME", "root"),
                           "--default-character-set=utf8mb4"]
        self.client, self.dump_client = client, dump_client

    def query(self, sql, database=None):
        argv = [self.client, *self.connection, "--batch", "--raw", "--skip-column-names", "--binary-mode"]
        if database:
            argv.append("--database=" + database_name(database))
        try:
            result = subprocess.run(argv, input=sql.encode("utf-8"), stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, env=self.env, timeout=600)
        except (OSError, subprocess.TimeoutExpired):
            raise BackupError("MariaDB client could not complete; check tool path, connection and privileges.") from None
        if result.returncode:
            # Numeric codes and SQLSTATE help diagnose syntax/privilege failures
            # without printing SQL fragments, server text, or private row data.
            code = re.search(rb"\bERROR ([0-9]{1,5}) \(([0-9A-Z]{5})\)(?: at line ([0-9]+))?", result.stderr)
            detail = ""
            if code:
                detail = " (error " + code[1].decode() + ", SQLSTATE " + code[2].decode()
                if code[3]:
                    detail += ", input line " + code[3].decode()
                detail += ")"
            raise BackupError("MariaDB SQL failed" + detail + "; SQL and server error text are suppressed.")
        return result.stdout.decode("utf-8").strip()

    def inspect(self, name):
        name = database_name(name)
        result = self.query("SELECT TABLE_NAME,TABLE_TYPE,COALESCE(ENGINE,'') FROM information_schema.TABLES "
                            f"WHERE TABLE_SCHEMA='{name}' ORDER BY TABLE_NAME;")
        tables = {}
        for line in result.splitlines():
            parts = line.split("\t")
            if len(parts) != 3 or not NAME.fullmatch(parts[0]) or parts[1:] != ["BASE TABLE", "InnoDB"]:
                raise BackupError("Source must contain only ordinary InnoDB tables; views are unsupported.")
            tables[parts[0]] = True
        unsupported = self.query("SELECT (SELECT COUNT(*) FROM information_schema.TRIGGERS "
                                 f"WHERE TRIGGER_SCHEMA='{name}') + (SELECT COUNT(*) FROM information_schema.ROUTINES "
                                 f"WHERE ROUTINE_SCHEMA='{name}') + (SELECT COUNT(*) FROM information_schema.EVENTS "
                                 f"WHERE EVENT_SCHEMA='{name}');")
        if len(tables) != TABLE_COUNT or unsupported != "0":
            raise BackupError("Expected 47 tables and no triggers, routines or events.")
        return set(tables)

    def dump(self, name, destination):
        argv = [self.dump_client, *self.connection, "--single-transaction", "--quick", "--compact",
                "--complete-insert", "--skip-extended-insert", "--hex-blob", "--order-by-primary",
                "--skip-triggers", "--skip-routines", "--skip-events", "--skip-lock-tables",
                "--skip-no-autocommit", database_name(name)]
        try:
            with destination.open("xb") as output:
                result = subprocess.run(argv, stdout=output, stderr=subprocess.PIPE, env=self.env, timeout=600)
        except (OSError, subprocess.TimeoutExpired):
            raise BackupError("MariaDB dump could not complete; incomplete output is not a backup.") from None
        if result.returncode:
            raise BackupError("MariaDB dump failed; incomplete output is not a backup.")

    def create(self, name):
        # No IF NOT EXISTS: the atomic CREATE must fail even for an empty target,
        # including when another process races the operator.
        name = database_name(name, restore=True)
        self.query(f"CREATE DATABASE `{name}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;")

    def import_sql(self, name, sql):
        database_name(name, restore=True)
        self.query("SET SESSION FOREIGN_KEY_CHECKS=0;\nSET SESSION SQL_MODE='NO_AUTO_VALUE_ON_ZERO';\n"
                   "SET SESSION time_zone='+00:00';\n" + sql + "\nSET SESSION FOREIGN_KEY_CHECKS=1;", name)


def create_backup(db, source, directory):
    source = database_name(source)
    directory = Path(directory)
    expected = db.inspect(source)
    try:
        directory.mkdir(parents=True, exist_ok=False, mode=0o700)
    except OSError:
        raise BackupError("Backup output must be a new, writable directory.") from None
    partial = directory / "dump.sql.partial"
    db.dump(source, partial)
    payload = partial.read_bytes()
    tables, _ = analyze_dump(payload)
    if set(tables) != expected or db.inspect(source) != expected:
        raise BackupError("Source table manifest changed while dumping; quiesce DDL and retry.")
    manifest = {"content_type": CONTENT_TYPE, "version": 1, "source": source,
                "created_utc": datetime.now(timezone.utc).isoformat(), "dump_file": "dump.sql",
                "sha256": sha256(payload), "tables": tables}
    partial.rename(directory / "dump.sql")
    temporary = directory / "manifest.json.partial"
    temporary.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.rename(directory / "manifest.json")
    return manifest


def read_backup(directory):
    directory = Path(directory)
    for name in ["manifest.json", "dump.sql"]:
        file = directory / name
        if file.is_symlink() or not file.is_file():
            raise BackupError("Backup must contain regular manifest.json and dump.sql files.")
    try:
        manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    except (ValueError, UnicodeError):
        raise BackupError("Invalid backup manifest JSON.") from None
    fields = {"content_type", "version", "source", "created_utc", "dump_file", "sha256", "tables"}
    if not isinstance(manifest, dict) or set(manifest) != fields:
        raise BackupError("Manifest fields do not match the supported format.")
    if type(manifest["version"]) is not int or manifest["version"] != 1 or manifest["content_type"] != CONTENT_TYPE or manifest["dump_file"] != "dump.sql":
        raise BackupError("Unsupported backup format, version or dump path.")
    database_name(manifest["source"])
    if not isinstance(manifest["created_utc"], str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?\+00:00", manifest["created_utc"]):
        raise BackupError("Manifest timestamp is invalid.")
    if not isinstance(manifest["sha256"], str) or not HASH.fullmatch(manifest["sha256"]):
        raise BackupError("Manifest checksum is invalid.")
    tables = manifest["tables"]
    if not isinstance(tables, dict) or len(tables) != TABLE_COUNT:
        raise BackupError("Manifest must contain exactly 47 tables.")
    for name, value in tables.items():
        if not NAME.fullmatch(name) or not isinstance(value, dict) or set(value) != {"rows", "schema_sha256", "data_sha256"}:
            raise BackupError("Invalid manifest table entry.")
        if type(value["rows"]) is not int or value["rows"] < 0 or any(not isinstance(value[key], str) or not HASH.fullmatch(value[key]) for key in ["schema_sha256", "data_sha256"]):
            raise BackupError("Invalid manifest row count or digest.")
    payload = (directory / "dump.sql").read_bytes()
    if sha256(payload) != manifest["sha256"]:
        raise BackupError("Dump checksum mismatch; restore refused.")
    actual, sql = analyze_dump(payload)
    if actual != tables:
        raise BackupError("Dump schema, row counts or row digests differ from the manifest.")
    return manifest, sql


def restore_backup(db, directory, target):
    target = database_name(target, restore=True)
    manifest, sql = read_backup(directory)
    # All offline validation precedes the only CREATE DATABASE operation.
    db.create(target)
    try:
        db.import_sql(target, sql)
        if db.inspect(target) != set(manifest["tables"]):
            raise BackupError("Restored table manifest differs from the snapshot.")
        with tempfile.TemporaryDirectory(prefix="tv3-verify-") as temporary:
            dump = Path(temporary) / "verify.sql"
            db.dump(target, dump)
            actual, _ = analyze_dump(dump.read_bytes())
        if actual != manifest["tables"]:
            raise BackupError("Restored schema, counts or row contents differ from the snapshot.")
    except BackupError as error:
        raise BackupError(str(error) + " The newly created target is retained for inspection; "
                          "this tool never drops databases. Use a different new target after resolving the cause.") from None
    return manifest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["backup", "restore"])
    parser.add_argument("--database", help="Backup source: furniture_shop, furniture_shop_tv3_dev or furniture_shop_test_*.")
    parser.add_argument("--output", type=Path, help="New local backup directory.")
    parser.add_argument("--backup", type=Path, help="Backup directory to verify and restore.")
    parser.add_argument("--target", help="New furniture_shop_test_restore_* database.")
    parser.add_argument("--client", default=default_tool("mariadb"))
    parser.add_argument("--dump-client", default=default_tool("mariadb-dump"))
    args = parser.parse_args(argv)
    if args.action == "backup":
        if not args.database or not args.output or args.backup or args.target:
            parser.error("backup requires --database and --output only.")
        database_name(args.database)
    else:
        if not args.backup or not args.target or args.database or args.output:
            parser.error("restore requires --backup and --target only.")
        database_name(args.target, restore=True)
    db = MariaDB(args.client, args.dump_client)
    if args.action == "backup":
        manifest = create_backup(db, args.database, args.output)
        print("Backup verified locally: 47 InnoDB tables, " + str(sum(t["rows"] for t in manifest["tables"].values())) + " rows. Keep both files private.")
    else:
        restore_backup(db, args.backup, args.target)
        print("Restore verified: " + args.target + "; all 47 table schemas, counts and row digests match the snapshot.")


if __name__ == "__main__":
    try:
        main()
    except (BackupError, OSError, ValueError):
        # Never expose arbitrary driver errors, SQL, row data or environment.
        error = sys.exc_info()[1]
        print("FAIL: " + (str(error) if isinstance(error, BackupError) else "Local backup file operation failed."), file=sys.stderr)
        sys.exit(1)
