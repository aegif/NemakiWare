/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package jp.aegif.nemaki.cmis.aspect.query;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.data.CmisExtensionElement;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.CmisExtensionElementImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;

import jp.aegif.nemaki.cmis.aspect.query.solr.SolrQueryProcessor;

/**
 * The search answers the cut-short locks hand to every reader (R344, 3.4.2): marked by the product's own producer
 * ({@code SolrQueryProcessor.markTruncated}) — with rows and more inside the window, at the end of the window, with no row,
 * with a count that equals its rows — and not marked, or marked {@code false}. The rows' names and ids and the lower bounds
 * are distinctive, so a refusal can be searched for them.
 */
public final class CutShortAnswers {

    /** The lower bound a marked answer with more inside the window carries; also the unmarked answer's count. */
    public static final int LOWER_BOUND = 7300;
    /** The lower bound a marked answer at the end of its window carries. */
    public static final int LOWER_BOUND_AT_THE_END = 7140;

    private CutShortAnswers() {
    }

    public static String rowId(int i) {
        return "visible-row-id-" + i;
    }

    public static String rowName(int i) {
        return "visible-row-name-" + i + ".txt";
    }

    static ObjectData row(int i) {
        PropertiesImpl properties = new PropertiesImpl();
        PropertyIdImpl objectId = new PropertyIdImpl("cmis:objectId", rowId(i));
        objectId.setQueryName("cmis:objectId");
        PropertyStringImpl name = new PropertyStringImpl("cmis:name", rowName(i));
        name.setQueryName("cmis:name");
        PropertyIdImpl type = new PropertyIdImpl("cmis:objectTypeId", "cmis:document");
        type.setQueryName("cmis:objectTypeId");
        PropertyIdImpl base = new PropertyIdImpl("cmis:baseTypeId", "cmis:document");
        base.setQueryName("cmis:baseTypeId");
        properties.addProperty(objectId);
        properties.addProperty(name);
        properties.addProperty(type);
        properties.addProperty(base);
        ObjectDataImpl object = new ObjectDataImpl();
        object.setProperties(properties);
        return object;
    }

    public static ObjectListImpl answer(int rows, Boolean hasMoreItems, int numItems) {
        ObjectListImpl answer = new ObjectListImpl();
        List<ObjectData> objects = new ArrayList<>();
        for (int i = 1; i <= rows; i++) {
            objects.add(row(i));
        }
        answer.setObjects(objects);
        answer.setHasMoreItems(hasMoreItems);
        answer.setNumItems(BigInteger.valueOf(numItems));
        return answer;
    }

    /** {@code answer} with the mark the product's own producer adds, after whatever extensions it already has. */
    public static ObjectListImpl markedByTheProducer(ObjectListImpl answer) {
        try {
            Method mark = SolrQueryProcessor.class.getDeclaredMethod("markTruncated", ObjectList.class);
            mark.setAccessible(true);
            mark.invoke(null, answer);
            return answer;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("SolrQueryProcessor.markTruncated could not be called", e);
        }
    }

    public static ObjectListImpl with(ObjectListImpl answer, CmisExtensionElement element) {
        List<CmisExtensionElement> extensions = new ArrayList<>();
        if (answer.getExtensions() != null) {
            extensions.addAll(answer.getExtensions());
        }
        extensions.add(element);
        answer.setExtensions(extensions);
        return answer;
    }

    /** The marked answers every reader refuses, by name. */
    public static Map<String, ObjectListImpl> marked() {
        Map<String, ObjectListImpl> cases = new LinkedHashMap<>();
        cases.put("markedWithMoreInsideTheWindow", markedByTheProducer(answer(2, Boolean.TRUE, LOWER_BOUND)));
        cases.put("markedAtTheEndOfTheWindow", markedByTheProducer(answer(2, Boolean.FALSE, LOWER_BOUND_AT_THE_END)));
        cases.put("markedWithNoRow", markedByTheProducer(answer(0, Boolean.FALSE, 0)));
        cases.put("markedWithTheCountOfItsRows", markedByTheProducer(answer(3, Boolean.FALSE, 3)));
        return cases;
    }

    /** An answer without the mark: two rows of {@link #LOWER_BOUND}, more to come. */
    public static ObjectListImpl unmarked() {
        return answer(2, Boolean.TRUE, LOWER_BOUND);
    }

    /** An answer with the mark's name and the value {@code false}: two rows, the whole set. */
    public static ObjectListImpl markedFalse() {
        return with(answer(2, Boolean.FALSE, 2), new CmisExtensionElementImpl(null, CutShortByAclScanLimit.MARK, null, "false"));
    }

    /** A refusal names no row, no id and no count of the answer it refuses. */
    public static void assertNamesNoRowIdOrCount(String refusal, String what) {
        for (int i = 1; i <= 3; i++) {
            assertFalse(refusal.contains(rowName(i)), what + " names a row: " + refusal);
            assertFalse(refusal.contains(rowId(i)), what + " names an id: " + refusal);
        }
        assertFalse(refusal.contains(String.valueOf(LOWER_BOUND)), what + " carries the lower bound: " + refusal);
        assertFalse(refusal.contains(String.valueOf(LOWER_BOUND_AT_THE_END)), what + " carries the lower bound: " + refusal);
    }
}
