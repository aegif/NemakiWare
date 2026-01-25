# 監査ログ監視ダッシュボード実装依頼書

**作成日**: 2026-01-25  
**対象バージョン**: NemakiWare 3.0.0-RC1  
**優先度**: 中

---

## 📋 実装概要

監査ログ機能のメトリクスを可視化する監視ダッシュボードを実装します。以下の3つのアプローチを実装します：

1. **外部ツール連携**: Prometheus形式でのメトリクスエクスポート
2. **NemakiWare UI内コンポーネント**: React + Ant Designによる統合ダッシュボード
3. **OpenAPI準拠エンドポイント**: API v1に監査ログメトリクスエンドポイントを追加

---

## 🎯 実装目標

### 1. 外部ツール連携（Prometheus形式）

**目的**: Prometheus、Grafana、その他の監視ツールとの統合

**要件**:
- Prometheus形式（text/plain）でメトリクスをエクスポート
- 標準的なPrometheusメトリクス命名規則に準拠
- 管理者認証で保護

**エンドポイント**:
```
GET /rest/all/audit/metrics/prometheus
Content-Type: text/plain; version=0.0.4; charset=utf-8
```

**出力形式例**:
```
# HELP nemakiware_audit_events_total Total number of audit events processed
# TYPE nemakiware_audit_events_total counter
nemakiware_audit_events_total 1000

# HELP nemakiware_audit_events_logged Number of audit events successfully logged
# TYPE nemakiware_audit_events_logged counter
nemakiware_audit_events_logged 950

# HELP nemakiware_audit_events_skipped Number of audit events skipped
# TYPE nemakiware_audit_events_skipped counter
nemakiware_audit_events_skipped 40

# HELP nemakiware_audit_events_failed Number of audit events that failed to log
# TYPE nemakiware_audit_events_failed counter
nemakiware_audit_events_failed 10

# HELP nemakiware_audit_enabled Whether audit logging is enabled (1=enabled, 0=disabled)
# TYPE nemakiware_audit_enabled gauge
nemakiware_audit_enabled 1
```

---

### 2. NemakiWare UI内コンポーネント

**目的**: 管理者がブラウザから直接監査ログの状態を確認できるUI

**要件**:
- React + TypeScript + Ant Designで実装
- `SolrMaintenance`コンポーネントと同様のパターンに準拠
- リアルタイム更新（30秒間隔）
- 管理者のみアクセス可能

**表示内容**:
- メトリクス統計（総イベント数、ログ出力済み、スキップ、失敗）
- レート表示（成功率、スキップ率、失敗率）
- 設定情報（有効/無効、READ監査レベル）
- 自動更新機能

**ルーティング**:
```
/#/audit-dashboard
```

**メニュー配置**:
- Adminサブメニュー内に「監査ログ ダッシュボード」を追加

---

### 3. OpenAPI準拠エンドポイント（新規追加）

**目的**: OpenAPI 3.0準拠のAPI v1に監査ログメトリクスエンドポイントを追加

**要件**:
- API v1のresourceパッケージに実装
- OpenAPIアノテーション（@Operation, @Tag等）を使用
- RFC 7807準拠のエラーレスポンス（ProblemDetail）
- 管理者認証で保護

**エンドポイント**:
```
GET /api/v1/cmis/audit/metrics
POST /api/v1/cmis/audit/metrics/reset
GET /api/v1/cmis/audit/metrics/prometheus
```

**OpenAPI仕様**:
- Tag: `audit`（新規追加）
- レスポンススキーマ定義
- エラーレスポンス（401, 403, 500）の定義

---

## 📁 実装ファイル一覧

### バックエンド（Java）

#### 1. `AuditMetricsResource.java` に追加（既存ファイル）

**ファイル**: `core/src/main/java/jp/aegif/nemaki/rest/AuditMetricsResource.java`

**追加メソッド**:
```java
/**
 * Returns audit metrics in Prometheus format.
 * Compatible with Prometheus scraping and Grafana dashboards.
 * 
 * @param httpRequest The HTTP request
 * @return Prometheus-formatted metrics (text/plain)
 */
@GET
@Path("/prometheus")
@Produces("text/plain; version=0.0.4; charset=utf-8")
public Response getPrometheusMetrics(@Context HttpServletRequest httpRequest) {
    // 実装詳細は後述
}
```

**実装要件**:
- 管理者認証チェック（`checkAdmin()`）
- Prometheus形式の出力（HELP、TYPE、メトリクス値）
- エラーハンドリング
- メトリクス名は`nemakiware_audit_*`プレフィックスを使用

