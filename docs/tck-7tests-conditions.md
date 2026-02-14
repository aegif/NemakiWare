# TCK 7件（削除関連）を成功させるための条件

## 概要

以下の7テストは **「Newly created folder is invalid」** で失敗する場合がある。

- `CrudTestGroup1#createAndDeleteFolderTest`, `createAndDeleteDocumentTest`, `createAndDeleteItemTest`
- `CrudTestGroup2#createAndDeleteRelationshipTest`, `setAndDeleteContentTest`, `deleteTreeTest`
- `VersioningTestGroup#versionDeleteTest`

エラー: `CmisObjectNotFoundException: [objectTypeId:test:customDocXXXX]The specified object is not found`

## 原因

OpenCMIS TCK はテスト用フォルダ作成時に **カスタム型**（例: `test:customDoc` + ランダムサフィックス）を作成し、その型でフォルダを作る。  
この型がリポジトリに存在しない状態で上記7件だけを実行すると、クライアントが `getTypeDefinition("test:customDoc...")` を呼んだ際に型が見つからず失敗する。

## 実施済みの修正（コード側）

1. **CompileServiceImpl**
   - コンテンツの種類（フォルダ/ドキュメント等）と型のベース型が一致しない場合、標準ベース型（`cmis:folder` 等）にフォールバックしてプロパティをコンパイル。
   - レスポンスの `cmis:objectTypeId` には、コンパイルに使った型の ID（`tdf.getId()`）を返す。

2. **TypeManagerImpl**
   - カスタムフォルダ型（`cmis:folder` を継承する型）にも、`cmis:parentId` 等のフォルダ用プロパティ定義を追加するよう変更。
   - `getTypeDefinition()` がフォルダ型で `cmis:parentId` を含むようにし、「parentId does not exist in type」を防止。

## 7件を成功させるための条件

**型が先にリポジトリに存在する** 状態で実行する。

### 方法A: 型作成を含むテストを先に実行する

```bash
# TypesTestGroup で型を作成してから、対象7件を実行
mvn test -f core/pom.xml -Pdevelopment \
  -Dtest="TypesTestGroup#createAndDeleteTypeTest,CrudTestGroup1#createAndDeleteFolderTest+createAndDeleteDocumentTest+createAndDeleteItemTest,CrudTestGroup2#createAndDeleteRelationshipTest+setAndDeleteContentTest+deleteTreeTest,VersioningTestGroup#versionDeleteTest"
```

※ `createAndDeleteTypeTest` が型を削除する場合は、実行順やスイート構成の調整が必要な場合あり。

### 方法B: スイート全体を実行する

```bash
# 全TCKグループを実行（型作成が他のグループで行われる想定）
mvn test -f core/pom.xml -Pdevelopment -Dtest=AllTest
```

### 方法C: サーバー起動とデプロイの手順

TCK は `http://localhost:8080/core/atom/bedroom` に接続するため、**修正を反映した WAR をデプロイしたサーバー** が起動している必要がある。

```bash
# 1. ビルド
mvn clean package -f core/pom.xml -Pdevelopment -DskipTests

# 2. WAR を Docker にコピーして core を再構築
cp core/target/core.war docker/core/core.war
cd docker && docker compose -f docker-compose-simple.yml up -d --build --force-recreate core

# 3. 起動待機（約90秒）
sleep 90

# 4. TCK 実行
cd .. && mvn test -f core/pom.xml -Pdevelopment -Dtest="..."
```

## 参考

- 元のエラー（型は存在するがフォルダ型に parentId が無い）:  
  `Cannot convert property 'cmis:parentId' because it does not exist in the object type`
- 上記は TypeManagerImpl の「カスタムフォルダ型にもフォルダ用プロパティを追加」で解消。
- 型が存在しない場合の「object not found」は、**型が先に作成される実行順・スイート** で解消する必要がある。
