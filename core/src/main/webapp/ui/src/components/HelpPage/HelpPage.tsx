import React from 'react';
import { Tabs, Typography, Collapse, Descriptions, Alert, Table, Space } from 'antd';
import {
  FileOutlined, FolderOutlined, SearchOutlined, UploadOutlined,
  EyeOutlined, EditOutlined, HistoryOutlined, LockOutlined,
  CloudOutlined, KeyOutlined, UserOutlined, GlobalOutlined,
  TeamOutlined, DatabaseOutlined, SendOutlined,
  SyncOutlined, BarChartOutlined, SwapOutlined, ApiOutlined,
  LoginOutlined, QuestionCircleOutlined
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../contexts/AuthContext';

const { Title, Paragraph, Text } = Typography;

const HelpPage: React.FC = () => {
  const { t } = useTranslation();
  const { authToken } = useAuth();
  const isAdmin = authToken?.isAdmin === true;

  return (
    <div style={{ maxWidth: 900, margin: '0 auto', padding: '24px 16px' }}>
      <Title level={2}>
        <QuestionCircleOutlined style={{ marginRight: 8 }} />
        {t('help.title', 'NemakiWare ヘルプ')}
      </Title>

      <Tabs
        defaultActiveKey="user"
        items={[
          {
            key: 'user',
            label: t('help.userGuide', 'ユーザーガイド'),
            children: <UserGuide />,
          },
          ...(isAdmin ? [{
            key: 'admin',
            label: t('help.adminGuide', '管理者ガイド'),
            children: <AdminGuide />,
          }] : []),
        ]}
      />
    </div>
  );
};

/* ───────────────────── ユーザーガイド ───────────────────── */

const UserGuide: React.FC = () => {
  const { t } = useTranslation();

  const sections = [
    {
      key: 'login',
      label: t('help.user.login', 'ログイン'),
      icon: <LoginOutlined />,
      children: (
        <>
          <Paragraph>{t('help.user.loginDesc', 'ブラウザで NemakiWare UI にアクセスし、ユーザー名とパスワードを入力してログインします。')}</Paragraph>
          <Descriptions bordered column={1} size="small">
            <Descriptions.Item label={t('help.user.authPassword', 'パスワード認証')}>{t('help.user.authPasswordDesc', '標準のユーザー名/パスワードによるログイン')}</Descriptions.Item>
            <Descriptions.Item label={t('help.user.authPasskey', 'パスキー認証')}>{t('help.user.authPasskeyDesc', 'Touch ID / Face ID / セキュリティキーによるパスワードレスログイン')}</Descriptions.Item>
            <Descriptions.Item label={t('help.user.authOIDC', 'OIDC')}>{t('help.user.authOIDCDesc', 'Google / Microsoft 等のクラウド認証')}</Descriptions.Item>
            <Descriptions.Item label={t('help.user.authSAML', 'SAML')}>{t('help.user.authSAMLDesc', 'エンタープライズ SSO 認証')}</Descriptions.Item>
          </Descriptions>
        </>
      ),
    },
    {
      key: 'documents',
      label: t('help.user.documents', 'ドキュメント一覧'),
      icon: <FolderOutlined />,
      children: (
        <>
          <Paragraph>{t('help.user.documentsDesc', 'ログイン後に表示される画面です。左ペインのフォルダツリーでフォルダを選択し、メインエリアでドキュメントを操作します。')}</Paragraph>
          <Table
            size="small" pagination={false}
            dataSource={[
              { key: '1', col: t('help.user.colName', '名前'), desc: t('help.user.colNameDesc', 'ファイル名またはフォルダ名。クリックで詳細表示/フォルダ移動') },
              { key: '2', col: t('help.user.colDate', '更新日時'), desc: t('help.user.colDateDesc', '最終更新日') },
              { key: '3', col: t('help.user.colUser', '更新者'), desc: t('help.user.colUserDesc', '最終更新ユーザー') },
              { key: '4', col: t('help.user.colSize', 'サイズ'), desc: t('help.user.colSizeDesc', 'ファイルサイズ') },
            ]}
            columns={[
              { title: t('help.user.column', '列'), dataIndex: 'col', width: 120 },
              { title: t('help.user.description', '説明'), dataIndex: 'desc' },
            ]}
          />
        </>
      ),
    },
    {
      key: 'folder',
      label: t('help.user.folder', 'フォルダ操作'),
      icon: <FolderOutlined />,
      children: (
        <Paragraph>{t('help.user.folderDesc', '「フォルダ作成」ボタンでフォルダを作成できます。フォルダ名とタイプ（カスタムタイプがある場合）を入力して「作成」をクリックします。削除時にサブフォルダやドキュメントが含まれる場合はカスケード削除の確認が表示されます。')}</Paragraph>
      ),
    },
    {
      key: 'upload',
      label: t('help.user.upload', 'アップロード'),
      icon: <UploadOutlined />,
      children: (
        <>
          <Paragraph>{t('help.user.uploadDesc', '「アップロード」ボタンをクリックし、ファイルをドラッグ＆ドロップまたは選択します。ドキュメントタイプやカスタムプロパティを設定してアップロードします。')}</Paragraph>
          <Paragraph><Text strong>{t('help.user.uploadFormats', '対応形式:')}</Text> PDF, Word (.docx), Excel (.xlsx), PowerPoint (.pptx), {t('help.user.uploadFormatsMore', '画像, テキスト, HTML, XML, JSON, その他任意のファイル')}</Paragraph>
        </>
      ),
    },
    {
      key: 'preview',
      label: t('help.user.preview', 'プレビュー'),
      icon: <EyeOutlined />,
      children: (
        <Table
          size="small" pagination={false}
          dataSource={[
            { key: '1', format: 'PDF', method: t('help.user.previewPDF', 'ブラウザ内 PDF ビューア') },
            { key: '2', format: 'Word / Excel / PPT', method: t('help.user.previewOffice', 'サーバーサイド変換 → PDF プレビュー') },
            { key: '3', format: t('help.user.previewImage', '画像'), method: t('help.user.previewImageDesc', 'インラインプレビュー') },
            { key: '4', format: t('help.user.previewText', 'テキスト / JSON / XML'), method: t('help.user.previewTextDesc', 'シンタックスハイライト') },
            { key: '5', format: t('help.user.previewVideo', '動画'), method: t('help.user.previewVideoDesc', 'ストリーミングプレイヤー') },
          ]}
          columns={[
            { title: t('help.user.format', '形式'), dataIndex: 'format', width: 200 },
            { title: t('help.user.previewMethod', 'プレビュー方法'), dataIndex: 'method' },
          ]}
        />
      ),
    },
    {
      key: 'properties',
      label: t('help.user.properties', 'プロパティ編集'),
      icon: <EditOutlined />,
      children: (
        <Paragraph>{t('help.user.propertiesDesc', '詳細画面の「プロパティ」タブから「編集」をクリックして値を変更し、「保存」します。cmis:objectId, cmis:createdBy 等のシステムプロパティは読み取り専用です。')}</Paragraph>
      ),
    },
    {
      key: 'versioning',
      label: t('help.user.versioning', 'バージョン管理'),
      icon: <HistoryOutlined />,
      children: (
        <Paragraph>{t('help.user.versioningDesc', '「チェックアウト」でロック→ファイル編集→「チェックイン」で新バージョン作成。「バージョン履歴」タブで過去バージョンの閲覧・ダウンロードが可能です。')}</Paragraph>
      ),
    },
    {
      key: 'search',
      label: t('help.user.search', '検索'),
      icon: <SearchOutlined />,
      children: (
        <Paragraph>{t('help.user.searchDesc', '画面上部の検索ボックスにキーワードを入力して Enter。ファイル名と本文から日本語・英語で検索できます。RAG（セマンティック検索）が有効な場合、関連性の高いドキュメントが優先表示されます。')}</Paragraph>
      ),
    },
    {
      key: 'acl',
      label: t('help.user.acl', '権限の確認'),
      icon: <LockOutlined />,
      children: (
        <Descriptions bordered column={1} size="small">
          <Descriptions.Item label="cmis:read">{t('help.user.aclRead', '閲覧')}</Descriptions.Item>
          <Descriptions.Item label="cmis:write">{t('help.user.aclWrite', '編集')}</Descriptions.Item>
          <Descriptions.Item label="cmis:all">{t('help.user.aclAll', '全操作（管理者権限）')}</Descriptions.Item>
        </Descriptions>
      ),
    },
    {
      key: 'cloud',
      label: t('help.user.cloud', 'クラウドドライブ連携'),
      icon: <CloudOutlined />,
      children: (
        <Paragraph>{t('help.user.cloudDesc', 'Google Drive / OneDrive が設定されている場合、「インポート」ボタンからクラウドファイルを取り込めます。同じファイルを再インポートすると「スキップ」通知が表示され、既存ドキュメントへのリンクが提供されます。')}</Paragraph>
      ),
    },
    {
      key: 'passkey',
      label: t('help.user.passkey', 'パスキー認証'),
      icon: <KeyOutlined />,
      children: (
        <>
          <Paragraph>{t('help.user.passkeyDesc', 'アカウント設定から Touch ID / Face ID / セキュリティキーを登録し、パスワードなしでログインできます。')}</Paragraph>
          <Alert type="info" showIcon message={t('help.user.passkeyNote', 'HTTPS 環境（または localhost）でのみ利用可能です。')} />
        </>
      ),
    },
    {
      key: 'language',
      label: t('help.user.language', '言語切り替え'),
      icon: <GlobalOutlined />,
      children: (
        <Paragraph>{t('help.user.languageDesc', '画面右上の言語切り替えボタンで日本語と英語を切り替えできます。設定はブラウザに保存されます。')}</Paragraph>
      ),
    },
  ];

  return (
    <Collapse
      defaultActiveKey={['login', 'documents']}
      items={sections.map(s => ({
        key: s.key,
        label: <Space>{s.icon}<Text strong>{s.label}</Text></Space>,
        children: s.children,
      }))}
    />
  );
};

/* ───────────────────── 管理者ガイド ───────────────────── */

const AdminGuide: React.FC = () => {
  const { t } = useTranslation();

  const sections = [
    {
      key: 'users',
      label: t('help.admin.users', 'ユーザー管理'),
      icon: <UserOutlined />,
      children: (
        <Paragraph>{t('help.admin.usersDesc', '「管理」→「ユーザー管理」でユーザーの作成・編集・削除・パスワードリセットが可能です。管理者権限の付与もここで行います。パスワードは BCrypt でハッシュ化されて保存されます。')}</Paragraph>
      ),
    },
    {
      key: 'groups',
      label: t('help.admin.groups', 'グループ管理'),
      icon: <TeamOutlined />,
      children: (
        <Paragraph>{t('help.admin.groupsDesc', 'グループの作成とメンバー管理。グループは親子関係を持て、子グループのメンバーは親グループの権限を継承します。')}</Paragraph>
      ),
    },
    {
      key: 'types',
      label: t('help.admin.types', 'タイプ管理'),
      icon: <FileOutlined />,
      children: (
        <Paragraph>{t('help.admin.typesDesc', 'CMIS オブジェクトタイプの定義を管理します。カスタムタイプの作成、プロパティの追加（文字列/数値/日付/真偽値）、JSON エディタでの直接編集、GUI エディタでのビジュアル編集が可能です。')}</Paragraph>
      ),
    },
    {
      key: 'archive',
      label: t('help.admin.archive', 'アーカイブ管理'),
      icon: <DatabaseOutlined />,
      children: (
        <Paragraph>{t('help.admin.archiveDesc', '削除されたドキュメントの一覧・復元・完全削除。一括操作にも対応しています。完全削除するとデータベースから物理削除され、復元できなくなります。')}</Paragraph>
      ),
    },
    {
      key: 'solr',
      label: t('help.admin.solr', '検索エンジン管理'),
      icon: <DatabaseOutlined />,
      children: (
        <Paragraph>{t('help.admin.solrDesc', 'Solr インデックスの再構築と RAG ベクトルインデックスの再構築。大量ドキュメントでは数分〜数十分かかります。ステータスでインデックス対象数と最終更新日時を確認できます。')}</Paragraph>
      ),
    },
    {
      key: 'webhook',
      label: t('help.admin.webhook', 'Webhook 管理'),
      icon: <SendOutlined />,
      children: (
        <Paragraph>{t('help.admin.webhookDesc', 'ドキュメントの作成・更新・削除時に外部 URL に通知を送信します。Webhook URL、イベント種別、対象フォルダを設定します。')}</Paragraph>
      ),
    },
    {
      key: 'sync',
      label: t('help.admin.sync', 'クラウドディレクトリ同期'),
      icon: <SyncOutlined />,
      children: (
        <Paragraph>{t('help.admin.syncDesc', 'Google Workspace / Microsoft Entra ID のユーザー・グループを NemakiWare に同期します。手動実行と定期自動同期に対応しています。')}</Paragraph>
      ),
    },
    {
      key: 'ingest',
      label: t('help.admin.ingest', '外部インジェスト'),
      icon: <SwapOutlined />,
      children: (
        <>
          <Paragraph>{t('help.admin.ingestDesc', '外部システム（メール、チャット、クラウドストレージ等）からドキュメントを自動取り込みます。')}</Paragraph>
          <Table
            size="small" pagination={false}
            dataSource={[
              { key: '1', adapter: 'IMAP', target: t('help.admin.ingestIMAP', 'メールサーバー') },
              { key: '2', adapter: 'Gmail', target: 'Gmail API' },
              { key: '3', adapter: 'M365 Mail', target: 'Microsoft 365' },
              { key: '4', adapter: 'Slack', target: t('help.admin.ingestSlack', 'チャンネルメッセージ') },
              { key: '5', adapter: 'Teams', target: t('help.admin.ingestTeams', 'チャンネルメッセージ') },
              { key: '6', adapter: 'Mattermost', target: t('help.admin.ingestMM', 'チャンネルメッセージ') },
              { key: '7', adapter: 'Chatwork', target: t('help.admin.ingestCW', 'ルームメッセージ') },
              { key: '8', adapter: 'Notion', target: t('help.admin.ingestNotion', 'ページ・データベース') },
              { key: '9', adapter: 'Salesforce', target: t('help.admin.ingestSF', 'レコード') },
              { key: '10', adapter: 'Box', target: t('help.admin.ingestBox', 'ファイル') },
              { key: '11', adapter: 'Dropbox', target: t('help.admin.ingestDropbox', 'ファイル') },
            ]}
            columns={[
              { title: t('help.admin.adapter', 'アダプタ'), dataIndex: 'adapter', width: 120 },
              { title: t('help.admin.target', '対象'), dataIndex: 'target' },
            ]}
          />
        </>
      ),
    },
    {
      key: 'audit',
      label: t('help.admin.audit', '監査ダッシュボード'),
      icon: <BarChartOutlined />,
      children: (
        <Paragraph>{t('help.admin.auditDesc', 'システムの操作ログと統計を確認できます。操作数のグラフ（日別/週別）、操作種別の内訳、ユーザー別の操作数が表示されます。')}</Paragraph>
      ),
    },
    {
      key: 'importexport',
      label: t('help.admin.importexport', 'インポート/エクスポート'),
      icon: <SwapOutlined />,
      children: (
        <Paragraph>{t('help.admin.importexportDesc', 'ドキュメントの一括インポート/エクスポート。エクスポートは ZIP 形式（メタデータ + コンテンツ）。インポート時に同名ファイルの上書き設定が可能です。')}</Paragraph>
      ),
    },
    {
      key: 'api',
      label: t('help.admin.api', 'API'),
      icon: <ApiOutlined />,
      children: (
        <>
          <Paragraph>{t('help.admin.apiDesc', 'Swagger UI で REST API の一覧と試行が可能です。')}</Paragraph>
          <Alert type="warning" showIcon message={t('help.admin.apiCsrf', 'REST API の POST/PUT/DELETE は CSRF 保護されています。CLI/curl からは X-Requested-With: XMLHttpRequest ヘッダーを付与してください。')} />
        </>
      ),
    },
  ];

  return (
    <Collapse
      defaultActiveKey={['users']}
      items={sections.map(s => ({
        key: s.key,
        label: <Space>{s.icon}<Text strong>{s.label}</Text></Space>,
        children: s.children,
      }))}
    />
  );
};

export default HelpPage;
