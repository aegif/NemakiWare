import React, { useState, useEffect } from 'react';
import {
  Table,
  Button,
  Space,
  Upload,
  Modal,
  Form,
  Input,
  message,
  Popconfirm,
  Tooltip,
  Row,
  Col,
  Card,
  Breadcrumb,
  Tag,
  Radio
} from 'antd';
import {
  FileOutlined,
  FolderOutlined,
  UploadOutlined,
  PlusOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EyeOutlined,
  LockOutlined,
  HomeOutlined,
  HistoryOutlined,
  EditOutlined,
  CheckOutlined,
  CloseOutlined
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { CMISService } from '../../services/cmis';
import { CMISObject } from '../../types/cmis';
import { FolderTree } from '../FolderTree/FolderTree';
import { useAuth } from '../../contexts/AuthContext';

interface DocumentListProps {
  repositoryId: string;
}

export const DocumentList: React.FC<DocumentListProps> = ({ repositoryId }) => {
  const [objects, setObjects] = useState<CMISObject[]>([]);
  const [loading, setLoading] = useState(false);
  const [currentFolderId, setCurrentFolderId] = useState<string>('');
  const [currentFolderPath, setCurrentFolderPath] = useState<string>('/');
  const [uploadModalVisible, setUploadModalVisible] = useState(false);
  const [folderModalVisible, setFolderModalVisible] = useState(false);
  const [checkInModalVisible, setCheckInModalVisible] = useState(false);
  const [versionHistoryModalVisible, setVersionHistoryModalVisible] = useState(false);
  const [currentDocumentId, setCurrentDocumentId] = useState<string>('');
  const [versionHistory, setVersionHistory] = useState<CMISObject[]>([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [isSearchMode, setIsSearchMode] = useState(false);

  const [form] = Form.useForm();
  const navigate = useNavigate();
  const { handleAuthError } = useAuth();
  const cmisService = new CMISService(handleAuthError);

  // Initialize root folder ID immediately
  useEffect(() => {
    if (!currentFolderId) {
      console.log('DocumentList DEBUG: Initializing with root folder ID');
      setCurrentFolderId('e02f784f8360a02cc14d1314c10038ff');
    }
  }, [repositoryId]); // Only depend on repositoryId

  // Load objects when currentFolderId changes
  useEffect(() => {
    if (currentFolderId) {
      console.log('DocumentList DEBUG: currentFolderId changed, loading objects for:', currentFolderId);
      loadObjects();
    }
  }, [currentFolderId]);

  const loadObjects = async () => {
    if (!currentFolderId) {
      console.warn('LOAD OBJECTS DEBUG: No currentFolderId, skipping load');
      return;
    }

    console.log('LOAD OBJECTS DEBUG: Loading children for repository:', repositoryId, 'folder:', currentFolderId);
    setLoading(true);
    try {
      const children = await cmisService.getChildren(repositoryId, currentFolderId);
      console.log('LOAD OBJECTS DEBUG: Successfully received', children.length, 'children:', children);
      setObjects(children);

      // Update folder path for root folder
      if (currentFolderId === 'e02f784f8360a02cc14d1314c10038ff') {
        setCurrentFolderPath('/');
        console.log('LOAD OBJECTS DEBUG: Set root folder path');
      }
    } catch (error) {
      console.error('LOAD OBJECTS DEBUG: Error loading children:', error);
      console.error('LOAD OBJECTS DEBUG: Error details:', {
        repositoryId,
        currentFolderId,
        errorMessage: error instanceof Error ? error.message : 'Unknown error'
      });
      message.error(`オブジェクトの読み込みに失敗しました: ${error instanceof Error ? error.message : 'Unknown error'}`);
      // Clear objects on error to show empty state
      setObjects([]);
    } finally {
      setLoading(false);
    }
  };

  const handleFolderSelect = (folderId: string, folderPath: string) => {
    setCurrentFolderId(folderId);
    setCurrentFolderPath(folderPath);
  };


  const handleUpload = async (values: any) => {
    const { file, name } = values;

    try {
      const actualFile = file?.[0]?.originFileObj || file?.[0] || file?.fileList?.[0]?.originFileObj;

      if (!actualFile) {
        message.error('ファイルが選択されていません');
        return;
      }

      if (!currentFolderId) {
        message.error('アップロード先フォルダが選択されていません');
        return;
      }

      await cmisService.createDocument(repositoryId, currentFolderId, actualFile, { 'cmis:name': name });

      message.success('ファイルをアップロードしました');
      setUploadModalVisible(false);
      form.resetFields();

      // FIXED: Await loadObjects() to ensure table updates before UI tests proceed
      await loadObjects();
    } catch (error) {
      console.error('Upload error:', error);
      message.error('ファイルのアップロードに失敗しました');
    }
  };

  const handleCreateFolder = async (values: any) => {
    try {
      await cmisService.createFolder(repositoryId, currentFolderId, values.name);
      message.success('フォルダを作成しました');
      setFolderModalVisible(false);
      form.resetFields();
      // FIXED: Await loadObjects() to ensure table updates before UI tests proceed
      await loadObjects();
    } catch (error) {
      message.error('フォルダの作成に失敗しました');
    }
  };

  const handleDelete = async (objectId: string) => {
    try {
      // Set loading state before starting deletion
      setLoading(true);

      await cmisService.deleteObject(repositoryId, objectId);

      // Reload objects from server after successful deletion
      await loadObjects();

      message.success('削除しました');
    } catch (error) {
      message.error('削除に失敗しました');
      setLoading(false);
    }
  };

  const handleDownload = (objectId: string) => {
    const url = cmisService.getDownloadUrl(repositoryId, objectId);
    window.open(url, '_blank');
  };

  const handleCheckOut = async (objectId: string) => {
    try {
      setLoading(true);
      await cmisService.checkOut(repositoryId, objectId);
      message.success('チェックアウトしました');
      await loadObjects();
    } catch (error) {
      console.error('Check-out error:', error);
      message.error('チェックアウトに失敗しました');
    } finally {
      setLoading(false);
    }
  };

  const handleCheckInClick = (objectId: string) => {
    setCurrentDocumentId(objectId);
    setCheckInModalVisible(true);
  };

  const handleCheckIn = async (values: any) => {
    const { file, versionType, comment } = values;

    try {
      setLoading(true);
      const actualFile = file?.[0]?.originFileObj || file?.[0] || file?.fileList?.[0]?.originFileObj;

      await cmisService.checkIn(
        repositoryId,
        currentDocumentId,
        actualFile,
        {
          major: versionType === 'major',
          checkinComment: comment || ''
        }
      );

      message.success('チェックインしました');
      setCheckInModalVisible(false);
      form.resetFields();
      await loadObjects();
    } catch (error) {
      console.error('Check-in error:', error);
      message.error('チェックインに失敗しました');
    } finally {
      setLoading(false);
    }
  };

  const handleCancelCheckOut = async (objectId: string) => {
    try {
      setLoading(true);
      await cmisService.cancelCheckOut(repositoryId, objectId);
      message.success('チェックアウトをキャンセルしました');
      await loadObjects();
    } catch (error) {
      console.error('Cancel check-out error:', error);
      message.error('チェックアウトのキャンセルに失敗しました');
    } finally {
      setLoading(false);
    }
  };

  const handleViewVersionHistory = async (objectId: string) => {
    try {
      setLoading(true);
      const history = await cmisService.getVersionHistory(repositoryId, objectId);
      setVersionHistory(history);
      setVersionHistoryModalVisible(true);
    } catch (error) {
      console.error('Version history error:', error);
      message.error('バージョン履歴の取得に失敗しました');
    } finally {
      setLoading(false);
    }
  };

  const handleSearch = async () => {
    if (!searchQuery.trim()) {
      message.warning('検索キーワードを入力してください');
      return;
    }

    setIsSearchMode(true);
    setLoading(true);

    try {
      const query = `SELECT * FROM cmis:document WHERE cmis:name LIKE '%${searchQuery}%'`;
      const searchResult = await cmisService.search(repositoryId, query);
      setObjects(searchResult.objects);
    } catch (error) {
      console.error('Search error:', error);
      message.error(`検索に失敗しました: ${error instanceof Error ? error.message : 'Unknown error'}`);
    } finally {
      setLoading(false);
    }
  };

  const handleClearSearch = () => {
    setSearchQuery('');
    setIsSearchMode(false);
    loadObjects();
  };

  const columns = [
    {
      title: 'タイプ',
      dataIndex: 'baseType',
      key: 'type',
      width: 60,
      render: (baseType: string) => (
        baseType === 'cmis:folder' ? 
          <FolderOutlined style={{ color: '#1890ff', fontSize: '16px' }} /> :
          <FileOutlined style={{ color: '#52c41a', fontSize: '16px' }} />
      ),
    },
    {
      title: '名前',
      dataIndex: 'name',
      key: 'name',
      render: (name: string, record: CMISObject) => {
        const isPWC = record.properties?.['cmis:isPrivateWorkingCopy'] === true ||
                      record.properties?.['cmis:isVersionSeriesCheckedOut'] === true;

        return (
          <Space>
            <Button
              type="link"
              onClick={() => {
                console.log('FOLDER CLICK DEBUG:', {
                  name: record.name,
                  id: record.id,
                  baseType: record.baseType,
                  objectType: record.objectType
                });
                if (record.baseType === 'cmis:folder') {
                  console.log('Setting folder ID to:', record.id);
                  setCurrentFolderId(record.id);
                } else {
                  navigate(`/documents/${record.id}`);
                }
              }}
            >
              {name}
            </Button>
            {isPWC && (
              <Tag color="orange">作業中</Tag>
            )}
          </Space>
        );
      },
    },
    {
      title: 'サイズ',
      dataIndex: 'contentStreamLength',
      key: 'size',
      width: 100,
      render: (size: number) => size ? `${Math.round(size / 1024)} KB` : '-',
    },
    {
      title: '更新日時',
      dataIndex: 'lastModificationDate',
      key: 'modified',
      width: 180,
      render: (date: string) => date ? new Date(date).toLocaleString('ja-JP') : '-',
    },
    {
      title: '更新者',
      dataIndex: 'lastModifiedBy',
      key: 'modifiedBy',
      width: 120,
    },
    {
      title: 'アクション',
      key: 'actions',
      width: 300,
      render: (_: any, record: CMISObject) => {
        const isPWC = record.properties?.['cmis:isPrivateWorkingCopy'] === true ||
                      record.properties?.['cmis:isVersionSeriesCheckedOut'] === true;
        const isVersionable = record.baseType === 'cmis:document';

        return (
          <Space>
            <Tooltip title="詳細表示">
              <Button
                icon={<EyeOutlined />}
                size="small"
                onClick={() => navigate(`/documents/${record.id}`)}
              />
            </Tooltip>
            {record.baseType === 'cmis:document' && (
              <Tooltip title="ダウンロード">
                <Button
                  icon={<DownloadOutlined />}
                  size="small"
                  onClick={() => handleDownload(record.id)}
                />
              </Tooltip>
            )}
            {isVersionable && !isPWC && (
              <Tooltip title="チェックアウト">
                <Button
                  icon={<EditOutlined />}
                  size="small"
                  onClick={() => handleCheckOut(record.id)}
                />
              </Tooltip>
            )}
            {isVersionable && isPWC && (
              <>
                <Tooltip title="チェックイン">
                  <Button
                    icon={<CheckOutlined />}
                    size="small"
                    type="primary"
                    onClick={() => handleCheckInClick(record.id)}
                  />
                </Tooltip>
                <Tooltip title="チェックアウトキャンセル">
                  <Button
                    icon={<CloseOutlined />}
                    size="small"
                    onClick={() => handleCancelCheckOut(record.id)}
                  />
                </Tooltip>
              </>
            )}
            {isVersionable && (
              <Tooltip title="バージョン履歴">
                <Button
                  icon={<HistoryOutlined />}
                  size="small"
                  onClick={() => handleViewVersionHistory(record.id)}
                />
              </Tooltip>
            )}
            <Tooltip title="権限管理">
              <Button
                icon={<LockOutlined />}
                size="small"
                onClick={() => navigate(`/permissions/${record.id}`)}
              />
            </Tooltip>
            <Popconfirm
              title="削除しますか？"
              onConfirm={() => handleDelete(record.id)}
              okText="はい"
              cancelText="いいえ"
            >
              <Tooltip title="削除">
                <Button
                  icon={<DeleteOutlined />}
                  size="small"
                  danger
                />
              </Tooltip>
            </Popconfirm>
          </Space>
        );
      },
    },
  ];

  const breadcrumbItems = currentFolderPath.split('/').filter(Boolean).map((segment, index) => ({
    title: index === 0 ? <HomeOutlined /> : segment,
  }));

  return (
    <div>
      <Row gutter={16}>
        <Col span={6}>
          <Card title="フォルダツリー" size="small">
            <FolderTree
              repositoryId={repositoryId}
              onSelect={handleFolderSelect}
              selectedFolderId={currentFolderId}
            />
          </Card>
        </Col>
        <Col span={18}>
          <Card>
            <Space direction="vertical" style={{ width: '100%' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <Breadcrumb items={breadcrumbItems} />
                <Space>
                  <Input
                    placeholder="ドキュメント検索"
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                    onPressEnter={handleSearch}
                    style={{ width: 200 }}
                    className="search-input"
                  />
                  <Button onClick={handleSearch} className="search-button">検索</Button>
                  {isSearchMode && (
                    <Button onClick={handleClearSearch}>クリア</Button>
                  )}
                  <Button
                    type="primary"
                    icon={<UploadOutlined />}
                    onClick={() => setUploadModalVisible(true)}
                  >
                    ファイルアップロード
                  </Button>
                  <Button
                    icon={<PlusOutlined />}
                    onClick={() => setFolderModalVisible(true)}
                  >
                    フォルダ作成
                  </Button>
                </Space>
              </div>
              
              <Table
                columns={columns}
                dataSource={objects}
                rowKey="id"
                loading={loading}
                pagination={{ pageSize: 20 }}
                size="small"
              />
            </Space>
          </Card>
        </Col>
      </Row>

      <Modal
        title="ファイルアップロード"
        open={uploadModalVisible}
        onCancel={() => setUploadModalVisible(false)}
        footer={null}
      >
        <Form form={form} onFinish={handleUpload} layout="vertical">
          <Form.Item
            name="file"
            label="ファイル"
            rules={[{ required: true, message: 'ファイルを選択してください' }]}
            valuePropName="fileList"
            getValueFromEvent={(e) => {
              if (Array.isArray(e)) {
                return e;
              }
              return e?.fileList;
            }}
          >
            <Upload.Dragger
              beforeUpload={() => false}
              maxCount={1}
              onChange={(info) => {
                if (info.fileList.length > 0 && info.fileList[0].name) {
                  form.setFieldsValue({ name: info.fileList[0].name });
                }
              }}
            >
              <p className="ant-upload-drag-icon">
                <UploadOutlined />
              </p>
              <p className="ant-upload-text">ファイルをドラッグ&amp;ドロップまたはクリックして選択</p>
            </Upload.Dragger>
          </Form.Item>
          <Form.Item
            name="name"
            label="ファイル名"
            rules={[{ required: true, message: 'ファイル名を入力してください' }]}
          >
            <Input placeholder="ファイル名を入力" />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">
                アップロード
              </Button>
              <Button onClick={() => {
                setUploadModalVisible(false);
                form.resetFields();
              }}>
                キャンセル
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="フォルダ作成"
        open={folderModalVisible}
        onCancel={() => setFolderModalVisible(false)}
        footer={null}
      >
        <Form form={form} onFinish={handleCreateFolder} layout="vertical">
          <Form.Item
            name="name"
            label="フォルダ名"
            rules={[{ required: true, message: 'フォルダ名を入力してください' }]}
          >
            <Input placeholder="フォルダ名を入力" />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">
                作成
              </Button>
              <Button onClick={() => setFolderModalVisible(false)}>
                キャンセル
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="チェックイン"
        open={checkInModalVisible}
        onCancel={() => {
          setCheckInModalVisible(false);
          form.resetFields();
        }}
        footer={null}
        width={600}
      >
        <Form form={form} onFinish={handleCheckIn} layout="vertical" initialValues={{ versionType: 'minor' }}>
          <Form.Item
            name="file"
            label="ファイル (オプション - 新しいコンテンツで更新する場合)"
            valuePropName="fileList"
            getValueFromEvent={(e) => {
              if (Array.isArray(e)) {
                return e;
              }
              return e?.fileList;
            }}
          >
            <Upload.Dragger
              beforeUpload={() => false}
              maxCount={1}
            >
              <p className="ant-upload-drag-icon">
                <UploadOutlined />
              </p>
              <p className="ant-upload-text">ファイルをドラッグ&amp;ドロップまたはクリックして選択</p>
              <p className="ant-upload-hint">チェックイン時にコンテンツを更新する場合のみファイルを選択してください</p>
            </Upload.Dragger>
          </Form.Item>
          <Form.Item
            name="versionType"
            label="バージョンタイプ"
            rules={[{ required: true, message: 'バージョンタイプを選択してください' }]}
          >
            <Radio.Group>
              <Radio value="minor">マイナーバージョン (例: 1.1 → 1.2)</Radio>
              <Radio value="major">メジャーバージョン (例: 1.1 → 2.0)</Radio>
            </Radio.Group>
          </Form.Item>
          <Form.Item
            name="comment"
            label="チェックインコメント"
          >
            <Input.TextArea
              rows={4}
              placeholder="変更内容のコメントを入力してください"
            />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">
                チェックイン
              </Button>
              <Button onClick={() => {
                setCheckInModalVisible(false);
                form.resetFields();
              }}>
                キャンセル
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="バージョン履歴"
        open={versionHistoryModalVisible}
        onCancel={() => setVersionHistoryModalVisible(false)}
        footer={null}
        width={800}
      >
        <Table
          dataSource={versionHistory}
          rowKey="id"
          pagination={false}
          size="small"
          columns={[
            {
              title: 'バージョン',
              dataIndex: 'versionLabel',
              key: 'version',
              width: 100,
            },
            {
              title: '更新日時',
              dataIndex: 'lastModificationDate',
              key: 'date',
              width: 180,
              render: (date: string) => date ? new Date(date).toLocaleString('ja-JP') : '-',
            },
            {
              title: '更新者',
              dataIndex: 'lastModifiedBy',
              key: 'author',
              width: 120,
            },
            {
              title: 'コメント',
              key: 'comment',
              render: (record: CMISObject) => record.properties?.['cmis:checkinComment'] || '-',
            },
            {
              title: 'アクション',
              key: 'actions',
              width: 100,
              render: (_: any, record: CMISObject) => (
                <Tooltip title="ダウンロード">
                  <Button
                    icon={<DownloadOutlined />}
                    size="small"
                    onClick={() => handleDownload(record.id)}
                  />
                </Tooltip>
              ),
            },
          ]}
        />
      </Modal>
    </div>
  );
};
