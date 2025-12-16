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
package org.apache.chemistry.opencmis.workbench;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.Format;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFormattedTextField;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerListModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.enums.Action;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.workbench.PropertyEditorFrame.UpdateStatus.StatusFlag;
import org.apache.chemistry.opencmis.workbench.icons.AddIcon;
import org.apache.chemistry.opencmis.workbench.icons.DownIcon;
import org.apache.chemistry.opencmis.workbench.icons.RemoveIcon;
import org.apache.chemistry.opencmis.workbench.icons.UpIcon;
import org.apache.chemistry.opencmis.workbench.model.ClientModel;
import org.apache.chemistry.opencmis.workbench.worker.LoadFolderWorker;
import org.apache.chemistry.opencmis.workbench.worker.LoadObjectWorker;

/**
 * Simple property editor.
 */
public class PropertyEditorFrame extends JFrame {

    private static final long serialVersionUID = 1L;

    private static final String WINDOW_TITLE = "Property Editor";
    private static final Icon ICON_ADD = new AddIcon(ClientHelper.ICON_BUTTON_ICON_SIZE,
            ClientHelper.ICON_BUTTON_ICON_SIZE);

    private final ClientModel model;
    private final CmisObject object;
    private List<PropertyInputPanel> propertyPanels;

    public PropertyEditorFrame(final ClientModel model, final CmisObject object) {
        super();

        this.model = model;
        this.object = object;

        createGUI();
    }