---

#### 2. OpenAPI準拠エンドポイント（新規ファイル）

**ファイル**: `core/src/main/java/jp/aegif/nemaki/api/v1/resource/AuditMetricsResource.java`

**実装内容**:
- OpenAPIアノテーション（@Operation, @Tag, @ApiResponses）
- RFC 7807準拠のエラーレスポンス（ProblemDetail）
- ApiExceptionを使用したエラーハンドリング
- レスポンスモデルクラスの定義

**参考**: `core/src/main/java/jp/aegif/nemaki/api/v1/resource/SearchEngineResource.java`

---

#### 3. レスポンスモデル（新規ファイル）

**ファイル**: `core/src/main/java/jp/aegif/nemaki/api/v1/model/response/AuditMetricsResponse.java`

**実装内容**:
- メトリクス値（total, logged, skipped, failed）
- レート計算値（success.rate, skip.rate, failure.rate）
- 設定情報（enabled, readAuditLevel）
- タイムスタンプ

---

### フロントエンド（React + TypeScript）

#### 1. サービス層の作成

**ファイル**: `core/src/main/webapp/ui/src/services/auditMetrics.ts`

**実装内容**:
- `AuditMetricsService`クラス
- `getMetrics()`: JSON形式でメトリクス取得
- `resetMetrics()`: メトリクスリセット（既存）
- TypeScript型定義（`AuditMetricsResponse`, `AuditMetrics`）

**参考**: `core/src/main/webapp/ui/src/services/solrMaintenance.ts`

---

#### 2. コンポーネントの作成

**ファイル**: `core/src/main/webapp/ui/src/components/AuditDashboard/AuditDashboard.tsx`

**実装内容**:
- Ant Designコンポーネント使用（`Card`, `Statistic`, `Row`, `Col`, `Alert`）
- 30秒間隔の自動更新
- エラーハンドリングとローディング状態
- 多言語対応（i18n）は将来の拡張として考慮

**参考**: `core/src/main/webapp/ui/src/components/SolrMaintenance/SolrMaintenance.tsx`

---

#### 3. ルーティング追加

**ファイル**: `core/src/main/webapp/ui/src/App.tsx`

**追加内容**:
```typescript
<Route path="/audit-dashboard" element={
  <ProtectedRoute>
    <AdminRoute>
      <AuditDashboard />
    </AdminRoute>
  </ProtectedRoute>
} />
```

**参考**: `/solr`ルートの実装（Line 294-300）

---

#### 4. メニュー追加

**ファイル**: `core/src/main/webapp/ui/src/components/Layout/Layout.tsx`

**追加内容**:
Adminサブメニューの`children`配列に以下を追加：
```typescript
{
  key: '/audit-dashboard',
  icon: <BarChartOutlined />,
  label: t('menu.auditDashboard') || '監査ログ ダッシュボード'
}
```

**アイコン**: `@ant-design/icons`の`BarChartOutlined`を使用

---

#### 5. 多言語対応（オプション）

**ファイル**: `core/src/main/webapp/ui/src/i18n/locales/ja.json`

**追加内容**:
```json
{
  "menu": {
    "auditDashboard": "監査ログ ダッシュボード"
  },
  "auditDashboard": {
    "title": "監査ログ ダッシュボード",
    "refresh": "更新",
    "enabled": "有効",
    "disabled": "無効",
    "readAuditLevel": "READ監査レベル",
    "totalEvents": "総イベント数",
    "logged": "ログ出力済み",
    "skipped": "スキップ",
    "failed": "失敗",
    "successRate": "成功率",
    "skipRate": "スキップ率",
    "failureRate": "失敗率",
    "lastUpdated": "最終更新",
    "config": "監査ログ設定"
  }
}
```

**ファイル**: `core/src/main/webapp/ui/src/i18n/locales/en.json`

**追加内容**:
```json
{
  "menu": {
    "auditDashboard": "Audit Log Dashboard"
  },
  "auditDashboard": {
    "title": "Audit Log Dashboard",
    "refresh": "Refresh",
    "enabled": "Enabled",
    "disabled": "Disabled",
    "readAuditLevel": "Read Audit Level",
    "totalEvents": "Total Events",
    "logged": "Logged",
    "skipped": "Skipped",
    "failed": "Failed",
    "successRate": "Success Rate",
    "skipRate": "Skip Rate",
    "failureRate": "Failure Rate",
    "lastUpdated": "Last Updated",
    "config": "Audit Log Configuration"
  }
}
```

