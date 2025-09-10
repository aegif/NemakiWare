import React, { useState, useEffect } from 'react';
import { Button, Space, Modal, message } from 'antd';
import { ActionDefinition } from '../../types/cmis';
import { ActionService } from '../../services/action';
import { ActionFormRenderer } from './ActionFormRenderer';

interface ActionButtonsProps {
  repositoryId: string;
  objectId: string;
  triggerType: 'UserButton' | 'UserCreate';
  onActionComplete?: () => void;
}

export const ActionButtons: React.FC<ActionButtonsProps> = ({
  repositoryId,
  objectId,
  triggerType,
  onActionComplete
}) => {
  const [actions, setActions] = useState<ActionDefinition[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalVisible, setModalVisible] = useState(false);
  const [selectedAction, setSelectedAction] = useState<ActionDefinition | null>(null);

  const actionService = new ActionService();

  useEffect(() => {
    loadActions();
  }, [repositoryId, objectId]);

  const loadActions = async () => {
    setLoading(true);
    try {
      const allActions = await actionService.discoverActions(repositoryId, objectId);
      const filteredActions = allActions.filter(action => 
        action.triggerType === triggerType && action.canExecute
      );
      setActions(filteredActions);
    } catch (error) {
      console.error('Failed to load actions:', error);
      message.error('アクションの読み込みに失敗しました');
    } finally {
      setLoading(false);
    }
  };

  const handleActionClick = (action: ActionDefinition) => {
    setSelectedAction(action);
    setModalVisible(true);
  };

  const handleActionComplete = () => {
    setModalVisible(false);
    setSelectedAction(null);
    onActionComplete?.();
  };

  if (actions.length === 0) {
    return null;
  }

  return (
    <>
      <Space>
        {actions.map(action => (
          <Button
            key={action.id}
            icon={action.fontAwesome ? <i className={action.fontAwesome} /> : undefined}
            onClick={() => handleActionClick(action)}
            loading={loading}
          >
            {action.title}
          </Button>
        ))}
      </Space>

      <Modal
        title={selectedAction?.title}
        open={modalVisible}
        onCancel={() => setModalVisible(false)}
        footer={null}
        width={600}
      >
        {selectedAction && (
          <ActionFormRenderer
            repositoryId={repositoryId}
            objectId={objectId}
            actionId={selectedAction.id}
            onComplete={handleActionComplete}
          />
        )}
      </Modal>
    </>
  );
};