    private void createGUI() {
        setTitle(WINDOW_TITLE);
        setIconImages(ClientHelper.getCmisIconImages());

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        setPreferredSize(WorkbenchScale.scaleDimension(new Dimension((int) (screenSize.getWidth() / 2),
                (int) (screenSize.getHeight() / 1.5))));
        setMinimumSize(WorkbenchScale.scaleDimension(new Dimension(300, 120)));

        setLayout(new BorderLayout());

        final JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.PAGE_AXIS));

        final Font labelFont = UIManager.getFont("Label.font");
        final Font boldFont = labelFont.deriveFont(Font.BOLD, labelFont.getSize2D() * 1.2f);

        final JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        topPanel.setBorder(WorkbenchScale.scaleBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5)));
        final JLabel nameLabel = new JLabel(object.getName());
        nameLabel.setFont(boldFont);
        topPanel.add(nameLabel);
        topPanel.add(new JLabel(object.getId()));
        add(topPanel, BorderLayout.PAGE_START);

        JScrollPane scrollPane = new JScrollPane(panel);
        add(scrollPane, BorderLayout.CENTER);

        propertyPanels = new ArrayList<PropertyEditorFrame.PropertyInputPanel>();

        int position = 0;

        // primary type
        for (PropertyDefinition<?> propDef : object.getType().getPropertyDefinitions().values()) {
            boolean isUpdatable = (propDef.getUpdatability() == Updatability.READWRITE)
                    || (propDef.getUpdatability() == Updatability.WHENCHECKEDOUT && object
                            .hasAllowableAction(Action.CAN_CHECK_IN));

            if (isUpdatable) {
                PropertyInputPanel propertyPanel = new PropertyInputPanel(propDef, object.getPropertyValue(propDef
                        .getId()), position++);

                propertyPanels.add(propertyPanel);
                panel.add(propertyPanel);
            }
        }

        // secondary types
        if (object.getSecondaryTypes() != null) {
            for (ObjectType secType : object.getSecondaryTypes()) {
                if (secType.getPropertyDefinitions() != null) {
                    for (PropertyDefinition<?> propDef : secType.getPropertyDefinitions().values()) {
                        boolean isUpdatable = (propDef.getUpdatability() == Updatability.READWRITE)
                                || (propDef.getUpdatability() == Updatability.WHENCHECKEDOUT && object
                                        .hasAllowableAction(Action.CAN_CHECK_IN));

                        if (isUpdatable) {
                            PropertyInputPanel propertyPanel = new PropertyInputPanel(propDef,
                                    object.getPropertyValue(propDef.getId()), position++);

                            propertyPanels.add(propertyPanel);
                            panel.add(propertyPanel);
                        }
                    }
                }
            }
        }

        final JPanel updateButtonPanel = new JPanel();
        updateButtonPanel.setLayout(new BoxLayout(updateButtonPanel, BoxLayout.PAGE_AXIS));
        updateButtonPanel.setBorder(WorkbenchScale.scaleBorder(BorderFactory.createEmptyBorder(0, 3, 3, 3)));

        JButton updateButton = new JButton("Update");
        updateButton.setBorder(WorkbenchScale.scaleBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5)));
        updateButton.setDefaultCapable(true);
        updateButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                if (doUpdate()) {
                    dispose();
                }
            }
        });

        int height = 30;
        height = Math.max(height, getFontMetrics(updateButton.getFont()).getHeight() + updateButton.getInsets().top
                + updateButton.getInsets().bottom);
        updateButton.setMaximumSize(WorkbenchScale.scaleDimension(new Dimension(Short.MAX_VALUE, height)));
        updateButtonPanel.add(updateButton);

        add(updateButtonPanel, BorderLayout.PAGE_END);

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        pack();
        setLocationRelativeTo(null);
        setVisible(true);
    }

    /**
     * Performs the update.
     */
    private boolean doUpdate() {
        try {
            setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

            Map<String, Object> properties = new HashMap<String, Object>();
            for (PropertyInputPanel propertyPanel : propertyPanels) {
                if (propertyPanel.includeInUpdate()) {
                    properties.put(propertyPanel.getId(), propertyPanel.getValue());
                }
            }

            if (properties.isEmpty()) {
                return false;
            }

            ObjectId newId = object.updateProperties(properties, false);

            if ((newId != null) && newId.getId().equals(model.getCurrentObject().getId())) {
                LoadObjectWorker.reloadObject(this, model);
                LoadFolderWorker.reloadFolder(getOwner(), model);
            }

            return true;
        } catch (Exception ex) {
            ClientHelper.showError(this, ex);
            return false;
        } finally {
            setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
        }
    }

    interface UpdateStatus {
        enum StatusFlag {
            DONT_CHANGE, UPDATE, UNSET
        }

        void setStatus(StatusFlag status);

        StatusFlag getStatus();
    }

    interface MultivalueManager {
        void addNewValue();

        void removeValue(int pos);

        void moveUp(int pos);

        void moveDown(int pos);
    }

    /**
     * Property input panel.
     */
    public static class PropertyInputPanel extends JPanel implements UpdateStatus, MultivalueManager {
        private static final long serialVersionUID = 1L;

        private static final Color BACKGROUND1 = UIManager.getColor("Table:\"Table.cellRenderer\".background");
        private static final Color BACKGROUND2 = UIManager.getColor("Table.alternateRowColor");
        private static final Color LINE = new Color(0xB8, 0xB8, 0xB8);

        private final PropertyDefinition<?> propDef;
        private final Object value;
        private final Color bgColor;
        private JComboBox<String> changeBox;
        private List<JComponent> valueComponents;

        public PropertyInputPanel(PropertyDefinition<?> propDef, Object value, int position) {
            super();
            this.propDef = propDef;
            this.value = value;
            bgColor = (position % 2 == 0 ? BACKGROUND1 : BACKGROUND2);
            createGUI();
        }

        private void createGUI() {
            setLayout(new BoxLayout(this, BoxLayout.PAGE_AXIS));

            setBackground(bgColor);
            setBorder(WorkbenchScale.scaleBorder(BorderFactory.createCompoundBorder(
                    WorkbenchScale.scaleBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, LINE)),
                    WorkbenchScale.scaleBorder(BorderFactory.createEmptyBorder(10, 5, 10, 5)))));

            Font labelFont = UIManager.getFont("Label.font");
            Font boldFont = labelFont.deriveFont(Font.BOLD, labelFont.getSize2D() * 1.2f);

            JPanel titlePanel = new JPanel();
            titlePanel.setLayout(new BorderLayout());
            titlePanel.setBackground(bgColor);
            titlePanel.setToolTipText("<html><b>"
                    + propDef.getPropertyType().value()
                    + "</b> ("
                    + propDef.getCardinality().value()
                    + " value)"
                    + (propDef.getDescription() != null ? "<br>"
                            + ClientHelper.encodeHtml(new StringBuilder(propDef.getDescription().length() + 16),
                                    propDef.getDescription()) : ""));
            add(titlePanel);

            JPanel namePanel = new JPanel();
            namePanel.setLayout(new BoxLayout(namePanel, BoxLayout.Y_AXIS));
            namePanel.setBackground(bgColor);
            JLabel displayNameLabel = new JLabel(propDef.getDisplayName());
            displayNameLabel.setFont(boldFont);
            namePanel.add(displayNameLabel);
            JLabel idLabel = new JLabel(propDef.getId());
            namePanel.add(idLabel);

            titlePanel.add(namePanel, BorderLayout.LINE_START);

            changeBox = new JComboBox<String>(new String[] { "Don't change     ", "Update    ", "Unset     " });
            titlePanel.add(changeBox, BorderLayout.LINE_END);

            valueComponents = new ArrayList<JComponent>();
            if (propDef.getCardinality() == Cardinality.SINGLE) {
                JComponent valueField = createInputField(value);
                valueComponents.add(valueField);
                add(valueField);
            } else {
                if (value instanceof List<?>) {
                    for (Object v : (List<?>) value) {
                        JComponent valueField = new MultiValuePropertyInputField(createInputField(v), this, bgColor);
                        valueComponents.add(valueField);
                        add(valueField);
                    }
                }

                JPanel addPanel = new JPanel(new BorderLayout());
                addPanel.setBackground(bgColor);
                JButton addButton = new JButton(ICON_ADD);
                addButton.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        addNewValue();
                        setStatus(StatusFlag.UPDATE);
                    }
                });
                addPanel.add(addButton, BorderLayout.LINE_END);
                add(addPanel);

                updatePositions();
            }

            setMaximumSize(new Dimension(Short.MAX_VALUE, getPreferredSize().height));
        }

        private JComponent createInputField(Object value) {
            switch (propDef.getPropertyType()) {
            case INTEGER:
                return new IntegerPropertyInputField(value, this, bgColor);
            case DECIMAL:
                return new DecimalPropertyInputField(value, this, bgColor);
            case DATETIME:
                return new DateTimePropertyInputField(value, this, bgColor);
            case BOOLEAN:
                return new BooleanPropertyInputField(value, this, bgColor);
            default:
                return new StringPropertyInputField(value, this, bgColor);
            }
        }

        private void updatePositions() {
            int n = valueComponents.size();
            for (int i = 0; i < n; i++) {
                MultiValuePropertyInputField comp = (MultiValuePropertyInputField) valueComponents.get(i);
                comp.updatePosition(i, i + 1 == n);
            }
        }

        @Override
        public void addNewValue() {
            JComponent valueField = new MultiValuePropertyInputField(createInputField(null), this, bgColor);
            valueComponents.add(valueField);
            add(valueField, getComponentCount() - 1);

            updatePositions();
            setStatus(StatusFlag.UPDATE);

            revalidate();
        }

        @Override
        public void removeValue(int pos) {
            remove(valueComponents.remove(pos));

            updatePositions();
            setStatus(StatusFlag.UPDATE);

            revalidate();
        }

        @Override
        public void moveUp(int pos) {
            JComponent comp = valueComponents.get(pos);
            Collections.swap(valueComponents, pos, pos - 1);

            remove(comp);
            add(comp, pos);

            updatePositions();
            setStatus(StatusFlag.UPDATE);

            revalidate();
        }

        @Override
        public void moveDown(int pos) {
            JComponent comp = valueComponents.get(pos);
            Collections.swap(valueComponents, pos, pos + 1);

            remove(comp);
            add(comp, pos + 2);

            updatePositions();
            setStatus(StatusFlag.UPDATE);

            revalidate();
        }

        public String getId() {
            return propDef.getId();
        }

        public Object getValue() {
            Object result = null;

            if (getStatus() == StatusFlag.UPDATE) {
                try {
                    if (propDef.getCardinality() == Cardinality.SINGLE) {
                        result = ((PropertyValue) valueComponents.get(0)).getPropertyValue();
                    } else {
                        List<Object> list = new ArrayList<Object>();
                        for (JComponent comp : valueComponents) {
                            list.add(((PropertyValue) comp).getPropertyValue());
                        }
                        result = list;
                    }
                } catch (Exception ex) {
                    ClientHelper.showError(this, ex);
                }
            }

            return result;
        }

        public boolean includeInUpdate() {
            return getStatus() != StatusFlag.DONT_CHANGE;
        }

        @Override
        public void setStatus(StatusFlag status) {
            switch (status) {
            case UPDATE:
                changeBox.setSelectedIndex(1);
                break;
            case UNSET:
                changeBox.setSelectedIndex(2);
                break;
            default:
                changeBox.setSelectedIndex(0);
            }
        }

        @Override
        public StatusFlag getStatus() {
            switch (changeBox.getSelectedIndex()) {
            case 1:
                return StatusFlag.UPDATE;
            case 2:
                return StatusFlag.UNSET;
            default:
                return StatusFlag.DONT_CHANGE;
            }
        }

        @Override
        public Dimension getMaximumSize() {
            Dimension size = getPreferredSize();
            size.width = Short.MAX_VALUE;
            return size;
        }
    }

    /**
     * Property value interface.
     */
    public interface PropertyValue {
        Object getPropertyValue() throws Exception;
    }

    /**
     * String property.
     */
    public static class StringPropertyInputField extends JTextField implements PropertyValue {
        private static final long serialVersionUID = 1L;

        public StringPropertyInputField(final Object value, final UpdateStatus status, final Color bgColor) {
            super(value == null ? "" : value.toString());

            addKeyListener(new KeyListener() {
                @Override
                public void keyTyped(KeyEvent e) {
                }

                @Override
                public void keyReleased(KeyEvent e) {
                    status.setStatus(StatusFlag.UPDATE);
                }

                @Override
                public void keyPressed(KeyEvent e) {
                }
            });
        }

        @Override
        public Object getPropertyValue() {
            return getText();
        }
    }

    /**
     * Formatted property.
     */
    public static class AbstractFormattedPropertyInputField extends JFormattedTextField implements PropertyValue {
        private static final long serialVersionUID = 1L;

        public AbstractFormattedPropertyInputField(final Object value, final Format format, final UpdateStatus status,
                final Color bgColor) {
            super(format);
            if (value != null) {
                setValue(value);
            }

            addKeyListener(new KeyListener() {
                @Override
                public void keyTyped(KeyEvent e) {
                }

                @Override
                public void keyReleased(KeyEvent e) {
                    status.setStatus(StatusFlag.UPDATE);
                }

                @Override
                public void keyPressed(KeyEvent e) {
                }
            });
        }

        @Override
        public Object getPropertyValue() throws ParseException {
            commitEdit();
            return getValue();
        }
    }

    /**
     * Integer property.
     */
    public static class IntegerPropertyInputField extends AbstractFormattedPropertyInputField {
        private static final long serialVersionUID = 1L;

        public IntegerPropertyInputField(final Object value, final UpdateStatus status, final Color bgColor) {
            super(value, createFormat(), status, bgColor);
            setHorizontalAlignment(SwingConstants.RIGHT);
        }

        private static DecimalFormat createFormat() {
            DecimalFormat result = new DecimalFormat("#,##0");
            result.setParseBigDecimal(true);
            result.setParseIntegerOnly(true);
            return result;
        }

        @Override
        public Object getPropertyValue() {
            return ((BigDecimal) super.getValue()).toBigIntegerExact();
        }
    }

    /**
     * Decimal property.
     */
    public static class DecimalPropertyInputField extends AbstractFormattedPropertyInputField {
        private static final long serialVersionUID = 1L;

        public DecimalPropertyInputField(final Object value, final UpdateStatus status, final Color bgColor) {
            super(value, createFormat(), status, bgColor);
            setHorizontalAlignment(SwingConstants.RIGHT);
        }

        private static DecimalFormat createFormat() {
            DecimalFormat result = new DecimalFormat("#,##0.#############################");
            result.setParseBigDecimal(true);
            return result;
        }
    }

    /**
     * Boolean property.
     */
    public static class BooleanPropertyInputField extends JComboBox<Boolean> implements PropertyValue {
        private static final long serialVersionUID = 1L;

        public BooleanPropertyInputField(final Object value, final UpdateStatus status, final Color bgColor) {
            super(new Boolean[] { Boolean.TRUE, Boolean.FALSE });
            setSelectedItem(value == null ? true : value);

            addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    status.setStatus(StatusFlag.UPDATE);
                }
            });
        }

        @Override
        public Boolean getPropertyValue() {
            return (Boolean) getSelectedItem();
        }
    }

    /**
     * DateTime property.
     */
    public static class DateTimePropertyInputField extends JPanel implements PropertyValue {
        private static final long serialVersionUID = 1L;

        private static final String[] MONTH_STRINGS;

        static {
            String[] months = new java.text.DateFormatSymbols().getMonths();
            int lastIndex = months.length - 1;

            if (months[lastIndex] == null || months[lastIndex].length() <= 0) {
                String[] monthStrings = new String[lastIndex];
                System.arraycopy(months, 0, monthStrings, 0, lastIndex);
                MONTH_STRINGS = monthStrings;
            } else {
                MONTH_STRINGS = months;
            }
        }

        private final SpinnerNumberModel day;
        private final SpinnerListModel month;
        private final SpinnerNumberModel year;
        private final SpinnerNumberModel hour;
        private final SpinnerNumberModel min;
        private final SpinnerNumberModel sec;
        private final TimeZone timezone;

        public DateTimePropertyInputField(final Object value, final UpdateStatus status, final Color bgColor) {
            setLayout(new FlowLayout(FlowLayout.LEFT, 0, 0));
            setBackground(bgColor);

            GregorianCalendar cal = (value == null ? new GregorianCalendar() : (GregorianCalendar) value);
            timezone = cal.getTimeZone();

            day = new SpinnerNumberModel(cal.get(Calendar.DATE), 1, 31, 1);
            addSpinner(new JSpinner(day), status);

            month = new SpinnerListModel(MONTH_STRINGS);
            month.setValue(MONTH_STRINGS[cal.get(Calendar.MONTH)]);
            JSpinner monthSpinner = new JSpinner(month);
            JComponent editor = monthSpinner.getEditor();
            if (editor instanceof JSpinner.DefaultEditor) {
                JFormattedTextField tf = ((JSpinner.DefaultEditor) editor).getTextField();
                tf.setColumns(6);
                tf.setHorizontalAlignment(SwingConstants.RIGHT);
            }
            addSpinner(monthSpinner, status);

            year = new SpinnerNumberModel(cal.get(Calendar.YEAR), 0, 9999, 1);
            JSpinner yearSpinner = new JSpinner(year);
            yearSpinner.setEditor(new JSpinner.NumberEditor(yearSpinner, "#"));
            yearSpinner.getEditor().setBackground(bgColor);
            addSpinner(yearSpinner, status);

            add(new JLabel("  "));

            hour = new SpinnerNumberModel(cal.get(Calendar.HOUR_OF_DAY), 0, 23, 1);
            JSpinner hourSpinner = new JSpinner(hour);
            addSpinner(hourSpinner, status);

            add(new JLabel(":"));

            min = new SpinnerNumberModel(cal.get(Calendar.MINUTE), 0, 59, 1);
            JSpinner minSpinner = new JSpinner(min);
            addSpinner(minSpinner, status);

            add(new JLabel(":"));

            sec = new SpinnerNumberModel(cal.get(Calendar.SECOND), 0, 59, 1);
            JSpinner secSpinner = new JSpinner(sec);
            addSpinner(secSpinner, status);

            add(new JLabel(" " + timezone.getDisplayName(true, TimeZone.SHORT)));
        }

        private void addSpinner(final JSpinner spinner, final UpdateStatus status) {
            spinner.addChangeListener(new ChangeListener() {
                @Override
                public void stateChanged(ChangeEvent e) {
                    status.setStatus(StatusFlag.UPDATE);
                }
            });

            add(spinner);
        }

        @Override
        public Object getPropertyValue() {
            GregorianCalendar result = new GregorianCalendar();

            result.setTimeZone(timezone);

            result.set(Calendar.YEAR, year.getNumber().intValue());
            int mi = 0;
            String ms = month.getValue().toString();
            for (int i = 0; i < MONTH_STRINGS.length; i++) {
                if (MONTH_STRINGS[i].equals(ms)) {
                    mi = i;
                    break;
                }
            }
            result.set(Calendar.MONTH, mi);
            result.set(Calendar.DATE, day.getNumber().intValue());
            result.set(Calendar.HOUR_OF_DAY, hour.getNumber().intValue());
            result.set(Calendar.MINUTE, min.getNumber().intValue());
            result.set(Calendar.SECOND, sec.getNumber().intValue());

            return result;
        }
    }

    /**
     * Multi value property.
     */
    public static class MultiValuePropertyInputField extends JPanel implements PropertyValue {
        private static final long serialVersionUID = 1L;

        private static final Icon ICON_UP = new UpIcon(ClientHelper.ICON_BUTTON_ICON_SIZE,
                ClientHelper.ICON_BUTTON_ICON_SIZE);
        private static final Icon ICON_UP_DISABLED = new UpIcon(ClientHelper.ICON_BUTTON_ICON_SIZE,
                ClientHelper.ICON_BUTTON_ICON_SIZE, false);
        private static final Icon ICON_DOWN = new DownIcon(ClientHelper.ICON_BUTTON_ICON_SIZE,
                ClientHelper.ICON_BUTTON_ICON_SIZE);
        private static final Icon ICON_DOWN_DISABLED = new DownIcon(ClientHelper.ICON_BUTTON_ICON_SIZE,
                ClientHelper.ICON_BUTTON_ICON_SIZE, false);
        private static final Icon ICON_REMOVE = new RemoveIcon(ClientHelper.ICON_BUTTON_ICON_SIZE,
                ClientHelper.ICON_BUTTON_ICON_SIZE);
        private static final Icon ICON_REMOVE_DISABLED = new RemoveIcon(ClientHelper.ICON_BUTTON_ICON_SIZE,
                ClientHelper.ICON_BUTTON_ICON_SIZE, false);

        private final JComponent component;
        private int position;

        private JButton upButton;
        private JButton downButton;

        public MultiValuePropertyInputField(final JComponent component, final MultivalueManager mutlivalueManager,
                final Color bgColor) {
            super();
            this.component = component;

            setLayout(new BorderLayout());
            setBackground(bgColor);

            add(component, BorderLayout.CENTER);

            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            buttonPanel.setBackground(bgColor);

            upButton = new JButton(ICON_UP);
            upButton.setDisabledIcon(ICON_UP_DISABLED);
            upButton.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    mutlivalueManager.moveUp(MultiValuePropertyInputField.this.position);
                }
            });
            buttonPanel.add(upButton);

            downButton = new JButton(ICON_DOWN);
            downButton.setDisabledIcon(ICON_DOWN_DISABLED);
            downButton.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    mutlivalueManager.moveDown(MultiValuePropertyInputField.this.position);
                }
            });
            buttonPanel.add(downButton);

            JButton removeButton = new JButton(ICON_REMOVE);
            removeButton.setDisabledIcon(ICON_REMOVE_DISABLED);
            removeButton.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    mutlivalueManager.removeValue(MultiValuePropertyInputField.this.position);
                }
            });
            buttonPanel.add(removeButton);

            add(buttonPanel, BorderLayout.LINE_END);
        }

        private void updatePosition(int position, boolean isLast) {
            this.position = position;
            upButton.setEnabled(position > 0);
            downButton.setEnabled(!isLast);
        }

        @Override
        public Object getPropertyValue() throws Exception {
            return ((PropertyValue) component).getPropertyValue();
        }
    }
}
