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
package org.apache.chemistry.opencmis.workbench.icons;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

import javax.swing.Icon;

import org.apache.chemistry.opencmis.workbench.WorkbenchScale;

public abstract class AbstractWorkbenchIcon implements Icon {

    protected static final Color DEFAULT_COLOR = new Color(0x33628c);
    protected static final Color DISABLED_COLOR = new Color(0x8e8f91);

    protected int width;
    protected int height;
    protected boolean enabled = true;
    protected Map<Integer, BufferedImage> images = new HashMap<Integer, BufferedImage>();

    public AbstractWorkbenchIcon() {
        this(true);
    }

    public AbstractWorkbenchIcon(boolean enabled) {
        this.enabled = enabled;
        this.width = WorkbenchScale.isScaling() ? (int) (getOrginalWidth() * WorkbenchScale.getScaleFactor())
                : getOrginalWidth();
        this.height = WorkbenchScale.isScaling() ? (int) (getOrginalHeight() * WorkbenchScale.getScaleFactor())
                : getOrginalHeight();
    }

    public AbstractWorkbenchIcon(int width, int height) {
        this(width, height, true);
    }

    public AbstractWorkbenchIcon(int width, int height, boolean enabled) {
        this.enabled = enabled;
        this.width = WorkbenchScale.isScaling() ? (int) (width * WorkbenchScale.getScaleFactor()) : width;
        this.height = WorkbenchScale.isScaling() ? (int) (height * WorkbenchScale.getScaleFactor()) : height;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public synchronized void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2d = (Graphics2D) g.create();

        double scaleD = g2d.getFontRenderContext().getTransform().getScaleX();
        int scaleP = (int) (scaleD * 100); // scale factor in percent

        BufferedImage image = null;

        synchronized (this) {
            image = images.get(scaleP);
            if (image == null) {
                image = renderIcon(scaleD);
                images.put(scaleP, image);
            }
        }

        if (scaleP != 100) {
            g2d.scale(1d / scaleD, 1d / scaleD);
            g2d.drawImage(image, (int) (x * scaleD), (int) (y * scaleD), c);
        } else {
            g2d.drawImage(image, x, y, c);
        }
    }

    @Override
    public final int getIconHeight() {
        return height;
    }

    @Override
    public final int getIconWidth() {
        return width;
    }

    protected Color getColor() {
        return enabled ? DEFAULT_COLOR : DISABLED_COLOR;
    }

    protected abstract int getOrginalHeight();

    protected abstract int getOrginalWidth();

    protected abstract void paint(Graphics2D g);

    protected void paintPlusBadge(Graphics2D g, Color color) {
        GeneralPath shape = new GeneralPath();

        shape.moveTo(59.82887, 47.315468);
        shape.lineTo(55.657738, 47.315468);
        shape.lineTo(55.657738, 43.144333);
        shape.curveTo(55.657738, 40.840477, 53.790462, 38.9732, 51.486603, 38.9732);
        shape.curveTo(49.182747, 38.9732, 47.31547, 40.840477, 47.31547, 43.144333);
        shape.lineTo(47.31547, 47.315468);
        shape.lineTo(43.144337, 47.315468);
        shape.curveTo(40.84048, 47.315468, 38.973206, 49.182743, 38.973206, 51.4866);
        shape.curveTo(38.973206, 53.790455, 40.84048, 55.657734, 43.144337, 55.657734);
        shape.lineTo(47.31547, 55.657734);
        shape.lineTo(47.31547, 59.828865);
        shape.curveTo(47.315468, 62.132725, 49.182747, 64.0, 51.4866, 64.0);
        shape.curveTo(53.790455, 64.0, 55.657734, 62.132725, 55.657734, 59.828865);
        shape.lineTo(55.657734, 55.65773);
        shape.lineTo(59.828865, 55.65773);
        shape.curveTo(62.132725, 55.657734, 64.0, 53.79046, 64.0, 51.4866);
        shape.curveTo(64.0, 49.182747, 62.132725, 47.315468, 59.82887, 47.315468);
        shape.closePath();

        g.setPaint(color);
        g.fill(shape);
    }

    private final BufferedImage renderIcon(double factor) {
        BufferedImage img = new BufferedImage((int) Math.round(getIconWidth() * factor),
                (int) Math.round(getIconHeight() * factor), BufferedImage.TYPE_INT_ARGB);

        double coef = Math.min(getIconWidth() * factor / getOrginalWidth(), getIconHeight() * factor
                / getOrginalHeight());

        Graphics2D g2d = img.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_NORMALIZE);
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);

        g2d.scale(coef, coef);
        paint(g2d);
        g2d.dispose();

        return img;
    }
}
