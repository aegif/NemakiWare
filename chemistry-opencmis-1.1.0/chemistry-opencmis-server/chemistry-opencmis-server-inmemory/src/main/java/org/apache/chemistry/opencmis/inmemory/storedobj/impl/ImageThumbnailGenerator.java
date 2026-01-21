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
package org.apache.chemistry.opencmis.inmemory.storedobj.impl;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;

import javax.imageio.ImageIO;

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;

public class ImageThumbnailGenerator {

    private static final int DEFAULT_LENGTH = 100;
    private static final String RENDITION_MIME_TYPE = "image/jpeg";
    private InputStream image;
    private int thumbWidth;
    private int thumbHeight;

    public ImageThumbnailGenerator(InputStream imageContent) {
        this.image = imageContent;
    }

    public int getWidth() {
        return thumbWidth;
    }

    public int getHeight() {
        return thumbHeight;
    }

    public ContentStream getRendition(int width, int height) {
        byte[] thumbnail;
        try {
            thumbnail = scaleImage(image, width, height);
            ContentStreamImpl cs = new ContentStreamImpl();
            cs.setFileName("thumbnail.jpg");
            cs.setMimeType(RENDITION_MIME_TYPE);
            cs.setStream(new ByteArrayInputStream(thumbnail));
            cs.setLength(BigInteger.valueOf(thumbnail.length));
            return cs;
        } catch (IOException e) {
            throw new CmisRuntimeException("Failed to generate thumbnail", e);
        }
    }

    private byte[] scaleImage(InputStream stream, int width, int height) throws IOException {

        BufferedImage resizedImage;
        BufferedImage originalImage = ImageIO.read(stream);

        if (width <= 0) {
            resizedImage = scaleLongerSideTo(originalImage, height);
        } else if (height <= 0) {
            resizedImage = scaleLongerSideTo(originalImage, width);
        } else {
            resizedImage = scaleImage(originalImage, width, height);
        }

        thumbWidth = resizedImage.getWidth();
        thumbHeight = resizedImage.getHeight();

        return storeImageinByteArray(resizedImage);
    }

    private BufferedImage scaleLongerSideTo(BufferedImage bi, int longerSideLengthParam) throws IOException {
        int width, height;
        int longerSideLength = longerSideLengthParam;

        if (longerSideLength <= 0) {
            longerSideLength = DEFAULT_LENGTH;
        }

        if (bi.getWidth() > bi.getHeight()) {
            width = longerSideLength;
            height = bi.getHeight() * longerSideLength / bi.getWidth();
        } else {
            height = longerSideLength;
            width = bi.getWidth() * longerSideLength / bi.getHeight();
        }

        BufferedImage resizedImage = scaleImage(bi, width, height);
        return resizedImage;
    }

    private BufferedImage scaleImage(BufferedImage originalImage, int width, int height) {

        BufferedImage resizedImage = new BufferedImage(width, height, originalImage.getType());
        Graphics2D g = resizedImage.createGraphics();

        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g.drawImage(originalImage, 0, 0, width, height, null);
        g.dispose();
        g.setComposite(AlphaComposite.Src);

        return resizedImage;
    }

    private byte[] storeImageinByteArray(BufferedImage bi) throws IOException {

        ByteArrayOutputStream os = new ByteArrayOutputStream();
        boolean ok = ImageIO.write(bi, "JPG", os);
        if (ok) {
            return os.toByteArray();
        } else {
            return null;
        }
    }

}
