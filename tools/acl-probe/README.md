# acl-probe — ACL 伝播・意味論の再測定スクリプト

[docs/design/v3.4-improvement-plan.md](../../docs/design/v3.4-improvement-plan.md) §7
(合格再判定) の測定資産。改善の before/after を同じ手順で測り直すために置いてある。
2026-08-09 の baseline 数値は同文書 §1 に記録済み。

前提: nb33 開発スタック (`docker/docker-compose-simple.yml`、admin:admin)。
すべて bedroom に fixture を**作成して**測る (書き込みあり)。実行後は各スクリプトの
出力に従って fixture を削除すること。**他の測定・TCK と同時に走らせない** —
子孫 readers の書き換えは JVM 全体で 1 スレッドに直列化されており (ragAclExecutor
max=1)、同時に走らせると互いの数値を汚す。

| スクリプト | 測るもの | baseline (2026-08-09) |
|---|---|---|
| `propagation_matrix.py` | 子孫数 S ごとの GRANT/REVOKE 収束時間・applyACL 応答・陰性対照 | GRANT ≈ 1.0s + S×14ms (ACE 2-3 件時)、REVOKE ≤0.5s、応答 147〜258ms |
| `direct_flag_repro.py` | C2 (PROPAGATE の direct 反転) の ACE 消失再現 + C3 (setACL の breakInheritance 分岐) | C2 陰性 / C3 は break 分岐限定 |
| `group_depth_cliff.py` | グループ入れ子の深さ別コストと 51 段の静かな権限消失 | 深さ 55 で hits=0/403、WARN 1 行のみ |
| `nested_group_visibility.py` | 10 段ネストの推移所属が getObject/検索に届くか | 届く (陰性対照 0 件) |

読み方の注意:

- **陰性対照 (無関係ユーザ) が 0 件でなければ、その回の数値は全て無効。**
  GROUP_EVERYONE がルートから read を継承しているため、外し忘れると全員が読めて
  検査が無意味になる (このリポジトリで過去 2 回踏んだ罠)。
- 伝播の測定は**必ず非 admin ユーザで**行う。admin は readers fq 自体を bypass
  するので、admin で検索しても伝播の進み具合は分からない。
- GRANT の検索収束には commitWithin=1000ms の可視化下限が固定加算される。
  書込レートを出すときは 1 秒を引いてから割ること。
- 書込レートは**フォルダの ACE 数と深さに強く依存する** (ACE 11 件では
  約 10〜30 docs/s まで落ちる)。before/after 比較は同一 fixture 構成で。
