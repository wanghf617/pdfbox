/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.pdfbox.pdmodel.font;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.fontbox.ttf.CMAPEncodingEntry;
import org.apache.fontbox.ttf.CMAPTable;
import org.apache.fontbox.ttf.GlyphData;
import org.apache.fontbox.ttf.GlyphTable;
import org.apache.fontbox.ttf.HeaderTable;
import org.apache.fontbox.ttf.HorizontalHeaderTable;
import org.apache.fontbox.ttf.HorizontalMetricsTable;
import org.apache.fontbox.ttf.NameRecord;
import org.apache.fontbox.ttf.NamingTable;
import org.apache.fontbox.ttf.OS2WindowsMetricsTable;
import org.apache.fontbox.ttf.PostScriptTable;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.fontbox.util.FontManager;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.encoding.Encoding;
import org.apache.pdfbox.encoding.MacOSRomanEncoding;
import org.apache.pdfbox.encoding.WinAnsiEncoding;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;

/**
 * TrueType font.
 * 
 * @author Ben Litchfield
 */
public class PDTrueTypeFont extends PDSimpleFont
{
    private static final Log LOG = LogFactory.getLog(PDTrueTypeFont.class);

    private static final int START_RANGE_F000 = 0xF000;
    private static final int START_RANGE_F100 = 0xF100;
    private static final int START_RANGE_F200 = 0xF200;

    /**
     * Loads a TTF to be embedded into a document.
     *
     * @param doc The PDF document that will hold the embedded font.
     * @param file a ttf file.
     * @return a PDTrueTypeFont instance.
     * @throws IOException If there is an error loading the data.
     */
    public static PDTrueTypeFont loadTTF(PDDocument doc, File file) throws IOException
    {
        return new PDTrueTypeFont(doc, new FileInputStream(file));
    }

    private CMAPEncodingEntry cmapWinUnicode = null;
    private CMAPEncodingEntry cmapWinSymbol = null;
    private CMAPEncodingEntry cmapMacintoshSymbol = null;
    private boolean cmapInitialized = false;

    private TrueTypeFont ttf = null;
    private HashMap<Integer, Float> advanceWidths = new HashMap<Integer, Float> ();

    /**
     * Creates a new TrueType font from a Font dictionary.
     *
     * @param fontDictionary The font dictionary according to the PDF specification.
     */
    public PDTrueTypeFont(COSDictionary fontDictionary)
    {
        super(fontDictionary);
    }

    /**
     * Creates a new TrueType font for embedding.
     */
    private PDTrueTypeFont(PDDocument document, InputStream ttf) throws IOException
    {
        font.setItem(COSName.SUBTYPE, COSName.TRUE_TYPE);

        PDStream stream = new PDStream(document, ttf, false);
        stream.getStream().setInt(COSName.LENGTH1, stream.getByteArray().length); // todo: wrong?
        stream.addCompression();

        // only support winansi encoding right now, should really
        // just use Identity-H with unicode mapping
        Encoding encoding = new WinAnsiEncoding(); // fixme: read encoding from TTF

        setFontEncoding(encoding);
        setEncoding(encoding.getCOSObject());

        // as the stream was close within the PDStream constructor, we have to recreate it
        InputStream stream2 = null;
        PDFontDescriptorDictionary fd;
        try
        {
            stream2 = stream.createInputStream();
            fd = getFontDescriptor(new TTFParser().parseTTF(stream2));
        }
        finally
        {
            IOUtils.closeQuietly(stream2);
        }


        fd.setFontFile2(stream);
        setFontDescriptor(fd);
    }