---

## 🔧 実装詳細

### 1. Prometheus形式エンドポイント

**実装仕様**:

```java
@GET
@Path("/prometheus")
@Produces("text/plain; version=0.0.4; charset=utf-8")
public Response getPrometheusMetrics(@Context HttpServletRequest httpRequest) {
    JSONArray errMsg = new JSONArray();
    
    // 管理者認証チェック
    if (!checkAdmin(errMsg, httpRequest)) {
        return Response.status(403).entity("# Access denied\n").build();
    }
    
    try {
        Map<String, Long> metrics = AuditLogger.getMetrics();
        StringBuilder prometheus = new StringBuilder();
        
        // メトリクス定義（HELP、TYPE）
        prometheus.append("# HELP nemakiware_audit_events_total Total number of audit events processed\n");
        prometheus.append("# TYPE nemakiware_audit_events_total counter\n");
        prometheus.append("nemakiware_audit_events_total ")
                  .append(metrics.getOrDefault("audit.events.total", 0L))
                  .append("\n\n");
        
        prometheus.append("# HELP nemakiware_audit_events_logged Number of audit events successfully logged\n");
        prometheus.append("# TYPE nemakiware_audit_events_logged counter\n");
        prometheus.append("nemakiware_audit_events_logged ")
                  .append(metrics.getOrDefault("audit.events.logged", 0L))
                  .append("\n\n");
        
        prometheus.append("# HELP nemakiware_audit_events_skipped Number of audit events skipped\n");
        prometheus.append("# TYPE nemakiware_audit_events_skipped counter\n");
        prometheus.append("nemakiware_audit_events_skipped ")
                  .append(metrics.getOrDefault("audit.events.skipped", 0L))
                  .append("\n\n");
        
        prometheus.append("# HELP nemakiware_audit_events_failed Number of audit events that failed to log\n");
        prometheus.append("# TYPE nemakiware_audit_events_failed counter\n");
        prometheus.append("nemakiware_audit_events_failed ")
                  .append(metrics.getOrDefault("audit.events.failed", 0L))
                  .append("\n\n");
        
        // 設定情報（gauge）
        prometheus.append("# HELP nemakiware_audit_enabled Whether audit logging is enabled (1=enabled, 0=disabled)\n");
        prometheus.append("# TYPE nemakiware_audit_enabled gauge\n");
        prometheus.append("nemakiware_audit_enabled ")
                  .append(AuditLogger.isEnabled() ? 1 : 0)
                  .append("\n");
        
        return Response.ok(prometheus.toString()).build();
        
    } catch (Exception e) {
        return Response.status(500)
            .entity("# Error: " + e.getMessage() + "\n")
            .build();
    }
}
```

**注意事項**:
- Prometheus形式は厳密なフォーマット（HELP、TYPE、値の順序）
- メトリクス名は`nemakiware_audit_*`プレフィックスを使用
- counter型とgauge型を適切に使い分け

---

### 2. OpenAPI準拠エンドポイント

**実装仕様**:

