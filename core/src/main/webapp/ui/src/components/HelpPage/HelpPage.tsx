import React from 'react';
import { Tabs, Typography, Collapse, Descriptions, Alert, Table, Space, Steps, Image, Divider, Card } from 'antd';
import {
  FileOutlined, FolderOutlined, SearchOutlined, UploadOutlined,
  EyeOutlined, HistoryOutlined, LockOutlined,
  CloudOutlined, KeyOutlined, UserOutlined, GlobalOutlined,
  TeamOutlined, DatabaseOutlined, SendOutlined,
  SyncOutlined, BarChartOutlined, SwapOutlined, ApiOutlined,
  LoginOutlined, QuestionCircleOutlined, DeleteOutlined,
  CheckCircleOutlined
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../contexts/AuthContext';

const { Title, Paragraph, Text } = Typography;

/** Screenshot helper — images are served from /core/ui/help-images/ */
const HelpImage: React.FC<{ src: string; alt: string }> = ({ src, alt }) => (
  <div style={{ margin: '16px 0', textAlign: 'center' }}>
    <Image
      src={`/core/ui/help-images/${src}`}
      alt={alt}
      style={{ maxWidth: '100%', border: '1px solid #d9d9d9', borderRadius: 8 }}
      preview={{ mask: 'クリックで拡大' }}
    />
    <div style={{ color: '#888', fontSize: 12, marginTop: 4 }}>{alt}</div>
  </div>
);

const HelpPage: React.FC = () => {
  const { t } = useTranslation();
  const { authToken } = useAuth();
  const isAdmin = authToken?.isAdmin === true;

  return (
    <div style={{ maxWidth: 960, margin: '0 auto', padding: '24px 16px' }}>
      <Title level={2}>
        <QuestionCircleOutlined style={{ marginRight: 8 }} />
        {t('help.title', 'NemakiWare ヘルプ')}
      </Title>
      <Paragraph type="secondary">
        画像はクリックすると拡大表示されます。
      </Paragraph>

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

/* ────────────── ユーザーガイド ────────────── */

const UserGuide: React.FC = () => {
  const sections = [
    {
      key: 'login',
      label: <Space><LoginOutlined /><Text strong>ログイン</Text></Space>,
      children: (
        <>
          <HelpImage src="00-login.png" alt="ログイン画面" />
          <Steps
            direction="vertical"
            size="small"
            items={[
              { title: 'NemakiWare にアクセス', description: 'ブラウザで http://<サーバー>:8080/core/ui/ を開きます' },
              { title: 'ユーザー名を入力', description: '管理者から通知されたユーザー名を入力します' },
              { title: 'パスワードを入力', description: 'パスワードを入力して「ログイン」をクリックします' },
            ]}
          />
          <Divider />
          <Text strong>認証方式:</Text>
          <Descriptions bordered column={1} size="small" style={{ marginTop: 8 }}>
            <Descriptions.Item label="パスワード">標準のユーザー名/パスワードによるログイン</Descriptions.Item>
            <Descriptions.Item label="パスキー">Touch ID / Face ID / セキュリティキー（HTTPS 環境のみ）</Descriptions.Item>
            <Descriptions.Item label="OIDC">Google / Microsoft 等のクラウド認証</Descriptions.Item>
            <Descriptions.Item label="SAML">エンタープライズ SSO 認証</Descriptions.Item>
          </Descriptions>
        </>
      ),
    },
    {
      key: 'documents',
      label: <Space><FolderOutlined /><Text strong>ドキュメント一覧</Text></Space>,
      children: (
        <>
          <Paragraph>ログイン後に表示されるメイン画面です。</Paragraph>
          <HelpImage src="01-document-list.png" alt="ドキュメント一覧画面" />
          <Card size="small" title="画面の構成">
            <Descriptions bordered column={1} size="small">
              <Descriptions.Item label="① 左ペイン（フォルダツリー）">フォルダをクリックして選択します。ダブルクリックでカレントフォルダに設定。</Descriptions.Item>
              <Descriptions.Item label="② メインエリア">選択したフォルダ内のファイルとサブフォルダの一覧。名前をクリックで詳細表示/フォルダ移動。</Descriptions.Item>
              <Descriptions.Item label="③ ツールバー">検索、ファイルアップロード、フォルダ作成、インポート/エクスポート</Descriptions.Item>
              <Descriptions.Item label="④ ヘッダー">リポジトリ名、言語切り替え、ユーザーメニュー</Descriptions.Item>
            </Descriptions>
          </Card>
        </>
      ),
    },
    {
      key: 'folder',
      label: <Space><FolderOutlined /><Text strong>フォルダ操作</Text></Space>,
      children: (
        <>
          <Title level={5}>フォルダの作成</Title>
          <Steps
            direction="vertical"
            size="small"
            items={[
              { title: '「＋ フォルダ作成」ボタンをクリック', description: 'ツールバー右側にあります' },
              { title: 'フォルダ名を入力', description: '日本語・英語・記号が使えます' },
              { title: 'タイプを選択（任意）', description: 'カスタムフォルダタイプがある場合は選択' },
              { title: '「作成」をクリック', description: '一覧に新しいフォルダが追加されます' },
            ]}
          />
          <Divider />
          <Title level={5}>フォルダの削除</Title>
          <Steps
            direction="vertical"
            size="small"
            items={[
              { title: '削除したいフォルダの行をチェック' },
              { title: '「削除」ボタンをクリック' },
              { title: '確認ダイアログで「OK」', description: '中にファイルがある場合はカスケード削除の確認が表示されます' },
            ]}
          />
        </>
      ),
    },
    {
      key: 'upload',
      label: <Space><UploadOutlined /><Text strong>ファイルアップロード</Text></Space>,
      children: (
        <>
          <HelpImage src="03-upload-dialog.png" alt="アップロードダイアログ" />
          <Steps
            direction="vertical"
            size="small"
            items={[
              { title: '「ファイルアップロード」ボタンをクリック', description: 'ツールバーの青いボタン' },
              { title: 'ファイルをドラッグ＆ドロップ', description: 'または「クリックしてファイルを選択」で選択' },
              { title: 'ドキュメントタイプを選択（任意）', description: 'カスタムタイプがある場合、プロパティ入力欄が表示されます' },
              { title: '「アップロード」をクリック', description: '完了後、一覧に新しいファイルが追加されます' },
            ]}
          />
          <Divider />
          <Text strong>対応ファイル形式:</Text>
          <Paragraph>PDF, Word (.docx), Excel (.xlsx), PowerPoint (.pptx), 画像 (PNG, JPEG, GIF, SVG), テキスト, HTML, XML, JSON, その他任意のファイル</Paragraph>
        </>
      ),
    },
    {
      key: 'preview',
      label: <Space><EyeOutlined /><Text strong>プレビュー</Text></Space>,
      children: (
        <>
          <Paragraph>ドキュメントをクリックして詳細画面を開き、「プレビュー」タブをクリックします。</Paragraph>
          <HelpImage src="04-document-detail.png" alt="ドキュメント詳細画面（プロパティタブ）" />
          <Table
            size="small" pagination={false}
            dataSource={[
              { key: '1', format: 'PDF', method: 'ブラウザ内 PDF ビューア（ページ送り・ズーム対応）' },
              { key: '2', format: 'Word / Excel / PPT', method: 'サーバーサイドで PDF に変換してプレビュー' },
              { key: '3', format: '画像 (PNG, JPEG, GIF)', method: 'インラインプレビュー（拡大・回転対応）' },
              { key: '4', format: 'テキスト / JSON / XML', method: 'シンタックスハイライト表示' },
              { key: '5', format: '動画 (MP4, WebM)', method: 'ストリーミングプレイヤー' },
            ]}
            columns={[
              { title: '形式', dataIndex: 'format', width: 200 },
              { title: 'プレビュー方法', dataIndex: 'method' },
            ]}
          />
        </>
      ),
    },
    {
      key: 'versioning',
      label: <Space><HistoryOutlined /><Text strong>バージョン管理</Text></Space>,
      children: (
        <>
          <Title level={5}>新しいバージョンの作成</Title>
          <Steps
            direction="vertical"
            size="small"
            items={[
              { title: 'ドキュメントの詳細画面を開く' },
              { title: '「チェックアウト」ボタンをクリック', description: 'ドキュメントがロックされ、他のユーザーは編集できなくなります' },
              { title: 'ファイルを編集' },
              { title: '「チェックイン」をクリック', description: '更新ファイルをアップロードし、メジャー/マイナーバージョンを選択' },
            ]}
          />
          <Divider />
          <Title level={5}>過去バージョンの閲覧</Title>
          <HelpImage src="06-version-history.png" alt="バージョン履歴タブ" />
          <Paragraph>詳細画面の「バージョン履歴」タブから過去のバージョンを確認・ダウンロードできます。</Paragraph>
        </>
      ),
    },
    {
      key: 'search',
      label: <Space><SearchOutlined /><Text strong>検索</Text></Space>,
      children: (
        <>
          <HelpImage src="07-search.png" alt="検索画面" />
          <Steps
            direction="vertical"
            size="small"
            items={[
              { title: '検索ボックスにキーワードを入力', description: '日本語・英語どちらでも検索できます' },
              { title: 'Enter キーまたは「検索」ボタンをクリック' },
              { title: '結果一覧からドキュメントをクリック', description: '詳細画面に移動します' },
            ]}
          />
          <Alert type="info" showIcon style={{ marginTop: 12 }}
            message="検索のヒント"
            description="ファイル名だけでなく PDF・Word・Excel の本文テキストも検索対象です。RAG（セマンティック検索）が有効な場合、意味的に関連性の高いドキュメントも表示されます。"
          />
        </>
      ),
    },
    {
      key: 'acl',
      label: <Space><LockOutlined /><Text strong>権限（ACL）</Text></Space>,
      children: (
        <>
          <Paragraph>ドキュメントやフォルダの詳細画面で「権限管理」ボタンをクリックすると、権限管理画面が開きます。</Paragraph>
          <HelpImage src="16-permissions.png" alt="権限管理画面（ACL一覧・継承/直接の区別）" />
          <Title level={5}>権限レベル</Title>
          <Descriptions bordered column={1} size="small">
            <Descriptions.Item label="cmis:read">閲覧のみ（ダウンロード・プロパティ参照）</Descriptions.Item>
            <Descriptions.Item label="cmis:write">編集（アップロード・プロパティ変更・バージョン作成）</Descriptions.Item>
            <Descriptions.Item label="cmis:all">全操作（権限の変更・削除を含むすべての操作が可能）</Descriptions.Item>
          </Descriptions>
          <Alert type="info" showIcon style={{ marginTop: 12 }}
            message="cmis:all と admin ユーザーの違い"
            description="cmis:all はドキュメント/フォルダ単位のアクセス権限です。あるフォルダに cmis:all を持つユーザーは、そのフォルダの権限変更が可能です。一方、admin ユーザーはシステム管理者で、ユーザー/グループの管理やシステム設定の変更ができます。両者は独立した概念です。"
          />
          <Divider />
          <Title level={5}>権限の変更</Title>
          <Steps
            direction="vertical"
            size="small"
            items={[
              { title: '対象のドキュメント/フォルダの詳細画面を開く' },
              { title: '「権限」タブをクリック' },
              { title: 'ユーザーまたはグループを追加', description: '権限レベル（cmis:read / cmis:write / cmis:all）を選択します' },
              { title: '「保存」をクリック' },
            ]}
          />
          <Alert type="info" showIcon style={{ marginTop: 8 }}
            message="権限の変更には、対象に対して cmis:all 権限を持っている必要があります"
          />
          <Divider />
          <Title level={5}>権限の継承</Title>
          <Paragraph>デフォルトでは親フォルダの権限を継承します。「継承を解除」をクリックすると、独自の権限設定に切り替わります。解除時に親の権限エントリが直接権限としてコピーされるので、そこから個別に編集できます。</Paragraph>
        </>
      ),
    },
    {
      key: 'cloud',
      label: <Space><CloudOutlined /><Text strong>クラウドドライブ連携</Text></Space>,
      children: (
        <>
          <Title level={5}>Google Drive / OneDrive からのインポート</Title>
          <Steps
            direction="vertical"
            size="small"
            items={[
              { title: '「インポート」ボタンをクリック', description: 'Google Drive または OneDrive を選択' },
              { title: 'クラウドにログイン', description: '初回のみ認証画面が表示されます' },
              { title: 'ファイルを選択' },
              { title: '「インポート」をクリック' },
            ]}
          />
          <Alert type="info" showIcon style={{ marginTop: 12 }}
            message="重複検出"
            description="同じファイルを再度インポートすると「インポートがスキップされました」通知が表示され、「既存ドキュメントを開く」ボタンで元のドキュメントに直接アクセスできます。"
          />
        </>
      ),
    },
    {
      key: 'passkey',
      label: <Space><KeyOutlined /><Text strong>パスキー認証</Text></Space>,
      children: (
        <>
          <HelpImage src="17-account-settings.png" alt="アカウント設定画面（パスキータブ）" />
          <Title level={5}>パスキーの登録</Title>
          <Steps
            direction="vertical"
            size="small"
            items={[
              { title: 'メニューの「アカウント設定」を開く' },
              { title: '「パスキーを追加」をクリック' },
              { title: 'ブラウザの認証プロンプトに従う', description: 'Touch ID / Face ID / セキュリティキーで認証' },
              { status: 'finish' as const, title: '完了', description: '次回から「パスキーでログイン」が使えます', icon: <CheckCircleOutlined /> },
            ]}
          />
          <Alert type="info" showIcon style={{ marginTop: 12 }}
            message="パスキーは HTTPS 環境（または localhost）でのみ利用可能です"
          />
        </>
      ),
    },
    {
      key: 'language',
      label: <Space><GlobalOutlined /><Text strong>言語切り替え</Text></Space>,
      children: (
        <Paragraph>画面右上の言語切り替えセレクターで「日本語」と「English」を切り替えできます。設定はブラウザに保存され、次回アクセス時も維持されます。</Paragraph>
      ),
    },
  ];

  return (
    <Collapse
      defaultActiveKey={['login', 'documents', 'upload']}
      items={sections}
    />
  );
};

/* ────────────── 管理者ガイド ────────────── */

const AdminGuide: React.FC = () => {
  const sections = [
    {
      key: 'users',
      label: <Space><UserOutlined /><Text strong>ユーザー管理</Text></Space>,
      children: (
        <>
          <HelpImage src="08-user-management.png" alt="ユーザー管理画面" />
          <Title level={5}>ユーザーの作成</Title>
          <Steps
            direction="vertical"
            size="small"
            items={[
              { title: '「管理」→「ユーザー管理」を開く' },
              { title: '「新規ユーザー」をクリック' },
              { title: 'ユーザー ID、表示名、パスワードを入力', description: 'パスワードは BCrypt でハッシュ化されて安全に保存されます' },
              { title: '管理者権限を設定（任意）' },
              { title: '「作成」をクリック' },
            ]}
          />
          <Alert type="info" showIcon style={{ marginTop: 12 }}
            message="管理者はパスワードリセットが可能"
            description="ユーザー一覧から対象ユーザーの「パスワードリセット」で新しいパスワードを設定できます（旧パスワード不要）。"
          />
        </>
      ),
    },
    {
      key: 'groups',
      label: <Space><TeamOutlined /><Text strong>グループ管理</Text></Space>,
      children: (
        <>
          <HelpImage src="09-group-management.png" alt="グループ管理画面" />
          <Paragraph>グループの作成、メンバーの追加/削除、親子関係の設定が可能です。子グループのメンバーは親グループの権限を自動継承します。</Paragraph>
        </>
      ),
    },
    {
      key: 'types',
      label: <Space><FileOutlined /><Text strong>タイプ管理</Text></Space>,
      children: (
        <>
          <HelpImage src="10-type-management.png" alt="タイプ管理画面" />
          <Paragraph>CMIS オブジェクトタイプの定義を管理します。カスタムタイプを作成してドキュメントに独自のプロパティを追加できます。</Paragraph>
          <Title level={5}>カスタムタイプの作成</Title>
          <Steps
            direction="vertical"
            size="small"
            items={[
              { title: '「新規タイプ」をクリック' },
              { title: 'タイプ ID と表示名を設定', description: '例: demo:policy, 社内規程' },
              { title: '親タイプを選択', description: 'cmis:document, cmis:folder 等' },
              { title: 'プロパティを追加', description: '文字列/数値/日付/真偽値から選択。必須/検索可能を設定' },
              { title: '「作成」をクリック' },
            ]}
          />
        </>
      ),
    },
    {
      key: 'archive',
      label: <Space><DeleteOutlined /><Text strong>アーカイブ管理</Text></Space>,
      children: (
        <>
          <HelpImage src="11-archive.png" alt="アーカイブ管理画面" />
          <Paragraph>削除されたドキュメントを復元したり、完全に削除したりできます。</Paragraph>
          <Descriptions bordered column={1} size="small">
            <Descriptions.Item label="復元">元のフォルダにドキュメントを復元します</Descriptions.Item>
            <Descriptions.Item label="完全削除">データベースから物理削除。復元できなくなります</Descriptions.Item>
            <Descriptions.Item label="一括操作">チェックボックスで複数選択し、一括復元/一括削除が可能</Descriptions.Item>
          </Descriptions>
        </>
      ),
    },
    {
      key: 'solr',
      label: <Space><DatabaseOutlined /><Text strong>検索エンジン管理</Text></Space>,
      children: (
        <>
          <HelpImage src="12-solr-management.png" alt="Solr 管理画面" />
          <Steps
            direction="vertical"
            size="small"
            items={[
              { title: 'フルインデックス再構築', description: '全ドキュメントの検索インデックスを再作成します（数分〜数十分）' },
              { title: 'RAG ベクトルインデックス再構築', description: 'セマンティック検索用の embedding を全ドキュメントで再計算します' },
            ]}
          />
        </>
      ),
    },
    {
      key: 'ingest',
      label: <Space><SwapOutlined /><Text strong>外部インジェスト</Text></Space>,
      children: (
        <>
          <HelpImage src="13-integration-settings.png" alt="統合設定画面" />
          <Paragraph>外部システムからドキュメントを自動取り込みます。コネクタの設定、インポートプロファイル、スケジューラ管理が可能です。</Paragraph>
          <Table
            size="small" pagination={false}
            dataSource={[
              { key: '1', adapter: 'IMAP', target: 'メールサーバー' },
              { key: '2', adapter: 'Gmail', target: 'Gmail API' },
              { key: '3', adapter: 'M365 Mail', target: 'Microsoft 365 メール' },
              { key: '4', adapter: 'Slack', target: 'チャンネルメッセージ' },
              { key: '5', adapter: 'Teams', target: 'チャンネルメッセージ' },
              { key: '6', adapter: 'Mattermost', target: 'チャンネルメッセージ' },
              { key: '7', adapter: 'Chatwork', target: 'ルームメッセージ' },
              { key: '8', adapter: 'Notion', target: 'ページ・データベース' },
              { key: '9', adapter: 'Salesforce', target: 'レコード' },
              { key: '10', adapter: 'Box', target: 'ファイル' },
              { key: '11', adapter: 'Dropbox', target: 'ファイル' },
            ]}
            columns={[
              { title: 'アダプタ', dataIndex: 'adapter', width: 120 },
              { title: '取り込み対象', dataIndex: 'target' },
            ]}
          />
          <Alert type="info" showIcon style={{ marginTop: 12 }}
            message="DLQ（デッドレターキュー）"
            description="取り込みに失敗したアイテムは DLQ に保存されます。「ジョブ履歴」タブで確認し、原因を修正してから「リトライ」で再取り込みできます。"
          />
        </>
      ),
    },
    {
      key: 'audit',
      label: <Space><BarChartOutlined /><Text strong>監査ダッシュボード</Text></Space>,
      children: (
        <>
          <HelpImage src="14-audit-dashboard.png" alt="監査ダッシュボード" />
          <Paragraph>操作数のグラフ（日別/週別）、操作種別の内訳、ユーザー別の操作数を確認できます。</Paragraph>
        </>
      ),
    },
    {
      key: 'webhook',
      label: <Space><SendOutlined /><Text strong>Webhook 管理</Text></Space>,
      children: (
        <>
        <HelpImage src="20-webhook.png" alt="Webhook 管理画面" />
        <Paragraph>ドキュメントの作成・更新・削除時に外部 URL に HTTP 通知を送信します。URL、イベント種別、対象フォルダを設定して「保存」します。</Paragraph>
        </>
      ),
    },
    {
      key: 'sync',
      label: <Space><SyncOutlined /><Text strong>クラウドディレクトリ同期</Text></Space>,
      children: (
        <Paragraph>Google Workspace / Microsoft Entra ID のユーザー・グループを NemakiWare に同期します。手動実行と定期自動同期に対応しています。</Paragraph>
      ),
    },
    {
      key: 'importexport',
      label: <Space><SwapOutlined /><Text strong>インポート/エクスポート</Text></Space>,
      children: (
        <>
        <HelpImage src="22-import-export.png" alt="インポート/エクスポート画面" />
        <Paragraph>ドキュメントの一括インポート/エクスポート。エクスポートは ZIP 形式（メタデータ + コンテンツ）。インポート時に同名ファイルの上書き設定が可能です。</Paragraph>
        </>
      ),
    },
    {
      key: 'api',
      label: <Space><ApiOutlined /><Text strong>API ドキュメント</Text></Space>,
      children: (
        <>
          <Paragraph>「管理」→「API ドキュメント」から Swagger UI で REST API の一覧と試行が可能です。</Paragraph>
          <Alert type="warning" showIcon
            message="CSRF 保護"
            description="REST API の POST/PUT/DELETE は CSRF 保護されています。CLI や curl からアクセスする場合は X-Requested-With: XMLHttpRequest ヘッダーを付与してください。"
          />
        </>
      ),
    },
  ];

  return (
    <Collapse
      defaultActiveKey={['users']}
      items={sections}
    />
  );
};

export default HelpPage;
