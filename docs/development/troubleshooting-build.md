# ビルドのトラブルシューティング

Maven 実行そのものが、ソースの誤りではない理由で落ちるケース。

---

## `NoSuchFileException` — 直前にコンパイルしたクラスが「不正です」

### 症状

`mvn ... test` / `test-compile` が、main のコンパイルに成功した直後の testCompile で落ちる。

```
[INFO] Compiling 775 source files with javac [debug target 21] to target/classes
[INFO] Compiling 365 source files with javac [debug target 21] to target/test-classes
[ERROR] .../SsrfGuardTest.java:[38,21] jp.aegif.nemaki.security.SsrfGuard にアクセスできません
  クラス・ファイル .../core/target/classes/jp/aegif/nemaki/security/SsrfGuard.class は不正です
    ファイル java.nio.file.NoSuchFileException: .../core/target/classes/.../SsrfGuard.class
    削除するか、クラスパスの正しいサブディレクトリにあるかを確認してください。
```

同時に 100 件以上のエラーが出るが、**すべて同じ形**である。エラーの主張は
「クラスファイルが壊れている」ではなく「**さっき書いたはずのファイルが無い**」。

### 原因

VS Code / Cursor の Java 拡張 (`redhat.java`) が動かす **Eclipse JDT language server が
`core/target/classes` を自分の出力先としても使う**。`.vscode/settings.json` の
`java.configuration.updateBuildConfiguration: "automatic"` により、ファイル変更を検知して
自動ビルドが走る。それが Maven の compile → testCompile の**間**に起きると、javac が
直前に書いたクラスファイルを JDT 側が消し、testCompile がそれを踏む。

**ソースの誤りではない。** 特に `mvn clean` の直後は JDT 側も出力を作り直すため、
race window が開きやすい。

### 判別方法

コンパイルエラーの内容を読む前に、この 2 つを見る。

```bash
find core/target/classes -name "*.class" | wc -l
```

775 の main source に対して極端に少ない (1000 前後) なら、この race。正常なビルドでは
内部クラスを含めて数千件になる。

```bash
pgrep -fl java | grep -i jdt
```

`org.eclipse.jdt.ls.core.id1` を含むプロセスが出れば language server が稼働している。

### 対処

**1. 同じコマンドをそのまま再実行する (最も安い・実績あり)**

race window は短い。2026-08-04 の作業では 2 回発生し、いずれも単純な再実行で解消した。
エディタは落とさなくてよい。

**2. 出力先を分離する (確実・検証済み)**

```bash
mvn -pl core clean test -DisolatedTarget
```

`core/pom.xml` の `isolated-target` プロファイルが有効になり、ビルド出力が
`core/target-cli/` に移る。JDT が触るのは `core/target/` なので、両者は完全に分離される。

- **`-Dproject.build.directory=...` は効かない。** このパスは POM 内で導出されるもので、
  ユーザープロパティとしては読まれない。プロファイル以外に移す場所が無いのはそのため。
- **WAR の出力先も `core/target-cli/core.war` に移る。** このプロファイルでビルドしたものを
  デプロイするなら、`cp core/target-cli/core.war docker/core/core.war` と読み替えること
  (通常の手順は `core/target/core.war` を見る)。

**3. JDT の自動ビルドを一時的に止める (未検証)**

`redhat.java` 拡張には `java.autobuild.enabled` 設定がある。`false` にすれば自動ビルドは
止まるが、**編集中の診断も止まる**。このリポジトリでは実測していないため、上の 2 つで
足りない場合の最後の手段として挙げるにとどめる。

### やってはいけない対処

**広い class glob での手作業削除**。

```bash
rm -f core/target/classes/.../CouchLineage*.class   # ← 禁止
```

意図したクラスだけでなく同じ prefix の無関係なクラスまで消える。実際に
`CouchLineage*` の削除で `CouchLineageEventV2` / `CouchLineageJournalRowV2` まで巻き添えになり、
30 件の見せかけのテストエラーを生んだ。**強制再コンパイルには Maven の clean lifecycle を
使う** — それが `clean` の役目であり、どのファイルを消すべきかを知っているのは Maven である。

### 自動化できない部分

**発生そのものは環境依存で、CI では起きない** (language server が居ないため)。
ローカルでの発生タイミングも編集の有無に依存するので、事前に予測することはできない。
できるのは上の判別と、`-DisolatedTarget` による回避までである。

---

## 関連

- ビルド・デプロイ手順: [`.claude/skills/build-deploy/`](../../.claude/skills/build-deploy/)
- PIT (mutation testing) は `JAVA_HOME` に JDK 21 を要求する。既定の JDK では
  静かに 0% 計装で BUILD SUCCESS になる。