```java
// core/src/main/java/jp/aegif/nemaki/api/v1/resource/AuditMetricsResource.java
package jp.aegif.nemaki.api.v1.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import jp.aegif.nemaki.api.v1.exception.ApiException;
import jp.aegif.nemaki.api.v1.exception.ProblemDetail;
import jp.aegif.nemaki.api.v1.model.response.AuditMetricsResponse;
import jp.aegif.nemaki.audit.AuditLogger;
import jp.aegif.nemaki.util.constant.CallContextKey;
import org.apache.chemistry.opencmis.commons.server.CallContext;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.logging.Logger;

@Component
@Path("/audit/metrics")
@Tag(name = "audit", description = "Audit logging metrics and monitoring")
@Produces(MediaType.APPLICATION_JSON)
public class AuditMetricsResource {
    
    private static final Logger logger = Logger.getLogger(AuditMetricsResource.class.getName());
    
    @Context
    private HttpServletRequest httpRequest;
    
    private void checkAdminAuthorization() {
        CallContext callContext = (CallContext) httpRequest.getAttribute("CallContext");
        if (callContext == null) {
            throw ApiException.unauthorized("Authentication required for audit metrics access");
        }
        Boolean isAdmin = (Boolean) callContext.get(CallContextKey.IS_ADMIN);
        if (isAdmin == null || !isAdmin) {
            throw ApiException.permissionDenied("Only administrators can access audit metrics");
        }
    }
    
    @GET
    @Operation(
            summary = "Get audit metrics",
            description = "Returns audit logging metrics including event counts, rates, and configuration status"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Audit metrics retrieved successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = AuditMetricsResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Unauthorized",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Forbidden - Admin access required",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Internal server error",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getMetrics() {
        logger.info("API v1: Getting audit metrics");
        
        checkAdminAuthorization();
        
        try {
            Map<String, Long> metrics = AuditLogger.getMetrics();
            
            AuditMetricsResponse response = new AuditMetricsResponse();
            response.setTotal(metrics.getOrDefault("audit.events.total", 0L));
            response.setLogged(metrics.getOrDefault("audit.events.logged", 0L));
            response.setSkipped(metrics.getOrDefault("audit.events.skipped", 0L));
            response.setFailed(metrics.getOrDefault("audit.events.failed", 0L));
            
            // Calculate rates
            long total = response.getTotal();
            if (total > 0) {
                response.setSuccessRate((double) response.getLogged() / total * 100);
                response.setSkipRate((double) response.getSkipped() / total * 100);
                response.setFailureRate((double) response.getFailed() / total * 100);
            }
            
            response.setEnabled(AuditLogger.isEnabled());
            response.setReadAuditLevel(AuditLogger.getReadAuditLevel());
            response.setTimestamp(System.currentTimeMillis());
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting audit metrics: " + e.getMessage());
            throw ApiException.internalError("Failed to get audit metrics: " + e.getMessage(), e);
        }
    }
    
    @POST
    @Path("/reset")
    @Operation(
            summary = "Reset audit metrics",
            description = "Resets all audit metrics counters to zero. Admin access required."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Metrics reset successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = AuditMetricsResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Forbidden - Admin access required",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response resetMetrics() {
        logger.info("API v1: Resetting audit metrics");
        
        checkAdminAuthorization();
        
        try {
            // Get metrics before reset
            Map<String, Long> beforeReset = AuditLogger.getMetrics();
            
            // Reset metrics
            AuditLogger.resetMetrics();
            
            AuditMetricsResponse response = new AuditMetricsResponse();
            response.setMessage("Audit metrics reset successfully");
            response.setPreviousTotal(beforeReset.getOrDefault("audit.events.total", 0L));
            response.setPreviousLogged(beforeReset.getOrDefault("audit.events.logged", 0L));
            response.setPreviousSkipped(beforeReset.getOrDefault("audit.events.skipped", 0L));
            response.setPreviousFailed(beforeReset.getOrDefault("audit.events.failed", 0L));
            response.setTimestamp(System.currentTimeMillis());
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error resetting audit metrics: " + e.getMessage());
            throw ApiException.internalError("Failed to reset audit metrics: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/prometheus")
    @Produces("text/plain; version=0.0.4; charset=utf-8")
    @Operation(
            summary = "Get audit metrics in Prometheus format",
            description = "Returns audit metrics in Prometheus exposition format for scraping"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Prometheus metrics",
                    content = @Content(
                            mediaType = "text/plain; version=0.0.4; charset=utf-8"
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Forbidden - Admin access required",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getPrometheusMetrics() {
        logger.info("API v1: Getting audit metrics in Prometheus format");
        
        checkAdminAuthorization();
        
        try {
            Map<String, Long> metrics = AuditLogger.getMetrics();
            StringBuilder prometheus = new StringBuilder();
            
            // メトリクス定義（既存の実装と同じ）
            // ... Prometheus形式の出力 ...
            
            return Response.ok(prometheus.toString())
                    .type("text/plain; version=0.0.4; charset=utf-8")
                    .build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting Prometheus metrics: " + e.getMessage());
            throw ApiException.internalError("Failed to get Prometheus metrics: " + e.getMessage(), e);
        }
    }
}
```

**注意事項**:
- `@Path("/audit/metrics")`はリポジトリ非依存のグローバルエンドポイント
- `ApiAuthenticationFilter`は`repositoryId`が必須のため、認証フィルターの例外処理が必要
- または、`/repositories/{repositoryId}/audit/metrics`形式にする（既存パターンに準拠）

---

### 3. レスポンスモデル

**実装仕様**:

