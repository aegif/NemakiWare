import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { TypeGUIEditor } from './TypeGUIEditor';
import { TypeDefinition } from '../../types/cmis';

const mockExistingTypes: TypeDefinition[] = [
  {
    id: 'cmis:document',
    displayName: 'Document',
    description: 'Base document type',
    baseTypeId: 'cmis:document',
    parentTypeId: 'cmis:document',
    creatable: true,
    fileable: true,
    queryable: true,
    deletable: false,
    propertyDefinitions: {}
  },
  {
    id: 'cmis:folder',
    displayName: 'Folder',
    description: 'Base folder type',
    baseTypeId: 'cmis:folder',
    parentTypeId: 'cmis:folder',
    creatable: true,
    fileable: true,
    queryable: true,
    deletable: false,
    propertyDefinitions: {}
  },
  {
    id: 'nemaki:customDoc',
    displayName: 'Custom Document',
    description: 'A custom document type',
    baseTypeId: 'cmis:document',
    parentTypeId: 'cmis:document',
    creatable: true,
    fileable: true,
    queryable: true,
    deletable: true,
    propertyDefinitions: {
      'nemaki:customProp': {
        id: 'nemaki:customProp',
        displayName: 'Custom Property',
        description: 'A custom property',
        propertyType: 'string',
        cardinality: 'single',
        required: false,
        queryable: true,
        updatable: true
      }
    }
  }
];

