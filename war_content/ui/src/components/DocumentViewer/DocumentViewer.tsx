import React, { useState, useEffect } from 'react';
import { 
  Card, 
  Tabs, 
  Button, 
  Space, 
  message, 
  Descriptions, 
  Table, 
  Modal, 
  Upload, 
  Form,
  Input,
  Tag,
  Popconfirm
} from 'antd';
import { 
  DownloadOutlined, 
  EditOutlined, 
  LockOutlined, 
  UnlockOutlined,
  UploadOutlined,
  ArrowLeftOutlined,
  PlusOutlined,
  DeleteOutlined
} from '@ant-design/icons';
import { useParams, useNavigate } from 'react-router-dom';
import { CMISService } from '../../services/cmis';
import { CMISObject, VersionHistory, TypeDefinition, Relationship } from '../../types/cmis';
import { PropertyEditor } from '../PropertyEditor/PropertyEditor';
import { PreviewComponent } from '../PreviewComponent/PreviewComponent';
import { canPreview } from '../../utils/previewUtils';

interface DocumentViewerProps {
  repositoryId: string;
}

export const DocumentViewer: React.FC<DocumentViewerProps> = ({ repositoryId }) => {
  const { objectId } = useParams<{ objectId: string }>();
  const navigate = useNavigate();
  const [object, setObject] = useState<CMISObject | null>(null);
  const [typeDefinition, setTypeDefinition] = useState<TypeDefinition | null>(null);
  const [versionHistory, setVersionHistory] = useState<VersionHistory | null>(null);
  const [relationships, setRelationships] = useState<Relationship[]>([]);
  const [loading, setLoading] = useState(true);
  const [checkoutModalVisible, setCheckoutModalVisible] = useState(false);
  const [relationshipModalVisible, setRelationshipModalVisible] = useState(false);
  const [form] = Form.useForm();
  const [relationshipForm] = Form.useForm();

  const cmisService = new CMISService();

  useEffect(() => {
    if (objectId) {
      loadObject();
      loadVersionHistory();
      loadRelationships();
    }
  }, [objectId, repositoryId]);

  const loadObject = async () => {
    if (!objectId) return;
    
    try {
      const obj = await cmisService.getObject(repositoryId, objectId);
      setObject(obj);
      
      const typeDef = await cmisService.getType(repositoryId, obj.objectType);
      setTypeDefinition(typeDef);
    } catch (error) {
      message.error('オブジェクトの読み込みに失敗しました');
    } finally {
      setLoading(false);
    }
  };

  const loadVersionHistory = async () => {
    if (!objectId) return;
    
    try {
      const history = await cmisService.getVersionHistory(repositoryId, objectId);
      setVersionHistory(history);
    } catch (error) {
      console.error('バージョン履歴の読み込みに失敗しました');
    }
  };

  const loadRelationships = async () => {
    if (!objectId) return;
    
    try {
      const rels = await cmisService.getRelationships(repositoryId, objectId);
      setRelationships(rels);
    } catch (error) {
      console.error('関係の読み込みに失敗しました');
    }
  };

  const handleDownload = async () => {
    if (object && object.baseType === 'cmis:document') {
      try {
        // Use authenticated content stream download instead of direct URL
        const response = await cmisService.getContentStream(repositoryId, object.id);
        const blob = new Blob([response]);
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = object.name || 'download';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        window.URL.revokeObjectURL(url);
      } catch (error) {
        console.error('Download error:', error);
        message.error('ダウンロードに失敗しました');
      }
    }
  };

  const handleCheckOut = async () => {
    if (!object) return;
    
    try {
      await cmisService.checkOut(repositoryId, object.id);
      message.success('チェックアウトしました');
      loadObject();
    } catch (error) {
      message.error('チェックアウトに失敗しました');
    }
  };

  const handleCheckIn = async (values: any) => {
    if (!object) return;
    
    try {
      const file = values.file?.file;
      await cmisService.checkIn(repositoryId, object.id, file, values);
      message.success('チェックインしました');
      setCheckoutModalVisible(false);
      form.resetFields();
      loadObject();
      loadVersionHistory();
    } catch (error) {
      message.error('チェックインに失敗しました');
    }
  };

  const handleCancelCheckOut = async () => {
    if (!object) return;
    
    try {
      await cmisService.cancelCheckOut(repositoryId, object.id);
      message.success('チェックアウトをキャンセルしました');
      loadObject();
    } catch (error) {
      message.error('チェックアウトのキャンセルに失敗しました');
    }
  };

  const handleUpdateProperties = async (properties: Record<string, any>) => {
    if (!object) return;
    
    try {
      const updatedObject = await cmisService.updateProperties(repositoryId, object.id, properties);
      setObject(updatedObject);
      message.success('プロパティを更新しました');
    } catch (error) {
      message.error('プロパティの更新に失敗しました');
    }
  };

  const handleCreateRelationship = async (values: any) => {
    if (!object) return;
    
    try {
      await cmisService.createRelationship(repositoryId, {
        sourceId: object.id,
        targetId: values.targetId,
        relationshipType: values.relationshipType || 'cmis:relationship',
      });
      message.success('関係を作成しました');
      setRelationshipModalVisible(false);
      relationshipForm.resetFields();
      loadRelationships();
    } catch (error) {
      message.error('関係の作成に失敗しました');
    }
  };

  const handleDeleteRelationship = async (relationshipId: string) => {
    try {
      await cmisService.deleteRelationship(repositoryId, relationshipId);
      message.success('関係を削除しました');
      loadRelationships();
    } catch (error) {
      message.error('関係の削除に失敗しました');
    }
  };

  if (loading || !object || !typeDefinition) {
    return <div>読み込み中...</div>;
  }

  const isCheckedOut = object.properties['cmis:isVersionSeriesCheckedOut'];
  const checkedOutBy = object.properties['cmis:versionSeriesCheckedOutBy'];

  const versionColumns = [
    {
      title: 'バージョン',
      dataIndex: 'versionLabel',
      key: 'version',
    },
    {
      title: '作成者',
      dataIndex: 'createdBy',
      key: 'createdBy',
    },
    {
      title: '作成日時',
      dataIndex: 'creationDate',
      key: 'creationDate',
      render: (date: string) => new Date(date).toLocaleString('ja-JP'),
    },
    {
      title: 'コメント',
      dataIndex: 'properties',
      key: 'comment',
      render: (properties: Record<string, any>) => properties['cmis:checkinComment'] || '-',
    },
    {
      title: 'アクション',
      key: 'actions',
      render: (_: any, record: CMISObject) => (
        <Button 
          size="small"
          onClick={async () => {
            try {
              const response = await cmisService.getContentStream(repositoryId, record.id);
              const blob = new Blob([response]);
              const url = window.URL.createObjectURL(blob);
              const a = document.createElement('a');
              a.href = url;
              a.download = record.name || 'download';
              document.body.appendChild(a);
              a.click();
              document.body.removeChild(a);
              window.URL.revokeObjectURL(url);
            } catch (error) {
              message.error('ダウンロードに失敗しました');
            }
          }}
        >
          ダウンロード
        </Button>
      ),
    },
  ];

  const relationshipColumns = [
    {
      title: 'タイプ',
      dataIndex: 'relationshipType',
      key: 'type',
    },
    {
      title: 'ソース',
      dataIndex: 'sourceId',
      key: 'source',
    },
    {
      title: 'ターゲット',
      dataIndex: 'targetId',
      key: 'target',
    },
    {
      title: 'アクション',
      key: 'actions',
      render: (_: any, record: Relationship) => (
        <Popconfirm
          title="この関係を削除しますか？"
          onConfirm={() => handleDeleteRelationship(record.id)}
          okText="はい"
          cancelText="いいえ"
        >
          <Button 
            size="small" 
            danger 
            icon={<DeleteOutlined />}
          >
            削除
          </Button>
        </Popconfirm>
      ),
    },
  ];

  const tabItems = [
    {
      key: 'properties',
      label: 'プロパティ',
      children: (
        <PropertyEditor
          object={object}
          propertyDefinitions={typeDefinition.propertyDefinitions}
          onSave={handleUpdateProperties}
          readOnly={isCheckedOut && checkedOutBy !== object.createdBy}
        />
      ),
    },
    ...(canPreview(object) ? [{
      key: 'preview',
      label: 'プレビュー',
      children: (
        <PreviewComponent
          repositoryId={repositoryId}
          object={object}
        />
      ),
    }] : []),
    {
      key: 'versions',
      label: 'バージョン履歴',
      children: (
        <Table
          columns={versionColumns}
          dataSource={versionHistory?.versions || []}
          rowKey="id"
          size="small"
          pagination={false}
        />
      ),
    },
    {
      key: 'relationships',
      label: '関係',
      children: (
        <Space direction="vertical" style={{ width: '100%' }}>
          <div style={{ marginBottom: 16 }}>
            <Button 
              type="primary" 
              icon={<PlusOutlined />}
              onClick={() => setRelationshipModalVisible(true)}
            >
              関係を追加
            </Button>
          </div>
          <Table
            columns={relationshipColumns}
            dataSource={relationships}
            rowKey="id"
            size="small"
            pagination={false}
            locale={{ emptyText: '関係がありません' }}
          />
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Card>
        <Space direction="vertical" style={{ width: '100%' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <Space>
              <Button 
                icon={<ArrowLeftOutlined />}
                onClick={() => navigate('/documents')}
              >
                戻る
              </Button>
              <h2 style={{ margin: 0 }}>{object.name}</h2>
              {isCheckedOut && (
                <Tag color="orange">
                  <LockOutlined /> チェックアウト中 ({checkedOutBy})
                </Tag>
              )}
            </Space>
            
            <Space>
              {object.baseType === 'cmis:document' && (
                <Button 
                  icon={<DownloadOutlined />}
                  onClick={handleDownload}
                >
                  ダウンロード
                </Button>
              )}
              
              {!isCheckedOut ? (
                <Button 
                  icon={<LockOutlined />}
                  onClick={handleCheckOut}
                >
                  チェックアウト
                </Button>
              ) : (
                <Space>
                  <Button 
                    type="primary"
                    icon={<UnlockOutlined />}
                    onClick={() => setCheckoutModalVisible(true)}
                  >
                    チェックイン
                  </Button>
                  <Popconfirm
                    title="チェックアウトをキャンセルしますか？"
                    onConfirm={handleCancelCheckOut}
                    okText="はい"
                    cancelText="いいえ"
                  >
                    <Button danger>
                      キャンセル
                    </Button>
                  </Popconfirm>
                </Space>
              )}
              
              <Button 
                icon={<EditOutlined />}
                onClick={() => navigate(`/permissions/${object.id}`)}
              >
                権限管理
              </Button>
            </Space>
          </div>

          <Descriptions bordered size="small">
            <Descriptions.Item label="ID">{object.id}</Descriptions.Item>
            <Descriptions.Item label="タイプ">{object.objectType}</Descriptions.Item>
            <Descriptions.Item label="ベースタイプ">{object.baseType}</Descriptions.Item>
            <Descriptions.Item label="パス">{object.path}</Descriptions.Item>
            <Descriptions.Item label="作成者">{object.createdBy}</Descriptions.Item>
            <Descriptions.Item label="作成日時">
              {object.creationDate ? new Date(object.creationDate).toLocaleString('ja-JP') : '-'}
            </Descriptions.Item>
            <Descriptions.Item label="更新者">{object.lastModifiedBy}</Descriptions.Item>
            <Descriptions.Item label="更新日時">
              {object.lastModificationDate ? new Date(object.lastModificationDate).toLocaleString('ja-JP') : '-'}
            </Descriptions.Item>
            {object.contentStreamLength && (
              <Descriptions.Item label="サイズ">
                {Math.round(object.contentStreamLength / 1024)} KB
              </Descriptions.Item>
            )}
            {object.contentStreamMimeType && (
              <Descriptions.Item label="MIMEタイプ">
                {object.contentStreamMimeType}
              </Descriptions.Item>
            )}
          </Descriptions>

          <Tabs items={tabItems} />
        </Space>
      </Card>

      <Modal
        title="チェックイン"
        open={checkoutModalVisible}
        onCancel={() => setCheckoutModalVisible(false)}
        footer={null}
        width={600}
      >
        <Form form={form} onFinish={handleCheckIn} layout="vertical">
          <Form.Item
            name="file"
            label="新しいファイル（オプション）"
          >
            <Upload.Dragger
              beforeUpload={() => false}
              maxCount={1}
            >
              <p className="ant-upload-drag-icon">
                <UploadOutlined />
              </p>
              <p className="ant-upload-text">新しいバージョンのファイルをアップロード（オプション）</p>
            </Upload.Dragger>
          </Form.Item>
          
          <Form.Item
            name="cmis:checkinComment"
            label="チェックインコメント"
          >
            <Input.TextArea rows={3} placeholder="変更内容を入力してください" />
          </Form.Item>
          
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">
                チェックイン
              </Button>
              <Button onClick={() => setCheckoutModalVisible(false)}>
                キャンセル
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="関係を追加"
        open={relationshipModalVisible}
        onCancel={() => {
          setRelationshipModalVisible(false);
          relationshipForm.resetFields();
        }}
        footer={null}
        width={500}
      >
        <Form form={relationshipForm} onFinish={handleCreateRelationship} layout="vertical">
          <Form.Item
            name="relationshipType"
            label="関係タイプ"
            initialValue="cmis:relationship"
          >
            <Input placeholder="cmis:relationship" />
          </Form.Item>
          
          <Form.Item
            name="targetId"
            label="ターゲットオブジェクトID"
            rules={[{ required: true, message: 'ターゲットオブジェクトIDを入力してください' }]}
          >
            <Input placeholder="ターゲットオブジェクトのIDを入力" />
          </Form.Item>
          
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">
                作成
              </Button>
              <Button onClick={() => {
                setRelationshipModalVisible(false);
                relationshipForm.resetFields();
              }}>
                キャンセル
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};