```java
// core/src/main/java/jp/aegif/nemaki/api/v1/model/response/AuditMetricsResponse.java
package jp.aegif.nemaki.api.v1.model.response;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonProperty;

@Schema(description = "Audit logging metrics")
public class AuditMetricsResponse {
    
    @Schema(description = "Total number of audit events processed")
    @JsonProperty("total")
    private Long total;
    
    @Schema(description = "Number of audit events successfully logged")
    @JsonProperty("logged")
    private Long logged;
    
    @Schema(description = "Number of audit events skipped")
    @JsonProperty("skipped")
    private Long skipped;
    
    @Schema(description = "Number of audit events that failed to log")
    @JsonProperty("failed")
    private Long failed;
    
    @Schema(description = "Success rate percentage")
    @JsonProperty("successRate")
    private Double successRate;
    
    @Schema(description = "Skip rate percentage")
    @JsonProperty("skipRate")
    private Double skipRate;
    
    @Schema(description = "Failure rate percentage")
    @JsonProperty("failureRate")
    private Double failureRate;
    
    @Schema(description = "Whether audit logging is enabled")
    @JsonProperty("enabled")
    private Boolean enabled;
    
    @Schema(description = "Read audit level (NONE, DOWNLOAD, METADATA, ALL)")
    @JsonProperty("readAuditLevel")
    private String readAuditLevel;
    
    @Schema(description = "Timestamp of the metrics snapshot")
    @JsonProperty("timestamp")
    private Long timestamp;
    
    @Schema(description = "Message (for reset operation)")
    @JsonProperty("message")
    private String message;
    
    // Previous values (for reset operation)
    @JsonProperty("previousTotal")
    private Long previousTotal;
    
    @JsonProperty("previousLogged")
    private Long previousLogged;
    
    @JsonProperty("previousSkipped")
    private Long previousSkipped;
    
    @JsonProperty("previousFailed")
    private Long previousFailed;
    
    // Getters and setters...
}
```

---

### 4. Reactコンポーネント実装

**実装仕様**:

```typescript
// core/src/main/webapp/ui/src/components/AuditDashboard/AuditDashboard.tsx
import React, { useState, useEffect } from 'react';
import {
  Card,
  Row,
  Col,
  Statistic,
  Button,
  Spin,
  Alert,
  Tag,
  Space
} from 'antd';
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  StopOutlined,
  ReloadOutlined,
  BarChartOutlined
} from '@ant-design/icons';
import { useAuth } from '../../contexts/AuthContext';
import { AuditMetricsService, AuditMetricsResponse } from '../../services/auditMetrics';

export const AuditDashboard: React.FC = () => {
  const { authToken, handleAuthError } = useAuth();
  const [metrics, setMetrics] = useState<AuditMetricsResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  
  const service = new AuditMetricsService(() => handleAuthError(null));

  const fetchMetrics = async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await service.getMetrics();
      setMetrics(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to fetch metrics');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchMetrics();
    // 30秒ごとに自動更新
    const interval = setInterval(fetchMetrics, 30000);
    return () => clearInterval(interval);
  }, []);

  if (loading && !metrics) {
    return <Spin size="large" style={{ display: 'block', textAlign: 'center', padding: '50px' }} />;
  }

  if (error) {
    return (
      <Alert
        message="エラー"
        description={error}
        type="error"
        action={
          <Button size="small" onClick={fetchMetrics}>
            再試行
          </Button>
        }
      />
    );
  }

  if (!metrics) {
    return null;
  }

  return (
    <div style={{ padding: '24px' }}>
      <Card
        title={
          <Space>
            <BarChartOutlined />
            <span>監査ログ ダッシュボード</span>
          </Space>
        }
        extra={
          <Space>
            <Tag color={metrics.enabled ? 'green' : 'red'}>
              {metrics.enabled ? '有効' : '無効'}
            </Tag>
            <Tag>レベル: {metrics.readAuditLevel}</Tag>
            <Button
              icon={<ReloadOutlined />}
              onClick={fetchMetrics}
              loading={loading}
            >
              更新
            </Button>
          </Space>
        }
      >
        {/* メトリクス統計 */}
        <Row gutter={16} style={{ marginBottom: 24 }}>
          <Col span={6}>
            <Statistic
              title="総イベント数"
              value={metrics.total}
              prefix={<BarChartOutlined />}
            />
          </Col>
          <Col span={6}>
            <Statistic
              title="ログ出力済み"
              value={metrics.logged}
              valueStyle={{ color: '#3f8600' }}
              prefix={<CheckCircleOutlined />}
            />
          </Col>
          <Col span={6}>
            <Statistic
              title="スキップ"
              value={metrics.skipped}
              valueStyle={{ color: '#faad14' }}
              prefix={<StopOutlined />}
            />
          </Col>
          <Col span={6}>
            <Statistic
              title="失敗"
              value={metrics.failed}
              valueStyle={{ color: '#cf1322' }}
              prefix={<CloseCircleOutlined />}
            />
          </Col>
        </Row>

        {/* レート表示 */}
        {metrics.successRate !== undefined && (
          <Row gutter={16} style={{ marginBottom: 24 }}>
            <Col span={8}>
              <Card size="small">
                <Statistic
                  title="成功率"
                  value={metrics.successRate.toFixed(2)}
                  suffix="%"
                  valueStyle={{ color: '#3f8600' }}
                />
              </Card>
            </Col>
            <Col span={8}>
              <Card size="small">
                <Statistic
                  title="スキップ率"
                  value={metrics.skipRate.toFixed(2)}
                  suffix="%"
                  valueStyle={{ color: '#faad14' }}
                />
              </Card>
            </Col>
            <Col span={8}>
              <Card size="small">
                <Statistic
                  title="失敗率"
                  value={metrics.failureRate.toFixed(2)}
                  suffix="%"
                  valueStyle={{ color: '#cf1322' }}
                />
              </Card>
            </Col>
          </Row>
        )}

        {/* 設定情報 */}
        <Alert
          message="監査ログ設定"
          description={
            <div>
              <p>状態: {metrics.enabled ? '有効' : '無効'}</p>
              <p>READ監査レベル: {metrics.readAuditLevel}</p>
              <p>最終更新: {new Date(metrics.timestamp).toLocaleString('ja-JP')}</p>
            </div>
          }
          type="info"
          showIcon
        />
      </Card>
    </div>
  );
};
```