    // creates a new font descriptor dictionary for the given TTF
    private PDFontDescriptorDictionary getFontDescriptor(TrueTypeFont ttf) throws IOException
    {
        PDFontDescriptorDictionary fd = new PDFontDescriptorDictionary();

        NamingTable naming = ttf.getNaming();
        List<NameRecord> records = naming.getNameRecords();
        for (NameRecord nr : records)
        {
            if (nr.getNameId() == NameRecord.NAME_POSTSCRIPT_NAME)
            {
                setBaseFont(nr.getString());
                fd.setFontName(nr.getString());
            }
            else if (nr.getNameId() == NameRecord.NAME_FONT_FAMILY_NAME)
            {
                fd.setFontFamily(nr.getString());
            }
        }

        OS2WindowsMetricsTable os2 = ttf.getOS2Windows();
        boolean isSymbolic = false;
        switch (os2.getFamilyClass())
        {
            case OS2WindowsMetricsTable.FAMILY_CLASS_SYMBOLIC:
                isSymbolic = true;
                break;
            case OS2WindowsMetricsTable.FAMILY_CLASS_SCRIPTS:
                fd.setScript(true);
                break;
            case OS2WindowsMetricsTable.FAMILY_CLASS_CLAREDON_SERIFS:
            case OS2WindowsMetricsTable.FAMILY_CLASS_FREEFORM_SERIFS:
            case OS2WindowsMetricsTable.FAMILY_CLASS_MODERN_SERIFS:
            case OS2WindowsMetricsTable.FAMILY_CLASS_OLDSTYLE_SERIFS:
            case OS2WindowsMetricsTable.FAMILY_CLASS_SLAB_SERIFS:
                fd.setSerif(true);
                break;
        }

        switch (os2.getWidthClass())
        {
            case OS2WindowsMetricsTable.WIDTH_CLASS_ULTRA_CONDENSED:
                fd.setFontStretch("UltraCondensed");
                break;
            case OS2WindowsMetricsTable.WIDTH_CLASS_EXTRA_CONDENSED:
                fd.setFontStretch("ExtraCondensed");
                break;
            case OS2WindowsMetricsTable.WIDTH_CLASS_CONDENSED:
                fd.setFontStretch("Condensed");
                break;
            case OS2WindowsMetricsTable.WIDTH_CLASS_SEMI_CONDENSED:
                fd.setFontStretch("SemiCondensed");
                break;
            case OS2WindowsMetricsTable.WIDTH_CLASS_MEDIUM:
                fd.setFontStretch("Normal");
                break;
            case OS2WindowsMetricsTable.WIDTH_CLASS_SEMI_EXPANDED:
                fd.setFontStretch("SemiExpanded");
                break;
            case OS2WindowsMetricsTable.WIDTH_CLASS_EXPANDED:
                fd.setFontStretch("Expanded");
                break;
            case OS2WindowsMetricsTable.WIDTH_CLASS_EXTRA_EXPANDED:
                fd.setFontStretch("ExtraExpanded");
                break;
            case OS2WindowsMetricsTable.WIDTH_CLASS_ULTRA_EXPANDED:
                fd.setFontStretch("UltraExpanded");
                break;
        }
        fd.setFontWeight(os2.getWeightClass());
        fd.setSymbolic(isSymbolic);
        fd.setNonSymbolic(!isSymbolic);

        // todo retval.setItalic
        // todo retval.setAllCap
        // todo retval.setSmallCap
        // todo retval.setForceBold

        HeaderTable header = ttf.getHeader();
        PDRectangle rect = new PDRectangle();
        float scaling = 1000f / header.getUnitsPerEm();
        rect.setLowerLeftX(header.getXMin() * scaling);
        rect.setLowerLeftY(header.getYMin() * scaling);
        rect.setUpperRightX(header.getXMax() * scaling);
        rect.setUpperRightY(header.getYMax() * scaling);
        fd.setFontBoundingBox(rect);

        HorizontalHeaderTable hHeader = ttf.getHorizontalHeader();
        fd.setAscent(hHeader.getAscender() * scaling);
        fd.setDescent(hHeader.getDescender() * scaling);

        GlyphTable glyphTable = ttf.getGlyph();
        GlyphData[] glyphs = glyphTable.getGlyphs();

        PostScriptTable ps = ttf.getPostScript();
        fd.setFixedPitch(ps.getIsFixedPitch() > 0);
        fd.setItalicAngle(ps.getItalicAngle());

        String[] names = ps.getGlyphNames();

        if (names != null)
        {
            for (int i = 0; i < names.length; i++)
            {
                // if we have a capital H then use that, otherwise use the tallest letter
                if (names[i].equals("H"))
                {
                    fd.setCapHeight(glyphs[i].getBoundingBox().getUpperRightY() / scaling);
                }
                if (names[i].equals("x"))
                {
                    fd.setXHeight(glyphs[i].getBoundingBox().getUpperRightY() / scaling);
                }
            }
        }

        // hmm there does not seem to be a clear definition for StemV,
        // this is close enough and I am told it doesn't usually get used.
        fd.setStemV(fd.getFontBoundingBox().getWidth() * .13f);

        CMAPTable cmapTable = ttf.getCMAP();
        CMAPEncodingEntry[] cmaps = cmapTable.getCmaps();
        CMAPEncodingEntry uniMap = null;

        for (CMAPEncodingEntry cmap : cmaps)
        {
            if (cmap.getPlatformId() == CMAPTable.PLATFORM_WINDOWS)
            {
                int platformEncoding = cmap.getPlatformEncodingId();
                if (CMAPTable.ENCODING_UNICODE == platformEncoding)
                {
                    uniMap = cmap;
                    break;
                }
            }
        }

        Map<Integer, String> codeToName = this.getFontEncoding().getCodeToNameMap();

        int firstChar = Collections.min(codeToName.keySet());
        int lastChar = Collections.max(codeToName.keySet());

        HorizontalMetricsTable hMet = ttf.getHorizontalMetrics();
        int[] widthValues = hMet.getAdvanceWidth();
        // some monospaced fonts provide only one value for the width
        // instead of an array containing the same value for every glyphid
        boolean isMonospaced = fd.isFixedPitch();
        int nWidths = lastChar - firstChar + 1;
        List<Integer> widths = new ArrayList<Integer>(nWidths);
        // use the first width as default
        // proportional fonts -> width of the .notdef character
        // monospaced-fonts -> the first width
        int defaultWidth = Math.round(widthValues[0] * scaling);
        for (int i = 0; i < nWidths; i++)
        {
            widths.add(defaultWidth);
        }
        // Encoding singleton to have acces to the chglyph name to
        // unicode cpoint point mapping of Adobe's glyphlist.txt
        Encoding glyphlist = WinAnsiEncoding.INSTANCE;

        // A character code is mapped to a glyph name via the provided font encoding
        // Afterwards, the glyph name is translated to a glyph ID.
        // For details, see PDFReference16.pdf, Section 5.5.5, p.401
        //
        for (Entry<Integer, String> e : codeToName.entrySet())
        {
            String name = e.getValue();
            // pdf code to unicode by glyph list.
            String c = glyphlist.getCharacter(name);
            int charCode = c.codePointAt(0);
            int gid = uniMap.getGlyphId(charCode);
            if (gid != 0)
            {
                if (isMonospaced)
                {
                    widths.set(e.getKey() - firstChar, defaultWidth);
                }
                else
                {
                    widths.set(e.getKey() - firstChar,
                               Math.round(widthValues[gid] * scaling));
                }
            }
        }
        setWidths(widths);
        font.setInt(COSName.FIRST_CHAR, firstChar);
        font.setInt(COSName.LAST_CHAR, lastChar);

        return fd;
    }

