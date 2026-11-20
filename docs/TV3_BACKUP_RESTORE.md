# TV3 local MariaDB backup and recovery

`scripts/tv3_backup.py` creates a logical snapshot of all 47 InnoDB tables and restores it only into a **new** `furniture_shop_test_restore_*` database. A successful restore requires matching table names, table definitions, exact row counts and row-content SHA-256 digests. The original database remains the backup source.

Python 3.10+ and the MariaDB command-line tools are required; no Python packages or Docker daemon are needed. On Windows the defaults are `C:\Program Files\MariaDB 12.2\bin\mariadb.exe` and `mariadb-dump.exe`. Other systems use `mariadb` and `mariadb-dump` from `PATH`. Override these with `--client` and `--dump-client` when needed. Use matching MariaDB server/client versions for recovery: this workflow deliberately accepts a narrow compact-dump dialect, rather than arbitrary SQL or cross-version migration dumps.

## Prepare the connection

Supply `DB_PASSWORD` in the invoking process environment using your normal local secret mechanism. Optional variables are `DB_HOST` (default `127.0.0.1`), `DB_PORT` (`3306`) and `DB_USERNAME` (`root`). Do not put a password in the command, shell history, documentation or repository. The script passes it only as `MYSQL_PWD` in each child process environment; it does not write credentials to a file or print client/server error contents. `--no-defaults` prevents local client option files from silently changing the connection or restore behavior.

The account needs metadata visibility, `SELECT` for every source table, and permissions to create and populate the intended disposable database. Prefer a recovery account whose grants are restricted to the approved source and `furniture_shop_test_restore_%` targets. Metadata checks must be able to see all tables, triggers, routines and events. The script cannot prove the completeness of metadata hidden by server grants.

Before the snapshot, pause schema changes, migrations and all other DDL. The dump uses `--single-transaction --quick`, so committed InnoDB row data is captured consistently while ordinary application writes can continue. It refuses sources with views, non-InnoDB tables, triggers, routines or scheduled events. Source table names are checked before and after dumping; this does not make concurrent DDL safe. Stop application background workers as well when independently comparing the live source to a restored database, because the live source may change after its snapshot.

## Create a backup

From the repository root, with the connection environment already supplied:

```powershell
python scripts/tv3_backup.py backup --database furniture_shop_tv3_dev --output backups/tv3-20261120
```

Permitted sources are `furniture_shop`, `furniture_shop_tv3_dev`, and `furniture_shop_test_*`, with lowercase ASCII letters, digits and underscores and at most 64 characters. The output directory must be new. The tool never reuses an existing directory, including an incomplete backup directory.

The directory contains `dump.sql` and `manifest.json` only after dumping and validation succeed. The manifest records its content type/version, source name, UTC creation time, exact dump SHA-256, and the schema digest, row count and row-content digest for each of the 47 tables. All row counts and digests come from the captured transactional dump, rather than later queries against a changing source. Row-content comparison is independent of dump row order. A partial or failed dump has no completed manifest and cannot be restored; use another new directory after resolving the cause. Interrupted operations may leave `.partial` files containing sensitive data.

`backups/` is ignored by Git. Both files contain or describe private business data, and SQL dumps can contain password hashes and personal information. Keep backups local and access controlled; do not commit them or upload them as CI/release artifacts. The tool requests a private directory on POSIX systems; on Windows, place backups in a directory with appropriate inherited NTFS permissions. Sensitive temporary verification dumps are removed on normal completion. Abrupt process termination can leave temporary files requiring local cleanup.

## Restore and verify

```powershell
python scripts/tv3_backup.py restore --backup backups/tv3-20261120 --target furniture_shop_test_restore_ops_20261120
```

Before creating anything, the script validates the complete manifest, fixed local dump filename, checksum, table entries and narrow SQL dialect. It rejects unsupported statements, client commands, ordinary or unexpected executable comments, qualified schema references, external storage engines and expression-based INSERTs. It accepts the exact MariaDB compact-dump charset housekeeping comments and sandbox marker. Quoted row values are lexed as values, so text resembling SQL inside a customer field is preserved.

The SQL is imported into the newly created target with foreign-key checks disabled during loading, UTF-8 connection encoding, UTC session time zone and `NO_AUTO_VALUE_ON_ZERO`. It then dumps that target again and compares all 47 schema digests, row counts and row-content digests with the captured snapshot. Successful verification means those captured table definitions and rows match; it is not a full application or point-in-time recovery test. The snapshot excludes users/grants, global server settings, binary logs and other databases.

`CREATE DATABASE` deliberately has no `IF NOT EXISTS`. Any existing target, including an empty one, makes restore fail before import. Production names, `furniture_shop_tv3_dev` and other test database families cannot be restore targets. There is no overwrite, drop, arbitrary SQL path or production-restore option.

After an import or verification failure the newly created target is retained for inspection. The tool never drops a database automatically, including after interrupted or concurrent operations. Resolve the failure and choose another new target. An operator can remove a disposable target later only after checking its exact name and confirming that it was created for this exercise.

## Trust and checks

A dump is sensitive executable input. Restore **only backups produced locally by this tool and kept under your control**. SHA-256 detects corruption; an unsigned manifest does not authenticate who produced a dump. The dialect checks reduce accidental or obvious dangerous directives and fail closed on unsupported constructs. They are not a general SQL sandbox or a substitute for least-privilege database grants. Never restore a downloaded or externally supplied dump merely because its checksum matches an accompanying manifest.

Run the server-independent recovery guard suite with:

```powershell
python -m unittest discover -s tests/operations -p test_tv3_backup.py
```

It covers source/target guards, existing empty targets, partial output, checksum and manifest tampering, qualified-schema and executable-comment attacks, literal data preservation, unsupported source objects, failed imports, row-content mismatch and secret handling. A real backup/restore check additionally requires a local MariaDB instance and the two commands above; record its actual outcome separately. Unit tests alone do not establish that a live recovery completed.

## Verified recovery evidence — 2026-11-20

The 15 server-independent recovery tests passed. The live local MariaDB 12.2 check then created `backups/tv3-20261120-final` from `furniture_shop_tv3_dev`: all 47 InnoDB tables and 702 captured rows. Restore into the new `furniture_shop_test_restore_release_20261120` database passed the complete table-name, schema-digest, row-count and row-content-digest comparison. This verifies the captured snapshot; application writes to the source after capture are outside that comparison. The restored database is also the isolated database used for the packaged-JAR/browser exercise.

The live exercise exposed two compatibility defects, each fixed after a failing regression check and followed by a passing suite. MariaDB 12.2 enables dump autocommit wrappers by default even with `--compact`; the dump command now explicitly supplies `--skip-no-autocommit`, keeping the accepted SQL dialect narrow. The SQL lexer also now preserves compound comparison operators such as `>=` in CHECK constraints, rather than splitting them into invalid SQL. A PREPARE-only MariaDB probe confirmed the corrected constraint syntax before retrying recovery. Earlier manifests produced with the faulty canonical schema digest were replaced by a fresh backup, rather than edited to bypass verification.

The failed first backup left only a private partial dump. The first failed restore target was retained without automatic deletion. After all local checks, the operator stopped the owned packaged app and removed only the four explicitly named session test databases and the two obsolete backup attempts. The verified `backups/tv3-20261120-final` snapshot remains private on the laptop; the original main/demo databases were retained.