---

### 5. サービス層実装

**実装仕様**:

```typescript
// core/src/main/webapp/ui/src/services/auditMetrics.ts
export interface AuditMetrics {
  total: number;
  logged: number;
  skipped: number;
  failed: number;
}

export interface AuditMetricsResponse {
  total: number;
  logged: number;
  skipped: number;
  failed: number;
  successRate?: number;
  skipRate?: number;
  failureRate?: number;
  enabled: boolean;
  readAuditLevel: string;
  timestamp: number;
  message?: string;
  previousTotal?: number;
  previousLogged?: number;
  previousSkipped?: number;
  previousFailed?: number;
}

export class AuditMetricsService {
  private baseUrl = '/core/api/v1/cmis/audit/metrics';  // OpenAPI準拠エンドポイントを使用

  constructor(private onAuthError: () => void) {}

  async getMetrics(): Promise<AuditMetricsResponse> {
    const authService = AuthService.getInstance();
    const headers = authService.getAuthHeaders();
    
    const response = await fetch(this.baseUrl, {
      headers: {
        ...headers,
        'Content-Type': 'application/json'
      },
      credentials: 'include'
    });
    
    if (response.status === 401 || response.status === 403) {
      this.onAuthError();
      throw new Error('Authentication required');
    }
    
    if (!response.ok) {
      const error = await response.json().catch(() => ({ detail: `HTTP ${response.status}` }));
      throw new Error(error.detail || `Failed to fetch metrics: ${response.status}`);
    }
    
    return response.json();
  }

  async resetMetrics(): Promise<AuditMetricsResponse> {
    const authService = AuthService.getInstance();
    const headers = authService.getAuthHeaders();
    
    const response = await fetch(`${this.baseUrl}/reset`, {
      method: 'POST',
      headers: {
        ...headers,
        'Content-Type': 'application/json'
      },
      credentials: 'include'
    });
    
    if (response.status === 401 || response.status === 403) {
      this.onAuthError();
      throw new Error('Authentication required');
    }
    
    if (!response.ok) {
      const error = await response.json().catch(() => ({ detail: `HTTP ${response.status}` }));
      throw new Error(error.detail || `Failed to reset metrics: ${response.status}`);
    }
    
    return response.json();
  }
}
```

---

## ⚠️ 重要な設計決定

### OpenAPI準拠エンドポイントのパス設計

**問題**: `ApiAuthenticationFilter`は`repositoryId`が必須のため、リポジトリ非依存のエンドポイント（`/audit/metrics`）は認証フィルターでブロックされる可能性がある

**解決策1**: リポジトリ依存パスに変更（推奨）
```
GET /api/v1/cmis/repositories/{repositoryId}/audit/metrics
```

