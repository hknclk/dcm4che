/* ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/gunterze/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * Agfa Healthcare.
 * Portions created by the Initial Developer are Copyright (C) 2013
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * ***** END LICENSE BLOCK ***** */

package org.dcm4che.image;

import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferUShort;

import org.dcm4che.data.Attributes;
import org.dcm4che.data.Tag;
import org.dcm4che.util.ByteUtils;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 *
 */
public class LUTFactory {

    private final StoredValue storedValue;
    private float rescaleSlope = 1;
    private float rescaleIntercept = 0;
    private LUT modalityLUT;
    private float windowCenter;
    private float windowWidth;
    private String voiLUTFunction;
    private LUT voiLUT;
    private LUT presentationLUT;
    private boolean inverse;

    public LUTFactory(StoredValue storedValue) {
        this.storedValue = storedValue;
    }

    public void init(Attributes attrs) {
        rescaleIntercept = attrs.getFloat(Tag.RescaleIntercept, 0);
        rescaleSlope = attrs.getFloat(Tag.RescaleSlope, 1);
        modalityLUT = createLUT(storedValue,
                attrs.getNestedDataset(Tag.ModalityLUTSequence));
        String pShape = attrs.getString(Tag.PresentationLUTShape);
        inverse = (pShape != null 
                ? "INVERSE".equals(pShape)
                : "MONOCHROME1".equals(
                        attrs.getString(Tag.PhotometricInterpretation)));
        if (rescaleSlope < 0)
            inverse = !inverse;
    }

    public void setWindowCenter(float windowCenter) {
        this.windowCenter = windowCenter;
    }

    public void setWindowWidth(float windowWidth) {
        this.windowWidth = windowWidth;
    }

    public void setVOI(Attributes img, int windowIndex, int voiLUTIndex,
            boolean preferWindow) {
        LUT voiLUT = createLUT(modalityLUT != null
                    ? new StoredValue.Unsigned(modalityLUT.outBits)
                    : storedValue,
                img.getNestedDataset(Tag.VOILUTSequence, voiLUTIndex));
        if (preferWindow || voiLUT == null) {
            float[] wcs = img.getFloats(Tag.WindowCenter);
            float[] wws = img.getFloats(Tag.WindowWidth);
            if (wcs != null && windowIndex < wcs.length
                    && wws != null && windowIndex < wws.length) {
                windowCenter = wcs[windowIndex];
                windowWidth = wws[windowIndex];
            } else
                this.voiLUT = voiLUT;
        } else
            this.voiLUT = voiLUT;
    }

    private LUT createLUT(StoredValue inBits, Attributes attrs) {
        if (attrs == null)
            return null;

        int[] desc = attrs.getInts(Tag.LUTDescriptor);
        if (desc == null)
            return null;

        if (desc.length != 3)
            return null;

        int len = desc[0] == 0 ? 0x10000 : desc[0];
        int offset = (short) desc[1];
        int outBits = desc[2];
        byte[] data = attrs.getSafeBytes(Tag.LUTData);
        if (data == null)
            return null;

        if (data.length == len << 1) {
            if (outBits > 8) {
                if (outBits > 16)
                    return null;

                short[] ss = new short[len];
                if (attrs.bigEndian())
                    for (int i = 0; i < ss.length; i++)
                        ss[i] = (short) ByteUtils.bytesToShortBE(data, i << 1);
                else
                    for (int i = 0; i < ss.length; i++)
                        ss[i] = (short) ByteUtils.bytesToShortLE(data, i << 1);

                return new LUTShort(storedValue, outBits, offset, ss);
            }
            // padded high bits -> use low bits
            data = halfLength(data, attrs.bigEndian() ? 1 : 0);
        }
        if (data.length != len)
            return null;
        
        if (outBits > 8)
            return null;

        return new LUTByte(inBits, outBits, offset, data);
    }

    static byte[] halfLength(byte[] data, int hilo) {
        byte[] bs = new byte[data.length >> 1];
        for (int i = 0; i < bs.length; i++)
            bs[i] = data[(i<<1)|hilo];

        return bs;
    }

    public LUT createLUT(int outBits) {
        LUT lut = combineModalityVOILUT(presentationLUT != null
                ? log2(presentationLUT.length())
                : outBits);
        if (presentationLUT != null) {
            lut = lut.combine(presentationLUT.adjustOutBits(outBits));
        } else if (inverse)
            lut.inverse();
        return lut;
    }

    private static int log2(int value) {
        int i = 0;
        while ((value>>>i) != 0);
            ++i;
        return i-1;
    }

    private LUT combineModalityVOILUT(int outBits) {
        float m = rescaleSlope;
        float b = rescaleIntercept;
        LUT modalityLUT = this.modalityLUT;
        LUT lut = this.voiLUT;
        if (lut == null) {
            float c = windowCenter;
            float w = windowWidth;

            if (w == 0 && modalityLUT != null)
                return modalityLUT.adjustOutBits(outBits);

            int size, offset;
            StoredValue inBits = modalityLUT != null
                    ? new StoredValue.Unsigned(modalityLUT.outBits)
                    : storedValue;
            if (w != 0) {
                size = Math.max(2,Math.abs(Math.round(w/m)));
                offset = Math.round(c/m-b) - size/2;
            } else {
                offset = inBits.minValue();
                size = inBits.maxValue() - inBits.minValue() + 1;
            }
            lut = outBits > 8
                    ? new LUTShort(inBits, outBits, offset, size)
                    : new LUTByte(inBits, outBits, offset, size);
        } else {
            lut = lut.adjustOutBits(outBits);
        }
        return modalityLUT != null ? modalityLUT.combine(lut) : lut;
    }

    public boolean autoWindowing(Attributes img, DataBuffer dataBuffer) {
        if (modalityLUT != null || voiLUT != null || windowWidth != 0)
            return false;

        int min = storedValue.valueOf(
              img.getInt(Tag.SmallestImagePixelValue, 0));
        int max = storedValue.valueOf(
                img.getInt(Tag.LargestImagePixelValue, 0));
        if (max == 0) {
            min = Integer.MAX_VALUE;
            max = Integer.MIN_VALUE;
            if (dataBuffer instanceof DataBufferByte)
                for (byte pixel : ((DataBufferByte) dataBuffer).getData()) {
                    int val = storedValue.valueOf(pixel);
                    if (val < min) min = val;
                    if (val > max) max = val;
                }
            else
                for (short pixel : ((DataBufferUShort) dataBuffer).getData()) {
                    int val = storedValue.valueOf(pixel);
                    if (val < min) min = val;
                    if (val > max) max = val;
                }
        }
        windowCenter = (min + max + 1) / 2 * rescaleSlope + rescaleIntercept;
        windowWidth = Math.abs((max + 1 - min) * rescaleSlope);
        return true;
    }
}