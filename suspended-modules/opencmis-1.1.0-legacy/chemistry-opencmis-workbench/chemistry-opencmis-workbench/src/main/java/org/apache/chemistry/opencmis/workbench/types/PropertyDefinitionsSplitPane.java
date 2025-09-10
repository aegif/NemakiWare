/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.chemistry.opencmis.workbench.types;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;

import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.commons.data.CmisExtensionElement;
import org.apache.chemistry.opencmis.commons.definitions.Choice;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDateTimeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDecimalDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyIntegerDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyStringDefinition;
import org.apache.chemistry.opencmis.commons.enums.DateTimeResolution;
import org.apache.chemistry.opencmis.commons.enums.DecimalPrecision;
import org.apache.chemistry.opencmis.workbench.ClientHelper;
import org.apache.chemistry.opencmis.workbench.WorkbenchScale;
import org.apache.chemistry.opencmis.workbench.model.ClientModel;
import org.apache.chemistry.opencmis.workbench.swing.ChoicesTree;
import org.apache.chemistry.opencmis.workbench.swing.CollectionRenderer;
import org.apache.chemistry.opencmis.workbench.swing.ExtensionsTree;
import org.apache.chemistry.opencmis.workbench.swing.InfoPanel;
import org.apache.chemistry.opencmis.workbench.swing.InfoTreePane;
import org.apache.chemistry.opencmis.workbench.swing.YesNoLabel;

public class PropertyDefinitionsSplitPane extends JSplitPane {

    private static final long serialVersionUID = 1L;

    public static final int ID_COLUMN = 1;

    private final ClientModel model;

    private PropertyDefinitionsTable propertyDefinitionsTable;
    private PropertyDefinitionInfoPanel propertyDefinitionPanel;

    public PropertyDefinitionsSplitPane(ClientModel model) {
        super(JSplitPane.HORIZONTAL_SPLIT);

        this.model = model;

        createGUI();
    }

    protected ClientModel getClientModel() {
        return model;
    }

    private void createGUI() {
        propertyDefinitionPanel = new PropertyDefinitionInfoPanel(model);
        propertyDefinitionsTable = new PropertyDefinitionsTable(propertyDefinitionPanel);

        setLeftComponent(new JScrollPane(propertyDefinitionsTable));
        setRightComponent(new JScrollPane(propertyDefinitionPanel));
        setDividerLocation(0.5);
        setOneTouchExpandable(true);
        setResizeWeight(0.5);
    }

    public void setType(ObjectType type) {
        propertyDefinitionsTable.setType(type);
    }

    static class PropertyDefinitionsTable extends JTable {

        private static final long serialVersionUID = 1L;

        private static final String[] COLUMN_NAMES = { "Name", "Id", "Description", "Local Namespace", "Local Name",
                "Query Name", "Type", "Cardinality", "Updatability", "Queryable", "Orderable", "Required", "Inherited",
                "Default Value", "Open Choice", "Choices" };
        private static final int[] COLUMN_WIDTHS = { 200, 200, 200, 200, 200, 200, 80, 80, 80, 50, 50, 50, 50, 200, 50,
                200 };

        private ObjectType type;
        private List<PropertyDefinition<?>> propertyDefintions;
        private final PropertyDefinitionInfoPanel propertyInfoPanel;

