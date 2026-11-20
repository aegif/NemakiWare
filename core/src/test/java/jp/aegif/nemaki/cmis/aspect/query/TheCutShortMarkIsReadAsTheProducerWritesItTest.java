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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.CmisExtensionElement;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.CmisExtensionElementImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectListImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


/**
 * The mark of an answer the ACL scan limit cut short is read as its producer writes it (R258, R344, 3.4.2).
 *
 * <p>What is locked, on the product's {@code CutShortByAclScanLimit.isCutShort}: an answer marked by the product's own
 * producer ({@code SolrQueryProcessor.markTruncated}) is cut short; an answer without the mark, with the value
 * {@code false}, with another name of value {@code true}, with the name nested under another element, or a null answer, is
 * not; a null element and a {@code false} element before the mark do not hide it.
 */
class TheCutShortMarkIsReadAsTheProducerWritesItTest {

    /** An answer marked by the product's own producer, through its private method. */
    static ObjectListImpl markedByTheProducer() {
        ObjectListImpl answer = new ObjectListImpl();
        answer.setObjects(new ArrayList<>());
        return CutShortAnswers.markedByTheProducer(answer);
    }

    private static ObjectListImpl withExtensions(CmisExtensionElement... extensions) {
        ObjectListImpl answer = new ObjectListImpl();
        answer.setExtensions(new ArrayList<>(Arrays.asList(extensions)));
        return answer;
    }

    @Test
    @DisplayName("an answer the producer marked is cut short")
    void theProducersMarkIsCutShort() {
        ObjectListImpl marked = markedByTheProducer();
        assertTrue(CutShortByAclScanLimit.isCutShort(marked), "the producer's own mark: " + marked.getExtensions());
    }

    @Test
    @DisplayName("an answer without the mark, or with it false, is not cut short")
    void anAnswerWithoutTheMarkIsNotCutShort() {
        assertFalse(CutShortByAclScanLimit.isCutShort(null), "a null answer");
        assertFalse(CutShortByAclScanLimit.isCutShort(new ObjectListImpl()), "an answer without extensions");
        assertFalse(CutShortByAclScanLimit.isCutShort(withExtensions()), "an answer with no extension element");
        assertFalse(CutShortByAclScanLimit.isCutShort(withExtensions(
                new CmisExtensionElementImpl(null, CutShortByAclScanLimit.MARK, null, "false"))), "the mark's name, the value false");
    }

    @Test
    @DisplayName("another name with the value true, or the name under another element, is not the mark")
    void onlyTheTopLevelNameIsTheMark() {
        assertFalse(CutShortByAclScanLimit.isCutShort(withExtensions(
                new CmisExtensionElementImpl(null, "somethingElse", null, "true"))), "another name, the value true");
        List<CmisExtensionElement> child = List.of(new CmisExtensionElementImpl(null, CutShortByAclScanLimit.MARK, null, "true"));
        assertFalse(CutShortByAclScanLimit.isCutShort(withExtensions(new CmisExtensionElementImpl(null, "other", null, child))),
                "the mark nested under another element");
    }

    @Test
    @DisplayName("a null element, or a false element before the mark, does not hide it")
    void theMarkIsFoundAmongOtherElements() {
        ObjectListImpl answer = new ObjectListImpl();
        List<CmisExtensionElement> extensions = new ArrayList<>();
        extensions.add(null);
        extensions.add(new CmisExtensionElementImpl(null, CutShortByAclScanLimit.MARK, null, "false"));
        extensions.add(new CmisExtensionElementImpl(null, CutShortByAclScanLimit.MARK, null, "true"));
        answer.setExtensions(extensions);
        assertTrue(CutShortByAclScanLimit.isCutShort(answer), "a null element and a false one before the mark");
    }
}
