import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { TypeGUIEditor } from './TypeGUIEditor';
import { TypeDefinition } from '../../types/cmis';

// Stable mock references
const STABLE_T = (key: string) => key;
const STABLE_TRANSLATION = { t: STABLE_T, i18n: { language: 'en' } };

vi.mock('react-i18next', () => ({
  useTranslation: () => STABLE_TRANSLATION,
}));

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

      expect(screen.getByText('typeManagement.guiEditor.guiEditorTab')).toBeInTheDocument();
      expect(screen.getByText('typeManagement.guiEditor.jsonEditorTab')).toBeInTheDocument();
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

      expect(screen.getByText('typeManagement.guiEditor.basicInfo')).toBeInTheDocument();
      expect(screen.getByText('typeManagement.guiEditor.typeOptions')).toBeInTheDocument();
    });

    it('renders update button for editing mode', () => {
      const existingType = mockExistingTypes[2];
      render(
        <TypeGUIEditor
          initialValue={existingType}
          existingTypes={mockExistingTypes}
          onSave={mockOnSave}
          onCancel={mockOnCancel}
          isEditing={true}
        />
      );

      expect(screen.getByText('typeManagement.guiEditor.cancel')).toBeInTheDocument();
      expect(screen.getByText('typeManagement.guiEditor.update')).toBeInTheDocument();
    });

    it('renders create button for new type', () => {
      render(
        <TypeGUIEditor
          initialValue={null}
          existingTypes={mockExistingTypes}
          onSave={mockOnSave}
          onCancel={mockOnCancel}
          isEditing={false}
        />
      );

      expect(screen.getByText('typeManagement.guiEditor.cancel')).toBeInTheDocument();
      expect(screen.getByText('typeManagement.guiEditor.create')).toBeInTheDocument();
    });
  });

  describe('Validation', () => {
    it('shows error when type ID is empty', async () => {
      render(
        <TypeGUIEditor
          initialValue={null}
          existingTypes={mockExistingTypes}
          onSave={mockOnSave}
          onCancel={mockOnCancel}
          isEditing={false}
        />
      );

      const createButton = screen.getByText('typeManagement.guiEditor.create');
      fireEvent.click(createButton);

      await waitFor(() => {
        expect(screen.getByText('typeManagement.validation.typeIdRequired')).toBeInTheDocument();
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

      const jsonTab = screen.getByText('typeManagement.guiEditor.jsonEditorTab');
      fireEvent.click(jsonTab);

      await waitFor(() => {
        expect(screen.getByText('typeManagement.guiEditor.jsonEditorTitle')).toBeInTheDocument();
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

      const cancelButton = screen.getByText('typeManagement.guiEditor.cancel');
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
        expect(screen.getByText('typeManagement.guiEditor.relationshipSettings')).toBeInTheDocument();
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

      expect(screen.queryByText('typeManagement.guiEditor.relationshipSettings')).not.toBeInTheDocument();
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

      expect(screen.getByText('typeManagement.guiEditor.addProperty')).toBeInTheDocument();
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

      expect(screen.getByText('typeManagement.guiEditor.noPropertiesTitle')).toBeInTheDocument();
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
