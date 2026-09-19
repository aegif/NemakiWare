# Phase 0 の棚卸し — content を書く経路 / mapper / anchor

2026-09-19。`BASE_SHA` = `df6c1f2ad`。
計画は [`v3.4.0-evidence-and-residuals-plan.md`](v3.4.0-evidence-and-residuals-plan.md)。

この文書が決めるのは **E1 の対象範囲**である。計画 §8 が
「Phase 0 で経路を列挙し、**列挙できたものだけ**を対象にする。列挙できなかった経路は
残件 1 行であり、成功扱いにしない」と定めているので、ここに書いていない経路について
「記録した」とは後段のどこでも書かない。

**読んで確かめた結果だけを書く。** 台帳の文言ではなくコードを根拠にする、という
計画 §0 の指示に従っている。

---

## 1. content bytes を書く経路

最下層は 2 つだけで、どちらも `ContentServiceImpl` の private メソッドである。

- `createAttachmentAtomic(...)` — 新しい bytes を書く
- `copyAttachmentAtomic(...)` — 既存の attachment を複製する

grep で呼び出し元を数え、囲みメソッドと、その CMIS / REST 入口まで辿った。

| # | bytes を書くサービス API | 最下層 | 入口 | 版の扱い |
|---|---|---|---|---|
| W1 | `createDocument` | `createAttachmentAtomic` | `ObjectServiceImpl.createDocument`、`Patch_InitialContentSetup`（2 か所） | 新規 |
| W2 | `createDocumentWithNewStream` | `createAttachmentAtomic` | `ObjectServiceImpl.setContentStream` | 新しい版を作る側 |
| W3 | `updateDocumentWithNewStream` | `createAttachmentAtomic` | `ObjectServiceImpl.setContentStream` | 既存の版を書き換える側 |
| W4 | `checkIn` | `createAttachmentAtomic` | `VersioningServiceImpl.checkIn` | PWC を版にする |
| W5 | `updateWithoutCheckInOut` | `createAttachmentAtomic` | `BulkCheckInResource`（REST） | checkOut を経ない更新 |
| W6 | `createDocumentFromSource` | `copyAttachmentAtomic` | `ObjectServiceImpl.createDocumentFromSource` | 複製 |
| W7 | `appendAttachment` | （別経路） | `ObjectServiceImpl.appendContentStream` | 追記 |
| W8 | `checkOut`（PWC 作成） | `copyAttachmentAtomic` | `VersioningServiceImpl.checkOut` | PWC へ複製 |

**外部取込は独自の書き込み経路を持たない。** `CanonicalImportServiceImpl` は
`versioningService.checkIn`（2 か所）と `objectService.createDocument`（1 か所）を呼ぶので、
W1 と W4 に合流する。取込のために E1 を別に作る必要はない。

### まだ確かめていないこと（成功扱いにしない）

- **W7 の追記**が最下層で何を書くか（`appendAttachment` の実装を読んでいない）。
  追記は「新しい bytes」でも「既存 bytes の延長」でもありうるので、statement の
  `commitmentKind` をどう置くかが決まらない。
- **W6 / W8 の複製**に、元と同じ `contentDigest` を持つ statement を書いてよいか。
  複製は新しい記録であって、元の記録の証拠を引き継ぐものではない。
- `deleteContentStream`（bytes を消す）を E1 がどう扱うか。消去も内容状態の遷移である。

---

## 2. mapper の定義

計画 A-1（R52）の対象。**定義は 3 つある。**

| 定義 | 場所 | 何を復号するか | R51 の deserializer |
|---|---|---|---|
| `ObjectMapperFactory` | `config/ObjectMapperFactory.java:47` | default / nemaki / couchdb / debug の 4 profile | 無し |
| `DaoHelper.createConfiguredObjectMapper` | `dao/impl/couch/delegate/DaoHelper.java:27` | archive / usergroup / attachment / changeevent / typedefinition の delegate | **有り** |
| `ContentDaoServiceImpl.createConfiguredObjectMapper` | `dao/impl/couch/ContentDaoServiceImpl.java:201` | content（folder / document / item ほか）。private な複製 | 無し |

**`ObjectMapperFactory` の javadoc は既に「The one place NemakiWare's mapper configurations
are defined」と書いている**（:31）。コードがそれを満たしていない。文書がコードより強い、
このブランチが潰してきた形そのものである。

A-1 は**定義元を `ObjectMapperFactory` に寄せる**。`DaoHelper` へ寄せない理由は、
そちらの javadoc が「唯一」を主張していないことと、profile を混ぜないためである
（DAO persistence / Couch persist / REST は別の profile であり、一本化とは
「定義の置き場所を 1 つにする」であって「設定を 1 つにする」ではない）。

---

## 3. 台帳 entry の現状（E1 の前提）

計画 §3 の指摘をコードで確認した。

`EvidenceLedgerRecorder.recordCaptureCompleted` が書く `CAPTURE_COMPLETED` の
`payloadDigest` は `captureDigest(...)`（:231）の戻り値で、その中身は

```
domain / repositoryId / intentId / connectorId / sourceObjectId
  + CaptureIntent.APPLIED_HASH_FIELDS の各値
```

である。**文書の bytes の digest は入っていない。** したがって現状の台帳 entry は
「この取込がこのメタデータで起きた」ことは結び付けるが、「その結果保存された bytes」は
結び付けていない。E1 が埋めるのはここ。

---

## 4. anchor の段

`evidence/anchor` 配下に `AnchorService` / `AnchorReceiptStore` /
`CouchAnchorReceiptStore` / `EvidenceRecordService` / `LongTermValidityService` /
`ErsFormat` / `RenewalNeed` がある。段の構成と、どの段が何を保存しているかは
**まだ読んでいない**。計画 §11（発行時の失効材料を保存する）に着手する前に、
ここを同じやり方で棚卸しする。

---

## 5. この文書で決めたこと

- E1 の対象は **W1〜W8 のうち、`commitmentKind` を決められたもの**に限る。
  現時点で決められているのは W1〜W5（新しい bytes を書く経路）。
- W6 / W7 / W8 と `deleteContentStream` は **未決**であり、決まるまで E1 の対象に
  しない。対象にしないことを RELEASE_NOTES と verifier の `limits` に書く。
- A-1 の寄せ先は `ObjectMapperFactory`。
