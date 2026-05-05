import React, { useState, useEffect } from 'react';
import { Tabs, Typography, Collapse, Descriptions, Alert, Table, Space, Steps, Image, Divider, Card } from 'antd';
import {
  FileOutlined, FolderOutlined, SearchOutlined, UploadOutlined,
  EyeOutlined, HistoryOutlined, LockOutlined,
  CloudOutlined, KeyOutlined, UserOutlined, GlobalOutlined,
  TeamOutlined, DatabaseOutlined, SendOutlined,
  SyncOutlined, BarChartOutlined, SwapOutlined, ApiOutlined,
  LoginOutlined, QuestionCircleOutlined, DeleteOutlined,
  CheckCircleOutlined, RobotOutlined
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../contexts/AuthContext';
import { fetchAdapterRegistry, AdapterDescriptor } from '../../services/externalIngest';

const { Title, Paragraph, Text } = Typography;

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

/**
 * HelpPage — accessible both before and after login.
 * When used as a public route (pre-login), authToken is null and
 * only the user guide is shown.
 */
const HelpPage: React.FC = () => {
  const { t } = useTranslation();
  // AuthProvider wraps the entire app (App → AuthProvider → AppContent),
  // so useAuth() is always available here — even on the unauthenticated
  // /help path, where authToken is simply null.
  const { authToken } = useAuth();
  const isAdmin = authToken?.isAdmin === true;

  return (
    <div style={{ maxWidth: 960, margin: '0 auto', padding: '24px 16px' }}>
      <Title level={2}>
        <QuestionCircleOutlined style={{ marginRight: 8 }} />
        {t('help.title', 'NemakiWare ヘルプ')}
      </Title>
      <Paragraph type="secondary">
        {t('help.imageHint', '画像はクリックすると拡大表示されます。')}
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
  const { t } = useTranslation();

  const sections = [
    {
      key: 'login',
      label: <Space><LoginOutlined /><Text strong>{t('help.user.loginTitle', 'ログイン')}</Text></Space>,
      children: (
        <>
          <HelpImage src="00-login.png" alt={t('help.user.loginScreenAlt', 'ログイン画面')} />
          <Steps
            direction="vertical"
            size="small"
            items={[
              { title: t('help.user.loginStep1', 'NemakiWare にアクセス'), description: t('help.user.loginStep1Desc', 'ブラウザで http://<サーバー>:8080/core/ui/ を開きます') },
              { title: t('help.user.loginStep2', 'ユーザー名を入力'), description: t('help.user.loginStep2Desc', '管理者から通知されたユーザー名を入力します') },
              { title: t('help.user.loginStep3', 'パスワードを入力'), description: t('help.user.loginStep3Desc', 'パスワードを入力して「ログイン」をクリックします') },
            ]}
          />
          <Divider />
          <Text strong>{t('help.user.authMethods', '認証方式:')}</Text>
          <Alert type="info" showIcon style={{ marginTop: 8, marginBottom: 8 }}
            message={t('help.user.authConditional', 'パスキー・OIDC・SAML ボタンはサーバーの認証設定が有効な場合のみ表示されます。表示されない場合は管理者にお問い合わせください。')}
          />
          <Descriptions bordered column={1} size="small" style={{ marginTop: 8 }}>
            <Descriptions.Item label={t('help.user.authPw', 'パスワード')}>{t('help.user.authPwDesc', '標準のユーザー名/パスワードによるログイン（常に利用可能）')}</Descriptions.Item>
            <Descriptions.Item label={t('help.user.authPasskey', 'パスキー')}>{t('help.user.authPasskeyDesc', 'Touch ID / Face ID / セキュリティキー（HTTPS 環境 + サーバー設定が必要）')}</Descriptions.Item>
            <Descriptions.Item label="OIDC">{t('help.user.authOIDCDesc', 'Google / Microsoft 等のクラウド認証（サーバー設定が必要）')}</Descriptions.Item>
            <Descriptions.Item label="SAML">{t('help.user.authSAMLDesc', 'エンタープライズ SSO 認証（サーバー設定が必要）')}</Descriptions.Item>
          </Descriptions>
        </>
      ),
    },
    {
      key: 'documents',
      label: <Space><FolderOutlined /><Text strong>{t('help.user.documentsTitle', 'ドキュメント一覧')}</Text></Space>,
      children: (
        <>
          <Paragraph>{t('help.user.documentsIntro', 'ログイン後に表示されるメイン画面です。')}</Paragraph>
          <HelpImage src="01-document-list.png" alt={t('help.user.documentsAlt', 'ドキュメント一覧画面')} />
          <Card size="small" title={t('help.user.screenLayout', '画面の構成')}>
            <Descriptions bordered column={1} size="small">
              <Descriptions.Item label={t('help.user.leftPane', '① 左ペイン（フォルダツリー）')}>{t('help.user.leftPaneDesc', 'フォルダをクリックして選択します。選択中のフォルダをもう一度クリックするとカレントフォルダに設定されます。')}</Descriptions.Item>
              <Descriptions.Item label={t('help.user.mainArea', '② メインエリア')}>{t('help.user.mainAreaDesc', '選択したフォルダ内のファイルとサブフォルダの一覧。名前をクリックで詳細表示/フォルダ移動。')}</Descriptions.Item>
              <Descriptions.Item label={t('help.user.toolbar', '③ ツールバー')}>{t('help.user.toolbarDesc', '検索、ファイルアップロード、フォルダ作成、インポート/エクスポート')}</Descriptions.Item>
              <Descriptions.Item label={t('help.user.header', '④ ヘッダー')}>{t('help.user.headerDesc', 'リポジトリ名、言語切り替え、ユーザーメニュー')}</Descriptions.Item>
            </Descriptions>
          </Card>
        </>
      ),
    },
    {
      key: 'folder',
      label: <Space><FolderOutlined /><Text strong>{t('help.user.folderTitle', 'フォルダ操作')}</Text></Space>,
      children: (
        <>
          <Title level={5}>{t('help.user.folderCreate', 'フォルダの作成')}</Title>
          <Steps direction="vertical" size="small" items={[
            { title: t('help.user.folderStep1', '「フォルダ作成」ボタンをクリック'), description: t('help.user.folderStep1Desc', 'ツールバー右側にあります') },
            { title: t('help.user.folderStep2', 'フォルダ名を入力') },
            { title: t('help.user.folderStep3', 'タイプを選択（任意）'), description: t('help.user.folderStep3Desc', 'カスタムフォルダタイプがある場合は選択') },
            { title: t('help.user.folderStep4', '「作成」をクリック') },
          ]} />
          <Divider />
          <Title level={5}>{t('help.user.folderDelete', 'フォルダの削除')}</Title>
          <Steps direction="vertical" size="small" items={[
            { title: t('help.user.folderDelStep1', '削除したいフォルダの行をチェック') },
            { title: t('help.user.folderDelStep2', '「削除する」ボタンをクリック') },
            { title: t('help.user.folderDelStep3', '確認ダイアログで「OK」'), description: t('help.user.folderDelStep3Desc', '中にファイルがある場合はカスケード削除の確認が表示されます') },
          ]} />
        </>
      ),
    },
    {
      key: 'upload',
      label: <Space><UploadOutlined /><Text strong>{t('help.user.uploadTitle', 'ファイルアップロード')}</Text></Space>,
      children: (
        <>
          <HelpImage src="03-upload-dialog.png" alt={t('help.user.uploadAlt', 'アップロードダイアログ')} />
          <Steps direction="vertical" size="small" items={[
            { title: t('help.user.uploadStep1', '「ファイルアップロード」ボタンをクリック'), description: t('help.user.uploadStep1Desc', 'ツールバーの青いボタン') },
            { title: t('help.user.uploadStep2', 'ファイルをドラッグ＆ドロップ'), description: t('help.user.uploadStep2Desc', 'または「クリックしてファイルを選択」で選択') },
            { title: t('help.user.uploadStep3', 'ドキュメントタイプを選択（任意）'), description: t('help.user.uploadStep3Desc', 'カスタムタイプがある場合、プロパティ入力欄が表示されます') },
            { title: t('help.user.uploadStep4', '「アップロード」をクリック') },
          ]} />
          <Divider />
          <Text strong>{t('help.user.uploadFormats', '対応ファイル形式:')}</Text>
          <Paragraph>PDF, Word (.docx), Excel (.xlsx), PowerPoint (.pptx), {t('help.user.uploadFormatsMore', '画像, テキスト, HTML, XML, JSON, その他任意のファイル')}</Paragraph>
        </>
      ),
    },
    {
      key: 'detail',
      label: <Space><EyeOutlined /><Text strong>{t('help.user.detailTitle', 'ドキュメント詳細・プレビュー')}</Text></Space>,
      children: (
        <>
          <Title level={5}>{t('help.user.detailOverview', 'ドキュメント詳細画面')}</Title>
          <Paragraph>{t('help.user.detailIntro', 'ドキュメント一覧でファイル名をクリックすると詳細画面が開きます。')}</Paragraph>
          <HelpImage src="04-document-detail.png" alt={t('help.user.detailAlt', 'ドキュメント詳細画面 — プロパティタブ')} />
          <Card size="small" title={t('help.user.detailButtons', '画面上部のボタン')}>
            <Descriptions bordered column={1} size="small">
              <Descriptions.Item label={t('help.user.btnBack', '戻る')}>{t('help.user.btnBackDesc', 'ドキュメント一覧に戻ります')}</Descriptions.Item>
              <Descriptions.Item label={t('help.user.btnDownload', 'ダウンロード')}>{t('help.user.btnDownloadDesc', 'ファイルをダウンロードします')}</Descriptions.Item>
              <Descriptions.Item label={t('help.user.btnCheckout', 'チェックアウト')}>{t('help.user.btnCheckoutDesc', 'ドキュメントをロックして編集を開始します')}</Descriptions.Item>
              <Descriptions.Item label={t('help.user.btnTypeChange', 'タイプを変更')}>{t('help.user.btnTypeChangeDesc', 'ドキュメントのオブジェクトタイプを変更します')}</Descriptions.Item>
              <Descriptions.Item label={t('help.user.btnPerms', '権限管理')}>{t('help.user.btnPermsDesc', '権限管理画面を開きます（別ページに遷移）')}</Descriptions.Item>
            </Descriptions>
          </Card>
          <Divider />
          <Title level={5}>{t('help.user.previewTitle', 'プレビュータブ')}</Title>
          <HelpImage src="05-preview.png" alt={t('help.user.previewAlt', 'プレビュータブ')} />
          <Table size="small" pagination={false}
            dataSource={[
              { key: '1', format: 'PDF', method: t('help.user.prevPdf', 'ブラウザ内 PDF ビューア（ページ送り・ズーム対応）') },
              { key: '2', format: 'Word / Excel / PPT', method: t('help.user.prevOffice', 'サーバーサイドで PDF に変換してプレビュー') },
              { key: '3', format: t('help.user.prevImageFmt', '画像 (PNG, JPEG, GIF)'), method: t('help.user.prevImage', 'インラインプレビュー（拡大・回転対応）') },
              { key: '4', format: t('help.user.prevTextFmt', 'テキスト / JSON / XML'), method: t('help.user.prevText', 'シンタックスハイライト表示') },
              { key: '5', format: t('help.user.prevVideoFmt', '動画 (MP4, WebM)'), method: t('help.user.prevVideo', 'ストリーミングプレイヤー') },
            ]}
            columns={[
              { title: t('help.user.format', '形式'), dataIndex: 'format', width: 200 },
              { title: t('help.user.previewMethod', 'プレビュー方法'), dataIndex: 'method' },
            ]}
          />
          <Divider />
          <Title level={5}>{t('help.user.secondaryTitle', 'セカンダリタイプ（アスペクト）タブ')}</Title>
          <Paragraph>{t('help.user.secondaryDesc', 'ドキュメントに追加のメタデータを付与するセカンダリタイプの管理ができます。セレクターから追加したいタイプを選択し「追加」をクリックします。')}</Paragraph>
          <HelpImage src="18-secondary-type.png" alt={t('help.user.secondaryAlt', 'セカンダリタイプタブ')} />
          <Divider />
          <Title level={5}>{t('help.user.relationshipTitle', 'リレーションシップタブ')}</Title>
          <Paragraph>{t('help.user.relationshipDesc', 'ドキュメント間の関連付け（リレーションシップ）を表示・管理します。外部インジェストで取り込まれた添付ファイルや関連レコードの関係もここに表示されます。')}</Paragraph>
          <HelpImage src="19-relationship.png" alt={t('help.user.relationshipAlt', 'リレーションシップタブ')} />
        </>
      ),
    },
    {
      key: 'versioning',
      label: <Space><HistoryOutlined /><Text strong>{t('help.user.versionTitle', 'バージョン管理')}</Text></Space>,
      children: (
        <>
          <Title level={5}>{t('help.user.versionCreate', '新しいバージョンの作成')}</Title>
          <Steps direction="vertical" size="small" items={[
            { title: t('help.user.verStep1', 'ドキュメントの詳細画面を開く') },
            { title: t('help.user.verStep2', '「チェックアウト」ボタンをクリック'), description: t('help.user.verStep2Desc', 'ドキュメントがロックされ、他のユーザーは編集できなくなります') },
            { title: t('help.user.verStep3', 'ファイルを編集') },
            { title: t('help.user.verStep4', '「チェックイン」をクリック'), description: t('help.user.verStep4Desc', '更新ファイルをアップロードし、メジャー/マイナーバージョンを選択') },
          ]} />
          <Divider />
          <Title level={5}>{t('help.user.versionHistory', '過去バージョンの閲覧')}</Title>
          <HelpImage src="06-version-history.png" alt={t('help.user.versionAlt', 'バージョン履歴タブ')} />
          <Paragraph>{t('help.user.versionHistoryDesc', '詳細画面の「バージョン履歴」タブから過去のバージョンを確認・ダウンロードできます。')}</Paragraph>
        </>
      ),
    },
    {
      key: 'search',
      label: <Space><SearchOutlined /><Text strong>{t('help.user.searchTitle', '検索')}</Text></Space>,
      children: (
        <>
          <HelpImage src="07-search.png" alt={t('help.user.searchAlt', '検索画面')} />
          <Steps direction="vertical" size="small" items={[
            { title: t('help.user.searchStep1', '検索ボックスにキーワードを入力'), description: t('help.user.searchStep1Desc', '日本語・英語どちらでも検索できます') },
            { title: t('help.user.searchStep2', 'Enter キーまたは「検索」ボタンをクリック') },
            { title: t('help.user.searchStep3', '結果一覧からドキュメントをクリック') },
          ]} />
          <Alert type="info" showIcon style={{ marginTop: 12 }}
            message={t('help.user.searchTip', '検索のヒント')}
            description={t('help.user.searchTipDesc', 'ファイル名だけでなく PDF・Word・Excel の本文テキストも検索対象です。RAG（セマンティック検索）が有効な場合、意味的に関連性の高いドキュメントも表示されます。')}
          />
        </>
      ),
    },
    {
      key: 'acl',
      label: <Space><LockOutlined /><Text strong>{t('help.user.aclTitle', '権限（ACL）')}</Text></Space>,
      children: (
        <>
          <Paragraph>{t('help.user.aclIntro', 'ドキュメント詳細画面の「権限管理」ボタンをクリックすると、専用の権限管理画面に移動します。')}</Paragraph>
          <HelpImage src="16-permissions.png" alt={t('help.user.aclAlt', '権限管理画面（ACL一覧・継承/直接の区別）')} />
          <Title level={5}>{t('help.user.aclLevels', '権限レベル')}</Title>
          <Descriptions bordered column={1} size="small">
            <Descriptions.Item label="cmis:read">{t('help.user.aclRead', '閲覧のみ（ダウンロード・プロパティ参照）')}</Descriptions.Item>
            <Descriptions.Item label="cmis:write">{t('help.user.aclWrite', '編集（アップロード・プロパティ変更・バージョン作成）')}</Descriptions.Item>
            <Descriptions.Item label="cmis:all">{t('help.user.aclAll', '全操作（権限の変更・削除を含むすべての操作が可能）')}</Descriptions.Item>
          </Descriptions>
          <Alert type="info" showIcon style={{ marginTop: 12 }}
            message={t('help.user.aclVsAdmin', 'cmis:all と admin ユーザーの違い')}
            description={t('help.user.aclVsAdminDesc', 'cmis:all はドキュメント/フォルダ単位のアクセス権限です。あるフォルダに cmis:all を持つユーザーは、そのフォルダの権限変更が可能です。一方、admin ユーザーはシステム管理者で、ユーザー/グループの管理やシステム設定の変更ができます。両者は独立した概念です。')}
          />
          <Divider />
          <Title level={5}>{t('help.user.aclChange', '権限の変更')}</Title>
          <Steps direction="vertical" size="small" items={[
            { title: t('help.user.aclStep1', 'ドキュメント詳細画面の「権限管理」ボタンをクリック'), description: t('help.user.aclStep1Desc', '権限管理画面に移動します') },
            { title: t('help.user.aclStep2', '「権限を追加」をクリック') },
            { title: t('help.user.aclStep3', 'ユーザーまたはグループを選択し、権限レベルを設定') },
          ]} />
          <Alert type="info" showIcon style={{ marginTop: 8 }}
            message={t('help.user.aclReq', '権限の変更には、対象に対して cmis:all 権限を持っている必要があります')}
          />
          <Divider />
          <Title level={5}>{t('help.user.aclInherit', '権限の継承')}</Title>
          <Paragraph>{t('help.user.aclInheritDesc', 'デフォルトでは親フォルダの権限を継承します。「継承を切る」ボタンをクリックすると独自の権限設定に切り替わります。解除時に親の権限エントリが直接権限としてコピーされるので、そこから個別に編集できます。')}</Paragraph>
        </>
      ),
    },
    {
      key: 'cloud',
      label: <Space><CloudOutlined /><Text strong>{t('help.user.cloudTitle', 'クラウドドライブ連携')}</Text></Space>,
      children: (
        <>
          <Title level={5}>{t('help.user.cloudImport', 'Google Drive / OneDrive からのインポート')}</Title>
          <Alert type="info" showIcon style={{ marginBottom: 12 }}
            message={t('help.user.cloudPrereq', 'クラウドインポートの前提条件')}
            description={t('help.user.cloudPrereqDesc', 'Google Drive ボタンは Google 認証でログインした場合、OneDrive ボタンは Microsoft 認証でログインした場合にのみ表示されます。パスワードログインの場合はこれらのボタンは表示されません。また、サーバー側でクラウド連携が有効に設定されている必要があります。')}
          />
          <Steps direction="vertical" size="small" items={[
            { title: t('help.user.cloudStep1', '「Google Driveからインポート」または「OneDriveからインポート」ボタンをクリック'), description: t('help.user.cloudStep1Desc', 'ツールバーにプロバイダ別のボタンが表示されます') },
            { title: t('help.user.cloudStep2', 'クラウドストレージのファイル一覧が表示されます') },
            { title: t('help.user.cloudStep3', 'インポートするファイルを選択') },
            { title: t('help.user.cloudStep4', '「インポート」をクリック') },
          ]} />
          <Alert type="info" showIcon style={{ marginTop: 12 }}
            message={t('help.user.cloudDupe', '重複検出')}
            description={t('help.user.cloudDupeDesc', '同じファイルを再度インポートすると「インポートがスキップされました」通知が表示され、「既存ドキュメントを開く」ボタンで元のドキュメントに直接アクセスできます。')}
          />
        </>
      ),
    },
    {
      key: 'passkey',
      label: <Space><KeyOutlined /><Text strong>{t('help.user.passkeyTitle', 'パスキー認証')}</Text></Space>,
      children: (
        <>
          <HelpImage src="17-account-settings.png" alt={t('help.user.passkeyAlt', 'アカウント設定画面')} />
          <Title level={5}>{t('help.user.passkeyRegister', 'パスキーの登録')}</Title>
          <Steps direction="vertical" size="small" items={[
            { title: t('help.user.pkStep1', 'メニューの「アカウント設定」を開く') },
            { title: t('help.user.pkStep2', '「パスキー」タブを選択') },
            { title: t('help.user.pkStep3', '「パスキーを登録」をクリック') },
            { title: t('help.user.pkStep4', 'ブラウザの認証プロンプトに従う'), description: t('help.user.pkStep4Desc', 'Touch ID / Face ID / セキュリティキーで認証') },
            { status: 'finish' as const, title: t('help.user.pkDone', '完了'), icon: <CheckCircleOutlined /> },
          ]} />
          <Alert type="info" showIcon style={{ marginTop: 12 }}
            message={t('help.user.passkeyReq', 'パスキーは HTTPS 環境（または localhost）でのみ利用可能です。サーバー設定で WebAuthn が有効である必要があります。')}
          />
        </>
      ),
    },
    {
      key: 'language',
      label: <Space><GlobalOutlined /><Text strong>{t('help.user.langTitle', '言語切り替え')}</Text></Space>,
      children: (
        <Paragraph>{t('help.user.langDesc', '画面右上の言語切り替えセレクターで「日本語」と「English」を切り替えできます。設定はブラウザに保存され、次回アクセス時も維持されます。')}</Paragraph>
      ),
    },
  ];

  return <Collapse defaultActiveKey={['login', 'documents']} items={sections} />;
};

/* ────────────── 管理者ガイド ────────────── */

const AdminGuide: React.FC = () => {
  const { t } = useTranslation();

  // Adapter registry — single source of truth for adapter tables
  const [adapters, setAdapters] = useState<AdapterDescriptor[]>([]);
  useEffect(() => {
    fetchAdapterRegistry().then(setAdapters).catch(() => {});
  }, []);

  const sections = [
    {
      key: 'users',
      label: <Space><UserOutlined /><Text strong>{t('help.admin.usersTitle', 'ユーザー管理')}</Text></Space>,
      children: (
        <>
          <HelpImage src="08-user-management.png" alt={t('help.admin.usersAlt', 'ユーザー管理画面')} />
          <Title level={5}>{t('help.admin.userCreate', 'ユーザーの作成')}</Title>
          <Steps direction="vertical" size="small" items={[
            { title: t('help.admin.userStep1', '「管理」→「ユーザー管理」を開く') },
            { title: t('help.admin.userStep2', '「作成」ボタンをクリック') },
            { title: t('help.admin.userStep3', 'ユーザー ID、表示名、パスワードを入力'), description: t('help.admin.userStep3Desc', 'パスワードは BCrypt でハッシュ化されて安全に保存されます。姓・名・メールアドレスも任意で設定可能です。') },
            { title: t('help.admin.userStep4', '管理者権限・所属グループを設定（任意）'), description: t('help.admin.userStep4Desc', '管理者スイッチ、所属グループ、許可する認証方式（パスワード/パスキー/OIDC/SAML）を設定できます。') },
            { title: t('help.admin.userStep5', '「作成」をクリック') },
          ]} />
          <Alert type="info" showIcon style={{ marginTop: 12 }}
            message={t('help.admin.userPwReset', '管理者はパスワードリセットが可能')}
            description={t('help.admin.userPwResetDesc', 'ユーザー一覧から対象ユーザーの「パスワードをリセット」で新しいパスワードを設定できます（旧パスワード不要）。')}
          />
        </>
      ),
    },
    {
      key: 'groups',
      label: <Space><TeamOutlined /><Text strong>{t('help.admin.groupsTitle', 'グループ管理')}</Text></Space>,
      children: (
        <>
          <HelpImage src="09-group-management.png" alt={t('help.admin.groupsAlt', 'グループ管理画面')} />
          <Paragraph>{t('help.admin.groupsDesc', 'グループの作成、メンバーの追加/削除、親子関係の設定が可能です。子グループのメンバーは親グループの権限を自動継承します。')}</Paragraph>
        </>
      ),
    },
    {
      key: 'types',
      label: <Space><FileOutlined /><Text strong>{t('help.admin.typesTitle', 'タイプ管理')}</Text></Space>,
      children: (
        <>
          <HelpImage src="10-type-management.png" alt={t('help.admin.typesAlt', 'タイプ管理画面')} />
          <Paragraph>{t('help.admin.typesIntro', 'CMIS オブジェクトタイプの定義を管理します。カスタムタイプを作成してドキュメントに独自のプロパティを追加できます。')}</Paragraph>
          <Title level={5}>{t('help.admin.typeCreate', 'カスタムタイプの作成')}</Title>
          <Steps direction="vertical" size="small" items={[
            { title: t('help.admin.typeStep1', '「新規タイプ」をクリック') },
            { title: t('help.admin.typeStep2', 'タイプ ID と表示名を設定') },
            { title: t('help.admin.typeStep3', '親タイプを選択'), description: t('help.admin.typeStep3Desc', 'cmis:document, cmis:folder, cmis:relationship, cmis:policy, cmis:item, cmis:secondary から選択') },
            { title: t('help.admin.typeStep4', 'プロパティを追加'), description: t('help.admin.typeStep4Desc', '文字列(string)/整数(integer)/小数(decimal)/真偽値(boolean)/日時(datetime) から選択。必須/検索可能を設定') },
            { title: t('help.admin.typeStep5', '「作成」をクリック') },
          ]} />
        </>
      ),
    },
    {
      key: 'archive',
      label: <Space><DeleteOutlined /><Text strong>{t('help.admin.archiveTitle', 'アーカイブ管理')}</Text></Space>,
      children: (
        <>
          <HelpImage src="11-archive.png" alt={t('help.admin.archiveAlt', 'アーカイブ管理画面')} />
          <Descriptions bordered column={1} size="small">
            <Descriptions.Item label={t('help.admin.archRestore', '復元')}>{t('help.admin.archRestoreDesc', '元のフォルダにドキュメントを復元します（行ごとのボタン）')}</Descriptions.Item>
            <Descriptions.Item label={t('help.admin.archDownload', 'ダウンロード')}>{t('help.admin.archDownloadDesc', 'アーカイブされたドキュメントのコンテンツをダウンロードします')}</Descriptions.Item>
            <Descriptions.Item label={t('help.admin.archForce', '強制アーカイブ')}>{t('help.admin.archForceDesc', '保留中（有効期限前）のドキュメントを即座にアーカイブに移行します')}</Descriptions.Item>
            <Descriptions.Item label={t('help.admin.archExtend', '有効期限延長')}>{t('help.admin.archExtendDesc', '保留中のドキュメントの有効期限を延長します')}</Descriptions.Item>
          </Descriptions>
        </>
      ),
    },
    {
      key: 'solr',
      label: <Space><DatabaseOutlined /><Text strong>{t('help.admin.solrTitle', '検索エンジン管理')}</Text></Space>,
      children: (
        <>
          <HelpImage src="12-solr-management.png" alt={t('help.admin.solrAlt', 'Solr 管理画面')} />
          <Paragraph>{t('help.admin.solrIntro', '検索インデックスの状態確認と保守操作を行います。Solr（全文検索）と RAG（ベクトル検索）の2つのセクションがあります。')}</Paragraph>
          <Title level={5}>{t('help.admin.solrFulltext', 'Solr（全文検索）')}</Title>
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label={t('help.admin.solrStep1', 'フルインデックス再構築')}>{t('help.admin.solrStep1Desc', '全ドキュメントの検索インデックスを再作成します（数分〜数十分）')}</Descriptions.Item>
            <Descriptions.Item label={t('help.admin.solrFolderReindex', 'フォルダ単位の再構築')}>{t('help.admin.solrFolderReindexDesc', '指定フォルダ配下のドキュメントのみインデックスを再作成します。再帰オプションで子フォルダも含められます。')}</Descriptions.Item>
            <Descriptions.Item label={t('help.admin.solrClear', 'インデックスクリア')}>{t('help.admin.solrClearDesc', '検索インデックスを全削除します。再構築が必要です。')}</Descriptions.Item>
            <Descriptions.Item label={t('help.admin.solrOptimize', 'インデックス最適化')}>{t('help.admin.solrOptimizeDesc', 'Solr インデックスのセグメント統合を実行し、検索パフォーマンスを改善します。')}</Descriptions.Item>
            <Descriptions.Item label={t('help.admin.solrCancel', 'キャンセル')}>{t('help.admin.solrCancelDesc', '実行中のインデックス再構築を中断します。')}</Descriptions.Item>
          </Descriptions>
          <Title level={5} style={{ marginTop: 16 }}>{t('help.admin.solrRag', 'RAG（ベクトル検索）')}</Title>
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label={t('help.admin.solrStep2', 'RAG ベクトルインデックス再構築')}>{t('help.admin.solrStep2Desc', 'セマンティック検索用の embedding を全ドキュメントで再計算します。TEI サービスが必要で、ドキュメント数に応じて長時間かかる場合があります。')}</Descriptions.Item>
            <Descriptions.Item label={t('help.admin.solrRagClear', 'RAG インデックスクリア')}>{t('help.admin.solrRagClearDesc', 'ベクトルインデックスを全削除します。')}</Descriptions.Item>
            <Descriptions.Item label={t('help.admin.solrRagCancel', 'キャンセル')}>{t('help.admin.solrRagCancelDesc', '実行中の RAG 再構築を中断します。')}</Descriptions.Item>
          </Descriptions>
        </>
      ),
    },
    {
      key: 'ingest',
      label: <Space><SwapOutlined /><Text strong>{t('help.admin.ingestTitle', '連携設定 — 外部インジェスト')}</Text></Space>,
      children: (
        <>
          <HelpImage src="13-integration-settings.png" alt={t('help.admin.ingestAlt', '連携設定画面')} />
          <Paragraph>{t('help.admin.ingestIntro', '外部システムからドキュメントを自動取り込みます。コネクタの設定、インポートプロファイル、スケジューラ管理が可能です。')}</Paragraph>

          {/* --- 概念説明 --- */}
          <Title level={5}>{t('help.admin.ingestConcepts', '基本概念')}</Title>
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label={t('help.admin.ingestConceptConnector', 'コネクタ (Connector)')}>
              {t('help.admin.ingestConceptConnectorDesc', '外部システムへの接続情報を定義します。接続先URL、認証トークン、類型（FILE_SHARE / MESSAGE_CONTEXT / CHAT_CONTEXT 等）を設定します。1つのコネクタは1つの外部システムに対応します。')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestConceptProfile', 'インポートプロファイル (Import Profile)')}>
              {t('help.admin.ingestConceptProfileDesc', 'コネクタに紐づく取り込みルールです。保存先フォルダ、重複検出ポリシー、バージョニング方針、スケジューラの有効/無効を設定します。1つのコネクタに複数のプロファイルを作成できます（例: Slack の複数チャンネル）。')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestConceptArchetype', '類型 (Source Archetype)')}>
              {t('help.admin.ingestConceptArchetypeDesc', '取り込み元データの種類を分類します。FILE_SHARE（ファイル）、MESSAGE_CONTEXT（メール）、COMPOUND_NOTE（ノート）、CHAT_CONTEXT（チャット）、BUSINESS_RECORD（業務レコード）の5種類があり、それぞれ専用のメタデータ（セカンダリタイプ）が自動付与されます。')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestConceptScheduler', 'スケジューラ')}>
              {t('help.admin.ingestConceptSchedulerDesc', '有効なプロファイルをポーリング実行します（デフォルト5分間隔、ingest.scheduler.pollIntervalSeconds で変更可能）。前回の取り込み位置（チェックポイント）を記憶しており、差分のみを取り込みます。cron 式ではなく固定間隔です。')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestConceptCircuitBreaker', 'サーキットブレーカー')}>
              {t('help.admin.ingestConceptCircuitBreakerDesc', 'コネクタが連続して失敗すると自動的にスキップされます（デフォルト5回、ingest.scheduler.circuitBreakerThreshold で変更可能）。次のポーリングサイクルでリトライ（HALF_OPEN）し、成功すればリセットされます。')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestConceptIdempotency', '冪等性 (Idempotency)')}>
              {t('help.admin.ingestConceptIdempotencyDesc', '同一の idempotencyKey を持つリクエストは7日間の TTL で重複排除されます。期限切れのキーは1時間ごとに自動パージされます。')}
            </Descriptions.Item>
          </Descriptions>

          {/* --- 対応アダプタ一覧 --- */}
          {/* --- 対応アダプタ一覧（AdapterRegistry API から動的生成） --- */}
          <Title level={5} style={{ marginTop: 24 }}>{t('help.admin.ingestAdapters', '対応アダプタ一覧')}</Title>
          <Table size="small" pagination={false}
            dataSource={adapters
              .filter(a => a.sourceSystem !== 'google_drive' && a.sourceSystem !== 'onedrive')
              .map(a => ({
                key: a.sourceSystem,
                adapter: a.displayName,
                archetype: a.archetype,
                params: [...a.requiredParams, ...a.optionalParams].filter(k => k !== 'limit').join(', ') || '-',
              }))}
            columns={[
              { title: t('help.admin.adapterCol', 'アダプタ'), dataIndex: 'adapter', width: 120 },
              { title: t('help.admin.ingestArchetypeCol', '類型'), dataIndex: 'archetype', width: 170 },
              { title: t('help.admin.ingestParamsCol', '主要パラメータ'), dataIndex: 'params' },
            ]}
          />

          {/* --- コネクタ登録手順 --- */}
          <Divider />
          <Title level={5}>{t('help.admin.ingestConnectorSetup', 'コネクタの登録手順')}</Title>
          <Steps direction="vertical" size="small" items={[
            { title: t('help.admin.ingestConnStep1', '「管理」→「連携設定」→「コネクタ」タブを開く') },
            { title: t('help.admin.ingestConnStep2', '「新規コネクタ」をクリック') },
            { title: t('help.admin.ingestConnStep3', 'コネクタID を入力（必須）'), description: t('help.admin.ingestConnStep3Desc', '一意の識別子です（例: "slack-workspace-1"）。作成後は変更できません。表示名（displayName）は任意で設定できます。') },
            { title: t('help.admin.ingestConnStep4', 'ソースシステムを選択（必須）'), description: t('help.admin.ingestConnStep4Desc', 'ドロップダウンから選択します。対応システム: IMAP / Gmail / M365 Mail / Slack / Microsoft Teams / Mattermost / Chatwork / Notion / Salesforce / Box / Dropbox。選択すると類型が自動設定されます。') },
            { title: t('help.admin.ingestConnStep5', '類型を選択（必須）'), description: t('help.admin.ingestConnStep5Desc', 'ドロップダウンから FILE_SHARE / COMPOUND_NOTE / CHAT_CONTEXT / BUSINESS_RECORD / MESSAGE_CONTEXT を選択します。ソースシステムを先に選択すると自動設定されます。新規作成時は FILE_SHARE が初期値です。') },
            { title: t('help.admin.ingestConnStep6', '認証方式・接続先を設定'), description: t('help.admin.ingestConnStep6Desc', '認証方式（authType）を oauth2 / api_key / service_account / none から選択し、エンドポイント（endpoint）にAPIのURLを入力します。Microsoft 系の場合はテナントID（tenantId）も設定します。Webhook を使う場合は webhookSecret を設定します。') },
            { title: t('help.admin.ingestConnStep7', '「作成」をクリック') },
          ]} />
          <Alert type="info" showIcon style={{ marginTop: 8 }}
            message={t('help.admin.ingestConnNote', 'コネクタのセキュリティ')}
            description={t('help.admin.ingestConnNoteDesc', 'Webhook シークレットは保存後の GET レスポンスで [configured] にマスクされます。値を変更する場合は新しい値を入力して保存してください。空欄で保存すると既存の値が維持されます。実際の API トークンはサーバー側の credentialRef（プロパティ参照）で管理され、UI には表示されません。')}
          />

          {/* --- インポートプロファイル設定 --- */}
          <Divider />
          <Title level={5}>{t('help.admin.ingestProfileSetup', 'インポートプロファイルの設定')}</Title>
          <Steps direction="vertical" size="small" items={[
            { title: t('help.admin.ingestProfStep1', '「連携設定」→「インポートプロファイル」タブを開く') },
            { title: t('help.admin.ingestProfStep2', '「新規プロファイル」をクリック') },
            { title: t('help.admin.ingestProfStep3', 'プロファイルID を入力（必須）'), description: t('help.admin.ingestProfStep3Desc', '一意の識別子です（例: "slack-general-ch"）。作成後は変更できません。') },
            { title: t('help.admin.ingestProfStep3b', 'コネクタを紐付け'), description: t('help.admin.ingestProfStep3bDesc', 'allowedConnectorIds に使用するコネクタIDを入力し、defaultConnectorId にスケジューラ実行時のデフォルトコネクタIDを設定します。') },
            { title: t('help.admin.ingestProfStep4', '保存先フォルダを指定'), description: t('help.admin.ingestProfStep4Desc', 'targetFolderId に CMIS フォルダIDを設定します。省略するとルートフォルダに保存されます。') },
            { title: t('help.admin.ingestProfStep5', 'ポリシーを設定'), description: t('help.admin.ingestProfStep5Desc', '重複検出（dedupePolicy）、更新（updatePolicy）、バージョニング（versioningPolicy）の各ポリシーを設定します（後述）') },
            { title: t('help.admin.ingestProfStep6', 'スケジューラパラメータを設定'), description: t('help.admin.ingestProfStep6Desc', 'アダプタ固有のパラメータ（channelId 等）を schedulerParams に JSON 形式で入力します') },
            { title: t('help.admin.ingestProfStep7', 'スケジューラを有効化'), description: t('help.admin.ingestProfStep7Desc', 'schedulerEnabled をオンにすると定期自動取り込みが開始されます（デフォルト5分間隔）。defaultConnectorId の設定が必須です。') },
          ]} />

          {/* --- ポリシー説明 --- */}
          <Title level={5} style={{ marginTop: 24 }}>{t('help.admin.ingestPolicies', 'ポリシー設定')}</Title>
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label={t('help.admin.ingestDedupePolicy', '重複検出 (dedupePolicy)')}>
              {t('help.admin.ingestDedupePolicyDesc', 'skip_if_same_version（デフォルト）: 同一ソースIDのドキュメントが既に存在する場合はスキップ。create_new_version: 既存ドキュメントに新バージョンとして追加。replace: 既存を削除して新規作成。create_new_if_parent_context_changed: ソースの親コンテキストが変わった場合のみ新規作成。replace_relationships_on_resync: 再同期時に既存リレーションシップを削除して再作成。')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestUpdatePolicy', '更新ポリシー (updatePolicy)')}>
              {t('help.admin.ingestUpdatePolicyDesc', 'always_version_up: 常に新しいバージョンを作成（チェックアウト→チェックイン）。update_metadata_only: メタデータのみ更新（コンテンツは変更なし）。version_up_on_content_change: コンテンツのSHA-256ハッシュが変わった場合のみバージョンアップ。')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestVersionPolicy', 'バージョニング (versioningPolicy)')}>
              {t('help.admin.ingestVersionPolicyDesc', 'major: メジャーバージョン（1.0 → 2.0）。minor: マイナーバージョン（1.0 → 1.1）。none: バージョニングなし。')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestDedupeMatchBy', '重複マッチング基準 (dedupeMatchBy)')}>
              {t('help.admin.ingestDedupeMatchByDesc', 'source_id（デフォルト）: ソースシステムのID（sourceObjectId + sourceSystem + sourceObjectType）で同一文書を判定。filename: ファイル名の一致で判定（チャット添付ファイルなど、外部IDが安定しない場合に有効）。source_id_or_filename: まずソースIDで検索し、見つからなければファイル名でフォールバック。')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestAclPolicy', 'ACL同期 (aclSyncPolicy)')}>
              {t('help.admin.ingestAclPolicyDesc', 'inherit_from_folder（デフォルト）: 親フォルダの権限を継承（CMIS標準動作）。none: 親フォルダからの継承を切断し独立した権限を設定。copy_from_source: ソース側のACLを NemakiWare のローカルACEとしてコピー。')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestPreserveEml', 'EML保存 (preserveOriginalEml)')}>
              {t('help.admin.ingestPreserveEmlDesc', '有効にすると、メール取り込み時に元の .eml ファイルを別ドキュメントとして保存し、nemaki:hasAttachment リレーションシップでメッセージ本文にリンクします。')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestClassification', '分類 (defaultClassification)')}>
              {t('help.admin.ingestClassificationDesc', '設定すると、取り込み時に nemaki:classificationInfo セカンダリタイプが自動付与されます（例: "confidential", "internal"）。')}
            </Descriptions.Item>
          </Descriptions>

          {/* --- 実行パターン --- */}
          <Title level={5} style={{ marginTop: 24 }}>{t('help.admin.ingestExecution', '実行パターン')}</Title>
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label={t('help.admin.ingestExecScheduled', '① 定期ポーリング（スケジューラ）')}>
              {t('help.admin.ingestExecScheduledDesc', 'プロファイルの schedulerEnabled をオンにすると、定期的に自動実行されます（デフォルト5分間隔、ingest.scheduler.pollIntervalSeconds で変更可能）。前回のチェックポイント（タイムスタンプやメッセージID）から差分のみを取り込みます。cron式ではなく固定間隔です。レート制限（rateLimitRpm）でAPI呼び出し頻度を制御できます。フェッチのタイムアウトはデフォルト30分です（ingest.scheduler.fetchTimeoutMinutes で変更可能）。')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestExecManual', '② 手動実行')}>
              {t('help.admin.ingestExecManualDesc', '「手動インポート」タブからプロファイルを選択して即時実行できます。スケジューラが無効でも実行可能です。テストや初回の大量取り込みに便利です。')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestExecWebhook', '③ Webhook（イベント駆動）')}>
              {t('help.admin.ingestExecWebhookDesc', 'Slack Events API、Microsoft Graph changeNotification、汎用 HMAC Webhook に対応しています。コネクタに webhookSecret を設定すると、外部システムからのイベント通知で即座に取り込みが実行されます。「コネクタ」タブに Webhook URL が表示されます。')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestExecIdle', '④ IMAP IDLE（リアルタイム監視）')}>
              {t('help.admin.ingestExecIdleDesc', 'IMAP コネクタ専用のリアルタイム監視モードです。管理 API の /idle/start/{profileId} で開始します。IMAP IDLE コマンドにより、メールボックスに新着メッセージが届くと即座に取り込みが実行されます。接続エラー時は指数バックオフで自動再接続します。')}
            </Descriptions.Item>
          </Descriptions>

          {/* --- アダプタ固有パラメータ --- */}
          {/* --- アダプタ固有パラメータ（AdapterRegistry API から動的生成） --- */}
          <Title level={5} style={{ marginTop: 24 }}>{t('help.admin.ingestSchedulerParams', 'アダプタ別スケジューラパラメータ')}</Title>
          <Paragraph>{t('help.admin.ingestSchedulerParamsDesc', 'schedulerParams にJSON形式で設定します。アダプタごとに必須パラメータが異なります。')}</Paragraph>
          <Table size="small" pagination={false}
            dataSource={adapters
              .filter(a => a.sourceSystem !== 'google_drive' && a.sourceSystem !== 'onedrive')
              .map(a => ({
                key: a.sourceSystem,
                adapter: a.displayName,
                required: a.requiredParams.length > 0 ? a.requiredParams.join(', ') : '-',
                optional: a.optionalParams.join(', ') || '-',
                example: a.paramsExample,
              }))}
            columns={[
              { title: t('help.admin.adapterCol', 'アダプタ'), dataIndex: 'adapter', width: 100 },
              { title: t('help.admin.ingestRequiredCol', '必須'), dataIndex: 'required', width: 160 },
              { title: t('help.admin.ingestOptionalCol', '任意'), dataIndex: 'optional', width: 120 },
              { title: t('help.admin.ingestExampleCol', '設定例'), dataIndex: 'example' },
            ]}
          />

          {/* --- チェックポイントとジョブ管理 --- */}
          <Divider />
          <Title level={5}>{t('help.admin.ingestJobManagement', 'ジョブ管理とチェックポイント')}</Title>
          <Paragraph>{t('help.admin.ingestJobManagementDesc', 'スケジューラ実行のたびにジョブレコードが作成され、進捗がリアルタイムで追跡されます。')}</Paragraph>
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label={t('help.admin.ingestJobStatus', 'ジョブステータス')}>
              {t('help.admin.ingestJobStatusDesc', 'RUNNING: 実行中（ハートビートで進捗監視）。COMPLETED: 正常完了。PARTIAL: 一部成功・一部失敗。FAILED: 全件失敗。タイムアウト（デフォルト30分、ingest.scheduler.fetchTimeoutMinutes で変更可能）を超えるとスレッドが中断され FAILED になります。コネクタが連続失敗するとサーキットブレーカーが発動し、次サイクルまでスキップされます。')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestCheckpoint', 'チェックポイント')}>
              {t('help.admin.ingestCheckpointDesc', '各プロファイルの最後に正常取得した位置を記憶します。次回実行時はこの位置から差分取り込みを行います。チェックポイントはアダプタごとに異なります（Gmail: 日付、Slack: メッセージts、IMAP: UID等）。管理APIでチェックポイントのリセット（全件再取り込み）が可能です。')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestContentHash', 'コンテンツハッシュ')}>
              {t('help.admin.ingestContentHashDesc', '取り込み時にSHA-256ハッシュを計算・保存します。version_up_on_content_change ポリシーでは、ハッシュが一致する場合はメタデータのみ更新し、不要なバージョンアップを防ぎます。')}
            </Descriptions.Item>
          </Descriptions>

          {/* --- DLQ --- */}
          <Title level={5} style={{ marginTop: 24 }}>{t('help.admin.dlq', 'DLQ（デッドレターキュー）')}</Title>
          <Paragraph>{t('help.admin.ingestDlqIntro', '取り込みに失敗したアイテムはエラー分類に基づいて処理されます。')}</Paragraph>
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label={t('help.admin.ingestDlqTransient', '一時的エラー (transient)')}>
              {t('help.admin.ingestDlqTransientDesc', 'ネットワークタイムアウト、HTTP 429（レート制限）、HTTP 503（サービス利用不可）。DLQ に保存され、「リトライ」ボタンで再取り込みできます。自動リトライは行われません。')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestDlqPermanent', '永続的エラー (permanent)')}>
              {t('help.admin.ingestDlqPermanentDesc', '認証エラー、権限不足、データ形式エラー等。原因を修正してからリトライしてください。元のコンテンツは DLQ レコードに CouchDB attachment として保存されています。')}
            </Descriptions.Item>
          </Descriptions>
          <Alert type="info" showIcon style={{ marginTop: 12 }}
            message={t('help.admin.ingestDlqRetry', 'DLQ リトライ')}
            description={t('help.admin.ingestDlqRetryDesc', '「ジョブ履歴」タブで失敗ジョブの DLQ アイテムを確認し、「リトライ」をクリックします。類型に応じた取り込みフローが自動選択されます。60秒のクールダウンがあります。')}
          />

          {/* --- 具体例 --- */}
          <Divider />
          <Title level={5}>{t('help.admin.ingestExample', '設定例: Slack チャンネルの取り込み')}</Title>
          <Steps direction="vertical" size="small" items={[
            { title: t('help.admin.ingestExStep1', '1. Slack Bot Token を取得'), description: t('help.admin.ingestExStep1Desc', 'Slack App を作成し、channels:history, channels:read, files:read のスコープを持つ Bot Token (xoxb-...) を取得します') },
            { title: t('help.admin.ingestExStep2', '2. コネクタを作成'), description: t('help.admin.ingestExStep2Desc', 'ソースシステム: slack を選択すると類型 CHAT_CONTEXT が自動設定されます。認証方式: api_key を選択。Bot Token はサーバー側プロパティで管理します。') },
            { title: t('help.admin.ingestExStep3', '3. インポートプロファイルを作成'), description: t('help.admin.ingestExStep3Desc', 'defaultConnectorId にコネクタIDを設定、schedulerParams: {"channelId":"C01ABCD2345"}、dedupePolicy: skip_if_same_version、targetFolderId: 保存先フォルダID') },
            { title: t('help.admin.ingestExStep4', '4. スケジューラを有効化'), description: t('help.admin.ingestExStep4Desc', 'schedulerEnabled をオンにすると、5分ごとにチャンネルの新着メッセージと添付ファイルが自動取り込みされます') },
            { title: t('help.admin.ingestExStep5', '5. Webhook（オプション）'), description: t('help.admin.ingestExStep5Desc', 'webhookSecret を設定し、Slack Events API の Request URL に「コネクタ」タブに表示される Webhook URL を登録すると、メッセージ投稿時に即座に取り込みが実行されます') },
          ]} />
          <Alert type="warning" showIcon style={{ marginTop: 12 }}
            message={t('help.admin.ingestRateLimit', 'レート制限について')}
            description={t('help.admin.ingestRateLimitDesc', '各アダプタにはデフォルトのレート制限が設定されています（例: CHAT_CONTEXT 系は3 RPM、FILE_SHARE 系は2 RPM）。rateLimitRpm パラメータでコネクタ単位に調整できます。API の利用制限に合わせて適切に設定してください。')}
          />
        </>
      ),
    },
    {
      key: 'audit',
      label: <Space><BarChartOutlined /><Text strong>{t('help.admin.auditTitle', '監査ダッシュボード')}</Text></Space>,
      children: (
        <>
          <HelpImage src="14-audit-dashboard.png" alt={t('help.admin.auditAlt', '監査ダッシュボード')} />
          <Paragraph>{t('help.admin.auditDesc', '監査イベントの統計情報（総イベント数、成功/スキップ/失敗の件数と割合）と、フィルタ付きのイベント一覧を確認できます。メトリクスのリセットも可能です。')}</Paragraph>
        </>
      ),
    },
    {
      key: 'webhook',
      label: <Space><SendOutlined /><Text strong>{t('help.admin.webhookTitle', 'Webhook 管理')}</Text></Space>,
      children: (
        <>
          <HelpImage src="20-webhook.png" alt={t('help.admin.webhookAlt', 'Webhook 管理画面')} />
          <Paragraph>{t('help.admin.webhookDesc', 'ドキュメントの作成・更新・削除時に外部 URL に HTTP 通知を送信します。Webhook の設定は REST API で管理し、この画面では登録済み設定の一覧、配送ログの確認、テスト送信が可能です。')}</Paragraph>
        </>
      ),
    },
    {
      key: 'sync',
      label: <Space><SyncOutlined /><Text strong>{t('help.admin.syncTitle', 'クラウドディレクトリ同期')}</Text></Space>,
      children: (
        <>
          <Paragraph>{t('help.admin.syncDesc', 'Google Workspace / Microsoft Entra ID のユーザー・グループを NemakiWare に同期します。「連携設定」→「ディレクトリ同期」タブから手動実行できます。')}</Paragraph>
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label={t('help.admin.syncGoogle', 'Google Workspace')}>
              {t('help.admin.syncGoogleDesc', 'Google Cloud のサービスアカウント（JSON キーファイル）とドメイン全体の委任が必要です。管理者メールアドレス、ドメイン名を設定します。')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.syncMicrosoft', 'Microsoft Entra ID')}>
              {t('help.admin.syncMicrosoftDesc', 'Azure AD アプリ登録で Client Credentials（テナントID、クライアントID、クライアントシークレット）を取得し設定します。')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.syncLdap', 'LDAP')}>
              {t('help.admin.syncLdapDesc', 'LDAP/LDAPS サーバーからユーザー・グループを同期します。Bind DN、ベース DN、接続先 URL を設定します。')}
            </Descriptions.Item>
          </Descriptions>
          <Alert type="info" showIcon style={{ marginTop: 12 }}
            message={t('help.admin.syncScheduleNote', '定期同期について')}
            description={t('help.admin.syncScheduleNoteDesc', '定期自動同期はサーバー設定ファイル（nemakiware.properties）の cloud.directory.sync.cron で cron 式を設定します。UI からの定期同期設定は現在未対応です。')}
          />
        </>
      ),
    },
    {
      key: 'importexport',
      label: <Space><SwapOutlined /><Text strong>{t('help.admin.ieTitle', 'インポート/エクスポート')}</Text></Space>,
      children: (
        <>
          <HelpImage src="22-import-export.png" alt={t('help.admin.ieAlt', 'インポート/エクスポート画面')} />
          <Paragraph>{t('help.admin.ieDesc', 'サーバーのローカルファイルシステムを経由したドキュメントの一括インポート/エクスポート。エクスポートは JSON メタデータ + コンテンツファイルの形式でサーバー上のディレクトリに出力されます。インポート時に同名ファイルの上書き設定が可能です。')}</Paragraph>
        </>
      ),
    },
    {
      key: 'config',
      label: <Space><DatabaseOutlined /><Text strong>{t('help.admin.configTitle', '設定ビューア')}</Text></Space>,
      children: (
        <>
          <HelpImage src="21-config-viewer.png" alt={t('help.admin.configAlt', '設定ビューア')} />
          <Paragraph>{t('help.admin.configDesc', 'システム設定の現在値と設定元（取得元）を確認できます。プロパティキーで検索やカテゴリーで絞り込みが可能です。')}</Paragraph>
          <Alert type="info" showIcon style={{ marginTop: 12 }}
            message={t('help.admin.configNote', '設定ビューアはほとんどの設定について読み取り専用です。パスワードポリシー（最小文字数）はこの画面から直接変更可能です。その他の設定はプロパティファイルを直接編集するか、管理 API を使用します。')}
          />
        </>
      ),
    },
    {
      key: 'api',
      label: <Space><ApiOutlined /><Text strong>{t('help.admin.apiTitle', 'API ドキュメント')}</Text></Space>,
      children: (
        <>
          <Paragraph>{t('help.admin.apiDesc', '「管理」→「API ドキュメント」から Swagger UI で REST API の一覧と試行が可能です。')}</Paragraph>
          <Alert type="warning" showIcon
            message={t('help.admin.apiCsrf', 'CSRF 保護')}
            description={t('help.admin.apiCsrfDesc', 'REST API の POST/PUT/DELETE は CSRF 保護されています。CLI や curl からアクセスする場合は X-Requested-With: XMLHttpRequest ヘッダーを付与するか、Bearer トークン / AUTH_TOKEN / X-API-Key ヘッダーで認証してください（これらは非 ambient credential のため CSRF バイパスされます）。Basic 認証のみの場合は X-Requested-With が必要です。')}
          />
        </>
      ),
    },
    {
      key: 'mcp',
      label: <Space><RobotOutlined /><Text strong>{t('help.admin.mcpTitle', 'MCP サーバー（AI 連携）')}</Text></Space>,
      children: (
        <>
          <Paragraph>{t('help.admin.mcpIntro', 'NemakiWare は MCP（Model Context Protocol）サーバーを内蔵しています。Claude Code などの MCP 対応 AI クライアントから、ドキュメントの検索・取得・セマンティック検索が可能です。特別な設定は不要で、NemakiWare Core が起動していれば自動的に利用可能です。')}</Paragraph>

          {/* --- エンドポイント --- */}
          <Title level={5}>{t('help.admin.mcpEndpoints', 'エンドポイント')}</Title>
          <Table size="small" pagination={false}
            dataSource={[
              { key: '1', path: '/core/mcp/message', method: 'POST', desc: t('help.admin.mcpEndpointMessage', 'MCP JSON-RPC メッセージ処理（メインエンドポイント）') },
              { key: '2', path: '/core/mcp/info', method: 'GET', desc: t('help.admin.mcpEndpointInfo', 'サーバー情報の取得') },
              { key: '3', path: '/core/mcp/health', method: 'GET', desc: t('help.admin.mcpEndpointHealth', 'ヘルスチェック') },
            ]}
            columns={[
              { title: t('help.admin.mcpPathCol', 'パス'), dataIndex: 'path', width: 220 },
              { title: t('help.admin.mcpMethodCol', 'メソッド'), dataIndex: 'method', width: 80 },
              { title: t('help.admin.mcpDescCol', '説明'), dataIndex: 'desc' },
            ]}
          />

          {/* --- 各クライアント接続手順 --- */}
          <Divider />
          <Title level={5}>{t('help.admin.mcpClients', 'MCP クライアントからの接続')}</Title>
          <Paragraph>{t('help.admin.mcpClientsIntro', 'NemakiWare の MCP サーバーは HTTP POST ベースの Streamable HTTP トランスポートを使用しています。MCP 対応の各種 AI ツールから以下の手順で接続できます。')}</Paragraph>
          <Alert type="info" showIcon style={{ marginBottom: 16 }}
            message={t('help.admin.mcpAuthNote', '認証について')}
            description={t('help.admin.mcpAuthNoteDesc', 'Basic 認証のヘッダー値は「ユーザー名:パスワード」を Base64 エンコードしたものです。例: admin:admin → YWRtaW46YWRtaW4=。接続後は nemakiware_login ツールでセッショントークンを取得し、Bearer 認証に切り替えることも可能です。')}
          />

          {/* --- Claude Desktop --- */}
          <Title level={5}>{t('help.admin.mcpClaudeDesktop', 'Claude Desktop')}</Title>
          <Paragraph>{t('help.admin.mcpCdIntro', 'Claude Desktop は stdio トランスポートのみ対応のため、mcp-remote プロキシを使用して NemakiWare の HTTP MCP サーバーに接続します。')}</Paragraph>
          <Steps direction="vertical" size="small" items={[
            { title: t('help.admin.mcpCdStep1', '1. Claude Desktop の設定を開く'), description: t('help.admin.mcpCdStep1Desc', 'メニュー「Claude」→「Settings...」→「Developer」→「Edit Config」をクリックします') },
            { title: t('help.admin.mcpCdStep2', '2. claude_desktop_config.json を編集'), description: t('help.admin.mcpCdStep2Desc', '下記の設定を追加します。ファイルの場所: macOS: ~/Library/Application Support/Claude/claude_desktop_config.json、Windows: %APPDATA%\\Claude\\claude_desktop_config.json') },
            { title: t('help.admin.mcpCdStep3', '3. Claude Desktop を再起動'), description: t('help.admin.mcpCdStep3Desc', '設定を反映するために Claude Desktop を完全に終了して再起動します。チャット画面のツールアイコン（🔨）に nemakiware ツールが表示されれば接続成功です。') },
          ]} />
          <Card size="small" title={t('help.admin.mcpCdConfig', '設定例（claude_desktop_config.json）')} style={{ marginTop: 12 }}>
            <pre style={{ fontSize: 12, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>{`{
  "mcpServers": {
    "nemakiware": {
      "command": "npx",
      "args": [
        "mcp-remote",
        "http://localhost:8080/core/mcp/message",
        "--header",
        "Authorization: Basic YWRtaW46YWRtaW4="
      ]
    }
  }
}`}</pre>
          </Card>

          {/* --- Claude Code --- */}
          <Divider />
          <Title level={5}>{t('help.admin.mcpClaudeCode', 'Claude Code（CLI / VS Code / JetBrains）')}</Title>
          <Steps direction="vertical" size="small" items={[
            { title: t('help.admin.mcpCcStep1', '1. 設定ファイルを作成'), description: t('help.admin.mcpCcStep1Desc', 'プロジェクト単位: プロジェクトルートに .mcp.json を作成。ユーザー全体: ~/.claude/mcp.json を作成。') },
            { title: t('help.admin.mcpCcStep2', '2. NemakiWare MCP サーバーを追加'), description: t('help.admin.mcpCcStep2Desc', '下記の JSON 設定を追加します。URL はご利用の NemakiWare サーバーに合わせて変更してください。') },
            { title: t('help.admin.mcpCcStep3', '3. Claude Code を再起動'), description: t('help.admin.mcpCcStep3Desc', 'CLI: /mcp コマンドで接続状態を確認。VS Code / JetBrains: 拡張機能を再読み込み。ツール一覧に nemakiware_* が表示されれば接続成功です。') },
          ]} />
          <Card size="small" title={t('help.admin.mcpConfigExample', '設定例（.mcp.json）')} style={{ marginTop: 12 }}>
            <pre style={{ fontSize: 12, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>{`{
  "mcpServers": {
    "nemakiware": {
      "url": "http://localhost:8080/core/mcp/message",
      "headers": {
        "Authorization": "Basic YWRtaW46YWRtaW4="
      }
    }
  }
}`}</pre>
          </Card>

          {/* --- Cursor --- */}
          <Divider />
          <Title level={5}>{t('help.admin.mcpCursor', 'Cursor')}</Title>
          <Steps direction="vertical" size="small" items={[
            { title: t('help.admin.mcpCursorStep1', '1. .cursor/mcp.json を作成'), description: t('help.admin.mcpCursorStep1Desc', 'プロジェクトルートに .cursor/mcp.json を作成します（Cursor の GUI から追加する場合は「Settings」→「MCP」タブ）') },
            { title: t('help.admin.mcpCursorStep2', '2. NemakiWare サーバーを追加'), description: t('help.admin.mcpCursorStep2Desc', '下記の JSON 設定を追加します。Cursor は HTTP トランスポートに対応しているため直接接続できます。') },
            { title: t('help.admin.mcpCursorStep3', '3. Cursor を再起動'), description: t('help.admin.mcpCursorStep3Desc', 'Cursor を再起動し、「Settings」→「MCP」タブで nemakiware が接続済みになっていれば成功です') },
          ]} />
          <Card size="small" title={t('help.admin.mcpCursorConfig', '設定例（.cursor/mcp.json）')} style={{ marginTop: 12 }}>
            <pre style={{ fontSize: 12, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>{`{
  "mcpServers": {
    "nemakiware": {
      "url": "http://localhost:8080/core/mcp/message",
      "headers": {
        "Authorization": "Basic YWRtaW46YWRtaW4="
      }
    }
  }
}`}</pre>
          </Card>

          {/* --- Windsurf --- */}
          <Divider />
          <Title level={5}>{t('help.admin.mcpWindsurf', 'Windsurf (Codeium)')}</Title>
          <Steps direction="vertical" size="small" items={[
            { title: t('help.admin.mcpWindsurfStep1', '1. Windsurf の MCP 設定を開く'), description: t('help.admin.mcpWindsurfStep1Desc', '「Cascade」→「MCP」アイコンをクリックするか、~/.codeium/windsurf/mcp_config.json を編集します') },
            { title: t('help.admin.mcpWindsurfStep2', '2. NemakiWare サーバーを追加'), description: t('help.admin.mcpWindsurfStep2Desc', '下記の設定を mcp_config.json に追加します') },
            { title: t('help.admin.mcpWindsurfStep3', '3. Windsurf を再起動'), description: t('help.admin.mcpWindsurfStep3Desc', 'Cascade パネルでツール一覧に nemakiware が表示されれば接続成功です') },
          ]} />
          <Card size="small" title={t('help.admin.mcpWindsurfConfig', '設定例（mcp_config.json）')} style={{ marginTop: 12 }}>
            <pre style={{ fontSize: 12, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>{`{
  "mcpServers": {
    "nemakiware": {
      "serverUrl": "http://localhost:8080/core/mcp/message",
      "headers": {
        "Authorization": "Basic YWRtaW46YWRtaW4="
      }
    }
  }
}`}</pre>
          </Card>

          {/* --- ChatGPT Desktop --- */}
          <Divider />
          <Title level={5}>{t('help.admin.mcpChatGPT', 'ChatGPT Desktop')}</Title>
          <Paragraph>{t('help.admin.mcpChatGPTIntro', 'ChatGPT Desktop アプリは MCP をサポートしていますが、stdio トランスポートのみ対応です。NemakiWare の HTTP MCP サーバーに接続するには mcp-remote プロキシを使用します。')}</Paragraph>
          <Steps direction="vertical" size="small" items={[
            { title: t('help.admin.mcpChatGPTStep1', '1. mcp-remote をインストール'), description: t('help.admin.mcpChatGPTStep1Desc', 'npx で直接実行するため事前インストールは不要です（Node.js 18+ が必要）') },
            { title: t('help.admin.mcpChatGPTStep2', '2. ChatGPT の MCP 設定を開く'), description: t('help.admin.mcpChatGPTStep2Desc', 'ChatGPT Desktop → 「Settings」→「Beta features」→「MCP servers」を有効化 →「Configure」をクリック') },
            { title: t('help.admin.mcpChatGPTStep3', '3. NemakiWare サーバーを追加'), description: t('help.admin.mcpChatGPTStep3Desc', '「+ Add Server」→ Name: nemakiware、Command: 下記の command 値を入力します') },
          ]} />
          <Card size="small" title={t('help.admin.mcpChatGPTConfig', '設定例（ChatGPT MCP settings.json）')} style={{ marginTop: 12 }}>
            <pre style={{ fontSize: 12, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>{`{
  "mcpServers": {
    "nemakiware": {
      "command": "npx",
      "args": [
        "mcp-remote",
        "http://localhost:8080/core/mcp/message",
        "--header",
        "Authorization: Basic YWRtaW46YWRtaW4="
      ]
    }
  }
}`}</pre>
          </Card>

          {/* --- Gemini CLI --- */}
          <Divider />
          <Title level={5}>{t('help.admin.mcpGemini', 'Gemini CLI')}</Title>
          <Paragraph>{t('help.admin.mcpGeminiIntro', 'Google の Gemini CLI は MCP をサポートしていますが、stdio トランスポートのみ対応です。ChatGPT と同様に mcp-remote プロキシを使用します。')}</Paragraph>
          <Steps direction="vertical" size="small" items={[
            { title: t('help.admin.mcpGeminiStep1', '1. Gemini CLI の設定ファイルを開く'), description: t('help.admin.mcpGeminiStep1Desc', '~/.gemini/settings.json を作成・編集します') },
            { title: t('help.admin.mcpGeminiStep2', '2. mcp-remote 経由で NemakiWare を追加'), description: t('help.admin.mcpGeminiStep2Desc', '下記の設定を追加します') },
            { title: t('help.admin.mcpGeminiStep3', '3. Gemini CLI を再起動'), description: t('help.admin.mcpGeminiStep3Desc', 'gemini コマンドを再起動し、/tools コマンドで nemakiware ツールが表示されれば接続成功です') },
          ]} />
          <Card size="small" title={t('help.admin.mcpGeminiConfig', '設定例（~/.gemini/settings.json）')} style={{ marginTop: 12 }}>
            <pre style={{ fontSize: 12, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>{`{
  "mcpServers": {
    "nemakiware": {
      "command": "npx",
      "args": [
        "mcp-remote",
        "http://localhost:8080/core/mcp/message",
        "--header",
        "Authorization: Basic YWRtaW46YWRtaW4="
      ]
    }
  }
}`}</pre>
          </Card>

          {/* --- その他 --- */}
          <Divider />
          <Title level={5}>{t('help.admin.mcpOther', 'その他の MCP クライアント')}</Title>
          <Paragraph>{t('help.admin.mcpOtherDesc', 'MCP プロトコル対応の任意のクライアントから接続できます。以下の情報を設定してください:')}</Paragraph>
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label={t('help.admin.mcpOtherUrl', 'エンドポイント URL')}>
              http://&lt;{t('help.admin.mcpOtherHost', 'サーバー')}&gt;:8080/core/mcp/message
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.mcpOtherTransport', 'トランスポート')}>
              {t('help.admin.mcpOtherTransportDesc', 'Streamable HTTP（HTTP POST）')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.mcpOtherAuthHeader', '認証ヘッダー')}>
              Authorization: Basic &lt;base64(username:password)&gt;
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.mcpOtherProtocol', 'プロトコルバージョン')}>
              2024-11-05
            </Descriptions.Item>
          </Descriptions>
          <Alert type="warning" showIcon style={{ marginTop: 12 }}
            message={t('help.admin.mcpStdioNote', 'stdio トランスポートのクライアントについて')}
            description={t('help.admin.mcpStdioNoteDesc', 'NemakiWare の MCP サーバーは HTTP ベースです。stdio（標準入出力）トランスポートのみ対応のクライアント（Claude Desktop、Gemini CLI 等）からは mcp-remote プロキシを使用してください: npx mcp-remote http://localhost:8080/core/mcp/message --header "Authorization: Basic YWRtaW46YWRtaW4="')}
          />

          {/* --- 利用可能なツール --- */}
          <Divider />
          <Title level={5}>{t('help.admin.mcpTools', '利用可能なツール')}</Title>
          <Table size="small" pagination={false}
            dataSource={[
              { key: '1', tool: 'nemakiware_login', auth: '-', desc: t('help.admin.mcpToolLogin', 'ユーザー名/パスワードでログインしセッショントークンを取得') },
              { key: '2', tool: 'nemakiware_apikey_login', auth: '-', desc: t('help.admin.mcpToolApikey', 'API Key でログイン') },
              { key: '3', tool: 'nemakiware_cloud_login', auth: '-', desc: t('help.admin.mcpToolCloud', 'OIDC（Google / Microsoft）クラウドログインを開始') },
              { key: '4', tool: 'nemakiware_cloud_login_status', auth: '-', desc: t('help.admin.mcpToolCloudStatus', 'クラウドログインのステータス確認') },
              { key: '5', tool: 'nemakiware_logout', auth: '-', desc: t('help.admin.mcpToolLogout', 'ログアウト') },
              { key: '6', tool: 'nemakiware_search', auth: t('help.admin.mcpToolAuthReq', '要'), desc: t('help.admin.mcpToolSearch', 'CMIS SQL クエリによるドキュメント検索') },
              { key: '7', tool: 'nemakiware_rag_search', auth: t('help.admin.mcpToolAuthReq', '要'), desc: t('help.admin.mcpToolRag', 'セマンティック（RAG）検索。自然文で関連ドキュメントを検索') },
              { key: '8', tool: 'nemakiware_similar_documents', auth: t('help.admin.mcpToolAuthReq', '要'), desc: t('help.admin.mcpToolSimilar', '指定ドキュメントに類似するドキュメントを検索') },
              { key: '9', tool: 'nemakiware_get_document_content', auth: t('help.admin.mcpToolAuthReq', '要'), desc: t('help.admin.mcpToolContent', 'ドキュメントの本文テキストを取得') },
            ]}
            columns={[
              { title: t('help.admin.mcpToolNameCol', 'ツール名'), dataIndex: 'tool', width: 280 },
              { title: t('help.admin.mcpToolAuthCol', '認証'), dataIndex: 'auth', width: 50 },
              { title: t('help.admin.mcpDescCol', '説明'), dataIndex: 'desc' },
            ]}
          />

          {/* --- 使い方の例 --- */}
          <Divider />
          <Title level={5}>{t('help.admin.mcpUsage', '使い方の例')}</Title>
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label={t('help.admin.mcpUsageSearch', 'ドキュメント検索')}>
              {t('help.admin.mcpUsageSearchDesc', 'Claude Code で「NemakiWare から四半期レポートを検索して」と依頼すると、nemakiware_search または nemakiware_rag_search ツールが自動的に呼び出されます。')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.mcpUsageContent', '内容の取得')}>
              {t('help.admin.mcpUsageContentDesc', '「このドキュメントの内容を要約して」と依頼すると、nemakiware_get_document_content でテキストを取得し、AI が要約を生成します。')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.mcpUsageSimilar', '類似検索')}>
              {t('help.admin.mcpUsageSimilarDesc', '「この契約書に関連するドキュメントを探して」と依頼すると、nemakiware_similar_documents で意味的に類似するドキュメントを検索します（RAG 有効時）。')}
            </Descriptions.Item>
          </Descriptions>
          <Alert type="info" showIcon style={{ marginTop: 12 }}
            message={t('help.admin.mcpRagNote', 'RAG 検索について')}
            description={t('help.admin.mcpRagNoteDesc', 'nemakiware_rag_search と nemakiware_similar_documents はツール一覧に常に表示されますが、RAG（ベクトル検索）が有効でない環境で実行すると「Vector search is not enabled」エラーになります。RAG が無効の場合は nemakiware_search（CMIS SQL）をご利用ください。')}
          />
        </>
      ),
    },
  ];

  return (
    <>
      <Alert type="info" showIcon style={{ marginBottom: 16 }}
        message={t('help.admin.toggleNote', '以下の管理機能は、サーバーの設定（feature toggle）によって無効化されている場合があります。メニューに表示されない機能はご利用の環境では利用できません。')}
      />
      <Collapse defaultActiveKey={['users']} items={sections} />
    </>
  );
};

export default HelpPage;