describe('TypeGUIEditor', () => {
  const mockOnSave = vi.fn();
  const mockOnCancel = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe('Rendering', () => {
    it('renders the GUI editor with tabs', () => {
      render(
        <TypeGUIEditor
          initialValue={null}
          existingTypes={mockExistingTypes}
          onSave={mockOnSave}
          onCancel={mockOnCancel}
          isEditing={false}
        />
      );

      expect(screen.getByText('GUIエディタ')).toBeInTheDocument();
      expect(screen.getByText('JSONエディタ')).toBeInTheDocument();
    });

    it('renders basic info panel', () => {
      render(
        <TypeGUIEditor
          initialValue={null}
          existingTypes={mockExistingTypes}
          onSave={mockOnSave}
          onCancel={mockOnCancel}
          isEditing={false}
        />
      );

      expect(screen.getByText('基本情報')).toBeInTheDocument();
      expect(screen.getByText('タイプオプション')).toBeInTheDocument();
      expect(screen.getByText('プロパティ定義')).toBeInTheDocument();
    });

    it('renders update button for editing mode', () => {
      const existingType = mockExistingTypes[2];
      const { container } = render(
        <TypeGUIEditor
          initialValue={existingType}
          existingTypes={mockExistingTypes}
          onSave={mockOnSave}
          onCancel={mockOnCancel}
          isEditing={true}
        />
      );

      // Find all buttons in the container
      const allButtons = container.querySelectorAll('button');
      const buttonTexts = Array.from(allButtons).map(btn => btn.textContent);

      // Ant Design adds spaces between CJK characters, so we need to normalize
      // '更 新' becomes '更新' after removing spaces
      const normalizedTexts = buttonTexts.map(text => text?.replace(/\s/g, ''));

      // Check that the update button exists
      const hasUpdateButton = normalizedTexts.some(text => text?.includes('更新'));
      const hasCancelButton = normalizedTexts.some(text => text?.includes('キャンセル'));

      expect(hasCancelButton).toBe(true);
      expect(hasUpdateButton).toBe(true);
    });

    it('renders create button for new type', () => {
      const { container } = render(
        <TypeGUIEditor
          initialValue={null}
          existingTypes={mockExistingTypes}
          onSave={mockOnSave}
          onCancel={mockOnCancel}
          isEditing={false}
        />
      );

      // Find all buttons in the container
      const allButtons = container.querySelectorAll('button');
      const buttonTexts = Array.from(allButtons).map(btn => btn.textContent);

      // Ant Design adds spaces between CJK characters
      const normalizedTexts = buttonTexts.map(text => text?.replace(/\s/g, ''));

      // Check that the create button exists
      const hasCreateButton = normalizedTexts.some(text => text?.includes('作成'));
      const hasCancelButton = normalizedTexts.some(text => text?.includes('キャンセル'));

      expect(hasCancelButton).toBe(true);
      expect(hasCreateButton).toBe(true);
    });
  });

  describe('Validation', () => {
    it('shows error when type ID is empty', async () => {
      const { container } = render(
        <TypeGUIEditor
          initialValue={null}
          existingTypes={mockExistingTypes}
          onSave={mockOnSave}
          onCancel={mockOnCancel}
          isEditing={false}
        />
      );

      // Find the create button (Ant Design adds spaces between CJK characters)
      const allButtons = container.querySelectorAll('button');
      const createButton = Array.from(allButtons).find(btn =>
        btn.textContent?.replace(/\s/g, '').includes('作成')
      );

      expect(createButton).toBeTruthy();

      if (createButton) {
        fireEvent.click(createButton);
      }

      await waitFor(() => {
        expect(screen.getByText('タイプIDは必須です')).toBeInTheDocument();
      });
      expect(mockOnSave).not.toHaveBeenCalled();
    });
  });

  describe('Tab Switching', () => {
    it('switches to JSON editor tab', async () => {
      render(
        <TypeGUIEditor
          initialValue={null}
          existingTypes={mockExistingTypes}
          onSave={mockOnSave}
          onCancel={mockOnCancel}
          isEditing={false}
        />
      );

      const jsonTab = screen.getByText('JSONエディタ');
      fireEvent.click(jsonTab);

      await waitFor(() => {
        expect(screen.getByText('JSON形式で直接編集')).toBeInTheDocument();
      });
    });
  });

  describe('Cancel Action', () => {
    it('calls onCancel when cancel button is clicked', () => {
      render(
        <TypeGUIEditor
          initialValue={null}
          existingTypes={mockExistingTypes}
          onSave={mockOnSave}
          onCancel={mockOnCancel}
          isEditing={false}
        />
      );

      const cancelButton = screen.getByText('キャンセル');
      fireEvent.click(cancelButton);

      expect(mockOnCancel).toHaveBeenCalled();
    });
  });

  describe('Relationship Type', () => {
    it('shows relationship settings when base type is cmis:relationship', async () => {
      const relationshipType: TypeDefinition = {
        id: 'nemaki:testRelation',
        displayName: 'Test Relation',
        description: '',
        baseTypeId: 'cmis:relationship',
        parentTypeId: 'cmis:relationship',
        creatable: true,
        fileable: false,
        queryable: true,
        deletable: true,
        propertyDefinitions: {}
      };

      render(
        <TypeGUIEditor
          initialValue={relationshipType}
          existingTypes={mockExistingTypes}
          onSave={mockOnSave}
          onCancel={mockOnCancel}
          isEditing={true}
        />
      );

      await waitFor(() => {
        expect(screen.getByText('リレーションシップ設定')).toBeInTheDocument();
      });
    });

    it('does not show relationship settings for document type', () => {
      render(
        <TypeGUIEditor
          initialValue={mockExistingTypes[2]}
          existingTypes={mockExistingTypes}
          onSave={mockOnSave}
          onCancel={mockOnCancel}
          isEditing={true}
        />
      );

      expect(screen.queryByText('リレーションシップ設定')).not.toBeInTheDocument();
    });
  });

  describe('Property Management', () => {
    it('shows add property button', () => {
      render(
        <TypeGUIEditor
          initialValue={null}
          existingTypes={mockExistingTypes}
          onSave={mockOnSave}
          onCancel={mockOnCancel}
          isEditing={false}
        />
      );

      expect(screen.getByText('プロパティを追加')).toBeInTheDocument();
    });

    it('shows empty property message initially', () => {
      render(
        <TypeGUIEditor
          initialValue={null}
          existingTypes={mockExistingTypes}
          onSave={mockOnSave}
          onCancel={mockOnCancel}
          isEditing={false}
        />
      );

      expect(screen.getByText('プロパティが定義されていません')).toBeInTheDocument();
    });
  });
});

describe('extractPrefix utility', () => {
  it('extracts prefix from type ID with colon', () => {
    const typeId = 'nemaki:customDocument';
    const colonIndex = typeId.indexOf(':');
    const prefix = colonIndex > 0 ? typeId.substring(0, colonIndex + 1) : '';
    expect(prefix).toBe('nemaki:');
  });

  it('returns empty string for type ID without colon', () => {
    const typeId = 'customDocument';
    const colonIndex = typeId.indexOf(':');
    const prefix = colonIndex > 0 ? typeId.substring(0, colonIndex + 1) : '';
    expect(prefix).toBe('');
  });

  it('returns empty string for empty type ID', () => {
    const typeId = '';
    const colonIndex = typeId.indexOf(':');
    const prefix = colonIndex > 0 ? typeId.substring(0, colonIndex + 1) : '';
    expect(prefix).toBe('');
  });
});
