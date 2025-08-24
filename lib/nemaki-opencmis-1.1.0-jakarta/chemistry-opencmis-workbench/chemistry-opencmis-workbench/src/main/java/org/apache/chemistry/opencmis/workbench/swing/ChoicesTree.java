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

import static org.apache.chemistry.opencmis.commons.impl.CollectionsHelper.isNotEmpty;
import static org.apache.chemistry.opencmis.commons.impl.CollectionsHelper.isNullOrEmpty;

import java.util.List;

import javax.swing.Icon;
import javax.swing.tree.DefaultMutableTreeNode;

import org.apache.chemistry.opencmis.commons.definitions.Choice;
import org.apache.chemistry.opencmis.workbench.ClientHelper;
import org.apache.chemistry.opencmis.workbench.icons.ChoiceIcon;

@SuppressWarnings("rawtypes")
public class ChoicesTree extends InfoTree<List<Choice>> {

    private static final long serialVersionUID = 1L;

    private static final Icon CHOICE_ICON = new ChoiceIcon(ClientHelper.OBJECT_ICON_SIZE, ClientHelper.OBJECT_ICON_SIZE);

    public ChoicesTree() {
        super();
    }

    public ChoicesTree(final List<Choice> choices) {
        super(choices);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void buildTree(DefaultMutableTreeNode parent, List<Choice> choices) {
        if (isNullOrEmpty(choices)) {
            return;
        }

        for (Choice choice : choices) {
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(new ChoiceNode(choice));
            parent.add(node);

            if (isNotEmpty(choice.getChoice())) {
                buildTree(node, choice.getChoice());
            }
        }
    }

    @Override
    protected Icon getIcon() {
        return CHOICE_ICON;
    }

    private static class ChoiceNode {
        private final Choice choice;

        public ChoiceNode(Choice choice) {
            this.choice = choice;
        }

        @Override
        public String toString() {
            String displayName = choice.getDisplayName() == null ? "" : choice.getDisplayName() + ": ";
            String value = choice.getValue() == null ? "" : (choice.getValue().size() == 1 ? choice.getValue().get(0)
                    .toString() : choice.getValue().toString());

            return displayName + value;
        }
    }
}