**解決策2**: 認証フィルターの例外処理を追加
```java
// ApiAuthenticationFilter.java に追加
if (path.startsWith("audit/metrics")) {
    // グローバルエンドポイントとして処理
    // 認証チェックはリソース内で実施
    return;
}
```

**推奨**: 解決策1（既存パターンに準拠）

---

### ApiV1ApplicationへのTag追加

**ファイル**: `core/src/main/java/jp/aegif/nemaki/api/v1/ApiV1Application.java`

**追加内容**:
```java
tags = {
    // ... 既存のtags ...
    @Tag(name = "audit", description = "Audit logging metrics and monitoring")
}
```

---

## ✅ 実装チェックリスト

### バックエンド

- [ ] `AuditMetricsResource.java`（既存）に`getPrometheusMetrics()`メソッドを追加
- [ ] `api/v1/resource/AuditMetricsResource.java`（新規）を作成
- [ ] `api/v1/model/response/AuditMetricsResponse.java`（新規）を作成
- [ ] OpenAPIアノテーション（@Operation, @Tag, @ApiResponses）を実装
- [ ] RFC 7807準拠のエラーレスポンス（ProblemDetail）を使用
- [ ] ApiExceptionを使用したエラーハンドリング
- [ ] 管理者認証チェック（`checkAdminAuthorization()`）
- [ ] `ApiV1Application.java`に`audit`タグを追加
- [ ] `ApiAuthenticationFilter.java`の例外処理（必要に応じて）

### フロントエンド

- [ ] `services/auditMetrics.ts`を作成
- [ ] `components/AuditDashboard/AuditDashboard.tsx`を作成
- [ ] `App.tsx`にルーティングを追加（`/audit-dashboard`）
- [ ] `Layout.tsx`のメニューに追加（Adminサブメニュー）
- [ ] 多言語対応ファイル（`ja.json`, `en.json`）に翻訳を追加
- [ ] 30秒間隔の自動更新を実装
- [ ] エラーハンドリングとローディング状態を実装
- [ ] Ant Designコンポーネントを使用（`Card`, `Statistic`, `Row`, `Col`, `Alert`）

### テスト

- [ ] Prometheusエンドポイントの動作確認
- [ ] OpenAPIエンドポイントの動作確認
- [ ] OpenAPI仕様書（`/api/v1/cmis/openapi.json`）にエンドポイントが含まれることを確認
- [ ] 管理者認証の動作確認
- [ ] Reactコンポーネントの表示確認
- [ ] 自動更新の動作確認
- [ ] エラーハンドリングの動作確認

---

## 📝 実装時の注意事項

### 1. セキュリティ

- **必須**: 全エンドポイント（JSON、Prometheus、OpenAPI）とも管理者認証で保護
- OpenAPIエンドポイントも`checkAdminAuthorization()`を使用

### 2. パフォーマンス

- 自動更新間隔は30秒（変更可能な場合は設定化を検討）
- メトリクス取得は軽量な操作のため、パフォーマンス問題は想定されない

### 3. エラーハンドリング

- OpenAPIエンドポイントはRFC 7807準拠（ProblemDetail）を使用
- 既存エンドポイント（`/rest/all/audit/metrics`）はJSON形式のまま維持（後方互換性）

### 4. UI/UX

- `SolrMaintenance`コンポーネントと同様のデザインパターンに準拠
- Ant Designの`Statistic`コンポーネントで数値を強調表示
- 色分け: 成功（緑）、警告（黄）、失敗（赤）

### 5. 多言語対応

- 初期実装は日本語固定でも可
- 将来の拡張を考慮してi18n対応を推奨

### 6. OpenAPI準拠

- 既存のAPI v1リソースパターンに準拠
- `@Operation`, `@Tag`, `@ApiResponses`アノテーションを使用
- レスポンススキーマを`@Schema`で定義
- エラーレスポンスも`ProblemDetail`スキーマで定義

---

## 🔍 参考実装

### 既存コンポーネント

- **SolrMaintenance**: `core/src/main/webapp/ui/src/components/SolrMaintenance/SolrMaintenance.tsx`
  - メトリクス表示のパターン
  - 自動更新の実装
  - エラーハンドリング

- **SearchEngineResource**: `core/src/main/java/jp/aegif/nemaki/api/v1/resource/SearchEngineResource.java`
  - OpenAPIアノテーションの使用例
  - 管理者認証チェックのパターン
  - エラーハンドリング

