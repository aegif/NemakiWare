# TCK Test Evidence Directory

このディレクトリには、CMIS 1.1 TCK（Technology Compatibility Kit）テストの実行証跡を保存します。

## ディレクトリ構造

```
tck-evidence/
├── README.md (このファイル)
├── YYYY-MM-DD-<purpose>/
│   ├── README.md (テスト実行の詳細レポート)
│   ├── TEST-*.xml (サニタイズ済みSurefire XMLレポート)
│   └── *.txt (テスト実行ログ)
└── ...
```

## 🔒 個人情報保護ポリシー

**重要**: TCK証跡をリポジトリにコミットする前に、**必ず**個人情報・環境依存情報をサニタイズしてください。

### サニタイズ対象

Surefire XMLレポート（`TEST-*.xml`）の`<properties>`セクションには以下の機密情報が含まれます：

- ✗ ユーザー名（`user.name`）
- ✗ ホームディレクトリパス（`user.home`）
- ✗ テンポラリディレクトリの絶対パス（`java.io.tmpdir`, `user.dir`）
- ✗ ライブラリパス（`java.library.path`, `sun.boot.library.path`）
- ✗ 実行コマンドのフルパス（`sun.java.command`）

### サニタイズ手順

#### 方法1: 自動サニタイズスクリプト（推奨）

```bash
# プロジェクトルートから実行
./sanitize-tck-evidence.sh tck-evidence/<evidence-directory>/

# 例
./sanitize-tck-evidence.sh tck-evidence/2025-10-05-code-review-validation/
```

スクリプトは`<properties>`セクション全体を削除し、テスト結果のみを残します。

#### 方法2: 手動サニタイズ

各`TEST-*.xml`ファイルから`<properties>`要素全体を削除してください：

```xml
<!-- 削除対象 -->
<testsuite ...>
  <properties>
    <property name="user.name" value="ishiiakinori"/>
    <property name="user.home" value="/Users/ishiiakinori"/>
    ...
  </properties>
  <testcase .../>
</testsuite>

<!-- サニタイズ後 -->
<testsuite ...>
  <testcase .../>
</testsuite>
```

### サニタイズ検証

コミット前に以下を確認してください：

```bash
# 個人情報が含まれていないことを確認
grep -r "user.name\|user.home\|ishiiakinori" tck-evidence/<evidence-directory>/*.xml

# 何も出力されなければOK（exitコード1）
```

## TCK証跡の追加手順

1. **テスト実行**
   ```bash
   mvn test -Dtest=TypesTestGroup,ControlTestGroup,BasicsTestGroup,VersioningTestGroup,FilingTestGroup \
     -f core/pom.xml -Pdevelopment
   ```

2. **証跡ディレクトリ作成**
   ```bash
   mkdir -p tck-evidence/YYYY-MM-DD-<purpose>
   cp core/target/surefire-reports/* tck-evidence/YYYY-MM-DD-<purpose>/
   ```

3. **サニタイズ実行**
   ```bash
   ./sanitize-tck-evidence.sh tck-evidence/YYYY-MM-DD-<purpose>/
   ```

4. **README作成**
   ```bash
   # テスト目的、結果、検証内容を記載
   vim tck-evidence/YYYY-MM-DD-<purpose>/README.md
   ```

5. **コミット**
   ```bash
   git add tck-evidence/YYYY-MM-DD-<purpose>/
   git commit -m "Add TCK evidence for <purpose>"
   ```

## セキュリティチェックリスト

コミット前に以下を確認：

- [ ] `<properties>`セクションが削除されている
- [ ] ユーザー名が含まれていない
- [ ] 絶対パスが含まれていない
- [ ] テスト結果（`<testcase>`要素）は保持されている
- [ ] README.mdでテスト目的と結果が文書化されている

## 参考情報

- サニタイズスクリプト: `/sanitize-tck-evidence.sh`
- Surefire XMLスキーマ: https://maven.apache.org/surefire/maven-surefire-plugin/xsd/surefire-test-report.xsd

🤖 Generated with [Claude Code](https://claude.com/claude-code)
