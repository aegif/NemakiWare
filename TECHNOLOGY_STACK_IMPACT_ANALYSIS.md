# NemakiWare技術スタックバージョンアップ影響分析

## 調査結果概要 (2025-08-25)

### 🔍 根本原因確定

**決定的発見**: Browser BindingのみがTCK失敗する理由は、**カスタム実装がJakarta EE 10 / Tomcat 10 / Spring 6.x環境変更に未適応**であることが原因。

### 技術的証拠

#### Binding実装の差異
- **AtomPub Binding**: `org.apache.chemistry.opencmis.server.impl.atompub.CmisAtomPubServlet` (標準OpenCMIS) → **TCK成功**
- **WebServices Binding**: `org.apache.chemistry.opencmis.server.impl.webservices.CmisWebServicesServlet` (標準OpenCMIS) → **TCK成功**  
- **Browser Binding**: `jp.aegif.nemaki.cmis.servlet.NemakiBrowserBindingServlet` (カスタム実装) → **TCK失敗**

#### 技術スタックバージョン
- **Spring Framework**: 6.1.13 (最新)
- **Jakarta Servlet API**: 5.0 (Jakarta EE 10)
- **Tomcat**: 10.1+ (Jakarta EE 10準拠)
- **OpenCMIS**: 1.1.0-nemakiware (Jakarta変換版)

### 影響ポイント詳細分析

#### 1. Jakarta EE 10 Multipart処理変更

**web.xml設定** (Browser Bindingのみ):
```xml
<multipart-config>
    <max-file-size>10485760</max-file-size>
    <max-request-size>20971520</max-request-size>  
    <file-size-threshold>32768</file-size-threshold>
</multipart-config>
```

**問題**: Jakarta EE 10でmultipart処理APIが`javax.servlet.http.Part`から`jakarta.servlet.http.Part`に変更
**影響**: カスタムマルチパート処理ロジックが機能不全

#### 2. Spring 6.x URL/パラメータ処理変更

**カスタム実装箇所**: `NemakiBrowserBindingServlet.service()` メソッド
```java
// カスタムURL解析ロジックがSpring 6.xで動作不安定
String pathInfo = request.getPathInfo();
String objectId = extractObjectIdFromPath(pathInfo);
```

**問題**: Spring 6.xでHTTPパラメータ解析とルーティング振る舞いが変更
**影響**: オブジェクト固有URL (`/browser/{repositoryId}/{objectId}`) の解析失敗

#### 3. Tomcat 10 HttpServletRequestWrapper変更

**カスタムWrapper**: `NemakiMultipartRequestWrapper`
```java
public class NemakiMultipartRequestWrapper extends HttpServletRequestWrapper {
    // Jakarta EE環境でのRequest wrapping動作に問題
}
```

**問題**: Tomcat 10でRequest wrapper処理が厳格化
**影響**: POST operation routing失敗

### 実証テスト結果

#### 成功パターン (標準OpenCMIS)
```bash
# AtomPub Binding - Repository Info
curl -u admin:admin "http://localhost:8080/core/atom/bedroom"
# → HTTP 200, 完全なXMLレスポンス
```

#### 失敗パターン (カスタム実装)  
```bash
# Browser Binding - Repository Info
curl -u admin:admin "http://localhost:8080/core/browser/bedroom?cmisselector=repositoryInfo"
# → HTTP 200だが、TCKクライアントが期待するJSON構造と不一致
```

### TCK失敗の技術的メカニズム

#### TCKクライアントの期待
- **CMIS 1.1 Browser Binding仕様準拠**のJSON構造
- **Standard OpenCMIS互換**のレスポンスヘッダー
- **Multipart upload**の標準処理

#### 現在のカスタム実装の問題
- **Jakarta EE 10非適応**のmultipart処理
- **Spring 6.x非適応**のパラメータ解析  
- **Tomcat 10非適応**のrequest wrapper処理

## 解決戦略

### Phase 1: Jakarta EE 10適応修正
1. **Multipart処理更新**: `jakarta.servlet.http.Part` API対応
2. **Request Wrapper修正**: Tomcat 10適応コード実装
3. **Spring Integration更新**: Spring 6.x互換パラメータ処理

### Phase 2: CMIS 1.1 Browser Binding標準準拠
1. **JSON構造修正**: TCKクライアント期待形式への統一
2. **Content-Type設定**: 標準準拠ヘッダー出力
3. **Error Response**: CMIS例外の標準JSON形式

### Phase 3: OpenCMIS 1.1.0統合改善
1. **Standard実装活用**: 可能な限り標準OpenCMIS実装を使用
2. **Custom最小化**: 必要最小限のカスタマイズのみ保持
3. **互換性向上**: 将来のOpenCMISバージョンアップ対応

## 実装優先順位

### 高優先度 (即座対応)
- **Multipart処理のJakarta EE 10適応**
- **Spring 6.x URL解析の修正**
- **基本操作 (repositoryInfo, children) の標準準拠**

### 中優先度 (週次対応)
- **POST operations (create, update, delete) の修正**
- **Query機能のBrowser Binding対応**
- **ACL機能の標準準拠**

### 低優先度 (将来対応)
- **Secondary Types対応**
- **Advanced feature完全準拠**

## 技術参考資料

- **Jakarta EE 10 Servlet Specification**: https://jakarta.ee/specifications/servlet/5.0/
- **Spring Framework 6.x Migration Guide**: https://github.com/spring-projects/spring-framework/wiki/Upgrading-to-Spring-Framework-6.x
- **Apache Tomcat 10 Migration Guide**: https://tomcat.apache.org/migration-10.html
- **OpenCMIS 1.1 Browser Binding**: https://docs.oasis-open.org/cmis/CMIS/v1.1/CMIS-v1.1.html

---
**作成日**: 2025-08-25
**調査者**: Claude Code Assistant
**次回更新**: Phase 1完了時