    /**
     * Return the TTF font as TrueTypeFont.
     * 
     * @return the TTF font
     * @throws IOException If there is an error loading the data
     */
    public TrueTypeFont getTTFFont() throws IOException
    {
        if (ttf == null)
        {
            PDFontDescriptorDictionary fd = (PDFontDescriptorDictionary) getFontDescriptor();
            if (fd != null)
            {
                PDStream ff2Stream = fd.getFontFile2();
                if (ff2Stream != null)
                {
                    TTFParser ttfParser = new TTFParser(true);
                    ttf = ttfParser.parseTTF(ff2Stream.createInputStream());
                }
            }
            if (ttf == null)
            {
                // check if there is a font mapping for an external font file
                ttf = FontManager.findTTFont(getBaseFont());
            }
        }
        return ttf;
    }

    @Override
    public float getFontWidth(int charCode)
    {
        float width = super.getFontWidth(charCode);
        if (width <= 0)
        {
            if (advanceWidths.containsKey(charCode))
            {
                width = advanceWidths.get(charCode);
            }
            else
            {
                TrueTypeFont ttf;
                try
                {
                    ttf = getTTFFont();
                    if (ttf != null)
                    {
                        int code = getGIDForCharacterCode(charCode);
                        width = ttf.getAdvanceWidth(code);
                        int unitsPerEM = ttf.getUnitsPerEm();
                        // do we have to scale the width
                        if (unitsPerEM != 1000)
                        {
                            width *= 1000f / unitsPerEM;
                        }
                    }
                }
                catch (IOException exception)
                {
                    width = 250;
                }
                advanceWidths.put(charCode, width);
            }
        }
        return width;
    }

