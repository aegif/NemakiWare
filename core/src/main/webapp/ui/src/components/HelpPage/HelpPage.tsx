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
  let isAdmin = false;
  try {
    // useAuth may throw if rendered outside AuthProvider (public route)
    const { authToken } = useAuth();
    isAdmin = authToken?.isAdmin === true;
  } catch {
    isAdmin = false;
  }

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
            { title: t('help.user.folderStep1', '「＋ フォルダ作成」ボタンをクリック'), description: t('help.user.folderStep1Desc', 'ツールバー右側にあります') },
            { title: t('help.user.folderStep2', 'フォルダ名を入力') },
            { title: t('help.user.folderStep3', 'タイプを選択（任意）'), description: t('help.user.folderStep3Desc', 'カスタムフォルダタイプがある場合は選択') },
            { title: t('help.user.folderStep4', '「作成」をクリック') },
          ]} />
          <Divider />
          <Title level={5}>{t('help.user.folderDelete', 'フォルダの削除')}</Title>
          <Steps direction="vertical" size="small" items={[
            { title: t('help.user.folderDelStep1', '削除したいフォルダの行をチェック') },
            { title: t('help.user.folderDelStep2', '「削除」ボタンをクリック') },
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
              <Descriptions.Item label={t('help.user.btnBack', '← 戻る')}>{t('help.user.btnBackDesc', 'ドキュメント一覧に戻ります')}</Descriptions.Item>
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
            { title: t('help.user.aclStep2', '「＋ 権限を追加」をクリック') },
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
            { title: t('help.user.cloudStep1', '「Google Drive からインポート」または「OneDrive からインポート」ボタンをクリック'), description: t('help.user.cloudStep1Desc', 'ツールバーにプロバイダ別のボタンが表示されます') },
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
            { title: t('help.user.pkStep3', '「パスキーを追加」をクリック') },
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

  // Check feature toggles to only show relevant sections
  // (In a future enhancement, these could be fetched from the server)

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
            { title: t('help.admin.userStep2', '「新規ユーザー」をクリック') },
            { title: t('help.admin.userStep3', 'ユーザー ID、表示名、パスワードを入力'), description: t('help.admin.userStep3Desc', 'パスワードは BCrypt でハッシュ化されて安全に保存されます') },
            { title: t('help.admin.userStep4', '管理者権限を設定（任意）') },
            { title: t('help.admin.userStep5', '「作成」をクリック') },
          ]} />
          <Alert type="info" showIcon style={{ marginTop: 12 }}
            message={t('help.admin.userPwReset', '管理者はパスワードリセットが可能')}
            description={t('help.admin.userPwResetDesc', 'ユーザー一覧から対象ユーザーの「パスワードリセット」で新しいパスワードを設定できます（旧パスワード不要）。')}
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
            { title: t('help.admin.typeStep3', '親タイプを選択'), description: 'cmis:document, cmis:folder' },
            { title: t('help.admin.typeStep4', 'プロパティを追加'), description: t('help.admin.typeStep4Desc', '文字列/数値/日付/真偽値から選択。必須/検索可能を設定') },
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
            <Descriptions.Item label={t('help.admin.archRestore', '復元')}>{t('help.admin.archRestoreDesc', '元のフォルダにドキュメントを復元します')}</Descriptions.Item>
            <Descriptions.Item label={t('help.admin.archPerm', '完全削除')}>{t('help.admin.archPermDesc', 'データベースから物理削除。復元できなくなります')}</Descriptions.Item>
            <Descriptions.Item label={t('help.admin.archBulk', '一括操作')}>{t('help.admin.archBulkDesc', 'チェックボックスで複数選択し、一括復元/一括削除が可能')}</Descriptions.Item>
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
          <Steps direction="vertical" size="small" items={[
            { title: t('help.admin.solrStep1', 'フルインデックス再構築'), description: t('help.admin.solrStep1Desc', '全ドキュメントの検索インデックスを再作成します（数分〜数十分）') },
            { title: t('help.admin.solrStep2', 'RAG ベクトルインデックス再構築'), description: t('help.admin.solrStep2Desc', 'セマンティック検索用の embedding を全ドキュメントで再計算します') },
          ]} />
        </>
      ),
    },
    {
      key: 'ingest',
      label: <Space><SwapOutlined /><Text strong>{t('help.admin.ingestTitle', '外部インジェスト')}</Text></Space>,
      children: (
        <>
          <HelpImage src="13-integration-settings.png" alt={t('help.admin.ingestAlt', '統合設定画面')} />
          <Paragraph>{t('help.admin.ingestIntro', '外部システムからドキュメントを自動取り込みます。コネクタの設定、インポートプロファイル、スケジューラ管理が可能です。')}</Paragraph>
          <Table size="small" pagination={false}
            dataSource={[
              { key: '1', adapter: 'IMAP', target: t('help.admin.ingestIMAP', 'メールサーバー') },
              { key: '2', adapter: 'Gmail', target: 'Gmail API' },
              { key: '3', adapter: 'M365 Mail', target: 'Microsoft 365' },
              { key: '4', adapter: 'Slack', target: t('help.admin.ingestChat', 'チャンネルメッセージ') },
              { key: '5', adapter: 'Teams', target: t('help.admin.ingestChat', 'チャンネルメッセージ') },
              { key: '6', adapter: 'Mattermost', target: t('help.admin.ingestChat', 'チャンネルメッセージ') },
              { key: '7', adapter: 'Chatwork', target: t('help.admin.ingestCW', 'ルームメッセージ') },
              { key: '8', adapter: 'Notion', target: t('help.admin.ingestNotion', 'ページ・データベース') },
              { key: '9', adapter: 'Salesforce', target: t('help.admin.ingestSF', 'レコード') },
              { key: '10', adapter: 'Box', target: t('help.admin.ingestFile', 'ファイル') },
              { key: '11', adapter: 'Dropbox', target: t('help.admin.ingestFile', 'ファイル') },
            ]}
            columns={[
              { title: t('help.admin.adapterCol', 'アダプタ'), dataIndex: 'adapter', width: 120 },
              { title: t('help.admin.targetCol', '取り込み対象'), dataIndex: 'target' },
            ]}
          />
          <Alert type="info" showIcon style={{ marginTop: 12 }}
            message={t('help.admin.dlq', 'DLQ（デッドレターキュー）')}
            description={t('help.admin.dlqDesc', '取り込みに失敗したアイテムは DLQ に保存されます。「ジョブ履歴」タブで確認し、原因を修正してから「リトライ」で再取り込みできます（60秒のクールダウンあり）。')}
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
          <Paragraph>{t('help.admin.auditDesc', '操作数のグラフ（日別/週別）、操作種別の内訳、ユーザー別の操作数を確認できます。')}</Paragraph>
        </>
      ),
    },
    {
      key: 'webhook',
      label: <Space><SendOutlined /><Text strong>{t('help.admin.webhookTitle', 'Webhook 管理')}</Text></Space>,
      children: (
        <>
          <HelpImage src="20-webhook.png" alt={t('help.admin.webhookAlt', 'Webhook 管理画面')} />
          <Paragraph>{t('help.admin.webhookDesc', 'ドキュメントの作成・更新・削除時に外部 URL に HTTP 通知を送信します。URL、イベント種別、対象フォルダを設定して「保存」します。')}</Paragraph>
        </>
      ),
    },
    {
      key: 'sync',
      label: <Space><SyncOutlined /><Text strong>{t('help.admin.syncTitle', 'クラウドディレクトリ同期')}</Text></Space>,
      children: (
        <Paragraph>{t('help.admin.syncDesc', 'Google Workspace / Microsoft Entra ID のユーザー・グループを NemakiWare に同期します。手動実行と定期自動同期に対応しています。')}</Paragraph>
      ),
    },
    {
      key: 'importexport',
      label: <Space><SwapOutlined /><Text strong>{t('help.admin.ieTitle', 'インポート/エクスポート')}</Text></Space>,
      children: (
        <>
          <HelpImage src="22-import-export.png" alt={t('help.admin.ieAlt', 'インポート/エクスポート画面')} />
          <Paragraph>{t('help.admin.ieDesc', 'ドキュメントの一括インポート/エクスポート。エクスポートは ZIP 形式（メタデータ + コンテンツ）。インポート時に同名ファイルの上書き設定が可能です。')}</Paragraph>
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
            message={t('help.admin.configNote', '設定ビューアは読み取り専用です。値を変更するにはプロパティファイルを直接編集するか、管理 API を使用します。')}
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
            description={t('help.admin.apiCsrfDesc', 'REST API の POST/PUT/DELETE は CSRF 保護されています。CLI や curl からアクセスする場合は X-Requested-With: XMLHttpRequest ヘッダーを付与してください。')}
          />
        </>
      ),
    },
  ];

  return <Collapse defaultActiveKey={['users']} items={sections} />;
};

export default HelpPage;