- **RepositoryResource**: `core/src/main/java/jp/aegif/nemaki/api/v1/resource/RepositoryResource.java`
  - OpenAPIアノテーションの使用例
  - レスポンスモデルの定義

---

## 📊 期待される結果

### Prometheusエンドポイント

```bash
$ curl -u admin:admin http://localhost:8080/core/rest/all/audit/metrics/prometheus

# HELP nemakiware_audit_events_total Total number of audit events processed
# TYPE nemakiware_audit_events_total counter
nemakiware_audit_events_total 1000

# ...
```

### OpenAPI準拠エンドポイント

```bash
$ curl -H "AUTH_TOKEN: <token>" http://localhost:8080/core/api/v1/cmis/audit/metrics

{
  "total": 1000,
  "logged": 950,
  "skipped": 40,
  "failed": 10,
  "successRate": 95.0,
  "skipRate": 4.0,
  "failureRate": 1.0,
  "enabled": true,
  "readAuditLevel": "DOWNLOAD",
  "timestamp": 1706184000000
}
```

### OpenAPI仕様書

```bash
$ curl http://localhost:8080/core/api/v1/cmis/openapi.json | jq '.paths["/audit/metrics"]'

{
  "get": {
    "tags": ["audit"],
    "summary": "Get audit metrics",
    "responses": {
      "200": {
        "description": "Audit metrics retrieved successfully",
        "content": {
          "application/json": {
            "schema": {
              "$ref": "#/components/schemas/AuditMetricsResponse"
            }
          }
        }
      }
    }
  }
}
```

### React UI

- ブラウザで`http://localhost:8080/core/ui/#/audit-dashboard`にアクセス
- メトリクス統計が表示される
- 30秒ごとに自動更新
- 管理者のみアクセス可能

---

## 🚀 実装後の確認事項

1. **Prometheus連携**
   - Prometheusサーバーからスクレイプ可能か確認
   - Grafanaでダッシュボード作成可能か確認

2. **OpenAPI準拠**
   - `/api/v1/cmis/openapi.json`にエンドポイントが含まれるか確認
   - Swagger UIでエンドポイントが表示されるか確認
   - エラーレスポンスがRFC 7807準拠か確認

3. **UI動作**
   - メトリクスが正しく表示されるか確認
   - 自動更新が動作するか確認
   - エラー時に適切なメッセージが表示されるか確認

4. **セキュリティ**
   - 非管理者がアクセスできないか確認
   - 認証エラーが適切に処理されるか確認

---

## 📚 関連ドキュメント

- **監査ログ機能**: `core/src/main/java/jp/aegif/nemaki/audit/`
- **REST API**: `core/src/main/java/jp/aegif/nemaki/rest/AuditMetricsResource.java`
- **OpenAPI API v1**: `core/src/main/java/jp/aegif/nemaki/api/v1/`
- **React UI**: `core/src/main/webapp/ui/src/components/`
- **SolrMaintenance参考**: `core/src/main/webapp/ui/src/components/SolrMaintenance/`
- **OpenAPI設計書**: `docs/design/REST-API-ODATA-DESIGN.md`

---

## 📝 補足

### 将来の拡張（オプション）

1. **時系列グラフ**: Chart.jsやRechartsを使用して時系列データを可視化
2. **アラート設定**: 失敗率が閾値を超えた場合の通知機能
3. **メトリクス履歴**: 過去のメトリクスデータの保持と表示
4. **フィルタリング**: 操作タイプ、ユーザー、時間範囲でのフィルタリング

### OpenAPI準拠の重要性

- **標準化**: OpenAPI準拠により、標準的なAPIクライアントツールが使用可能
- **ドキュメント**: OpenAPI仕様書が自動生成され、APIドキュメントとして機能
- **検証**: リクエスト/レスポンスのスキーマ検証が可能
- **統合**: Swagger UI、Postman、その他のツールとの統合が容易

---

**実装担当者へのメッセージ**:

既存の`SolrMaintenance`コンポーネント、`SearchEngineResource`、`RepositoryResource`を参考に実装してください。パターンに準拠することで、コードの一貫性と保守性が向上します。

OpenAPI準拠エンドポイントの実装では、既存のAPI v1リソースのパターンに厳密に準拠してください。特にエラーハンドリング（ProblemDetail）と認証チェック（`checkAdminAuthorization()`）のパターンを踏襲してください。

質問や不明点があれば、既存の実装を参照するか、プロジェクトメンバーに確認してください。