    /**
     * Returns the GID for the given character code.
     *
     * @param code character code
     * @return GID (glyph index)
     */
    public int getGIDForCharacterCode(int code)
    {
        extractCmapTable();
        int result = 0;
        if (getFontEncoding() != null && !isSymbolicFont())
        {
            try
            {
                String characterName = getFontEncoding().getName(code);
                if (characterName != null)
                {
                    if (cmapWinUnicode != null)
                    {
                        String unicode = Encoding.getCharacterForName(characterName);
                        if (unicode != null)
                        {
                            result = unicode.codePointAt(0);
                        }
                        result = cmapWinUnicode.getGlyphId(result);
                    }
                    else if (cmapMacintoshSymbol != null &&
                             MacOSRomanEncoding.INSTANCE.hasCodeForName(characterName))
                    {
                        result = MacOSRomanEncoding.INSTANCE.getCode(characterName);
                        result = cmapMacintoshSymbol.getGlyphId(result);
                    }
                    else if (cmapWinSymbol != null)
                    {
                        // fallback scenario if the glyph can't be found yet
                        // maybe the 3,0 cmap provides a suitable mapping
                        // see PDFBOX-2091
                        result = cmapWinSymbol.getGlyphId(code);
                    }
                }
            }
            catch (IOException exception)
            {
                LOG.error("Caught an exception getGlyhcode: " + exception);
            }
        }
        if (getFontEncoding() == null || isSymbolicFont())
        {
            if (cmapWinSymbol != null)
            {
                result = cmapWinSymbol.getGlyphId(code);
                if (code >= 0 && code <= 0xFF)
                {
                    // the CMap may use one of the following code ranges,
                    // so that we have to add the high byte to get the
                    // mapped value
                    if (result == 0)
                    {
                        // F000 - F0FF
                        result = cmapWinSymbol.getGlyphId(code + START_RANGE_F000);
                    }
                    if (result == 0)
                    {
                        // F100 - F1FF
                        result = cmapWinSymbol.getGlyphId(code + START_RANGE_F100);
                    }
                    if (result == 0)
                    {
                        // F200 - F2FF
                        result = cmapWinSymbol.getGlyphId(code + START_RANGE_F200);
                    }
                }
            }
            else if (cmapMacintoshSymbol != null)
            {
                result = cmapMacintoshSymbol.getGlyphId(code);
            }
        }
        return result;
    }

    /**
     * extract all useful "cmap" subtables.
     */
    private void extractCmapTable()
    {
        if (cmapInitialized)
        {
            return;
        }

        try
        {
            getTTFFont();
        }
        catch(IOException exception)
        {
            LOG.error("Can't read the true type font", exception);
        }

        CMAPTable cmapTable = ttf.getCMAP();
        if (cmapTable != null)
        {
            // get all relevant "cmap" subtables
            CMAPEncodingEntry[] cmaps = cmapTable.getCmaps();
            for (CMAPEncodingEntry cmap1 : cmaps)
            {
                if (CMAPTable.PLATFORM_WINDOWS == cmap1.getPlatformId())
                {
                    if (CMAPTable.ENCODING_UNICODE == cmap1.getPlatformEncodingId())
                    {
                        cmapWinUnicode = cmap1;
                    }
                    else if (CMAPTable.ENCODING_SYMBOL == cmap1.getPlatformEncodingId())
                    {
                        cmapWinSymbol = cmap1;
                    }
                }
                else if (CMAPTable.PLATFORM_MACINTOSH == cmap1.getPlatformId())
                {
                    if (CMAPTable.ENCODING_SYMBOL == cmap1.getPlatformEncodingId())
                    {
                        cmapMacintoshSymbol = cmap1;
                    }
                }
            }
        }
        cmapInitialized = true;
    }

    @Override
    public void clear()
    {
        super.clear();
        cmapWinUnicode = null;
        cmapWinSymbol = null;
        cmapMacintoshSymbol = null;
        ttf = null;
        if (advanceWidths != null)
        {
            advanceWidths.clear();
        }
    }
}