        public PropertyDefinitionsTable(final PropertyDefinitionInfoPanel propertyInfoPanel) {
            this.propertyInfoPanel = propertyInfoPanel;

            setDefaultRenderer(Collection.class, new CollectionRenderer());
            setModel(new PropertyDefinitionTableModel(this));

            setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            setAutoResizeMode(AUTO_RESIZE_OFF);
            setAutoCreateRowSorter(true);

            for (int i = 0; i < COLUMN_WIDTHS.length; i++) {
                TableColumn column = getColumnModel().getColumn(i);
                column.setPreferredWidth(WorkbenchScale.scaleInt(COLUMN_WIDTHS[i]));
            }

            setRowHeight((int) (getFontMetrics(getFont()).getHeight() * 1.1));

            final JPopupMenu popup = new JPopupMenu();
            JMenuItem menuItem = new JMenuItem("Copy table to clipboard");
            popup.add(menuItem);

            menuItem.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    ClientHelper.copyTableToClipboard(PropertyDefinitionsTable.this, false);
                }
            });

            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    maybeShowPopup(e);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    maybeShowPopup(e);
                }

                private void maybeShowPopup(MouseEvent e) {
                    if (e.isPopupTrigger()) {
                        popup.show(e.getComponent(), e.getX(), e.getY());
                    }
                }
            });

            getSelectionModel().addListSelectionListener(new ListSelectionListener() {
                @Override
                public void valueChanged(ListSelectionEvent e) {
                    if (e.getValueIsAdjusting()) {
                        return;
                    }

                    int row = getSelectedRow();
                    if (row > -1) {
                        String propId = getModel().getValueAt(getRowSorter().convertRowIndexToModel(row), ID_COLUMN)
                                .toString();
                        propertyInfoPanel.setPropertyDefinition(type.getPropertyDefinitions().get(propId));
                    }
                }
            });

            setFillsViewportHeight(true);
        }

        public void setType(ObjectType type) {
            this.type = type;

            if (type != null && type.getPropertyDefinitions() != null) {
                propertyDefintions = new ArrayList<PropertyDefinition<?>>();
                for (PropertyDefinition<?> propDef : type.getPropertyDefinitions().values()) {
                    propertyDefintions.add(propDef);
                }

                Collections.sort(propertyDefintions, new Comparator<PropertyDefinition<?>>() {
                    @Override
                    public int compare(PropertyDefinition<?> pd1, PropertyDefinition<?> pd2) {
                        return pd1.getId().compareTo(pd2.getId());
                    }
                });
            } else {
                propertyDefintions = null;
            }

            ((AbstractTableModel) getModel()).fireTableDataChanged();

            if (type != null && type.getPropertyDefinitions() != null && !type.getPropertyDefinitions().isEmpty()) {
                getSelectionModel().setSelectionInterval(0, 0);
            } else {
                propertyInfoPanel.setPropertyDefinition(null);
            }
        }

        public ObjectType getType() {
            return type;
        }

        public List<PropertyDefinition<?>> getPropertyDefinitions() {
            return propertyDefintions;
        }

        static class PropertyDefinitionTableModel extends AbstractTableModel {

            private static final long serialVersionUID = 1L;

            private final PropertyDefinitionsTable table;

            public PropertyDefinitionTableModel(PropertyDefinitionsTable table) {
                this.table = table;
            }

            @Override
            public String getColumnName(int columnIndex) {
                return COLUMN_NAMES[columnIndex];
            }

            @Override
            public int getColumnCount() {
                return COLUMN_NAMES.length;
            }

            @Override
            public int getRowCount() {
                if (table.getPropertyDefinitions() == null) {
                    return 0;
                }

                return table.getPropertyDefinitions().size();
            }

            @Override
            public Object getValueAt(int rowIndex, int columnIndex) {
                PropertyDefinition<?> propDef = table.getPropertyDefinitions().get(rowIndex);

                switch (columnIndex) {
                case 0:
                    return propDef.getDisplayName();
                case 1:
                    return propDef.getId();
                case 2:
                    return propDef.getDescription();
                case 3:
                    return propDef.getLocalNamespace();
                case 4:
                    return propDef.getLocalName();
                case 5:
                    return propDef.getQueryName();
                case 6:
                    return propDef.getPropertyType();
                case 7:
                    return propDef.getCardinality();
                case 8:
                    return propDef.getUpdatability();
                case 9:
                    return propDef.isQueryable();
                case 10:
                    return propDef.isOrderable();
                case 11:
                    return propDef.isRequired();
                case 12:
                    return propDef.isInherited();
                case 13:
                    return propDef.getDefaultValue();
                case 14:
                    return propDef.isOpenChoice();
                case 15:
                    return propDef.getChoices();
                default:
                }

                return null;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                if ((columnIndex == 13) || (columnIndex == 15)) {
                    return Collection.class;
                }

                return super.getColumnClass(columnIndex);
            }
        }
    }

    static class PropertyDefinitionInfoPanel extends InfoPanel {

        private static final long serialVersionUID = 1L;

        private JTextField nameField;
        private JTextField descriptionField;
        private JTextField idField;
        private JTextField localNamespaceField;
        private JTextField localNameField;
        private JTextField queryNameField;
        private JTextField propertyTypeField;
        private JTextField cardinalityField;
        private JTextField updatabilityField;
        private YesNoLabel queryableLabel;
        private YesNoLabel orderableLabel;
        private YesNoLabel requiredLabel;
        private YesNoLabel inheritedLabel;
        private InfoList defaultValueField;
        private YesNoLabel openChoiceLabel;
        @SuppressWarnings("rawtypes")
        private InfoTreePane<List<Choice>> choicesTree;
        private JTextField minField;
        private JTextField maxField;
        private JTextField maxLengthField;
        private JTextField precisionField;
        private JTextField dateTimeResolutionField;
        private InfoTreePane<List<CmisExtensionElement>> extensionsTree;

        public PropertyDefinitionInfoPanel(ClientModel model) {
            super(model);
            createGUI();
        }

        @SuppressWarnings({ "unchecked", "rawtypes" })
        public void setPropertyDefinition(PropertyDefinition<?> propDef) {
            if (propDef != null) {
                nameField.setText(propDef.getDisplayName());
                descriptionField.setText(propDef.getDescription());
                idField.setText(propDef.getId());
                localNamespaceField.setText(propDef.getLocalNamespace());
                localNameField.setText(propDef.getLocalName());
                queryNameField.setText(propDef.getQueryName());

                propertyTypeField
                        .setText(propDef.getPropertyType() == null ? "???" : propDef.getPropertyType().value());
                cardinalityField.setText(propDef.getCardinality() == null ? "???" : propDef.getCardinality().value());
                updatabilityField
                        .setText(propDef.getUpdatability() == null ? "???" : propDef.getUpdatability().value());
                queryableLabel.setValue(is(propDef.isQueryable()));
                orderableLabel.setValue(is(propDef.isOrderable()));
                requiredLabel.setValue(is(propDef.isRequired()));
                inheritedLabel.setValue(is(propDef.isInherited()));
                defaultValueField.setList(propDef.getDefaultValue());
                openChoiceLabel.setValue(is(propDef.isOpenChoice()));

                List choices = (List) propDef.getChoices();
                choicesTree.setData(choices == null || choices.isEmpty() ? null : choices);

                if (propDef instanceof PropertyIntegerDefinition) {
                    maxLengthField.setText("");
                    BigInteger min = ((PropertyIntegerDefinition) propDef).getMinValue();
                    minField.setText(min == null ? "" : min.toString());
                    BigInteger max = ((PropertyIntegerDefinition) propDef).getMaxValue();
                    maxField.setText(max == null ? "" : max.toString());
                    precisionField.setText("");
                    dateTimeResolutionField.setText("");
                } else if (propDef instanceof PropertyDecimalDefinition) {
                    maxLengthField.setText("");
                    BigDecimal min = ((PropertyDecimalDefinition) propDef).getMinValue();
                    minField.setText(min == null ? "" : min.toPlainString());
                    BigDecimal max = ((PropertyDecimalDefinition) propDef).getMaxValue();
                    maxField.setText(max == null ? "" : max.toPlainString());
                    DecimalPrecision precision = ((PropertyDecimalDefinition) propDef).getPrecision();
                    precisionField.setText(precision == null ? "" : precision.value().toString() + "-bit precision");
                    dateTimeResolutionField.setText("");
                } else if (propDef instanceof PropertyStringDefinition) {
                    BigInteger maxLen = ((PropertyStringDefinition) propDef).getMaxLength();
                    maxLengthField.setText(maxLen == null ? "" : maxLen.toString());
                    minField.setText("");
                    maxField.setText("");
                    precisionField.setText("");
                    dateTimeResolutionField.setText("");
                } else if (propDef instanceof PropertyDateTimeDefinition) {
                    maxLengthField.setText("");
                    minField.setText("");
                    maxField.setText("");
                    precisionField.setText("");
                    DateTimeResolution dateTimeResolution = ((PropertyDateTimeDefinition) propDef)
                            .getDateTimeResolution();
                    dateTimeResolutionField.setText(dateTimeResolution == null ? "" : dateTimeResolution.value());
                } else {
                    minField.setText("");
                    maxField.setText("");
                    maxLengthField.setText("");
                    precisionField.setText("");
                    dateTimeResolutionField.setText("");
                }

                extensionsTree.setData(propDef.getExtensions());
            } else {
                nameField.setText("");
                descriptionField.setText("");
                idField.setText("");
                localNamespaceField.setText("");
                localNameField.setText("");
                queryNameField.setText("");
                propertyTypeField.setText("");
                cardinalityField.setText("");
                updatabilityField.setText("");
                queryableLabel.setValue(false);
                orderableLabel.setValue(false);
                requiredLabel.setValue(false);
                inheritedLabel.setValue(false);
                defaultValueField.removeAll();
                openChoiceLabel.setValue(false);
                choicesTree.setData(null);
                maxLengthField.setText("");
                minField.setText("");
                maxField.setText("");
                precisionField.setText("");
                dateTimeResolutionField.setText("");
                extensionsTree.setData(null);
            }

            regenerateGUI();
        }

        private boolean is(Boolean b) {
            if (b == null) {
                return false;
            }

            return b.booleanValue();
        }

        @SuppressWarnings("rawtypes")
        private void createGUI() {
            setupGUI();

            nameField = addLine("Name:", true);
            descriptionField = addLine("Description:");
            idField = addLine("Id:");
            localNamespaceField = addLine("Local Namespace:");
            localNameField = addLine("Local Name:");
            queryNameField = addLine("Query Name:");
            propertyTypeField = addLine("Property Type:");
            cardinalityField = addLine("Cardinality:");
            updatabilityField = addLine("Updatability:");
            queryableLabel = addYesNoLabel("Queryable:");
            orderableLabel = addYesNoLabel("Orderable:");
            requiredLabel = addYesNoLabel("Required:");
            inheritedLabel = addYesNoLabel("Inherited:");
            defaultValueField = addComponent("Default Value:", new InfoList());
            openChoiceLabel = addYesNoLabel("Open Choice:");
            choicesTree = addComponent("Choices:", new InfoTreePane<List<Choice>>(new ChoicesTree()));
            maxLengthField = addLine("Max Length:");
            minField = addLine("Min:");
            maxField = addLine("Max:");
            precisionField = addLine("Precision:");
            dateTimeResolutionField = addLine("DateTime Resolution:");
            extensionsTree = addComponent("Extensions:", new InfoTreePane<List<CmisExtensionElement>>(
                    new ExtensionsTree()));

            regenerateGUI();
        }
    }
}
