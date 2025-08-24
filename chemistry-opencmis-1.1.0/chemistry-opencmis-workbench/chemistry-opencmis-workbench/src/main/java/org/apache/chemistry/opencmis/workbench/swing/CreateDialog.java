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
package org.apache.chemistry.opencmis.workbench.swing;

import java.awt.BorderLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.math.BigDecimal;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.WindowConstants;

import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.workbench.ClientHelper;
import org.apache.chemistry.opencmis.workbench.WorkbenchScale;
import org.apache.chemistry.opencmis.workbench.model.ClientModel;

public abstract class CreateDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    private final ClientModel model;
    private final JPanel panel;
    private final JPanel mandatoryOrOnCreatePropertiesPanel;
    private final JPanel actionPanel;
    private final Map<String, JComponent> mandatoryOrOnCreateProperties;

    public CreateDialog(Frame owner, String title, ClientModel model) {
        super(owner, title, true);
        this.model = model;

        setLayout(new BorderLayout());
        panel = new JPanel(new GridBagLayout());
        panel.setBorder(WorkbenchScale.scaleBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10)));
        add(panel, BorderLayout.CENTER);

        mandatoryOrOnCreateProperties = new HashMap<String, JComponent>();
        mandatoryOrOnCreatePropertiesPanel = new JPanel(new GridBagLayout());
        mandatoryOrOnCreatePropertiesPanel.setBorder(WorkbenchScale.scaleBorder(BorderFactory.createCompoundBorder(
                WorkbenchScale.scaleBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0)),
                WorkbenchScale.scaleBorder(BorderFactory.createTitledBorder("Mandatory or OnCreate properties")))));

        actionPanel = new JPanel();
        createRow(actionPanel, 10);
    }

    protected ClientModel getClientModel() {
        return model;
    }

    protected final void createRow(String label, JComponent comp, int row) {
        createRow(panel, label, comp, row);
    }

    protected final void createActionRow(String label, JComponent comp, int row) {
        createRow(actionPanel, label, comp, row);
    }

    protected final void createRow(JPanel panel, String label, JComponent comp, int row) {
        JLabel textLabel = new JLabel(label);
        textLabel.setLabelFor(comp);

        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.LINE_START;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0;
        c.gridy = row;
        panel.add(textLabel, c);
        c.gridx = 1;
        panel.add(comp, c);
    }

    protected final void createRow(JComponent comp, int row) {
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.LINE_START;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 2;
        panel.add(comp, c);
    }

    public final void showDialog() {
        panel.invalidate();

        ClientHelper.installEscapeBinding(this, getRootPane(), true);

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        pack();
        setLocationRelativeTo(null);
        setVisible(true);
    }

    protected final ObjectTypeItem[] getTypes(String rootTypeId) {
        List<ObjectType> types = model.getTypesAsList(rootTypeId, true);

        ObjectTypeItem[] result = new ObjectTypeItem[types.size()];

        int i = 0;
        for (final ObjectType type : types) {
            result[i] = new ObjectTypeItem() {
                @Override
                public ObjectType getObjectType() {
                    return type;
                }

                @Override
                public String toString() {
                    return type.getDisplayName() + " (" + type.getId() + ")";
                }
            };

            i++;
        }

        return result;
    }

    protected final void updateMandatoryOrOnCreateFields(TypeDefinition type) {
        mandatoryOrOnCreateProperties.clear();
        mandatoryOrOnCreatePropertiesPanel.removeAll();

        final Map<String, PropertyDefinition<?>> propertyDefinitions = type.getPropertyDefinitions();
        if (propertyDefinitions != null) {
            int row = 0;
            for (PropertyDefinition<?> definition : propertyDefinitions.values()) {
                if ((Boolean.TRUE.equals(definition.isRequired()) || Updatability.ONCREATE.equals(definition
                        .getUpdatability()))
                        && !(PropertyIds.NAME.equals(definition.getId())
                                || PropertyIds.OBJECT_TYPE_ID.equals(definition.getId())
                                || PropertyIds.SOURCE_ID.equals(definition.getId()) || PropertyIds.TARGET_ID
                                    .equals(definition.getId()))) {
                    JComponent child = createPropertyComponent(definition);
                    mandatoryOrOnCreateProperties.put(definition.getId(), child);
                    createRow(mandatoryOrOnCreatePropertiesPanel, definition.getDisplayName() + ":", child, row);
                    row++;
                }
            }
        }

        if (mandatoryOrOnCreatePropertiesPanel.getComponents().length > 0) {
            createRow(mandatoryOrOnCreatePropertiesPanel, 9);
        } else {
            panel.remove(mandatoryOrOnCreatePropertiesPanel);
        }

        pack();
        repaint();
    }

    protected JComponent createPropertyComponent(PropertyDefinition<?> definition) {
        final PropertyType propertyType = definition.getPropertyType();
        JComponent result;
        switch (propertyType) {
        case BOOLEAN:
            result = new JCheckBox();
            break;
        case INTEGER:
            DecimalFormat intFormat = new DecimalFormat("#,##0");
            intFormat.setParseBigDecimal(true);
            intFormat.setParseIntegerOnly(true);
            result = new JFormattedTextField(intFormat);
            ((JFormattedTextField) result).setColumns(50);
            break;
        case DECIMAL:
            DecimalFormat decFormat = new DecimalFormat("#,##0.#############################");
            decFormat.setParseBigDecimal(true);
            result = new JFormattedTextField(decFormat);
            ((JFormattedTextField) result).setColumns(50);
            break;
        case DATETIME:
            result = new JFormattedTextField(DateFormat.getDateTimeInstance());
            ((JFormattedTextField) result).setColumns(50);
            break;
        default:
            result = new JTextField("", 50);
            break;
        }

        return result;
    }

    protected Map<String, Object> getMandatoryOrOnCreatePropertyValues(TypeDefinition type) {
        if (mandatoryOrOnCreateProperties.isEmpty()) {
            return null;
        }

        Map<String, Object> result = new HashMap<String, Object>();

        for (Map.Entry<String, JComponent> component : mandatoryOrOnCreateProperties.entrySet()) {
            PropertyDefinition<?> propDef = type.getPropertyDefinitions().get(component.getKey());

            Object value = null;
            if (component.getValue() instanceof JFormattedTextField) {
                value = ((JFormattedTextField) component.getValue()).getValue();

                if (value != null) {
                    if (propDef.getPropertyType() == PropertyType.INTEGER) {
                        value = ((BigDecimal) value).toBigIntegerExact();
                    }
                }
            } else if (component.getValue() instanceof JTextField) {
                value = ((JTextField) component.getValue()).getText();
            } else if (component.getValue() instanceof JCheckBox) {
                value = ((JCheckBox) component.getValue()).isSelected();
            }

            if (value != null) {
                result.put(component.getKey(), value);
            }
        }

        return result;
    }

    public interface ObjectTypeItem {
        ObjectType getObjectType();
    }
}
