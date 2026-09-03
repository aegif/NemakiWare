#!/usr/bin/env python3
"""Re-runnable negative controls: sabotage a protection, expect its lock to go RED.

Why this file exists
--------------------
The design ledger (docs/design/p3-4-custody-transfer.md, §36-§42) claims dozens of
negative controls "fired". Until this file, every one of those was a hand edit:
sabotage, run, read the failing test name, revert — honest at the time, and
unrepeatable afterwards. A reviewer could not distinguish "fired" from "was said
to have fired", and the session that ran them miscounted its own total twice.

This runner makes the claim mechanical. Each control below names:
  - the production file and the EXACT code it removes or degrades (the sabotage),
  - the test class to run,
  - the test method(s) that must FAIL while the sabotage is applied.

For every control, the runner: backs the file up, applies the sabotage (refusing
loudly if the anchor text no longer matches — a moved anchor means the control is
stale, which is itself a finding), runs the named test, asserts the expected
method failed, restores the file, and finally re-runs the test to prove the tree
is back to green.

Rules learned the hard way, encoded here:
  - Sabotage the CALL SITE / effect, not the helper: a lock that only exercises a
    helper stays green when the wiring is reverted (§38).
  - The sabotage must COMPILE. Two hand-run controls (FF, GC) silently measured
    nothing because the edit broke the build — string-internal semicolons and
    unbalanced braces. Anchors here are exact statements, removed whole.
  - The runner never runs Maven concurrently with anything else (CLAUDE.md).

Usage:
    python3 tools/negative-controls/run_negative_controls.py            # all
    python3 tools/negative-controls/run_negative_controls.py FE GG      # subset
Exit code 0 = every control fired and the tree was restored to green.
"""

import subprocess
import re
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
REPORTS = REPO / "core" / "target" / "surefire-reports"

# Each control: id, description, file, sabotage (find -> replace), test class,
# and the test methods that MUST fail under the sabotage.
CONTROLS = [
    dict(
        id="FE",
        what="anchor store: 'view did not answer' is no longer a separate fact",
        file="core/src/main/java/jp/aegif/nemaki/evidence/anchor/CouchAnchorReceiptStore.java",
        find="            queryFailed.set(true);\n            lastUnreadable.set(lastUnreadable.get() + 1);",
        replace="            lastUnreadable.set(lastUnreadable.get() + 1);",
        test="CouchAnchorReceiptStoreTest",
        expect_fail=["anUnansweredViewIsNotARowCount"],
    ),
    dict(
        id="FF3",
        what="RAG reindex: the truncation note is read but never appended",
        file="core/src/main/java/jp/aegif/nemaki/rag/maintenance/RAGIndexMaintenanceServiceImpl.java",
        find_span=('status.getErrors().add("... only the first "', 'described here.");'),
        replace="",
        test="RagReindexRefusesAWipeTest",
        expect_fail=["theRagErrorListSaysWhenItWasCutOff"],
    ),
    dict(
        id="FG",
        what="unwired rendition recorder goes back to returning in silence",
        file="core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java",
        find_span=('log.warn("No FormatDuplicationRecorder is wired', 'untrustworthy.");'),
        replace="",
        test="CopyRenditionsAreRecordedTest",
        expect_fail=["anUnwiredRecorderIsNotSilent"],
    ),
    dict(
        id="FH",
        what="upgradePending acts on a partly-readable list, then reports 0",
        file="core/src/main/java/jp/aegif/nemaki/evidence/anchor/AnchorService.java",
        find_span=("        if (receiptStore.unreadableCount() > 0) {",
                   'own answer then has to deny");\n        }'),
        replace="",
        test="AnchorServiceTest",
        expect_fail=["aPartlyReadablePendingListRefusesBeforeActing"],
    ),
    dict(
        id="GB",
        what="a subtree's failure list is discarded again (callerless sibling)",
        file="core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java",
        find="failureIds.addAll(deleteTree(callContext, repositoryId, child.getId(),\n\t\t\t\t\t\t\t\tallVersions, continueOnFailure, true));",
        replace="deleteTree(callContext, repositoryId, child.getId(),\n\t\t\t\t\t\t\t\tallVersions, continueOnFailure, true);",
        test="DeleteTreeKeepsTheParentOfUnreadableChildrenTest",
        expect_fail=["nestedFailuresAreNotDiscarded"],
    ),
    dict(
        id="GC2",
        what="the CMIS reindex walk trusts a decode-shortened listing again",
        file="core/src/main/java/jp/aegif/nemaki/businesslogic/impl/SolrIndexMaintenanceServiceImpl.java",
        find_span=("            int unreadableHere = contentService.lastUnreadableChildCount();",
                   'were NOT indexed");\n                }\n            }'),
        replace="",
        test="SolrIndexMaintenanceServiceImplReindexTest",
        expect_fail=["aShortListingIsAReindexFailureNotASmallerFolder"],
    ),
    dict(
        id="GD",
        what="the RAG reindex walk trusts a decode-shortened listing again",
        file="core/src/main/java/jp/aegif/nemaki/rag/maintenance/RAGIndexMaintenanceServiceImpl.java",
        find_span=("            int unreadableHere = contentService.lastUnreadableChildCount();",
                   'were NOT indexed");\n            }'),
        replace="",
        test="RagReindexRefusesAWipeTest",
        expect_fail=["aShortListingIsCountedByTheRagWalk"],
    ),
    dict(
        id="GE",
        what="ZipImporter's folder dedupe trusts a short listing again",
        file="core/src/main/java/jp/aegif/nemaki/rest/importexport/ZipImporter.java",
        find_span=("        // Same rule as the document arm above: an unreadable sibling row may BE this folder.",
                   'risking a duplicate");\n        }'),
        replace="",
        test="ShortListingsDoNotReachDestructiveConsumersTest",
        expect_fail=["everyDestructiveConsumerChecks"],
    ),
    dict(
        id="GF2",
        what="the production DFS stops asking how short the listing was",
        file="core/src/main/java/jp/aegif/nemaki/cmis/service/impl/ObjectServiceImpl.java",
        find="unreadableChildren = contentService.lastUnreadableChildCount();",
        replace="unreadableChildren = 0;",
        test="ShortListingsDoNotReachDestructiveConsumersTest",
        expect_fail=["everyDestructiveConsumerChecks"],
    ),
    dict(
        id="GG",
        what="the DFS keeps the read but the keep-the-folder guard is dead",
        file="core/src/main/java/jp/aegif/nemaki/cmis/service/impl/ObjectServiceImpl.java",
        find="\t\tif (unreadableChildren > 0 || failedIds.size() > failuresBefore) {",
        replace="\t\tif (false) {",
        test="DeleteTreeDfsKeepsFoldersOverInvisibleChildrenTest",
        expect_fail=["aShortListingKeepsTheFolder", "aFailedDescendantKeepsTheAncestors"],
    ),
    dict(
        id="HB",
        what="containment: an incomplete walk deletes by absence again",
        file="core/src/main/java/jp/aegif/nemaki/rest/purview/relationship/PurviewContainmentRelationshipServiceImpl.java",
        find="        String normalizedPreviousSnapshot = normalizeSnapshot(previousSnapshot);\n        if (lastWalkIncomplete.get()) {",
        replace="        String normalizedPreviousSnapshot = normalizeSnapshot(previousSnapshot);\n        if (false) {",
        test="PurviewContainmentRelationshipServiceImplTest",
        expect_fail=["anIncompleteWalkPublishesButNeverDeletes"],
    ),
    dict(
        id="HC",
        what="cloud metadata: an incomplete walk reconciles absence again",
        file="core/src/main/java/jp/aegif/nemaki/rest/purview/publish/PurviewCloudMetadataPublishServiceImpl.java",
        find="        if (walkIncomplete) {",
        replace="        if (false) {",
        test="PurviewCloudMetadataPublishServiceImplTest",
        expect_fail=["anIncompleteWalkPublishesChangesButReconcilesNothing"],
    ),
    dict(
        id="HD",
        what="archives: a decode-shortened listing is diffed and reconciled again",
        file="core/src/main/java/jp/aegif/nemaki/rest/purview/publish/PurviewArchivePublishServiceImpl.java",
        find="            if (contentDaoService.lastUnreadableArchiveCount() > 0) {",
        replace="            if (false) {",
        test="PurviewArchivePublishServiceImplTest",
        expect_fail=["anUnreadableArchiveRowRefusesTheSyncInsteadOfReconciling"],
    ),
    dict(
        id="HE",
        what="lineage retry: an incomplete walk reconciles absence again",
        file="core/src/main/java/jp/aegif/nemaki/rest/purview/publish/PurviewCloudMetadataPublishServiceImpl.java",
        find="        String normalizedPreviousSnapshot = normalizeSnapshot(previousSnapshot);\n        if (lastWalkIncomplete.get()) {",
        replace="        String normalizedPreviousSnapshot = normalizeSnapshot(previousSnapshot);\n        if (false) {",
        test="PurviewCloudMetadataPublishServiceImplTest",
        expect_fail=["theLineageRetryRefusesAnIncompleteWalk"],
    ),
    dict(
        id="HF",
        what="FULL sync walks past an unreadable row and seeds the token over it",
        file="core/src/main/java/jp/aegif/nemaki/rest/purview/publish/PurviewDocumentPublishServiceImpl.java",
        find="                if (contentDaoService.lastUnreadableChildCount() > 0) {",
        replace="                if (false) {",
        test="PurviewDocumentPublishServiceImplTest",
        expect_fail=["aFullSyncRefusesADecodeShortenedPage"],
    ),
    dict(
        id="HG",
        what="incremental sync advances the cursor past an undecodable change row",
        file="core/src/main/java/jp/aegif/nemaki/rest/purview/sync/PurviewIncrementalSyncServiceImpl.java",
        find="        if (contentDaoService.lastUnreadableChangeCount() > 0) {",
        replace="        if (false) {",
        test="PurviewIncrementalSyncServiceImplTest",
        expect_fail=["anUnreadableChangeRowKeepsTheCursorWhereItWas"],
    ),
    dict(
        id="HH",
        what="the CMIS change feed serves a list with a hole, advancing clients past it",
        file="core/src/main/java/jp/aegif/nemaki/businesslogic/impl/delegate/ChangeEventServiceDelegate.java",
        find="		if (contentDaoService.lastUnreadableChangeCount() > 0) {",
        replace="		if (false) {",
        test="ChangeEventServiceDelegateTest",
        expect_fail=["aDroppedRowRefusesTheFeed"],
    ),
    dict(
        id="HI",
        what="skipFirst drops the first row unconditionally again",
        file="core/src/main/java/jp/aegif/nemaki/businesslogic/impl/delegate/ChangeEventServiceDelegate.java",
        find="			if (first != null && startToken.equals(first.getToken())) {\n\t\t\t\tchanges.remove(0);\n\t\t\t}",
        replace="			changes.remove(0);",
        test="ChangeEventServiceDelegateTest",
        expect_fail=["skipFirstOnlyDropsTheDeliveredRow", "aNullTokenFirstRowIsKept"],
    ),
    dict(
        id="HJ",
        what="joined groups: an unanswered view is 'belongs to nothing' again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/UserGroupDaoDelegate.java",
        find_span=('\t\t\tif (result.getRows() == null) {\n\t\t\t\t// A user\'s group memberships decide',
                   'no groups");\n\t\t\t}'),
        replace="\t\t\tif (result.getRows() == null) {\n\t\t\t\treturn new ArrayList<String>();\n\t\t\t}",
        test="MembershipAnswersAreNeverSilentlyShortTest",
        expect_fail=["joinedGroupsNullRowsRefuse"],
    ),
    dict(
        id="HK",
        what="joined groups: a valueless membership row is skipped again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/UserGroupDaoDelegate.java",
        find='\t\t\t\t\tif (row.getValue() == null) {\n\t\t\t\t\t\tthrow new IllegalStateException("a joined-group row for user \'" + userId\n\t\t\t\t\t\t\t\t+ "\' carries no value; refusing to answer the membership short");\n\t\t\t\t\t}',
        replace="\t\t\t\t\tif (row.getValue() == null) {\n\t\t\t\t\t\tcontinue;\n\t\t\t\t\t}",
        test="MembershipAnswersAreNeverSilentlyShortTest",
        expect_fail=["joinedGroupsNullValueRefuses"],
    ),
    dict(
        id="HL",
        what="joined groups: an unreadable membership row is warn-skipped again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/UserGroupDaoDelegate.java",
        find_span=('\t\t\t\t\t\t} catch (Exception e) {\n\t\t\t\t\t\t\t// Warn-and-skip made an unreadable membership row',
                   'membership short", e);\n\t\t\t\t\t\t}'),
        replace='\t\t\t\t\t\t} catch (Exception e) {\n\t\t\t\t\t\t\tlog.warn("skip");\n\t\t\t\t\t\t}',
        test="MembershipAnswersAreNeverSilentlyShortTest",
        expect_fail=["joinedGroupsUndecodableRowRefuses"],
    ),
    dict(
        id="HM",
        what="joined groups: a failed resolution returns an empty membership again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/UserGroupDaoDelegate.java",
        find='\t\t\tthrow new IllegalStateException("the joined groups of user \'" + userId\n\t\t\t\t\t+ "\' could not be resolved; this is NOT a finding that there are none", e);',
        replace="\t\t\treturn new ArrayList<String>();",
        test="MembershipAnswersAreNeverSilentlyShortTest",
        expect_fail=["joinedGroupsOuterFailureRefuses"],
    ),
    dict(
        id="HN",
        what="nested expansion: an unanswered hierarchy view skips the group again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/UserGroupDaoDelegate.java",
        find_span=('\t\t\t\tif (result == null || result.getRows() == null) {\n\t\t\t\t\tthrow new IllegalStateException("the group-hierarchy view answered without"',
                   'belonging to no groups");\n\t\t\t\t}'),
        replace="\t\t\t\tif (result == null || result.getRows() == null) {\n\t\t\t\t\tcontinue;\n\t\t\t\t}",
        test="MembershipAnswersAreNeverSilentlyShortTest",
        expect_fail=["nestedExpansionNullRowsRefuse"],
    ),
    dict(
        id="HO",
        what="nested expansion: an unreadable hierarchy row is warn-skipped again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/UserGroupDaoDelegate.java",
        find_span=('\t\t\t\t\t} catch (Exception e) {\n\t\t\t\t\t\tthrow new IllegalStateException("a group-hierarchy row for group \'"',
                   'membership short", e);\n\t\t\t\t\t}'),
        replace='\t\t\t\t\t} catch (Exception e) {\n\t\t\t\t\t\tlog.warn("skip");\n\t\t\t\t\t}',
        test="MembershipAnswersAreNeverSilentlyShortTest",
        expect_fail=["nestedExpansionUndecodableRowRefuses"],
    ),
    dict(
        id="HP",
        what="nested expansion: a failed expansion answers with the partial membership again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/UserGroupDaoDelegate.java",
        find='\t\t\tthrow new IllegalStateException(\n\t\t\t\t\t"nested group expansion failed; refusing to answer the membership short", e);',
        replace="",
        test="MembershipAnswersAreNeverSilentlyShortTest",
        expect_fail=["nestedExpansionFailureRefuses"],
    ),
    dict(
        id="HQ",
        what="reverse lookup: an unanswered view is 'nothing references it' again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/UserGroupDaoDelegate.java",
        find_span=('\t\tif (result == null || result.getRows() == null) {\n\t\t\t// The javadoc above already makes the argument',
                   'nothing referencing it");\n\t\t}'),
        replace="\t\tif (result == null || result.getRows() == null) {\n\t\t\treturn parents;\n\t\t}",
        test="MembershipAnswersAreNeverSilentlyShortTest",
        expect_fail=["reverseLookupGroupNullResultRefuses", "reverseLookupGroupNullRowsRefuse",
                     "reverseLookupUserNullRowsRefuse"],
    ),
    dict(
        id="HR",
        what="trash count: a failed count renders an empty trash again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/ArchiveDaoDelegate.java",
        find='\t\t\tlog.error("Error getting archive count in repository: " + repositoryId, e);\n\t\t\tthrow new IllegalStateException("the archives could not be counted in \'"\n\t\t\t\t\t+ repositoryId + "\'; this is NOT a finding that there are none", e);',
        replace='\t\t\tlog.error("Error getting archive count in repository: " + repositoryId, e);\n\t\t\treturn 0;',
        test="ArchiveCountsAreNotZeroOnFailureTest",
        expect_fail=["aFailedCountRefuses"],
    ),
    dict(
        id="HS",
        what="trash count by state: the twin count answers 0 on failure again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/ArchiveDaoDelegate.java",
        find_span=('\t\t\t// Same rule as the unfiltered count above: a failed count is not zero.',
                   'there are none", e);\n\t\t}'),
        replace="\t\t\treturn 0;\n\t\t}",
        test="ArchiveCountsAreNotZeroOnFailureTest",
        expect_fail=["aFailedByStateCountRefuses"],
    ),
    dict(
        id="HT",
        what="containment: the incomplete walk keeps the un-widened previous snapshot again",
        file="core/src/main/java/jp/aegif/nemaki/rest/purview/relationship/PurviewContainmentRelationshipServiceImpl.java",
        find='            return new PurviewContainmentSyncResult(String.join("\\n", mergedKeys),\n                    published > 0, published, 0);',
        replace="            return new PurviewContainmentSyncResult(normalizedPreviousSnapshot,\n                    published > 0, published, 0);",
        test="PurviewContainmentRelationshipServiceImplTest",
        expect_fail=["aCreatedEdgeFromAnIncompleteRoundIsDeletedOnceItVanishes",
                     "anIncompleteWalkPublishesButNeverDeletes"],
    ),
    dict(
        id="HU",
        what="cloud metadata: the incomplete walk keeps the un-widened snapshot again",
        file="core/src/main/java/jp/aegif/nemaki/rest/purview/publish/PurviewCloudMetadataPublishServiceImpl.java",
        find="            String snapshotToKeep = widened",
        replace="            String snapshotToKeep = false",
        test="PurviewCloudMetadataPublishServiceImplTest",
        expect_fail=["anIncompleteWalkWidensTheSnapshotWithWhatItPublished"],
    ),
    dict(
        id="HV",
        what="incremental sync: a failed stream re-saves the old cursor, dropping the widened baseline",
        file="core/src/main/java/jp/aegif/nemaki/rest/purview/sync/PurviewIncrementalSyncServiceImpl.java",
        find_span=("            return new PurviewCursorState(\n                    failureState.getRepositoryId(),",
                   "failureState.getDeadLetterCount());"),
        replace="            return failureState;",
        test="PurviewIncrementalSyncServiceImplTest",
        expect_fail=["anIncompleteCloudWalkPersistsTheWidenedBaselineWhileStillFailing"],
    ),
    dict(
        id="HX",
        what="RSS folder feed serves a window with a hole in it again",
        file="core/src/main/java/jp/aegif/nemaki/rss/RssFeedService.java",
        find="        // fetch error the reader retries; RssFeedResource maps this to HTTP 500.\n        if (contentDaoService.lastUnreadableChangeCount() > 0) {",
        replace="        if (false) {",
        test="RssFeedsAreNeverSilentlyShortTest",
        expect_fail=["aFolderFeedRefusesAShortChangeWindow"],
    ),
    dict(
        id="HY",
        what="RSS document feed serves the same hole again — the twin",
        file="core/src/main/java/jp/aegif/nemaki/rss/RssFeedService.java",
        find="        // Same rule as the folder feed above: no cursor, no redelivery, so no short windows.\n        if (contentDaoService.lastUnreadableChangeCount() > 0) {",
        replace="        if (false) {",
        test="RssFeedsAreNeverSilentlyShortTest",
        expect_fail=["aDocumentFeedRefusesAShortChangeWindow"],
    ),
    dict(
        id="HZ",
        what="RSS folder filter silently drops a subtree again",
        file="core/src/main/java/jp/aegif/nemaki/rss/RssFeedService.java",
        find="        if (contentService.lastUnreadableChildCount() > 0) {",
        replace="        if (false) {",
        test="RssFeedsAreNeverSilentlyShortTest",
        expect_fail=["aShortChildListingRefusesTheFolderFilter"],
    ),
    dict(
        id="IA",
        what="the change query sends CouchDB a limit it rejects, and the feed dies",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/ChangeEventDaoDelegate.java",
        find='\t\t\tqueryParams.put("limit", maxItems > 0 ? Math.min(maxItems, MAX_CHANGE_PAGE)',
        replace='\t\t\tqueryParams.put("limit", maxItems > 0 ? maxItems',
        test="ChangeEventDaoDelegateLimitTest",
        expect_fail=["aHugeLimitIsClamped"],
    ),
    dict(
        id="IB",
        what="skipFirst's +1 overflows MAX_VALUE into an unbounded query again",
        file="core/src/main/java/jp/aegif/nemaki/businesslogic/impl/delegate/ChangeEventServiceDelegate.java",
        find="\t\tint fetchLimit = (skipFirst && limit < Integer.MAX_VALUE) ? limit + 1 : limit;",
        replace="\t\tint fetchLimit = skipFirst ? limit + 1 : limit;",
        test="ChangeEventServiceDelegateTest",
        expect_fail=["aHugeMaxItemsDoesNotOverflow"],
    ),
    dict(
        id="IC",
        what="joined groups: a Map row without a groupId is skipped again (direct)",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/UserGroupDaoDelegate.java",
        find_span=('\t\t\t\t\t\t\tif (groupId == null || groupId.isEmpty()) {\n\t\t\t\t\t\t\t\t// A row that decodes but carries no usable',
                   'membership short");\n\t\t\t\t\t\t\t}'),
        replace="\t\t\t\t\t\t\tif (groupId == null || groupId.isEmpty()) {\n\t\t\t\t\t\t\t\tcontinue;\n\t\t\t\t\t\t\t}",
        test="MembershipAnswersAreNeverSilentlyShortTest",
        expect_fail=["joinedGroupsRowWithoutGroupIdRefuses"],
    ),
    dict(
        id="ID",
        what="nested expansion: a hierarchy row without a groupId is skipped again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/UserGroupDaoDelegate.java",
        find_span=('\t\t\t\t\t\tif (parentGroupId == null || parentGroupId.isEmpty()) {\n\t\t\t\t\t\t\t// Same rule as the direct half',
                   'membership short");\n\t\t\t\t\t\t}'),
        replace="\t\t\t\t\t\tif (parentGroupId == null || parentGroupId.isEmpty()) {\n\t\t\t\t\t\t\tcontinue;\n\t\t\t\t\t\t}",
        test="MembershipAnswersAreNeverSilentlyShortTest",
        expect_fail=["nestedExpansionRowWithoutGroupIdRefuses"],
    ),
    dict(
        id="IE",
        what="nested expansion: a valueless hierarchy row is skipped again — the unmeasured twin of HK",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/UserGroupDaoDelegate.java",
        find_span=('\t\t\t\t\tif (row.getValue() == null) {\n\t\t\t\t\t\tthrow new IllegalStateException("a group-hierarchy row for group \'"\n\t\t\t\t\t\t\t\t+ groupId + "\' carries no value',
                   'membership short");\n\t\t\t\t\t}'),
        replace="\t\t\t\t\tif (row.getValue() == null) {\n\t\t\t\t\t\tcontinue;\n\t\t\t\t\t}",
        test="MembershipAnswersAreNeverSilentlyShortTest",
        expect_fail=["nestedExpansionNullValueRefuses"],
    ),
    dict(
        id="IF",
        what="RSS folder filter: the short-listing check only lives at depth 0 again",
        file="core/src/main/java/jp/aegif/nemaki/rss/RssFeedService.java",
        find="        if (contentService.lastUnreadableChildCount() > 0) {",
        replace="        if (currentDepth == 0 && contentService.lastUnreadableChildCount() > 0) {",
        test="RssFeedsAreNeverSilentlyShortTest",
        expect_fail=["aDeepShortChildListingRefuses"],
    ),
    dict(
        id="IG",
        what="RSS folder filter: a null child listing silently drops the subtree again",
        file="core/src/main/java/jp/aegif/nemaki/rss/RssFeedService.java",
        find_span=('        if (children == null) {\n            throw new IllegalStateException("the children of',
                   'cannot be built from that");\n        }'),
        replace="        if (children == null) {\n            return;\n        }",
        test="RssFeedsAreNeverSilentlyShortTest",
        expect_fail=["aNullChildListingRefuses"],
    ),
    dict(
        id="IH",
        what="type definitions: a short type list is served again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/TypeDefinitionDaoDelegate.java",
        # Re-anchored: three more unreadableRows guards were added to this file for the
        # property-definition reads, so the bare `if (unreadableRows > 0) {` now matches four
        # times and the runner refused it. The anchor carries the type-definition message.
        find_span=("\t\t\tif (unreadableRows > 0) {\n\t\t\t\tthrow new IllegalStateException(unreadableRows + \" type definition row(s) in '\"",
                   'delete the unreadable ones downstream");\n\t\t\t}'),
        replace="\t\t\tif (false) {\n\t\t\t\tthrow new IllegalStateException(\"unreachable\");\n\t\t\t}",
        test="TypeDefinitionsAreNotSilentlyFewerTest",
        expect_fail=["anUnreadableTypeRowRefuses"],
    ),
    dict(
        id="II",
        what="type definitions: a failed read synthesizes a two-type repository again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/TypeDefinitionDaoDelegate.java",
        find_span=('\t\t\tthrow new IllegalStateException("the type definitions of \'" + repositoryId',
                   '" exist", e);'),
        replace='\t\t\treturn new ArrayList<NemakiTypeDefinition>();',
        test="TypeDefinitionsAreNotSilentlyFewerTest",
        expect_fail=["aFailedReadRefuses"],
    ),
    dict(
        id="IJ",
        what="getContent: a failed lookup is 'does not exist' again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/ContentDaoServiceImpl.java",
        find_span=('\t\t\tthrow new IllegalStateException("the content \'" + objectId + "\' in \'" + repositoryId',
                   'does not exist", e);'),
        replace="\t\t\treturn null;",
        test="ContentLookupFailuresAreNotAbsenceTest",
        expect_fail=["aFailedLookupThrows"],
    ),
    dict(
        id="IK",
        what="principal delete: a failed parent re-fetch is skipped again (user)",
        file="core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java",
        find_span=('\t\t\t\t// Same as the group twin above: re-creating the same user id later would',
                   'dangling membership");'),
        replace="\t\t\t\tcontinue;",
        test="PrincipalDeleteRefusesDanglingReferencesTest",
        expect_fail=["aFailedRefetchAbortsTheUserDelete"],
    ),
    dict(
        id="IL",
        what="principal delete: a failed parent re-fetch is skipped again (group twin)",
        file="core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java",
        find_span=('\t\t\t\t// The view SAID this group references the principal being deleted',
                   'nested reference");'),
        replace="\t\t\t\tcontinue;",
        test="PrincipalDeleteRefusesDanglingReferencesTest",
        expect_fail=["aFailedRefetchAbortsTheGroupDelete"],
    ),
    dict(
        id="IM",
        what="trash byCreator: an unreadable row shortens the listing again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/ArchiveDaoDelegate.java",
        find="\t\t\tif (unreadable > 0) {\n\t\t\t\tthrow new IllegalStateException(unreadable + \" archive row(s) by creator could\"",
        replace="\t\t\tif (false) {\n\t\t\t\tthrow new IllegalStateException(unreadable + \" archive row(s) by creator could\"",
        test="ArchiveCountsAreNotZeroOnFailureTest",
        expect_fail=["byCreatorUnreadableRowRefuses"],
    ),
    dict(
        id="IN",
        what="trash byCreator: an unanswered view is an empty trash again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/ArchiveDaoDelegate.java",
        find_span=('\t\t\tif (result == null || result.getRows() == null) {\n\t\t\t\tthrow new IllegalStateException("the byCreator view answered without rows',
                   'being empty");\n\t\t\t}'),
        replace="\t\t\tif (result == null || result.getRows() == null) {\n\t\t\t\treturn new ArrayList<Archive>();\n\t\t\t}",
        test="ArchiveCountsAreNotZeroOnFailureTest",
        expect_fail=["byCreatorNullRowsRefuse"],
    ),
    dict(
        id="IO",
        what="archive byOriginalId: a failed lookup is 'no archive' again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/ArchiveDaoDelegate.java",
        find_span=('\t\t\tthrow new IllegalStateException("the archive for original \'" + originalId + "\' in \'"',
                   'none exists", e);'),
        replace="\t\t\treturn null;",
        test="ArchiveCountsAreNotZeroOnFailureTest",
        expect_fail=["byOriginalIdFailureRefuses"],
    ),
    dict(
        id="IP",
        what="wrapper: a failed count answers 0 again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/CloudantClientWrapper.java",
        find_span=('\t\t\tlog.error("Error getting view count for " + designDoc + "/" + viewName + ": " + e.getMessage(), e);\n\t\t\tthrow new org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException(\n\t\t\t\t\t"CouchDB view count failed',
                   'e.getMessage(), e);\n\t\t}'),
        replace='\t\t\tlog.error("Error getting view count for " + designDoc + "/" + viewName + ": " + e.getMessage(), e);\n\t\t\treturn 0;\n\t\t}',
        test="CloudantViewFailuresAreNotEmptyAnswersTest",
        expect_fail=["aFailedCountThrows"],
    ),
    dict(
        id="IQ",
        what="wrapper: a failed keyed count answers 0 again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/CloudantClientWrapper.java",
        find_span=('\t\t\tlog.error("Error getting view count by key for " + designDoc + "/" + viewName + " (key=" + key + "): " + e.getMessage(), e);\n\t\t\tthrow',
                   'e.getMessage(), e);\n\t\t}'),
        replace='\t\t\tlog.error("Error getting view count by key for " + designDoc + "/" + viewName + " (key=" + key + "): " + e.getMessage(), e);\n\t\t\treturn 0;\n\t\t}',
        test="CloudantViewFailuresAreNotEmptyAnswersTest",
        expect_fail=["aFailedKeyedCountThrows"],
    ),
    dict(
        id="IR",
        what="wrapper: a failed paged read answers an empty page again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/CloudantClientWrapper.java",
        find_span=('\t\t\tlog.error("Error in queryViewPaged " + designDoc + "/" + viewName + ": " + e.getMessage(), e);\n\t\t\tthrow',
                   'e.getMessage(), e);\n\t\t}'),
        replace='\t\t\tlog.error("Error in queryViewPaged " + designDoc + "/" + viewName + ": " + e.getMessage(), e);\n\t\t\treturn new PagedViewResult<>(new ArrayList<>(), 0);\n\t\t}',
        test="CloudantViewFailuresAreNotEmptyAnswersTest",
        expect_fail=["aFailedPagedReadThrows"],
    ),
    dict(
        id="IS",
        what="wrapper: a failed keyed paged read answers an empty page again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/CloudantClientWrapper.java",
        find_span=('\t\t\tlog.error("Error in queryViewPagedWithKey " + designDoc + "/" + viewName + ": " + e.getMessage(), e);\n\t\t\tthrow',
                   'e.getMessage(), e);\n\t\t}'),
        replace='\t\t\tlog.error("Error in queryViewPagedWithKey " + designDoc + "/" + viewName + ": " + e.getMessage(), e);\n\t\t\treturn new PagedViewResult<>(new ArrayList<>(), 0);\n\t\t}',
        test="CloudantViewFailuresAreNotEmptyAnswersTest",
        expect_fail=["aFailedKeyedPagedReadThrows"],
    ),
    dict(
        id="IT",
        what="RSS tokens: a documentless row is skipped from the revocation list again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/RssTokenDaoServiceImpl.java",
        find_span=('            if (row.getDoc() == null) {\n                throw new IllegalStateException("an RSS token row for user \'" + userId\n                        + "\' carries no document',
                   'token list short");\n            }'),
        replace="            if (row.getDoc() == null) {\n                continue;\n            }",
        test="RssTokenListingsAreNeverSilentlyShortTest",
        expect_fail=["aDocumentlessTokenRowRefuses"],
    ),
    dict(
        id="IU",
        what="RSS tokens: an unanswered view is 'no tokens' again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/RssTokenDaoServiceImpl.java",
        find_span=('        if (result == null || result.getRows() == null) {\n            throw new IllegalStateException("the RSS token view answered without rows',
                   'no tokens");\n        }'),
        replace="        if (result == null || result.getRows() == null) {\n            return tokens;\n        }",
        test="RssTokenListingsAreNeverSilentlyShortTest",
        expect_fail=["aTokenListingWithoutRowsRefuses"],
    ),
    dict(
        id="IV",
        what="RSS tokens: a failed getById is 'Token not found' again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/RssTokenDaoServiceImpl.java",
        find_span=('            log.error("Failed to get RSS token: " + e.getMessage(), e);\n            throw new IllegalStateException("the RSS token \'" + tokenId',
                   'does not exist", e);'),
        replace='            log.error("Failed to get RSS token: " + e.getMessage(), e);\n            return null;',
        test="RssTokenListingsAreNeverSilentlyShortTest",
        expect_fail=["aFailedGetByIdRefuses"],
    ),
    dict(
        id="IW",
        what="change feed: an out-of-range maxItems truncates into 'no limit' again",
        file="core/src/main/java/jp/aegif/nemaki/businesslogic/impl/delegate/ChangeEventServiceDelegate.java",
        find_span=("\t\t// intValue() TRUNCATES: 2^31 becomes negative, 2^32 becomes 0",
                   ": maxItems.intValue();"),
        replace="\t\tint limit = maxItems.intValue();",
        test="ChangeEventServiceDelegateTest",
        expect_fail=["anOutOfRangeMaxItemsIsClamped"],
    ),
    dict(
        id="IX",
        what="change feed: a non-positive limit is an unbounded query again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/ChangeEventDaoDelegate.java",
        find_span=('\t\t\t// ALWAYS bounded. maxItems <= 0 used to mean "no limit param"',
                   ': MAX_CHANGE_PAGE);'),
        replace='\t\t\tif (maxItems > 0) {\n\t\t\t\tqueryParams.put("limit", Math.min(maxItems, MAX_CHANGE_PAGE));\n\t\t\t}',
        test="ChangeEventDaoDelegateLimitTest",
        expect_fail=["aNonPositiveLimitIsStillBounded"],
    ),
    dict(
        id="IY",
        what="RSS: a non-positive limit flows into the change query again",
        file="core/src/main/java/jp/aegif/nemaki/rss/RssFeedService.java",
        find="        // Same lower bound as the folder feed: a non-positive limit must not reach the DAO.\n        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, maxLimit) : defaultLimit;",
        replace="        int effectiveLimit = limit != null ? Math.min(limit, maxLimit) : defaultLimit;",
        test="RssFeedsAreNeverSilentlyShortTest",
        expect_fail=["aNonPositiveLimitFallsBackToTheDefault"],
    ),
    dict(
        id="IZ",
        what="RSS: a negative maxDepth kills the folder walk again",
        file="core/src/main/java/jp/aegif/nemaki/rss/RssFeedService.java",
        find="        int effectiveMaxDepth = (maxDepth != null && maxDepth >= 0)\n                ? Math.min(maxDepth, MAX_DEPTH_LIMIT) : defaultMaxDepth;",
        replace="        int effectiveMaxDepth = maxDepth != null ? maxDepth : defaultMaxDepth;",
        test="RssFeedsAreNeverSilentlyShortTest",
        expect_fail=["aNegativeMaxDepthFallsBackToTheDefault"],
    ),
    dict(
        id="JA",
        what="cloud widening: a document that did not publish enters the baseline again",
        file="core/src/main/java/jp/aegif/nemaki/rest/purview/publish/PurviewCloudMetadataPublishServiceImpl.java",
        find="                documentPublishService.upsertContents(repositoryId, List.of(changedDocument));\n                if (documentPublishService.lastEntityPublishFailureCount() == 0) {",
        replace="                documentPublishService.upsertContents(repositoryId, List.of(changedDocument));\n                if (documentPublishService.lastEntityPublishFailureCount() >= 0) {",
        test="PurviewCloudMetadataPublishServiceImplTest",
        expect_fail=["aPartiallyPublishedRoundWidensOnlyWhatLanded"],
    ),
    dict(
        id="JB",
        what="change feed: maxItems=0 with a token becomes a one-row treadmill again",
        file="core/src/main/java/jp/aegif/nemaki/businesslogic/impl/delegate/ChangeEventServiceDelegate.java",
        find="\t\tif (limit <= 0) {\n\t\t\tlimit = Integer.MAX_VALUE;\n\t\t}",
        replace="",
        test="ChangeEventServiceDelegateTest",
        expect_fail=["aResumedZeroAskStillPagesFully"],
    ),
    dict(
        id="JC",
        what="cached getContent flattens the store's refusal back into null again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/cached/ContentDaoServiceImpl.java",
        find_span=('\t\t\tthrow new IllegalStateException("the content \'" + objectId\n\t\t\t\t\t+ "\' could not be read through the cache',
                   'does not exist", e);'),
        replace="\t\t\treturn null;",
        test="CachedLookupFailuresAreNotAbsenceTest",
        expect_fail=["aCacheMissOverAFailureThrows"],
    ),
    dict(
        id="JD",
        what="cached getFolder re-flattens getContent's refusal again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/cached/ContentDaoServiceImpl.java",
        find_span=("\t\t// No catch: getContent now throws for failures and returns null only for absence.",
                   "Content content = this.getContent(repositoryId, objectId);"),
        replace="\t\tContent content = null;\n\t\ttry {\n\t\t\tcontent = this.getContent(repositoryId, objectId);\n\t\t} catch (Exception e) {\n\t\t\treturn null;\n\t\t}",
        test="CachedLookupFailuresAreNotAbsenceTest",
        expect_fail=["getFolderPropagatesTheRefusal"],
    ),
    dict(
        id="JE",
        what="getDocumentFresh swallows the delegate's refusal again — the Fresh family",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/cached/ContentDaoServiceImpl.java",
        find="\t\tDocument freshDocument = nonCachedContentDaoService.getDocument(repositoryId, objectId);",
        replace="\t\tDocument freshDocument;\n\t\ttry {\n\t\t\tfreshDocument = nonCachedContentDaoService.getDocument(repositoryId, objectId);\n\t\t} catch (Exception e) {\n\t\t\treturn null;\n\t\t}",
        test="CachedLookupFailuresAreNotAbsenceTest",
        expect_fail=["getDocumentFreshPropagates"],
    ),
    dict(
        id="JF",
        what="cached getGroupItemByIdFresh answers 'no such group' for a failure again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/cached/ContentDaoServiceImpl.java",
        find_span=('\t\t\tthrow new IllegalStateException("group \'" + groupId\n\t\t\t\t\t+ "\' could not be freshly read',
                   'exist", e);'),
        replace="\t\t\treturn null;",
        test="CachedLookupFailuresAreNotAbsenceTest",
        expect_fail=["getGroupItemByIdFreshRefuses"],
    ),
    dict(
        id="JG",
        what="the typed wrapper get answers null for a failure again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/CloudantClientWrapper.java",
        find_span=('\t\t\tlog.error("Error getting document with ID: " + id + " as class: " + clazz.getName() + ": " + e.getMessage(), e);\n\t\t\tthrow new org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException(\n\t\t\t\t\t"Failed to read document \'" + id',
                   'does not exist", e);'),
        replace='\t\t\treturn null;',
        test="CloudantViewFailuresAreNotEmptyAnswersTest",
        expect_fail=["aFailedTypedGetThrows"],
    ),
    dict(
        id="JH",
        what="the type registry completes initialization base-only again",
        file="core/src/main/java/jp/aegif/nemaki/cmis/aspect/type/impl/TypeManagerImpl.java",
        find_span=('\t\t\tthrow new IllegalStateException("the type definitions of \'" + repositoryId\n\t\t\t\t\t+ "\' could not be loaded into the type registry',
                   'base-only type system", e);'),
        replace="\t\t\treturn;",
        test="TypeRegistryRefusesBaseOnlyInitTest",
        expect_fail=["aFailedTypeReadAbortsTheRefresh"],
    ),
    dict(
        id="JI",
        what="deleteDocument 'falls back to single version deletion' over the series again",
        file="core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java",
        find_span=('\t\t\t\tlog.error("getAllVersions failed for versionSeriesId {}: {}", versionSeriesId, e.getMessage(), e);\n\t\t\t\tthrow new IllegalStateException("the version series',
                   'would orphan the versions it hides", e);'),
        replace='\t\t\t\tlog.error("getAllVersions failed for versionSeriesId {}: {}", versionSeriesId, e.getMessage(), e);\n\t\t\t\tlog.warn("Falling back to single version deletion for document: {}", objectId);\n\t\t\t\tversionList.add(document);',
        test="DeleteFlowsRefuseBlindVersionListsTest",
        expect_fail=["theAllVersionsCatchRefuses"],
    ),
    dict(
        id="JJ",
        what="the single-version delete escalates blind to a series delete again",
        file="core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java",
        find_span=('\t\t\t\tlog.error("Failed to get all versions for single version deletion: {}", e.getMessage(), e);\n\t\t\t\tthrow new IllegalStateException("the version series',
                   'without the list", e);'),
        replace='\t\t\t\tlog.error("Failed to get all versions for single version deletion: {}", e.getMessage(), e);',
        test="DeleteFlowsRefuseBlindVersionListsTest",
        expect_fail=["theSingleVersionCatchRefuses"],
    ),
    dict(
        id="JK",
        what="a partially failed edge delete proceeds over the survivors again",
        file="core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java",
        find_span=("\t\tif (deletedCount < relationshipIds.size()) {\n\t\t\t// A WARN here let the object deletion proceed",
                   'orphaning the survivors");\n\t\t}'),
        replace='\t\tif (deletedCount < relationshipIds.size()) {\n\t\t\tlog.warn("deleteRelationshipsBatch: Only " + deletedCount + " of " + relationshipIds.size() + " relationships were deleted");\n\t\t}',
        test="DeleteFlowsRefuseBlindVersionListsTest",
        expect_fail=["aPartialEdgeDeleteAborts"],
    ),
    dict(
        id="JM",
        what="the principal-delete re-fetch goes back to the (stale-able) cached read",
        file="core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java",
        find="\t\t\t// (FRESH — same stale-cache argument as the group twin above)\n\t\t\tjp.aegif.nemaki.model.GroupItem g = getGroupItemByIdFresh(repositoryId, parentId);",
        replace="\t\t\tjp.aegif.nemaki.model.GroupItem g = getGroupItemById(repositoryId, parentId);",
        test="PrincipalDeleteRefusesDanglingReferencesTest",
        expect_fail=["aFailedRefetchAbortsTheUserDelete"],
    ),
    dict(
        id="JN",
        what="the singular latest-change lookup answers 'no changes' for a failure again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/ChangeEventDaoDelegate.java",
        find_span=('\t\t\tlog.error("Error getting latest change in repository: " + repositoryId, e);\n\t\t\tthrow new IllegalStateException("the latest change could not be read',
                   'no changes", e);'),
        replace='\t\t\treturn null;',
        test="ChangeEventDaoDelegateLimitTest",
        expect_fail=["aFailedLatestChangeLookupRefuses"],
    ),
    dict(
        id="JO",
        what="the childrenNames liveness probe says 'alive' when it cannot tell again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/ContentDaoServiceImpl.java",
        find_span=('\t\t\tthrow new IllegalStateException("could not establish whether the childrenNames"',
                   'cannot run blind", e);'),
        replace="\t\t\treturn true;",
        test="ContentLookupFailuresAreNotAbsenceTest",
        expect_fail=["aFailedLivenessProbeRefuses"],
    ),
    dict(
        id="JP",
        what="a failed bulk read returns the partial map again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/ContentDaoServiceImpl.java",
        find_span=('\t\t\tthrow new IllegalStateException("the bulk read of " + objectIds.size()',
                   'read as the whole", e);'),
        replace="\t\t\treturn result;",
        test="ContentLookupFailuresAreNotAbsenceTest",
        expect_fail=["aFailedBulkReadThrows"],
    ),
    dict(
        id="JQ",
        what="an unconvertible bulk row is warn-skipped again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/ContentDaoServiceImpl.java",
        find_span=('\t\t\t\t} catch (IllegalStateException e) {\n\t\t\t\t\tthrow e;\n\t\t\t\t} catch (Exception e) {\n\t\t\t\t\tthrow new IllegalStateException("document \'" + objectId + "\' was fetched"',
                   'answer short",\n\t\t\t\t\t\t\te);\n\t\t\t\t}'),
        replace='\t\t\t\t} catch (Exception e) {\n\t\t\t\t\tlog.warn("Failed to convert document " + objectId + ": " + e.getMessage());\n\t\t\t\t}',
        test="ContentLookupFailuresAreNotAbsenceTest",
        expect_fail=["anUnconvertibleBulkRowThrows"],
    ),
    dict(
        id="JR",
        what="a failed version-series lookup answers 'none exists' again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/ContentDaoServiceImpl.java",
        find_span=('\t\t\tthrow new IllegalStateException("the version lookup for \'" + nodeId',
                   'none exists", e);'),
        replace="\t\t\treturn null;",
        test="ContentLookupFailuresAreNotAbsenceTest",
        expect_fail=["aFailedVersionSeriesLookupThrows"],
    ),
    dict(
        id="JS",
        what="a failed token delete reports 'Token deleted' again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/RssTokenDaoServiceImpl.java",
        find_span=('            throw new IllegalStateException("the RSS token \'" + tokenId + "\' could not be"',
                   'revoked token alive", e);'),
        replace="            return;",
        test="RssTokenListingsAreNeverSilentlyShortTest",
        expect_fail=["aFailedDeleteRefuses"],
    ),
    dict(
        id="JT",
        what="a failed validation lookup is 'invalid token' again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/RssTokenDaoServiceImpl.java",
        find_span=('            throw new IllegalStateException("the RSS token could not be validated against \'"',
                   'is invalid", e);'),
        replace="            return null;",
        test="RssTokenListingsAreNeverSilentlyShortTest",
        expect_fail=["aFailedValidationLookupRefuses"],
    ),
    dict(
        id="JU",
        what="a documentless paged row silently shortens the page again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/CloudantClientWrapper.java",
        find_span=("\t\t\t\t// A row without its document (or without properties) is a row the caller\n\t\t\t\t// cannot see — the page silently shortened, which for the trash listings\n\t\t\t\t// reads as \"these archives do not exist\".\n\t\t\t\tif (row.getDoc() == null) {\n\t\t\t\t\tthrow new org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException(\n\t\t\t\t\t\t\t\"a row of \" + designDoc + \"/\" + viewName + \" carries no document;\"",
                   'serve the page short");\n\t\t\t\t}'),
        replace="\t\t\t\tif (row.getDoc() == null) {\n\t\t\t\t\tcontinue;\n\t\t\t\t}",
        test="CloudantViewFailuresAreNotEmptyAnswersTest",
        expect_fail=["aDocumentlessPagedRowRefusesThePage"],
    ),
    dict(
        id="JV",
        what="a failed group re-read answers 'no such group' again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/UserGroupDaoDelegate.java",
        find_span=('\t\t\tlog.error("Error in getGroupItemByIdInternal for groupId \'" + groupId + "\' in repository \'" + repositoryId + "\'", e);\n\t\t\tthrow new IllegalStateException("group \'" + groupId + "\' could not be read',
                   'does not exist", e);'),
        replace="\t\t\treturn null;",
        test="MembershipAnswersAreNeverSilentlyShortTest",
        expect_fail=["aFailedGroupRefetchRefuses"],
    ),
    dict(
        id="JW",
        what="an existing-but-unusable group answers 'does not exist' again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/UserGroupDaoDelegate.java",
        find_span=('\t\t\t\t\t\tthrow new IllegalStateException("group \'" + groupId + "\' exists but its"',
                   'does not exist");'),
        replace="\t\t\t\t\t\treturn null;",
        test="MembershipAnswersAreNeverSilentlyShortTest",
        expect_fail=["anUnusableExistingGroupRefuses"],
    ),
    dict(
        id="JX",
        what="the specific groupId-missing message is re-wrapped into the generic one again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/UserGroupDaoDelegate.java",
        find="\t\t\t\t\t\t} catch (IllegalStateException e) {\n\t\t\t\t\t\t\t// Not re-wrapped: the groupId-missing arm above already says\n\t\t\t\t\t\t\t// exactly what happened, and \"could not be read\" would bury it.\n\t\t\t\t\t\t\tthrow e;\n\t\t\t\t\t\t} catch (Exception e) {",
        replace="\t\t\t\t\t\t} catch (Exception e) {",
        test="MembershipAnswersAreNeverSilentlyShortTest",
        expect_fail=["joinedGroupsRowWithoutGroupIdRefuses"],
    ),
    dict(
        id="JY",
        what="the complete cloud walk advances the baseline over failed documents again",
        file="core/src/main/java/jp/aegif/nemaki/rest/purview/publish/PurviewCloudMetadataPublishServiceImpl.java",
        find="        if (!failedPublishDocuments.isEmpty()) {",
        replace="        if (false) {",
        test="PurviewCloudMetadataPublishServiceImplTest",
        expect_fail=["aCompleteWalkKeepsFailedDocumentsChangedInTheBaseline",
                     "aCompleteWalkDropsAFailedNewDocumentFromTheBaseline"],
    ),
    dict(
        id="KA",
        what="cached getContent: an unwired cache pool is 'the object does not exist' again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/cached/ContentDaoServiceImpl.java",
        find_span=('\t\tif (nemakiCachePool == null) {\n\t\t\tthrow new IllegalStateException(',
                   'getContent cannot answer");\n\t\t}'),
        replace="\t\tif (nemakiCachePool == null) {\n\t\t\treturn null;\n\t\t}",
        test="CachedLookupFailuresAreNotAbsenceTest",
        expect_fail=["anUnwiredCachePoolRefuses"],
    ),
    dict(
        id="KB",
        what="cached getContent: an unwired delegate is 'does not exist' again — the twin",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/cached/ContentDaoServiceImpl.java",
        find_span=('\t\tif (nonCachedContentDaoService == null) {\n\t\t\tthrow new IllegalStateException(\n\t\t\t\t\t"nonCachedContentDaoService is not wired; getContent cannot answer");',
                   'getContent cannot answer");\n\t\t}'),
        replace="\t\tif (nonCachedContentDaoService == null) {\n\t\t\treturn null;\n\t\t}",
        test="CachedLookupFailuresAreNotAbsenceTest",
        expect_fail=["anUnwiredDelegateRefuses"],
    ),
    dict(
        id="KC",
        what="bulk read: an unexplained row is skipped again, shortening the map",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/CloudantClientWrapper.java",
        find_span=("\t\t\t\t\tif (row.getDoc() == null || row.getId() == null) {",
                   'answer the batch short");\n\t\t\t\t\t}'),
        replace="\t\t\t\t\tif (row.getDoc() == null || row.getId() == null) {\n\t\t\t\t\t\tcontinue;\n\t\t\t\t\t}",
        test="CloudantViewFailuresAreNotEmptyAnswersTest",
        expect_fail=["anUnexplainedBulkRowRefuses"],
    ),
    dict(
        id="KD",
        what="bulk read: a row error that is not absence is skipped again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/CloudantClientWrapper.java",
        find_span=("\t\t\t\t\tif (rowError != null && !rowError.isBlank()) {",
                   'the document being absent");\n\t\t\t\t\t}'),
        replace="\t\t\t\t\tif (rowError != null && !rowError.isBlank()) {\n\t\t\t\t\t\tcontinue;\n\t\t\t\t\t}",
        test="CloudantViewFailuresAreNotEmptyAnswersTest",
        expect_fail=["aFailedBulkRowRefuses"],
    ),
    dict(
        id="KE",
        what="bulk read: a failed batch continues with the next one again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/CloudantClientWrapper.java",
        find_span=('\t\t\t\tthrow new org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException(\n\t\t\t\t\t\t"bulk read batch " + (i + 1)',
                   'missing documents being absent", e);'),
        replace="",
        test="CloudantViewFailuresAreNotEmptyAnswersTest",
        expect_fail=["aFailedBulkBatchRefuses"],
    ),
    dict(
        id="KF",
        what="the liveness probe says 'alive' with no client again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/ContentDaoServiceImpl.java",
        find_span=("\t\t\tif (client == null) {\n\t\t\t\t// \"Say alive\" here is the same answer",
                   'uniqueness check must not run");\n\t\t\t}'),
        replace="\t\t\tif (client == null) {\n\t\t\t\treturn true;\n\t\t\t}",
        test="ContentLookupFailuresAreNotAbsenceTest",
        expect_fail=["aClientlessLivenessProbeRefuses"],
    ),
    dict(
        id="KG",
        what="cloud baseline: the mixed count decides landing again",
        file="core/src/main/java/jp/aegif/nemaki/rest/purview/publish/PurviewCloudMetadataPublishServiceImpl.java",
        find="            // Same rule as the incomplete arm above: the batch return is a mixed count.\n            documentPublishService.upsertContents(repositoryId, List.of(changedDocument));\n            if (documentPublishService.lastEntityPublishFailureCount() == 0) {",
        replace="            if (documentPublishService.upsertContents(repositoryId, List.of(changedDocument)) > 0) {",
        test="PurviewCloudMetadataPublishServiceImplTest",
        expect_fail=["aDocumentWhoseEntityFailedDoesNotEnterTheBaselineOnAMixedCount"],
    ),
    dict(
        id="KH",
        what="publish service: an unbuildable entity is not counted as a failure again",
        file="core/src/main/java/jp/aegif/nemaki/rest/purview/publish/PurviewDocumentPublishServiceImpl.java",
        find_span=("            if (entity == null) {\n                // Never even attempted",
                   "lastEntityPublishFailures.set(lastEntityPublishFailures.get() + 1);\n                continue;\n            }"),
        replace="            if (entity == null) {\n                continue;\n            }",
        test="PurviewDocumentPublishServiceImplTest",
        expect_fail=["anUnbuildableEntityIsCountedAsAPublishFailure"],
    ),
    dict(
        id="KI",
        what="type registry: a null type definition is skipped again",
        file="core/src/main/java/jp/aegif/nemaki/cmis/aspect/type/impl/TypeManagerImpl.java",
        find_span=('\t\t\t\tif (subtype == null) {\n\t\t\t\t\tthrow new IllegalStateException("a null type definition came back',
                   'assemble the registry around it");\n\t\t\t\t}'),
        replace='\t\t\t\tif (subtype == null) {\n\t\t\t\t\tcontinue;\n\t\t\t\t}',
        test="TypeRegistryRefusesBaseOnlyInitTest",
        expect_fail=["aNullTypeDefinitionAborts"],
    ),
    dict(
        id="KJ",
        what="type registry: a type without BaseId is skipped again",
        file="core/src/main/java/jp/aegif/nemaki/cmis/aspect/type/impl/TypeManagerImpl.java",
        find_span=('\t\t\t\tif (subtype.getBaseId() == null) {\n\t\t\t\t\tthrow new IllegalStateException("type definition',
                   'silently omits it");\n\t\t\t\t}'),
        replace='\t\t\t\tif (subtype.getBaseId() == null) {\n\t\t\t\t\tcontinue;\n\t\t\t\t}',
        test="TypeRegistryRefusesBaseOnlyInitTest",
        expect_fail=["aTypeWithoutBaseIdAborts"],
    ),
    dict(
        id="KK",
        what="navigation: maxItems is truncated by intValue() again",
        file="core/src/main/java/jp/aegif/nemaki/cmis/service/impl/NavigationServiceImpl.java",
        find="\t\tint _maxItems = (maxItems != null) ? clampToPage(maxItems) : DEFAULT_MAX_ITEMS;",
        replace="\t\tint _maxItems = (maxItems != null) ? maxItems.intValue() : DEFAULT_MAX_ITEMS;",
        test="NavigationPagingArgumentsAreNotTruncatedTest",
        expect_fail=["theCallSitesUseTheClamps"],
    ),
    dict(
        id="KL",
        what="navigation: the clamp itself stops clamping (helper half)",
        file="core/src/main/java/jp/aegif/nemaki/cmis/service/impl/NavigationServiceImpl.java",
        find="\t\treturn maxItems.compareTo(java.math.BigInteger.valueOf(MAX_PAGE)) >= 0\n\t\t\t\t? MAX_PAGE\n\t\t\t\t: maxItems.intValue();",
        replace="\t\treturn maxItems.intValue();",
        test="NavigationPagingArgumentsAreNotTruncatedTest",
        expect_fail=["aHugeMaxItemsBecomesAPage"],
    ),
    dict(
        id="KM",
        what="compile service: the page arithmetic truncates maxItems again",
        file="core/src/main/java/jp/aegif/nemaki/cmis/aspect/impl/CompileServiceImpl.java",
        find="\t\t\tint _maxItems = clampMaxItems(skipCount, maxItems);\n\n\t\t\tif (_skipCount >= objectDataList.size()) {",
        replace="\t\t\tint _maxItems = maxItems.intValue();\n\n\t\t\tif (_skipCount >= objectDataList.size()) {",
        test="CompiledPagesAreNotTruncatedTest",
        expect_fail=["bothPagingBlocksUseTheClamps"],
    ),
    dict(
        id="KN",
        what="compile service: the clamp itself stops clamping (helper half)",
        file="core/src/main/java/jp/aegif/nemaki/cmis/aspect/impl/CompileServiceImpl.java",
        find="\t\tint page = maxItems.compareTo(BigInteger.valueOf(MAX_PAGE)) >= 0\n\t\t\t\t? MAX_PAGE\n\t\t\t\t: maxItems.intValue();",
        replace="\t\tint page = maxItems.intValue();",
        test="CompiledPagesAreNotTruncatedTest",
        expect_fail=["aHugeMaxItemsBecomesAPage"],
    ),
    dict(
        id="KO",
        what="navigation: the small-folder branch hands the compile service raw values again",
        file="core/src/main/java/jp/aegif/nemaki/cmis/service/impl/NavigationServiceImpl.java",
        find="\t\t\t\t\t\tBigInteger.valueOf(_maxItems), BigInteger.valueOf(_skipCount),\n\t\t\t\t\t\tfolderOnly, orderBy);",
        replace="\t\t\t\t\t\tmaxItems, skipCount, folderOnly, orderBy);",
        test="NavigationPagingArgumentsAreNotTruncatedTest",
        expect_fail=["theCallSitesUseTheClamps"],
    ),
    dict(
        id="KP",
        what="type assembly: a typeless definition drops the type and its subtree again",
        file="core/src/main/java/jp/aegif/nemaki/cmis/aspect/type/impl/TypeManagerImpl.java",
        find_span=('\t\tif (type == null || type.getTypeId() == null) {\n\t\t\tthrow new IllegalStateException("a type definition with no typeId',
                   'assemble the registry around it");\n\t\t}'),
        replace="\t\tif (type == null || type.getTypeId() == null) {\n\t\t\treturn;\n\t\t}",
        test="TypeRegistryRefusesBaseOnlyInitTest",
        expect_fail=["theAssemblyHalfRefusesATypelessDefinition"],
    ),
    dict(
        id="KQ",
        what="type listing: maxItems is truncated by intValue() again",
        file="core/src/main/java/jp/aegif/nemaki/cmis/aspect/type/impl/TypeManagerImpl.java",
        find="\t\treturn maxItems.compareTo(java.math.BigInteger.valueOf(MAX_TYPE_PAGE)) >= 0\n\t\t\t\t? MAX_TYPE_PAGE\n\t\t\t\t: maxItems.intValue();",
        replace="\t\treturn maxItems.intValue();",
        test="TypeRegistryRefusesBaseOnlyInitTest",
        expect_fail=["aHugeTypeListingMaxItemsIsAPage"],
    ),
    dict(
        id="KR",
        what="type listing: skip and depth are truncated again",
        file="core/src/main/java/jp/aegif/nemaki/cmis/aspect/type/impl/TypeManagerImpl.java",
        find="\t\treturn skipCount.compareTo(java.math.BigInteger.valueOf(Integer.MAX_VALUE)) >= 0\n\t\t\t\t? Integer.MAX_VALUE\n\t\t\t\t: skipCount.intValue();",
        replace="\t\treturn skipCount.intValue();",
        test="TypeRegistryRefusesBaseOnlyInitTest",
        expect_fail=["typeListingSkipAndDepthKeepTheirMeaning"],
    ),
    dict(
        id="KS",
        what="query paging: Math.max(0, intValue()) comes back",
        file="core/src/main/java/jp/aegif/nemaki/cmis/aspect/query/solr/SolrQueryProcessor.java",
        find="\t\t\t\tint max = (maxItems == null) ? totalAuthorized : clampQueryPage(maxItems);",
        replace="\t\t\t\tint max = (maxItems == null) ? totalAuthorized : Math.max(0, maxItems.intValue());",
        test="QueryPagingArgumentsAreNotTruncatedTest",
        expect_fail=["theQueryPagerUsesTheClamps"],
    ),
    dict(
        id="KT",
        what="query paging: the clamp itself stops clamping (helper half)",
        file="core/src/main/java/jp/aegif/nemaki/cmis/aspect/query/solr/SolrQueryProcessor.java",
        find="\t\treturn maxItems.compareTo(java.math.BigInteger.valueOf(MAX_QUERY_PAGE)) >= 0\n\t\t\t\t? MAX_QUERY_PAGE\n\t\t\t\t: maxItems.intValue();",
        replace="\t\treturn maxItems.intValue();",
        test="QueryPagingArgumentsAreNotTruncatedTest",
        expect_fail=["aHugeQueryMaxItemsIsAPage"],
    ),
    dict(
        id="KU",
        what="the liveness probe says 'alive' when the document count does not answer",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/ContentDaoServiceImpl.java",
        find_span=("\t\t\tif (info == null || info.getDocCount() == null) {",
                   'uniqueness check must not run");\n\t\t\t}\n\t\t\treturn info.getDocCount() <= 10L;'),
        replace="\t\t\treturn info == null || info.getDocCount() == null || info.getDocCount() <= 10L;",
        test="ContentLookupFailuresAreNotAbsenceTest",
        expect_fail=["aCountlessLivenessProbeRefuses"],
    ),
    dict(
        id="KV",
        what="bulk read: a requested id with no row at all is silently absent again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/CloudantClientWrapper.java",
        find_span=("\t\tjava.util.Set<String> answered = new java.util.HashSet<>(result.keySet());",
                   'document being absent");\n\t\t\t}\n\t\t}'),
        replace="",
        test="CloudantViewFailuresAreNotEmptyAnswersTest",
        expect_fail=["aRequestedIdWithNoRowRefuses"],
    ),
    dict(
        id="KW",
        what="compile paging: a non-positive maxItems is an empty page again",
        file="core/src/main/java/jp/aegif/nemaki/cmis/aspect/impl/CompileServiceImpl.java",
        find_span=("\t\tif (maxItems == null || maxItems.signum() <= 0) {\n\t\t\t// A non-positive ask is a DEFAULT page",
                   "return DEFAULT_PAGE_FOR_NON_POSITIVE;\n\t\t}"),
        replace="\t\tif (maxItems == null || maxItems.signum() <= 0) {\n\t\t\treturn maxItems == null ? MAX_PAGE : 0;\n\t\t}",
        test="CompiledPagesAreNotTruncatedTest",
        expect_fail=["aNonPositiveMaxItemsIsTheDefaultPage"],
    ),
    dict(
        id="KX",
        what="policy lookup: a failed read is 'no such policy' again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/ContentDaoServiceImpl.java",
        find_span=('\t\t\tthrow new IllegalStateException("the policy \'" + objectId + "\' in \'" + repositoryId',
                   'does not exist", e);'),
        replace="\t\t\treturn null;",
        test="IdentityAndPolicyLookupsRefuseFailuresTest",
        expect_fail=["aFailedPolicyLookupRefuses"],
    ),
    dict(
        id="KY",
        what="user-by-id lookup: a failed read is 'no such user' again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/UserGroupDaoDelegate.java",
        find_span=('\t\t\tthrow new IllegalStateException("the user \'" + userId + "\' could not be read in \'"',
                   'does not exist", e);'),
        replace="\t\t\treturn null;",
        test="IdentityAndPolicyLookupsRefuseFailuresTest",
        expect_fail=["aFailedUserByIdLookupRefuses"],
    ),
    dict(
        id="KZ",
        what="group lookup: a failed read is 'no such group' again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/UserGroupDaoDelegate.java",
        find_span=('\t\t\tthrow new IllegalStateException("the group item \'" + objectId + "\' in \'" + repositoryId',
                   'does not exist", e);'),
        replace="\t\t\treturn null;",
        test="IdentityAndPolicyLookupsRefuseFailuresTest",
        expect_fail=["aFailedGroupItemLookupRefuses"],
    ),
    dict(
        id="LA",
        what="retention: a failed expiration sweep reports 'nothing expired' again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/ArchiveDaoDelegate.java",
        find_span=('\t\t\tthrow new IllegalStateException("the expired documents of \'" + repositoryId',
                   'none have expired", e);'),
        replace="\t\t\treturn new ArrayList<String>();",
        test="ArchiveCountsAreNotZeroOnFailureTest",
        expect_fail=["aFailedExpirationSweepRefuses"],
    ),
    dict(
        id="LB",
        what="RSS validation: a process-local cache answers again",
        file="core/src/main/java/jp/aegif/nemaki/rss/RssTokenService.java",
        find_span=("        if (rssTokenDaoService == null) {\n            // An access decision must not be made",
                   'a token cannot be validated");\n        }'),
        replace="        if (rssTokenDaoService == null) {\n            return null;\n        }",
        test="RssTokenServiceTest",
        expect_fail=["anUnwiredStoreRefusesInsteadOfAnsweringInvalid"],
    ),
    dict(
        id="LC",
        what="startup grace goes back to guessing from the thread name",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/CloudantClientWrapper.java",
        find_span=("\t\t// Declared, not guessed. The old form asked whether the current thread's NAME",
                   "return jp.aegif.nemaki.init.StartupPhase.isProvisioning();"),
        replace='\t\tString threadName = Thread.currentThread().getName();\n\t\treturn threadName.contains("main") || threadName.contains("startup") || threadName.contains("init");',
        test="StartupPhaseIsDeclaredNotGuessedTest",
        expect_fail=["theStoreLayerAsksStartupPhase"],
    ),
    dict(
        id="LD",
        what="the provisioning window is never opened, so provisioning runs strict",
        file="core/src/main/java/jp/aegif/nemaki/init/DatabasePreInitializer.java",
        find="        StartupPhase.begin();",
        replace="        // StartupPhase.begin();",
        test="StartupPhaseIsDeclaredNotGuessedTest",
        expect_fail=["provisioningDeclaresTheWindow"],
    ),
    dict(
        id="LE",
        what="the provisioning window default flips to lenient",
        file="core/src/main/java/jp/aegif/nemaki/init/StartupPhase.java",
        find="            new java.util.concurrent.atomic.AtomicInteger(0);",
        replace="            new java.util.concurrent.atomic.AtomicInteger(1);",
        test="StartupPhaseIsDeclaredNotGuessedTest",
        expect_fail=["theDefaultIsStrict"],
    ),
    dict(
        id="LF",
        what="directory sync deletes users with a private copy of the stripping again",
        file="core/src/main/java/jp/aegif/nemaki/sync/service/DirectorySyncServiceImpl.java",
        find="                            contentService.deleteUser(repositoryId, userId);",
        replace="                            contentService.delete(new SystemCallContext(repositoryId), repositoryId, existingUser.getId(), false);",
        test="DirectorySyncDeletesThroughTheCanonicalPathTest",
        expect_fail=["orphanUsersUseDeleteUser"],
    ),
    dict(
        id="LG",
        what="directory sync deletes groups with no nested-reference stripping again",
        file="core/src/main/java/jp/aegif/nemaki/sync/service/DirectorySyncServiceImpl.java",
        find="                            contentService.deleteGroup(repositoryId, existingGroup.getGroupId());",
        replace="                            contentService.delete(new SystemCallContext(repositoryId), repositoryId, existingGroup.getId(), false);",
        test="DirectorySyncDeletesThroughTheCanonicalPathTest",
        expect_fail=["orphanGroupsUseDeleteGroup"],
    ),
    dict(
        id="LH",
        what="document-entity dead letters go back to counting as failures for ever",
        file="core/src/main/java/jp/aegif/nemaki/rest/purview/sync/PurviewDeadLetterRetryServiceImpl.java",
        find_span=("        if (DOCUMENT_ENTITY_STREAM_KIND.equals(deadLetterState.getStreamKind())) {",
                   "retryDocumentEntityDeadLetter(repositoryId, deadLetterState);\n            return;\n        }"),
        replace="",
        test="PurviewDeadLetterRetryServiceImplTest",
        expect_fail=["aDocumentEntityDeadLetterIsRetriedInsteadOfCountedAsAFailureForEver"],
    ),
    dict(
        id="LI",
        what="a document whose entity still fails has its dead letter cleared anyway",
        file="core/src/main/java/jp/aegif/nemaki/rest/purview/sync/PurviewDeadLetterRetryServiceImpl.java",
        find_span=("        if (documentPublishService.lastEntityPublishFailureCount() > 0) {",
                   'keeping the dead letter");\n        }'),
        replace="",
        test="PurviewDeadLetterRetryServiceImplTest",
        expect_fail=["aDocumentEntityThatStillFailsKeepsItsDeadLetter"],
    ),
    dict(
        id="LJ",
        what="the recorded relationship GUIDs can no longer be forgotten (no repair path)",
        file="core/src/main/java/jp/aegif/nemaki/rest/purview/relationship/PurviewContainmentRelationshipServiceImpl.java",
        find="        stateStore.removeAll(new java.util.ArrayList<>(keys));",
        replace="",
        test="PurviewContainmentRelationshipServiceImplTest",
        expect_fail=["theRecordedGuidsCanBeForgottenSoTheCatalogIsRepairable"],
    ),
    dict(
        id="LK",
        # The original LK sabotaged a refusal that turned out to break login: null from the
        # keyed view means "no row for this userId", not "did not answer". The refusal was
        # withdrawn, so what must stay true is the opposite — absence keeps its answer.
        what="user-by-id: an absent user refuses again (the withdrawn contract returns)",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/UserGroupDaoDelegate.java",
        find_span=("\t\t\tif (result == null) {\n\t\t\t\tlog.debug(\"No user with userId \"",
                   "\t\t\t\treturn null;\n\t\t\t}"),
        replace="\t\t\tif (result == null) {\n\t\t\t\tthrow new IllegalStateException(\"unreachable\");\n\t\t\t}",
        test="IdentityAndPolicyLookupsRefuseFailuresTest",
        expect_fail=["anAbsentUserIsNotARefusal"],
    ),
    dict(
        id="LL",
        what="user-by-id: an unreadable row is skipped again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/UserGroupDaoDelegate.java",
        find_span=("\t\t\t\t\tif (!(rawDoc instanceof Map)) {\n\t\t\t\t\t\t// The row the answer may hinge on.",
                   'existence without it");\n\t\t\t\t\t}'),
        replace="\t\t\t\t\tif (!(rawDoc instanceof Map)) {\n\t\t\t\t\t\tcontinue;\n\t\t\t\t\t}",
        test="IdentityAndPolicyLookupsRefuseFailuresTest",
        expect_fail=["anUnreadableUserRowRefuses"],
    ),
    dict(
        id="LM",
        what="group-by-id: an absent group refuses again (the withdrawn contract returns)",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/UserGroupDaoDelegate.java",
        find_span=("\t\t\tif (result == null) {\n\t\t\t\tlog.debug(\"No group with groupId \"",
                   "\t\t\t\treturn null;\n\t\t\t}"),
        replace="\t\t\tif (result == null) {\n\t\t\t\tthrow new IllegalStateException(\"unreachable\");\n\t\t\t}",
        test="IdentityAndPolicyLookupsRefuseFailuresTest",
        expect_fail=["anAbsentGroupIsNotARefusal"],
    ),
    dict(
        id="LN",
        what="retention: an unanswered view is 'no candidates' again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/ArchiveDaoDelegate.java",
        find_span=('\t\t\t\t\tclient.queryView("_repo", "documentsByExpirationDate", params);\n\n\t\t\tList<String> ids = new ArrayList<String>();\n\t\t\tif (viewResult == null || viewResult.getRows() == null) {',
                   'candidates");\n\t\t\t}'),
        replace='\t\t\t\t\tclient.queryView("_repo", "documentsByExpirationDate", params);\n\n\t\t\tList<String> ids = new ArrayList<String>();\n\t\t\tif (viewResult == null || viewResult.getRows() == null) {\n\t\t\t\treturn new ArrayList<String>();\n\t\t\t}',
        test="ArchiveCountsAreNotZeroOnFailureTest",
        expect_fail=["anUnansweredRetentionViewRefuses"],
    ),
    dict(
        id="LO",
        what="type listing: a non-positive maxItems is an empty list again",
        file="core/src/main/java/jp/aegif/nemaki/cmis/aspect/type/impl/TypeManagerImpl.java",
        find_span=("\t\tif (maxItems.signum() <= 0) {\n\t\t\t// The DEFAULT page, not an empty one",
                   "return DEFAULT_TYPE_PAGE_FOR_NON_POSITIVE;\n\t\t}"),
        replace="\t\tif (maxItems.signum() <= 0) {\n\t\t\treturn 0;\n\t\t}",
        test="TypeRegistryRefusesBaseOnlyInitTest",
        expect_fail=["aNonPositiveTypeMaxItemsIsTheDefaultPage"],
    ),
    dict(
        id="LP",
        what="query paging: a non-positive maxItems is an empty page again",
        file="core/src/main/java/jp/aegif/nemaki/cmis/aspect/query/solr/SolrQueryProcessor.java",
        find_span=("\t\tif (maxItems == null || maxItems.signum() <= 0) {\n\t\t\t// The default page, matching the children listing",
                   "return DEFAULT_QUERY_PAGE_FOR_NON_POSITIVE;\n\t\t}"),
        replace="\t\tif (maxItems == null || maxItems.signum() <= 0) {\n\t\t\treturn 0;\n\t\t}",
        test="QueryPagingArgumentsAreNotTruncatedTest",
        expect_fail=["aNonPositiveQueryMaxItemsIsTheDefaultPage"],
    ),
    dict(
        id="LQ",
        what="user-by-id: an existing-but-unusable document is 'no such user' again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/UserGroupDaoDelegate.java",
        find_span=('\t\t\t\t\t\tthrow new IllegalStateException("user \'" + userId + "\' exists but its"',
                   'the user does not exist");'),
        replace="\t\t\t\t\t\treturn null;",
        test="IdentityAndPolicyLookupsRefuseFailuresTest",
        expect_fail=["anUnusableExistingUserRefuses"],
    ),
    dict(
        id="LR",
        what="type listing: the CALL SITE stops clamping (the sibling that had no lock)",
        file="core/src/main/java/jp/aegif/nemaki/cmis/aspect/type/impl/TypeManagerImpl.java",
        find="\t\tint max = clampPage(maxItems);",
        replace="\t\tint max = (maxItems == null) ? Integer.MAX_VALUE : maxItems.intValue();",
        test="TypeRegistryRefusesBaseOnlyInitTest",
        expect_fail=["theTypeListingCallSitesUseTheClamps"],
    ),
    dict(
        id="LS",
        what="type registry: a failed forced refresh answers 'no such type' again",
        file="core/src/main/java/jp/aegif/nemaki/cmis/aspect/type/impl/TypeManagerImpl.java",
        find_span=("\t\t\t\tlog.error(\"NEMAKI TYPE ERROR: Exception during forced refresh\", e);\n\t\t\t\tthrow new IllegalStateException(\"the type registry of '\" + repositoryId",
                   'the type does not exist", e);'),
        replace='\t\t\t\tlog.error("NEMAKI TYPE ERROR: Exception during forced refresh", e);',
        test="TypeRegistryRefusesBaseOnlyInitTest",
        expect_fail=["aFailedForcedRefreshRefuses"],
    ),
    dict(
        id="LT",
        what="the startup decision consults the thread name again, alongside the window",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/CloudantClientWrapper.java",
        find="\t\treturn jp.aegif.nemaki.init.StartupPhase.isProvisioning();",
        replace='\t\treturn jp.aegif.nemaki.init.StartupPhase.isProvisioning()\n\t\t\t\t|| Thread.currentThread().getName().contains("main");',
        test="StartupPhaseIsDeclaredNotGuessedTest",
        expect_fail=["theStoreLayerAsksStartupPhase"],
    ),
    dict(
        id="LU",
        what="provisioning drops the try/finally around its window",
        file="core/src/main/java/jp/aegif/nemaki/init/DatabasePreInitializer.java",
        find="        StartupPhase.begin();\n        try {\n            provisionDatabases(event);\n        } finally {\n            StartupPhase.end();\n        }",
        replace="        StartupPhase.begin();\n        provisionDatabases(event);\n        StartupPhase.end();",
        test="StartupPhaseIsDeclaredNotGuessedTest",
        expect_fail=["provisioningDeclaresTheWindow"],
    ),
    dict(
        id="MA",
        what="findChildTypes: an unanswered type list is 'nobody is my child' again",
        file="core/src/main/java/jp/aegif/nemaki/cmis/aspect/type/impl/TypeManagerImpl.java",
        find_span=("\t\tif (allTypes == null) {\n\t\t\tthrow new IllegalStateException(\"the type definitions of '\" + repositoryId",
                   ' established");\n\t\t}'),
        replace="\t\tif (allTypes == null) {\n\t\t\treturn childTypes;\n\t\t}",
        test="TypeDeletionRefusesUnknownDependenciesTest",
        expect_fail=["anUnansweredTypeListRefusesTheDelete"],
    ),
    dict(
        id="MB",
        what="findChildTypes: a hole in the type list is skipped again",
        file="core/src/main/java/jp/aegif/nemaki/cmis/aspect/type/impl/TypeManagerImpl.java",
        find_span=("\t\t\tif (type == null) {\n\t\t\t\tthrow new IllegalStateException(\"a null type definition",
                   ' established");\n\t\t\t}'),
        replace="\t\t\tif (type == null) {\n\t\t\t\tcontinue;\n\t\t\t}",
        test="TypeDeletionRefusesUnknownDependenciesTest",
        expect_fail=["aNullElementRefusesTheDelete"],
    ),
    dict(
        id="MC",
        what="property cores: a failed read answers 'no property is defined' again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/TypeDefinitionDaoDelegate.java",
        find_span=("\t\t\tthrow new IllegalStateException(\"the property definition cores of '\" + repositoryId",
                   'no property is defined", e);'),
        replace="\t\t\treturn new ArrayList<NemakiPropertyDefinitionCore>();",
        test="PropertyDefinitionReadsRefuseFailuresTest",
        expect_fail=["aFailedCoresReadRefuses"],
    ),
    dict(
        id="MD",
        what="property cores: an undecodable row is warn-skipped again (the count never rises)",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/TypeDefinitionDaoDelegate.java",
        find="\t\t\t\t\t\t} catch (Exception e) {\n\t\t\t\t\t\t\tunreadableRows++;\n\t\t\t\t\t\t\tlog.warn(\"Failed to convert property definition core document: \" + e.getMessage());",
        replace="\t\t\t\t\t\t} catch (Exception e) {\n\t\t\t\t\t\t\tlog.warn(\"Failed to convert property definition core document: \" + e.getMessage());",
        test="PropertyDefinitionReadsRefuseFailuresTest",
        expect_fail=["anUnreadableCoreRowRefuses"],
    ),
    dict(
        id="ME",
        what="property core by propertyId: a failed read answers 'not defined' again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/TypeDefinitionDaoDelegate.java",
        find_span=("\t\t\tthrow new IllegalStateException(\"the property definition core for '\" + propertyId",
                   'that the property is undefined", e);'),
        replace="\t\t\treturn null;",
        test="PropertyDefinitionReadsRefuseFailuresTest",
        expect_fail=["aFailedByPropertyIdReadRefuses"],
    ),
    dict(
        id="MF",
        what="details by core: unreadable rows no longer refuse (the empty catch returns)",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/TypeDefinitionDaoDelegate.java",
        find_span=("\t\t\tif (unreadableRows > 0) {\n\t\t\t\tthrow new IllegalStateException(unreadableRows + \" property definition detail\"\n\t\t\t\t\t\t+ \" row(s) in '\" + repositoryId + \"' could not be read, so whether core '\"",
                   'cannot be established");\n\t\t\t}'),
        replace="",
        test="PropertyDefinitionReadsRefuseFailuresTest",
        expect_fail=["anUnreadableDetailRowRefuses"],
    ),
    dict(
        id="MG",
        what="the wrapper drops view rows it cannot decode, silently, one layer under the DAO",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/CloudantClientWrapper.java",
        find_span=("\t\t\tif (unreadableRows > 0) {\n\t\t\t\tthrow new org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException(\n\t\t\t\t\t\tunreadableRows + \" row(s) of \" + viewPath",
                   'complete answer to key \'" + key + "\'");\n\t\t\t}'),
        replace="",
        test="CloudantClientWrapperViewValueTest",
        expect_fail=["aProjectionFallsBackToAReadById"],
    ),
    dict(
        id="MH",
        what="the patch view canary reads 'could not ask the document count' as zero again",
        file="core/src/main/java/jp/aegif/nemaki/patch/PatchUtil.java",
        find_span=("\t\t\tcom.ibm.cloud.cloudant.v1.model.DatabaseInformation info = client.getDatabaseInfo();\n\t\t\tif (info == null || info.getDocCount() == null) {",
                   "\t\t\tlong documents = info.getDocCount();"),
        replace="\t\t\tlong documents = client.getDatabaseInfo() == null\n\t\t\t\t\t? 0L : client.getDatabaseInfo().getDocCount();",
        test="PatchViewCanaryTest",
        expect_fail=["aDocumentCountThatDidNotAnswerIsRefused"],
    ),
    dict(
        id="MI",
        what="the ZIP export finishes an archive whose document lost its bytes",
        file="core/src/main/java/jp/aegif/nemaki/rest/importexport/ZipExporter.java",
        find_span=("        } catch (ExportRefusedException e) {\n            throw e;\n        } catch (Exception e) {\n            throw new ExportRefusedException(\"the content of \" + entryPath",
                   'metadata describes bytes that are not in it", e);\n        }'),
        replace="        } catch (Exception e) {\n            log.warn(\"Failed to export content for: \" + entryPath, e);\n        }",
        test="ExportsRefuseMissingBytesTest",
        expect_fail=["anUnreadableDocumentAbortsTheZip"],
    ),
    dict(
        id="MJ",
        what="the filesystem export walks past an attachment it could not produce, silently",
        file="core/src/main/java/jp/aegif/nemaki/rest/importexport/FilesystemExporter.java",
        find_span=("                        if (attachment == null) {\n                            throw new IOException(\"the attachment \" + doc.getAttachmentNodeId()",
                   'document has no content.");\n                        }'),
        replace="                        if (attachment == null) {\n                            continue;\n                        }",
        test="ExportsRefuseMissingBytesTest",
        expect_fail=["anAbsentAttachmentIsReportedByTheFilesystemExport"],
    ),
    dict(
        id="MK",
        what="the filesystem export loses a version's bytes without recording anything",
        file="core/src/main/java/jp/aegif/nemaki/rest/importexport/FilesystemExporter.java",
        find_span=("                        log.warn(\"Failed to export version content: \" + versionFileName, e);\n                        result.errors.add(\"Failed to export version content: \"",
                   "                        versionNum++;\n                        continue;"),
        replace="                        log.warn(\"Failed to export version content: \" + versionFileName, e);",
        test="ExportsRefuseMissingBytesTest",
        expect_fail=["anUnreadableVersionIsReportedByTheFilesystemExport"],
    ),
    dict(
        id="ML",
        what="a body that could not be read is a document with no content again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/AttachmentDaoDelegate.java",
        find="\t\t\t\t\t// Genuine absence: the document carries no `content` attachment.\n\t\t\t\t\tlog.debug(\"No binary attachment stream found for: \" + attachmentId);",
        replace="\t\t\t\t\tlog.debug(\"No binary attachment stream found for: \" + attachmentId);\n\t\t\t\t} catch (Exception streamEx) {\n\t\t\t\t\tlog.warn(\"Error retrieving binary attachment stream for: \" + attachmentId);",
        test="AttachmentReadFailuresAreNotAbsenceTest",
        expect_fail=["aFailedBodyReadRefuses"],
    ),
    dict(
        id="MM",
        what="a stored size that could not be measured falls back to the recorded length again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/AttachmentDaoDelegate.java",
        find_span=("\t\t\tthrow new IllegalStateException(\"the stored size of attachment '\" + attachmentId",
                   'that it matches the recorded length", e);'),
        replace="\t\t\treturn null;",
        test="AttachmentReadFailuresAreNotAbsenceTest",
        expect_fail=["aFailedSizeReadRefuses"],
    ),
    dict(
        id="MN",
        what="a userId the index and the document disagree on is 'no such user' again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/UserGroupDaoDelegate.java",
        find_span=("\t\t\t\t\t\tthrow new IllegalStateException(\"the userItemsById view matched a\"",
                   'cannot be established");'),
        replace="\t\t\t\t\t\treturn null;",
        test="UserLookupRefusesIndexDisagreementTest",
        expect_fail=["aMismatchedRowRefuses"],
    ),
    dict(
        id="MR",
        what="a lock reports harness breakage as an AssertionError again",
        file="core/src/test/java/jp/aegif/nemaki/cmis/aspect/query/solr/QueryPagingArgumentsAreNotTruncatedTest.java",
        find="            throw new HarnessBroken(method + \" was renamed — update this test with it, or \"",
        replace="            throw new AssertionError(method + \" was renamed — update this test with it, or \"",
        test="HarnessBreakageIsNotAFiringTest",
        expect_fail=["noTestStillReportsBreakageAsAnAssertion"],
    ),
    dict(
        id="MS",
        what="JavaSource says 'method not found' with an AssertionError again",
        file="core/src/test/java/jp/aegif/nemaki/util/test/JavaSource.java",
        find='        throw new HarnessBroken("method not found, so nothing was checked: " + signatureFragment);',
        replace='        throw new AssertionError("method not found, so nothing was checked: " + signatureFragment);',
        test="HarnessBreakageIsNotAFiringTest",
        expect_fail=["aMissingMethodRaisesHarnessBroken"],
    ),
    dict(
        id="MT",
        what="the system stage runs before any gate again",
        file="core/src/main/java/jp/aegif/nemaki/patch/AbstractNemakiPatch.java",
        find_span=("\t\tboolean allSucceeded = true;\n\t\tif (!systemStageMayRun()) {",
                   "\t\t} else {\n\t\t\tapplySystemPatch();\n\t\t}"),
        replace="\t\tapplySystemPatch();\n\t\tboolean allSucceeded = true;",
        test="SystemStagePassesTheViewGateTest",
        expect_fail=["aSilentRepositoryStopsTheSystemStage"],
    ),
    dict(
        id="MU",
        what="the always-run patch override skips the view gate again",
        file="core/src/main/java/jp/aegif/nemaki/patch/Patch_WebAuthnCredentialViews.java",
        find_span=("            if (!patchUtil.cmisViewsAreAnswering(repositoryId)) {\n                log.error(\"[patch=\" + getName() + \", repositoryId=\" + repositoryId\n                        + \"] skipped: the repository's views are not answering",
                   "                allSucceeded = false;\n                continue;\n            }"),
        replace="",
        test="SystemStagePassesTheViewGateTest",
        expect_fail=["theAlwaysRunOverrideIsGated"],
    ),
    dict(
        id="MV",
        what="the evidence ledger reports a lost write as a refusal again",
        file="core/src/main/java/jp/aegif/nemaki/evidence/EvidenceLedgerService.java",
        find="                return new AppendResult(AppendOutcome.INDETERMINATE, -1, null,",
        replace="                return new AppendResult(AppendOutcome.REFUSED, -1, null,",
        test="LedgerAndJournalUnknownsAreNotZeroTest",
        expect_fail=["aThrownWriteIsIndeterminate"],
    ),
    dict(
        id="MW",
        what="an unreadable retry count is 'never retried' again",
        file="core/src/main/java/jp/aegif/nemaki/rest/purview/journal/CouchLineageJournalStore.java",
        find_span=("            logger.warn(\"The retry count of record {} on target {} could not be read: {}\",\n                    recordId, target, e.getMessage());\n            throw new LineageViewUnreadableException(\"the retry count of record \" + recordId",
                   "been\"\n                    + \" retried\", e);"),
        replace="            logger.debug(\"Error reading retry count\");\n            return 0;",
        test="LedgerAndJournalUnknownsAreNotZeroTest",
        expect_fail=["anUnreadableRetryCountRefuses"],
    ),
    dict(
        id="MX",
        what="a type definition the archive refers to is skipped with a warn again",
        file="core/src/main/java/jp/aegif/nemaki/rest/importexport/ZipExporter.java",
        find_span=("            if (typeDef == null) {\n                // The id came from an object IN this export",
                   "it does not carry\", null);\n            }"),
        replace="            if (typeDef == null) {\n                log.warn(\"Type definition not found for export: \" + typeId);\n                continue;\n            }",
        test="ExportsRefuseMissingBytesTest",
        expect_fail=["anUnreadableTypeDefinitionAbortsTheZip"],
    ),
    dict(
        id="MY",
        what="the export resource swallows the custom-type collection refusal again",
        file="core/src/main/java/jp/aegif/nemaki/rest/ImportExportResource.java",
        # The type-definition catch is textually identical at both call sites, so the runner
        # refused an ambiguous span start — the uniqueness check added to it this round doing
        # its job. The custom-type COLLECTION refusal is unique and is asserted by the same
        # lock, so that is what this control removes.
        find_span=("                        } catch (Exception e) {\n                            // Warned and carried on: the walk that decides WHICH type",
                   "would be incomplete\", e);\n                        }"),
        replace="                        } catch (Exception e) {\n                            log.warn(\"Failed to collect custom type definitions: \" + e.getMessage(), e);\n                        }",
        test="ExportRefusalReachesTheClientTest",
        # The lock this named was renamed when it stopped being a spelling check and started
        # walking the streaming bodies. The control kept the OLD method name, so it sabotaged
        # correctly and then looked for a failure in a method that no longer exists — the
        # KC/KD/KE shape again, and the reason the preflight below now refuses a control
        # whose expect_fail names a method the test class does not declare.
        expect_fail=["theFolderExportStreamerRefuses"],
    ),
    dict(
        id="MZ",
        what="a length nobody could read is indexed as 0 again",
        file="core/src/main/java/jp/aegif/nemaki/cmis/aspect/query/solr/SolrUtil.java",
        find="\t\t\treturn AttachmentContent.LENGTH_UNKNOWN;",
        replace="\t\t\treturn 0L;",
        test="SolrUtilAttachmentSingleReadTest",
        expect_fail=["aLengthThatCouldNotBeReadIsNotZero"],
    ),
    dict(
        id="NA",
        what="a children listing short by undecodable rows is served again",
        file="core/src/main/java/jp/aegif/nemaki/cmis/service/impl/NavigationServiceImpl.java",
        find_span=("\t\t\tint probeUnreadable = contentService.lastUnreadableChildCount();\n\t\t\tif (probeUnreadable > 0) {",
                   "read as complete\");\n\t\t\t}"),
        replace="\t\t\tint probeUnreadable = 0;\n\t\t\tif (probeUnreadable > 0) {\n\t\t\t\tthrow new CmisRuntimeException(\"unreachable\");\n\t\t\t}",
        test="ChildrenPageIsNotSilentlyShortTest",
        expect_fail=["aPageShortByADecodeFailureRefuses"],
    ),
    dict(
        id="NB",
        what="a lazy re-init opens the process-wide provisioning window again",
        file="core/src/main/java/jp/aegif/nemaki/cmis/aspect/type/impl/TypeManagerImpl.java",
        find="\t\t\tboolean firstInitialization = !everInitialized;",
        replace="\t\t\tboolean firstInitialization = true;",
        test="OneRepositoryDoesNotTakeDownTheRegistryTest",
        expect_fail=["aLaterInitDoesNotOpenTheWindow"],
    ),
    dict(
        id="NC",
        what="the provisioning windows stop nesting (an inner end closes the outer)",
        file="core/src/main/java/jp/aegif/nemaki/init/StartupPhase.java",
        find="        openWindows.updateAndGet(depth -> depth > 0 ? depth - 1 : 0);",
        replace="        openWindows.set(0);",
        test="StartupPhaseIsDeclaredNotGuessedTest",
        expect_fail=["theWindowsNest"],
    ),
    dict(
        id="ND",
        what="the group twin of the userId-mismatch arm answers null again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/UserGroupDaoDelegate.java",
        find_span=("\t\t\t\t\t\tthrow new IllegalStateException(\"the view matched a group row for '\"",
                   "cannot be established\");"),
        replace="\t\t\t\t\t\treturn null;",
        test="UserLookupRefusesIndexDisagreementTest",
        expect_fail=["aMismatchedGroupRowRefuses"],
    ),
    dict(
        id="NE",
        # The original NE sabotaged a REFUSAL that turned out to be an over-correction and
        # was withdrawn (the view emits doc.name directly, so a null value is a child with no
        # name, not a name that was lost). What has to stay true is the opposite: a nameless
        # child is skipped rather than counted as a name.
        what="a nameless child is counted as a name again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/ContentDaoServiceImpl.java",
        # Dropping the `continue` alone NPEs on the null value, which the runner rightly
        # scores as harness breakage. The sabotage has to produce a WRONG ANSWER: the
        # nameless child enters the name set.
        find="\t\t\t\t\t\tnamelessRows++;\n\t\t\t\t\t\tcontinue;",
        replace="\t\t\t\t\t\tnamelessRows++;\n\t\t\t\t\t\tnames.add(\"\");\n\t\t\t\t\t\tcontinue;",
        test="NamelessChildrenAreSkippedNotRefusedTest",
        expect_fail=["aNamelessChildIsSkippedNotRefused"],
    ),
    dict(
        id="NF",
        what="the four CMIS-visible type listings answer from a base-only map again",
        file="core/src/main/java/jp/aegif/nemaki/cmis/aspect/type/impl/TypeManagerImpl.java",
        # getTypeByQueryName. The comment here USED to say getTypesDescendants "refuses for a
        # second reason even without its guard, so sabotaging it measured nothing" — and that
        # was checked and found false: on this fixture (includePropertyDefinitions=false)
        # flattenTypeDefinitionContainer never reaches getTypeDefinition, so there is no
        # second refusal on the path and a control there DOES fire. It is OB, below. A false
        # "we checked, it cannot be measured", recorded in the tool whose job is measuring,
        # is the same substitution this tool exists to end.
        # The span has to REMOVE the guard. The first version inserted a dead `if (false)`
        # call above it and left the real one in place, so the sabotage changed nothing —
        # which the runner reported as DID NOT FIRE, correctly.
        find_span=("\tpublic TypeDefinition getTypeByQueryName(String repositoryId, String typeQueryName) {\n\t\tensureInitialized();",
                   "\t\tassertRepositoryTypesLoaded(repositoryId);"),
        replace="\tpublic TypeDefinition getTypeByQueryName(String repositoryId, String typeQueryName) {\n\t\tensureInitialized();",
        test="OneRepositoryDoesNotTakeDownTheRegistryTest",
        expect_fail=["theCmisVisibleListingsRefuseToo"],
    ),
    dict(
        id="NG",
        what="a rendition body that could not be read is a rendition with no bytes again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/AttachmentDaoDelegate.java",
        # The outer catch, which is where the test's fixture (a body read that throws) lands.
        # Sabotaging the non-stream arm instead measured nothing, because that arm is not the
        # one this fixture reaches — the runner said DID NOT FIRE.
        find_span=("\t\t\tthrow new IllegalStateException(\"the rendition '\" + objectId + \"' in '\"",
                   "not exist\", e);"),
        replace="\t\t\treturn null;",
        test="AttachmentReadFailuresAreNotAbsenceTest",
        expect_fail=["aFailedRenditionBodyReadRefuses"],
    ),
    dict(
        id="NH",
        what="the wrapper answers null for a size it could not measure, under the closed DAO",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/CloudantClientWrapper.java",
        find_span=("\t\t\tthrow new org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException(\n\t\t\t\t\t\"the stored size of \" + docId",
                   "it has none\", e);"),
        replace="\t\t\treturn null;",
        test="AttachmentSizeRefusalReachesTheDaoTest",
        expect_fail=["theWrapperRefusesAFailedMeasurement"],
    ),
    dict(
        id="NI",
        what="the FOLDER streamer builds the archive over the response stream (NQ is objects-only)",
        file="core/src/main/java/jp/aegif/nemaki/rest/ImportExportResource.java",
        # What this control actually does, which is not what its comment used to say.
        #
        # The old text described restoring try-with-resources — "the sabotage has to restore
        # the DEFECT" — while the sabotage below swaps the archive's sink for the response
        # stream. Both make `theRefusalStopsForwardingBeforeClosing` fail, so the run stayed
        # green and nothing pointed at the disagreement; a review reading the two side by
        # side did. A control whose description names a different defect than it injects is
        # the measuring layer's version of a stale comment, and the ledger inherited it.
        #
        # NOT covered by any control: the try-with-resources half of that same lock. It is a
        # SOURCE assertion (`assertFalse(body.contains("try (ZipOutputStream"))`), and a
        # sabotage that reinstates it has to restructure the whole streaming body to still
        # compile — which a declarative find/replace cannot do. The lock holds it; the runner
        # does not, and saying so is the point of this note.
        #
        # Both streamers carry an identical block, so the anchor carries the one call that
        # follows only in the folder one.
        find="                    ZipOutputStream zos = new ZipOutputStream(sink);\n                    try {\n                        Set<String> customTypeIds = new HashSet<>();\n                        try {\n                            collectCustomTypeIds(repositoryId, folder, customTypeIds);",
        replace="                    ZipOutputStream zos = new ZipOutputStream(output);\n                    try {\n                        Set<String> customTypeIds = new HashSet<>();\n                        try {\n                            collectCustomTypeIds(repositoryId, folder, customTypeIds);",
        test="ExportRefusalReachesTheClientTest",
        expect_fail=["theRefusalStopsForwardingBeforeClosing"],
    ),
    dict(
        id="NJ",
        what="the ledger reports a failed TAIL read as unknown-whether-written again",
        file="core/src/main/java/jp/aegif/nemaki/evidence/EvidenceLedgerService.java",
        find="                return new AppendResult(AppendOutcome.REFUSED, -1, null,\n                        \"the tail of the chain could not be read (\" + e.getMessage()",
        replace="                return new AppendResult(AppendOutcome.INDETERMINATE, -1, null,\n                        \"the tail of the chain could not be read (\" + e.getMessage()",
        test="LedgerAndJournalUnknownsAreNotZeroTest",
        expect_fail=["aFailedTailReadIsRefusedNotIndeterminate"],
    ),
    dict(
        id="NK",
        what="repository discovery for a target answers 'none pending' on a dead view again",
        file="core/src/main/java/jp/aegif/nemaki/rest/purview/journal/CouchLineageJournalStore.java",
        find_span=("                throw new LineageViewUnreadableException(\"the non_terminal_by_target_repo view\"",
                   "none do\",\n                        null);"),
        replace="                return List.of();",
        test="LedgerAndJournalUnknownsAreNotZeroTest",
        expect_fail=["aDeadDiscoveryViewIsNotAnEmptySetOfRepositories"],
    ),
    dict(
        id="NL",
        # Rewritten. The document arm and the version arm are now ONE method with two
        # callers, so the pair of controls that used to sabotage each arm separately would
        # both be sabotaging the same lines. What replaced them measures the two properties
        # that method actually carries.
        what="the copy writes straight to the destination, so a failure destroys the previous export",
        file="core/src/main/java/jp/aegif/nemaki/rest/importexport/FilesystemExporter.java",
        find_span=('            staging = Files.createTempFile(destination.getParent(),',
                   '                         StandardOpenOption.TRUNCATE_EXISTING)) {'),
        replace='            staging = Files.createDirectories(destination.getParent())\n                    .resolve(destination.getFileName());\n        } catch (IOException | RuntimeException cannotStage) {\n            try {\n                is.close();\n            } catch (Exception ignored) {\n                // the staging failure is the one worth reporting\n            }\n            throw cannotStage;\n        }\n        try {\n            try (InputStream in = is;\n                 OutputStream os = Files.newOutputStream(staging,\n                         StandardOpenOption.CREATE,\n                         StandardOpenOption.TRUNCATE_EXISTING)) {',
        test="ExportsRefuseMissingBytesTest",
        expect_fail=["aMidCopyFailureDoesNotDestroyThePreviousExport",
                     "aMidCopyVersionFailureDoesNotDestroyThePreviousExport"],
    ),
    dict(
        id="NM",
        what="the staging file is left on disk when the copy fails",
        file="core/src/main/java/jp/aegif/nemaki/rest/importexport/FilesystemExporter.java",
        find="            try {\n                Files.deleteIfExists(staging);\n            } catch (Exception cleanup) {",
        replace="            try {\n                if (false) Files.deleteIfExists(staging);\n            } catch (Exception cleanup) {",
        test="ExportsRefuseMissingBytesTest",
        expect_fail=["aMidCopyFailureDoesNotDestroyThePreviousExport",
                     "aMidCopyVersionFailureDoesNotDestroyThePreviousExport"],
    ),
    dict(
        id="NN",
        what="the refusal path closes the archive before it stops forwarding",
        file="core/src/main/java/jp/aegif/nemaki/rest/ImportExportResource.java",
        find="                        sink.stopForwarding();\n                        closeQuietly(zos);\n                        log.error(\"Export streaming failed: \" + e.getMessage(), e);\n                        AuditLogger audit = getAuditLogger();",
        replace="                        closeQuietly(zos);\n                        sink.stopForwarding();\n                        log.error(\"Export streaming failed: \" + e.getMessage(), e);\n                        AuditLogger audit = getAuditLogger();",
        test="ExportRefusalReachesTheClientTest",
        expect_fail=["theRefusalStopsForwardingBeforeClosing"],
    ),
    dict(
        id="NO",
        what="the OBJECTS streamer closes before it stops forwarding (NN is folder-only)",
        file="core/src/main/java/jp/aegif/nemaki/rest/ImportExportResource.java",
        find="                        sink.stopForwarding();\n                        closeQuietly(zos);\n                        log.error(\"Export streaming failed: \" + e.getMessage(), e);\n                        throw new IOException(\"Export failed: \" + e.getMessage(), e);",
        replace="                        closeQuietly(zos);\n                        sink.stopForwarding();\n                        log.error(\"Export streaming failed: \" + e.getMessage(), e);\n                        throw new IOException(\"Export failed: \" + e.getMessage(), e);",
        test="ExportRefusalReachesTheClientTest",
        expect_fail=["theRefusalStopsForwardingBeforeClosing"],
    ),
    dict(
        id="NP",
        what="the OBJECTS streamer swallows the custom-type refusal (MY is folder-only)",
        file="core/src/main/java/jp/aegif/nemaki/rest/ImportExportResource.java",
        find_span=("                        } catch (Exception e) {\n                            // The objects-export sibling of the folder-export refusal above.",
                   "would be incomplete\", e);\n                        }"),
        replace="                        } catch (Exception e) {\n                            log.warn(\"Failed to collect custom type definitions: \" + e.getMessage(), e);\n                        }",
        test="ExportRefusalReachesTheClientTest",
        expect_fail=["theObjectsExportStreamerRefuses"],
    ),
    dict(
        id="NQ",
        what="the OBJECTS streamer builds the archive over the response stream (NI is folder-only)",
        file="core/src/main/java/jp/aegif/nemaki/rest/ImportExportResource.java",
        find="                    ZipOutputStream zos = new ZipOutputStream(sink);\n                    try {\n                        Set<String> customTypeIds = new HashSet<>();\n                        try {\n                            for (Content c : contents) {",
        replace="                    ZipOutputStream zos = new ZipOutputStream(output);\n                    try {\n                        Set<String> customTypeIds = new HashSet<>();\n                        try {\n                            for (Content c : contents) {",
        test="ExportRefusalReachesTheClientTest",
        expect_fail=["theRefusalStopsForwardingBeforeClosing"],
    ),
    dict(
        id="NR",
        what="the TYPED keyed view answers null for an undeployed design document again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/CloudantClientWrapper.java",
        find_span=("\t\t\t// is a failure, not an absence.\n\t\t\tif (isStartupPhase()) {",
                   "\t\t\t\t\t\t\t+ databaseName + \"', so it cannot answer for key '\" + key + \"'\", e);"),
        replace="\t\t\treturn null;",
        test="CloudantViewFailuresAreNotEmptyAnswersTest",
        expect_fail=["aTypedKeyedReadRefusesAnUndeployedView"],
    ),
    dict(
        id="NS",
        what="a successful copy is never moved onto the destination",
        file="core/src/main/java/jp/aegif/nemaki/rest/importexport/FilesystemExporter.java",
        # The other direction. Every assertion written for the destructive case is satisfied
        # by a copy that writes nothing at all, so the safe answer has to be told apart from
        # a broken one — otherwise "the previous export survives" is met by an exporter that
        # exports nothing.
        find='            if (allowOverwrite) {\n                Files.move(staging, destination,\n                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);',
        replace='            if (allowOverwrite) {\n                Files.deleteIfExists(staging);',
        test="ExportsRefuseMissingBytesTest",
        expect_fail=["aSuccessfulOverwriteStillReplacesTheFile"],
    ),
    dict(
        id="NU",
        what="the stopped sink still forwards single bytes (NX covers the array overload, NV the flush)",
        file="core/src/main/java/jp/aegif/nemaki/rest/importexport/ImportExportUtils.java",
        # DiscardableOutputStream has THREE guards after stopForwarding — write(int),
        # write(byte[],int,int) and flush() — and one control covered one of them. The
        # one-arm shape again, this time in the measuring layer; an audit of the controls
        # named it rather than a run.
        find="        public void write(int b) throws java.io.IOException {\n            if (forwarding) {\n                delegate.write(b);\n            }\n        }",
        replace="        public void write(int b) throws java.io.IOException {\n            delegate.write(b);\n        }",
        test="DiscardableOutputStreamTest",
        expect_fail=["aStoppedSinkDelegatesNothing"],
    ),
    dict(
        id="NV",
        what="the stopped sink still forwards flushes (the third arm)",
        file="core/src/main/java/jp/aegif/nemaki/rest/importexport/ImportExportUtils.java",
        find="        public void flush() throws java.io.IOException {\n            if (forwarding) {\n                delegate.flush();\n            }\n        }",
        replace="        public void flush() throws java.io.IOException {\n            delegate.flush();\n        }",
        test="DiscardableOutputStreamTest",
        expect_fail=["aStoppedSinkDelegatesNothing"],
    ),

    dict(
        id="NT",
        what="refreshTypes completes a load without recording it, so a later init reopens the window",
        file="core/src/main/java/jp/aegif/nemaki/cmis/aspect/type/impl/TypeManagerImpl.java",
        find_span=("\t\t\t// The invariant the startup window rests on: `initialized` implies",
                   "\t\t\teverInitialized = true;"),
        replace="",
        test="OneRepositoryDoesNotTakeDownTheRegistryTest",
        expect_fail=["refreshTypesRecordsThatALoadCompleted"],
    ),
    dict(
        id="NW",
        what="a FAILED first init spends the bootstrap grace again",
        file="core/src/main/java/jp/aegif/nemaki/cmis/aspect/type/impl/TypeManagerImpl.java",
        # The defect is not "the flag is never set" — that breaks a NEIGHBOURING property and
        # fires the wrong test, which the runner reported. The defect is the flag being set
        # in the finally, so a FAILED init spends the grace. That is what this restores.
        find="\t\t\t\tif (firstInitialization) {\n\t\t\t\t\tStartupPhase.end();\n\t\t\t\t}",
        replace="\t\t\t\tif (firstInitialization) {\n\t\t\t\t\teverInitialized = true;\n\t\t\t\t\tStartupPhase.end();\n\t\t\t\t}",
        test="OneRepositoryDoesNotTakeDownTheRegistryTest",
        expect_fail=["aFailedFirstInitDoesNotSpendTheGrace"],
    ),
    dict(
        id="NX",
        what="the discardable sink forwards writes after it was told to stop",
        file="core/src/main/java/jp/aegif/nemaki/rest/importexport/ImportExportUtils.java",
        find="        public void write(byte[] b, int off, int len) throws java.io.IOException {\n            if (forwarding) {\n                delegate.write(b, off, len);\n            }\n        }",
        replace="        public void write(byte[] b, int off, int len) throws java.io.IOException {\n            delegate.write(b, off, len);\n        }",
        test="DiscardableOutputStreamTest",
        expect_fail=["aStoppedSinkDelegatesNothing"],
    ),
    dict(
        id="NY",
        what="the always-run override runs its SYSTEM stage without the gate again",
        file="core/src/main/java/jp/aegif/nemaki/patch/Patch_WebAuthnCredentialViews.java",
        find_span=("        boolean allSucceeded = true;\n        if (!systemStageMayRun()) {",
                   "        } else {\n            applySystemPatch();\n        }"),
        replace="        applySystemPatch();\n        boolean allSucceeded = true;",
        test="SystemStagePassesTheViewGateTest",
        expect_fail=["theAlwaysRunOverrideIsGated"],
    ),
    dict(
        id="NZ",
        what="the CMIS paged type listing answers from a base-only map again",
        file="core/src/main/java/jp/aegif/nemaki/cmis/aspect/type/impl/TypeManagerImpl.java",
        find_span=("\tpublic TypeDefinitionList getTypesChildren(CallContext context,\n\t\t\tString repositoryId, String typeId,\n\t\t\tboolean includePropertyDefinitions, BigInteger maxItems, BigInteger skipCount) {",
                   "\t\tassertRepositoryTypesLoaded(repositoryId);"),
        replace="\tpublic TypeDefinitionList getTypesChildren(CallContext context,\n\t\t\tString repositoryId, String typeId,\n\t\t\tboolean includePropertyDefinitions, BigInteger maxItems, BigInteger skipCount) {",
        test="OneRepositoryDoesNotTakeDownTheRegistryTest",
        expect_fail=["theCmisVisibleListingsRefuseToo"],
    ),
    dict(
        id="OA",
        what="creating a connector trusts the Mango index alone again",
        file="core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java",
        # Re-anchored. The original anchor was the `} else if (readByDeterministicId(...))`
        # line, and a follow-up fix IN THE SAME ROUND hoisted that call into a local —
        # so the anchor stopped matching and the runner raised SystemExit AT this
        # control, taking OB, MO, MP, MQ and HA down with it. The preflight added below
        # now refuses every stale anchor before anything runs, instead of dying at one.
        find='        com.ibm.cloud.cloudant.v1.model.Document deterministic = existing.isEmpty()\n                ? readByDeterministicId(cloudant, dbName, def.getConnectorId())\n                : null;',
        replace='        com.ibm.cloud.cloudant.v1.model.Document deterministic = null;',
        test="ConnectorCreationRefusesAnIndexDisagreementTest",
        expect_fail=["theWriteConsultsTheDeterministicId"],
    ),
    dict(
        id="OB",
        what="getTypesDescendants answers from a base-only map again — the control NF's comment said could not exist",
        file="core/src/main/java/jp/aegif/nemaki/cmis/aspect/type/impl/TypeManagerImpl.java",
        # NF recorded that a control here "measured nothing" because the descendants call
        # refuses for a second reason. Traced on the actual fixture, it does not: with
        # includePropertyDefinitions=false, flattenTypeDefinitionContainer never reaches
        # getTypeDefinition, and nothing else on the path raises. So the guard IS
        # measurable, and the note saying otherwise was the only thing standing where this
        # control should have been.
        find='\t\tassertRepositoryTypesLoaded(repositoryId);\n\t\t\n\t\tif (log.isDebugEnabled()) {\n\t\t\tlog.debug("getTypesDescendants ENTRY:',
        replace='\t\t\n\t\tif (log.isDebugEnabled()) {\n\t\t\tlog.debug("getTypesDescendants ENTRY:',
        test="OneRepositoryDoesNotTakeDownTheRegistryTest",
        expect_fail=["theCmisVisibleListingsRefuseToo"],
    ),
    dict(
        id="OC",
        what='the DEFAULT export path (overwrite off) never installs the staged copy',
        file='core/src/main/java/jp/aegif/nemaki/rest/importexport/FilesystemExporter.java',
        # NS covers the allowOverwrite=true arm only. This is the arm the endpoint
        # actually takes by default, and replacing it with a delete left all
        # nineteen tests green while the exporter produced no document files at
        # all. The one-arm shape, in the arm that runs most often.
        find='                // No REPLACE_EXISTING: this is the CREATE_NEW the caller asked for, so a\n                // destination that appeared during the copy still refuses.\n                Files.move(staging, destination);',
        replace='                Files.deleteIfExists(staging);',
        test='ExportsRefuseMissingBytesTest',
        expect_fail=['aPlainExportStillWritesTheDocument'],
    ),
    dict(
        id="OD",
        what='exported files go back to owner-only, and an overwrite downgrades an existing one',
        file='core/src/main/java/jp/aegif/nemaki/rest/importexport/FilesystemExporter.java',
        # Files.createTempFile creates 0600 and Files.move replaces the inode, so the
        # staging fix silently turned every exported file from 0644 into 0600. Two
        # reviewers read that change without catching it; measuring the actual mode
        # on disk did.
        # Re-anchored in round 6: the OM fix gave the call a third argument (result), which
        # killed this anchor — the same self-inflicted drift OA suffered a round earlier.
        # The fix-review preflight caught it at review time, before any run: without it the
        # first contact would have refused all 203 controls.
        find='            giveTheStagingFileTheModeTheDestinationShouldHave(staging, destination, result);\n',
        replace='',
        test='ExportsRefuseMissingBytesTest',
        expect_fail=['aStagedExportKeepsTheModeAnOrdinaryCreateWouldGive'],
    ),
    dict(
        id="OE",
        what='the importer reads a half-written export as a document again',
        file='core/src/main/java/jp/aegif/nemaki/rest/importexport/FilesystemImporter.java',
        # The exporter's javadoc asserted that no importer reads a staging file. A
        # review checked and disproved it: this walk collects every regular file and
        # skipped only sidecars and version files, so a leftover .part was ingested
        # as a document holding the truncated bytes of a failed export — the exact
        # substitution the export refusals exist to prevent, arriving from the other
        # direction, introduced by the fix for it.
        find='            if (relativePath.endsWith(META_SUFFIX) || isVersionFile(relativePath)\n                    || isExportStagingFile(relativePath)) {',
        replace='            if (relativePath.endsWith(META_SUFFIX) || isVersionFile(relativePath)) {',
        test='StagingFilesAreNotImportableTest',
        expect_fail=['theImporterConsultsTheRule'],
    ),
    dict(
        id="OF",
        what='the masked secret is written AS the credential when the row cannot be read back',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionController.java',
        # GET hands out "[configured]" in place of the real credential and this PUT
        # restores it from the stored row — through a MANGO SELECTOR. A selector whose
        # index is rebuilding answers 'no such connector', after which the literal
        # string was written as the credential and the real one was gone. The window
        # was only noticed while reviewing a service-layer refusal that had been
        # relaxed: the refusal downstream had been standing in for this guard.
        find_span=('        if (existing == null\n                && ("[configured]".equals(def.getCredentialRef())',
                   '                            + " written. Retry, or send the real values.");\n        }'),
        replace='',
        test='ConnectorDefinitionControllerPartialPutTest',
        expect_fail=['aMaskedSecretIsNotWrittenWhenTheStoredRowCouldNotBeReadBack'],
    ),
    dict(
        id="OG",
        what='a 2xx carrying no total_rows counts as zero again',
        file='core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/CloudantClientWrapper.java',
        # Not hypothetical: a REDUCE response omits total_rows, and this read it as 0,
        # which the patch gate took for 'the views are not answering' and refused 312
        # times against a healthy database. That was fixed at one CALLER; the method
        # kept the silent 0 for every other one.
        find_span=('\t\t\tif (result.getTotalRows() == null) {',
                   '\t\t\treturn result.getTotalRows();'),
        replace='\t\t\treturn (result.getTotalRows() != null) ? result.getTotalRows() : 0;',
        test='CloudantViewFailuresAreNotEmptyAnswersTest',
        expect_fail=['aCountWithoutTotalRowsThrows'],
    ),
    dict(
        id="OH",
        what="a deliberate count refusal is re-wrapped by the method's own catch-all as a crash",
        file='core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/CloudantClientWrapper.java',
        # Both count methods end in a bare catch (Exception) that logs at ERROR and
        # re-wraps. A refusal raised inside the try came out one layer deeper,
        # described as an unexpected failure — and the message assertions passed
        # either way, because the wrapper quotes what it wrapped.
        find_span=('\t\t} catch (org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException refusal) {\n\t\t\t// A refusal raised INSIDE the try above',
                   '\t\t\tthrow refusal;'),
        replace='',
        test='CloudantViewFailuresAreNotEmptyAnswersTest',
        expect_fail=['aCountWithoutTotalRowsThrows'],
    ),
    dict(
        id="OI",
        what="the KEYED count refusal is re-wrapped by its own catch-all (OH is unkeyed only)",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/CloudantClientWrapper.java",
        # OH covers queryViewCount. queryViewCountByKey has the identical rethrow arm and
        # nothing measured it: the keyed lock asserted only that the message names the guard,
        # which stays true when the catch-all wraps it, because the wrapper quotes the message
        # it wrapped. Removing the keyed rethrow fired neither OH nor the lock. Found by a
        # review of the controls, not by a run — the one-arm shape, in the measuring layer,
        # for the fourth time in this batch.
        find='\t\t} catch (org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException refusal) {\n\t\t\t// The keyed twin of the arm in queryViewCount: a refusal raised inside the try is\n\t\t\t// on its way out, not an unexpected failure to log and re-wrap.\n\t\t\tthrow refusal;\n',
        replace="",
        test="CloudantViewFailuresAreNotEmptyAnswersTest",
        expect_fail=["aKeyedCountSeparatesMalformedFromEmpty"],
    ),
    dict(
        id="OJ",
        what="stopForwarding stops stopping — the method body, not the three arms that read it",
        file="core/src/main/java/jp/aegif/nemaki/rest/importexport/ImportExportUtils.java",
        # NU, NV and NX each disable ONE of the guards that consult `forwarding`. None of them
        # touches the method that sets it, so a stopForwarding() emptied to a no-op — the
        # single edit that defeats all three at once — had no control at all. The lock catches
        # it; the runner did not.
        find='        public void stopForwarding() {\n            this.forwarding = false;\n        }',
        replace='        public void stopForwarding() {\n        }',
        test="DiscardableOutputStreamTest",
        expect_fail=["aStoppedSinkDelegatesNothing"],
    ),
    dict(
        id="OK",
        what='the DOCUMENT sidecar is a direct truncating write again (round-5 staged only the content)',
        file='core/src/main/java/jp/aegif/nemaki/rest/importexport/FilesystemExporter.java',
        # Files.write truncates in place, exactly what the FileWriter did: with
        # allowOverwrite a mid-write failure destroys the old complete metadata while
        # the content beside it is staged. The lock counts the helper's call sites, so
        # detaching one arm cannot pass as a refactor.
        # Re-anchored a SECOND time in round 6: the TOCTOU fix wrapped this call in a
        # try block, which moved it one indent level deeper and killed the anchor —
        # the third self-inflicted drift of the round (OA, OD, now OK). Caught by the
        # convergence review's in-memory preflight, before any run.
        find='                        copyLeavingTheTargetIntactOnFailure(\n                                new java.io.ByteArrayInputStream(\n                                        metadata.toJSONString().getBytes(StandardCharsets.UTF_8)),\n                                metaPath, allowOverwrite, result);',
        replace='                        java.nio.file.Files.write(metaPath,\n                                metadata.toJSONString().getBytes(StandardCharsets.UTF_8));',
        test='ExportsRefuseMissingBytesTest',
        expect_fail=['theSidecarsGoThroughTheStagingHelperToo'],
    ),
    dict(
        id="OL",
        what='the ZIP importer reads a half-written filesystem export as a document again',
        file='core/src/main/java/jp/aegif/nemaki/rest/importexport/ZipImporter.java',
        # FilesystemImporter got this skip in round 5; ZipImporter — the same consumer
        # one format over — did not, and an admin who zips an export directory with a
        # .part leftover was importing the truncated bytes of a failed copy. A round-6
        # sibling sweep found it.
        find='                if (path.endsWith(META_SUFFIX) || isVersionFile(path)\n                        || isExportStagingFile(path)) {',
        replace='                if (path.endsWith(META_SUFFIX) || isVersionFile(path)) {',
        test='StagingFilesAreNotImportableTest',
        expect_fail=['theZipImporterConsultsTheRuleToo'],
    ),
    dict(
        id="OM",
        what='a mode that could not be set is only logged again — the export reports clean success',
        file='core/src/main/java/jp/aegif/nemaki/rest/importexport/FilesystemExporter.java',
        # The round-5 mode fix caught its own failure and log.warn'd it: fail-open. The
        # export said SUCCESS while the file came out 0600 and a backup agent cannot
        # read it. The report into result.errors is what turns the status to partial.
        find='            result.errors.add("The exported file " + destination.getFileName()\n                    + " may be owner-only: its permissions could not be set ("\n                    + notPosixOrNotPermitted.getMessage() + "). The bytes are complete.");',
        replace='',
        test='ExportsRefuseMissingBytesTest',
        expect_fail=['aModeFailureIsReportedNotJustLogged'],
    ),
    dict(
        id="ON",
        what='a connector CREATE stores the literal "[configured]" as the credential again',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionController.java',
        # Round 5 gated the PUT; the POST arm stayed open and stored the mask sentinel
        # as the real credential — a connector that can never authenticate, created
        # with a 201. A round-6 sibling sweep found it.
        find_span=('        if ("[configured]".equals(def.getCredentialRef())\n                || "[configured]".equals(def.getWebhookSecret())) {',
                   '+ " secret to keep; send the real credentialRef/webhookSecret.");\n        }'),
        replace='',
        test='ConnectorDefinitionControllerPartialPutTest',
        expect_fail=['aCreateCarryingTheMaskIsRefused'],
    ),
    dict(
        id="OO",
        what='the unkeyed paged refusal is re-wrapped by its own catch-all as a crash',
        file='core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/CloudantClientWrapper.java',
        # Same shape OH closed for queryViewCount: the documentless-row refusal raised
        # inside the try was logged at ERROR as unexpected and wrapped one layer deeper.
        # The lock's message assertion alone passed under the wrap (the wrapper quotes
        # what it wrapped); the anti-wrap assertFalse is what this fires.
        find_span=('\t\t} catch (org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException refusal) {\n\t\t\t// A deliberate refusal (documentless row, unreadable properties) on its way out.',
                   '\t\t\t// twins still wrapping.\n\t\t\tthrow refusal;'),
        replace='',
        test='CloudantViewFailuresAreNotEmptyAnswersTest',
        expect_fail=['aDocumentlessPagedRowRefusesThePage'],
    ),
    dict(
        id="OP",
        what='the KEYED paged refusal is re-wrapped — its lock did not exist before round 6',
        file='core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/CloudantClientWrapper.java',
        # The keyed twin had NO test reaching its documentless-row refusal at all (the
        # only keyed paged test drove the transport failure), so this arm and the
        # refusal behind it were deletable with everything green.
        find='\t\t} catch (org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException refusal) {\n\t\t\t// Same rethrow rule as the unkeyed twin above.\n\t\t\tthrow refusal;',
        replace='',
        test='CloudantViewFailuresAreNotEmptyAnswersTest',
        expect_fail=['aDocumentlessKeyedPagedRowRefusesToo'],
    ),
    dict(
        id="OQ",
        what='an UPDATE adopts the deterministic row again — the withdrawn fix that destroyed configuration',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        # Restores the withdrawn round-4 fix: adopt the row because _id and _rev are in
        # hand. On this path the request was assembled against the SAME selector that
        # just missed, so it carries "[configured]" where the credential belongs —
        # adoption writes that over the real configuration. No control anchored this
        # block before; a round-6 audit listed the cheap defeats of the source lock and
        # this anchor is the tripwire half of the answer (the loosened assertFalse on
        # deterministic.getRev is the other half).
        # Span start extended to TWO lines: the update-side scan refusal added in the
        # same review round begins with the identical first line, and the preflight
        # refused the ambiguity the moment it appeared.
        find_span=('            throw new ConnectorIndexNotReadyException("connector " + def.getConnectorId()\n                    + " exists under its deterministic id but the index did not report it."',
                   'caught up.");'),
        replace='            doc.setId(deterministic.getId());\n            doc.setRev(deterministic.getRev());',
        test='ConnectorCreationRefusesAnIndexDisagreementTest',
        expect_fail=['anUpdateRefusesRetryably'],
    ),
    dict(
        id="OR",
        what='the PUT mask gate narrows to credentialRef only — the webhook arm reopens',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionController.java',
        # OF removes the whole gate, so a NARROWING — deleting just the webhookSecret
        # clause — kept OF firing and every test green: no test PUT a masked webhook
        # secret with the read-back missing. The one-arm shape inside the guard OF was
        # added for, named by a round-6 audit.
        find='        if (existing == null\n                && ("[configured]".equals(def.getCredentialRef())\n                        || "[configured]".equals(def.getWebhookSecret()))) {',
        replace='        if (existing == null\n                && "[configured]".equals(def.getCredentialRef())) {',
        test='ConnectorDefinitionControllerPartialPutTest',
        expect_fail=['aMaskedWebhookSecretIsNotWrittenEither'],
    ),
    dict(
        id="OS",
        what='the retryable refusal reaches the client as a 500 again',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionController.java',
        # The source lock's defeat, listed by a round-6 audit: update() names
        # SERVICE_UNAVAILABLE twice, so rewording only the CATCH to a 500 keeps both
        # contains() green. The behavioural lock drives a thrown
        # ConnectorIndexNotReadyException through the controller and cannot be fooled
        # by spelling; this control measures that lock.
        find_span=('        } catch (ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException e) {',
                   '            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());'),
        replace='        } catch (ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException e) {\n            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());',
        test='ConnectorDefinitionControllerPartialPutTest',
        expect_fail=['theRetryableRefusalReachesTheClientAs503'],
    ),
    dict(
        id="OT",
        what="the CREATE mask gate narrows to credentialRef only — the webhook arm reopens",
        file="core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionController.java",
        # The CREATE twin of OR. ON removes the WHOLE gate, so a narrowing — deleting just
        # the webhookSecret clause — kept ON firing and every test green: no test POSTed a
        # masked webhook secret. The same one-arm gap this round closed on the PUT side
        # (A5), reproduced inside the gate this round added. A parallel review caught it
        # AFTER the 203-sweep, which could not have: no control measured the clause.
        find='        if ("[configured]".equals(def.getCredentialRef())\n                || "[configured]".equals(def.getWebhookSecret())) {',
        replace='        if ("[configured]".equals(def.getCredentialRef())) {',
        test="ConnectorDefinitionControllerPartialPutTest",
        expect_fail=["aCreateCarryingAMaskedWebhookSecretIsRefusedToo"],
    ),
    dict(
        id="OU",
        what='the divergent-twin arm retires the legacy row anyway — a silent winner is chosen',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        # Removes the early return, so the divergent arm falls through to the
        # conditional delete: the legacy row's configuration is destroyed on the very
        # disagreement the migration refuses to resolve. The lock verifies the delete
        # NEVER happens on divergence.
        # Re-anchored in the migration fix round: the divergent ERROR message was
        # rewritten to prescribe the one-row delete (the old text prescribed an
        # operation that deleted BOTH rows).
        find='                result.divergent.add(connectorId + " (legacy " + legacyId + " vs "\n                        + deterministicId + ")");\n                logger.error("Connector {} exists as BOTH {} and {} with DIFFERENT content."\n                        + " Neither row was touched. Resolve by deleting the row you do NOT"\n                        + " want: DELETE .../admin/connectors/{}?docId=<one of the two ids"\n                        + " above>", connectorId, legacyId, deterministicId, connectorId);\n                return;',
        replace='                result.divergent.add(connectorId + " (legacy " + legacyId + " vs "\n                        + deterministicId + ")");',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['aDivergentTwinIsUntouchedAndReported'],
    ),
    dict(
        id="OV",
        what="an unanswered _all_docs listing reads as 'nothing to migrate'",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        # The enumeration failing must throw: a migration that answers 'complete' on a
        # listing it never received is the failure-as-absence shape one layer up. The
        # sabotage turns the refusal into a quiet break.
        find_span=('            if (listing == null || listing.getRows() == null) {',
                   'startup");\n            }'),
        replace='            if (listing == null || listing.getRows() == null) {\n                break;\n            }',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['anUnansweredListingRefuses'],
    ),
    dict(
        id="OW",
        what='a conflicted retirement is logged and swallowed — the pass reports itself clean',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        # Drops the failures entry and keeps only the log line. A concurrent edit that
        # defeats the conditional delete then leaves both rows behind with the summary
        # claiming a clean pass — the next startup's divergent report becomes the only
        # trace, and the patch returned true.
        find='            result.failures.add(connectorId + " (" + e.getMessage() + ")");\n',
        replace='',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['aConflictedRetirementIsReportedNotSwallowed'],
    ),
    dict(
        id="OX",
        what='the migration patch is not registered at all',
        file='core/src/main/webapp/WEB-INF/classes/patchContext.xml',
        # Deleting the chain entry: the class exists, every unit test of the service
        # passes, and no startup ever migrates anything — §62 stays open on every
        # upgraded installation with all Java-side locks green. Only the XML lock sees
        # registration.
        find='\t\t\t\t<!-- §62: legacy generated-id connector rows are rewritten under their\n\t\t\t\t     deterministic ids. MUST come before\n\t\t\t\t     Patch_DefaultCloudDriveConnectorProfile: that patch\'s existence check is\n\t\t\t\t     a Mango selector, and with the legacy row migrated first, even a selector\n\t\t\t\t     whose index is rebuilding cannot lead to a duplicate — the id-addressed\n\t\t\t\t     check inside create() sees the deterministic row. Always-run, historyless\n\t\t\t\t     and ungated (reads only _all_docs and id-addressed gets); the class\n\t\t\t\t     javadoc carries the full argument. -->\n\t\t\t\t<bean class="jp.aegif.nemaki.patch.Patch_ConnectorDefinitionDeterministicIds">\n\t\t\t\t\t<property name="patchUtil">\n\t\t\t\t\t\t<ref bean="patchUtil" />\n\t\t\t\t\t</property>\n\t\t\t\t</bean>\n',
        replace='',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['theMigrationRunsBeforeTheDefaultConnectorPatch'],
    ),
    dict(
        id="OY",
        what='the migration patch runs AFTER the default-connector patch',
        file='core/src/main/webapp/WEB-INF/classes/patchContext.xml',
        # The swap: both patches present, order reversed. The one startup that creates
        # default connectors then runs against the unmigrated state — the exact window
        # the ordering comment in the XML is about.
        find='\t\t\t\t<!-- §62: legacy generated-id connector rows are rewritten under their\n\t\t\t\t     deterministic ids. MUST come before\n\t\t\t\t     Patch_DefaultCloudDriveConnectorProfile: that patch\'s existence check is\n\t\t\t\t     a Mango selector, and with the legacy row migrated first, even a selector\n\t\t\t\t     whose index is rebuilding cannot lead to a duplicate — the id-addressed\n\t\t\t\t     check inside create() sees the deterministic row. Always-run, historyless\n\t\t\t\t     and ungated (reads only _all_docs and id-addressed gets); the class\n\t\t\t\t     javadoc carries the full argument. -->\n\t\t\t\t<bean class="jp.aegif.nemaki.patch.Patch_ConnectorDefinitionDeterministicIds">\n\t\t\t\t\t<property name="patchUtil">\n\t\t\t\t\t\t<ref bean="patchUtil" />\n\t\t\t\t\t</property>\n\t\t\t\t</bean>\n\t\t\t\t<!-- Default cloud drive connector/profile definitions -->\n\t\t\t\t<bean class="jp.aegif.nemaki.patch.Patch_DefaultCloudDriveConnectorProfile">\n\t\t\t\t\t<property name="patchUtil">\n\t\t\t\t\t\t<ref bean="patchUtil" />\n\t\t\t\t\t</property>\n\t\t\t\t</bean>\n',
        replace='\t\t\t\t<!-- Default cloud drive connector/profile definitions -->\n\t\t\t\t<bean class="jp.aegif.nemaki.patch.Patch_DefaultCloudDriveConnectorProfile">\n\t\t\t\t\t<property name="patchUtil">\n\t\t\t\t\t\t<ref bean="patchUtil" />\n\t\t\t\t\t</property>\n\t\t\t\t</bean>\n\t\t\t\t<!-- §62: legacy generated-id connector rows are rewritten under their\n\t\t\t\t     deterministic ids. MUST come before\n\t\t\t\t     Patch_DefaultCloudDriveConnectorProfile: that patch\'s existence check is\n\t\t\t\t     a Mango selector, and with the legacy row migrated first, even a selector\n\t\t\t\t     whose index is rebuilding cannot lead to a duplicate — the id-addressed\n\t\t\t\t     check inside create() sees the deterministic row. Always-run, historyless\n\t\t\t\t     and ungated (reads only _all_docs and id-addressed gets); the class\n\t\t\t\t     javadoc carries the full argument. -->\n\t\t\t\t<bean class="jp.aegif.nemaki.patch.Patch_ConnectorDefinitionDeterministicIds">\n\t\t\t\t\t<property name="patchUtil">\n\t\t\t\t\t\t<ref bean="patchUtil" />\n\t\t\t\t\t</property>\n\t\t\t\t</bean>\n',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['theMigrationRunsBeforeTheDefaultConnectorPatch'],
    ),
    dict(
        id="OZ",
        what='the _all_docs walk stops after its first page',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        # A database with more than one page of config rows silently keeps its legacy
        # rows: the walk claims completion after page one. The lock builds a full first
        # page and puts the legacy row on page two.
        find='            if (listing.getRows().size() < MIGRATION_PAGE) {\n                break;\n            }',
        replace='            break;',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['theWalkPagesPastTheFirstPage'],
    ),
    dict(
        id="PA",
        what='a CREATE trusts the selector and the deterministic id alone again — the scan is gone',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        # The residual §62 window from the CREATE side: with the index-free scan
        # removed, a legacy row invisible to a rebuilding selector AND to the
        # deterministic id lets create() write the divergent twin the migration exists
        # to prevent — exactly what a review showed the failed-migration path doing.
        # Re-anchored: the scan gained its UPDATE arm in the same review round, so the
        # block now carries both refusals and removing it opens both at once.
        # Re-anchored: the scan call gained the unprovable-row type-split (503 for
        # updates), so the block now begins at the local declaration.
        find_span=('            boolean someRowDefinesThisConnector;\n            try {',
                   'caught up.");\n            }\n'),
        replace='',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['aCreateRefusesWhenTheScanFindsALegacyRow',
                     'anUpdateOverAnInvisibleLegacyRowRefusesRetryably'],
    ),
    dict(
        id="PB",
        what='the walk processes a re-served continuation row twice',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        # Without the client-side id dedup, the continuation key that still exists is
        # re-served as the first row of the next page and migrated AGAIN — a second
        # copy attempt against an id that now exists, and double counting.
        find='                    if (id.equals(resumeAfterId)) {\n                        // the continuation key itself, re-served because it still exists\n                        continue;\n                    }\n',
        replace='',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['aBoundaryRowIsHandledExactlyOnce'],
    ),
    dict(
        id="PC",
        what='an attachment-bearing legacy row is migrated without its attachments',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        # getProperties() does not carry attachments; with the guard gone the copy
        # silently drops them and the retirement destroys the only holder.
        find_span=('            if (legacy.getAttachments() != null && !legacy.getAttachments().isEmpty()) {',
                   'drop the attachments first", legacyId);\n                return;\n            }'),
        replace='            if (legacy.getAttachments() != null && !legacy.getAttachments().isEmpty()) {\n            }',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['anAttachmentBearingRowIsRefused'],
    ),
    dict(
        id="PD",
        what='a tombstone-blocked copy retries for ever with a cause nothing names',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        # Removes the purge-and-retry arm: the 409 from a tombstoned deterministic id
        # goes straight to the failure entry, and the row re-fails identically on
        # every startup.
        find_span=('                } catch (Exception firstAttempt) {',
                   '                    } else {\n                        throw firstAttempt;\n                    }\n                }'),
        replace='                } catch (Exception firstAttempt) {\n                    throw firstAttempt;\n                }',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['aTombstoneBlockedCopyIsPurgedAndRetried'],
    ),
    dict(
        id="PE",
        what='the one-row delete removes rows of OTHER connectors',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        # Narrows the verification to null-props only: an id-addressed delete then
        # removes any row whatsoever — worse than the divergence it resolves.
        find='        if (props == null\n                || !ConnectorDefinition.DOC_TYPE.equals(props.get("type"))\n                || !connectorId.equals(props.get("connectorId"))) {',
        replace='        if (props == null) {',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['theOneRowDeleteRefusesAMismatchedRow'],
    ),
    dict(
        id="PF",
        what='the migration compares and copies storage fields again — identical twins read divergent',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        # findBySelector strips _id/_rev before mapping; the migration's raw-map
        # comparison did not. Two rows identical in content then stand as DIVERGENT
        # for ever (a false ERROR each startup), and the copy carries the legacy _rev.
        find='        Map<String, Object> content = new HashMap<>(props);\n        content.remove("_id");\n        content.remove("_rev");\n        content.remove("_attachments");\n        return content;',
        replace='        return new HashMap<>(props);',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['aTwinDifferingOnlyInStorageFieldsIsIdentical', 'aCopyNeverCarriesStorageFields'],
    ),
    dict(
        id="PG",
        what='the index-free scan narrows back to CREATE only — the update arm reopens',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        # PA removes the whole block; a NARROWING keeps PA's create half firing and
        # every update green while a real-value PUT writes the divergent twin with a
        # 200. The one-arm shape, measured directly.
        # Re-anchored to the split shape: the narrowing now short-circuits the scan
        # inside the try, which also keeps the unprovable arm reachable for create.
        find='            boolean someRowDefinesThisConnector;\n            try {\n                someRowDefinesThisConnector =\n                        aConnectorRowExistsIndexFree(cloudant, dbName, def.getConnectorId());',
        replace='            boolean someRowDefinesThisConnector;\n            try {\n                someRowDefinesThisConnector = creating\n                        && aConnectorRowExistsIndexFree(cloudant, dbName, def.getConnectorId());',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['anUpdateOverAnInvisibleLegacyRowRefusesRetryably'],
    ),
    dict(
        id="PH",
        what="an update whose scan cannot read a row escapes as a 500 again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        # The closure-time record made real: unwrap the type-split and the unprovable
        # refusal reaches the update controller untyped — a 500 that opens tickets and
        # carries no retry hint, for a condition as transient as an index rebuild.

        find='            } catch (IllegalStateException unprovable) {\n                // The scan could not CLASSIFY a row, so uniqueness is unprovable right now.\n                // For a CREATE that stays the existing contract (IllegalStateException →\n                // 400, with its own lock). For an UPDATE it used to escape as a 500 —\n                // recorded at closure time as "twin-free but unlocked" — while the\n                // condition is exactly as transient as the rebuilding-index refusals this\n                // exception exists for. A retry reads the row and proceeds.\n                if (creating) {\n                    throw unprovable;\n                }\n                throw new ConnectorIndexNotReadyException(unprovable.getMessage());\n            }',
        replace='            } catch (IllegalStateException unprovable) {\n                throw unprovable;\n            }',
        test="ConnectorLegacyIdMigrationTest",
        expect_fail=["anUpdateWhoseScanCannotReadRefusesRetryablyToo"],
    ),
    dict(
        id="MO",
        what="the type registry reads the store outside the declared startup window",
        file="core/src/main/java/jp/aegif/nemaki/cmis/aspect/type/impl/TypeManagerImpl.java",
        # Re-anchored: the window is now opened only for the FIRST initialization, so the
        # begin() sits behind a condition rather than directly above the try.
        find="\t\t\tif (firstInitialization) {\n\t\t\t\tStartupPhase.begin();\n\t\t\t}",
        replace="\t\t\tif (firstInitialization) {\n\t\t\t}",
        test="OneRepositoryDoesNotTakeDownTheRegistryTest",
        expect_fail=["initDeclaresTheStartupWindow"],
    ),
    dict(
        id="MP",
        what="one repository's type failure escapes the loop and takes the others down",
        file="core/src/main/java/jp/aegif/nemaki/cmis/aspect/type/impl/TypeManagerImpl.java",
        find_span=("\t\t\ttry {\n\t\t\t\tgenerate(key);\n\t\t\t\ttypeLoadFailures.remove(key);",
                   "until a later refresh succeeds.\", e);\n\t\t\t}"),
        replace="\t\t\tgenerate(key);",
        test="OneRepositoryDoesNotTakeDownTheRegistryTest",
        expect_fail=["aBrokenRepositoryIsIsolated"],
    ),
    dict(
        id="MQ",
        what="a repository whose types failed to load answers from its base-only map",
        file="core/src/main/java/jp/aegif/nemaki/cmis/aspect/type/impl/TypeManagerImpl.java",
        find="\tpublic Collection<TypeDefinitionContainer> getTypeDefinitionList(String repositoryId) {\n\t\tensureInitialized();\n\t\tassertRepositoryTypesLoaded(repositoryId);",
        replace="\tpublic Collection<TypeDefinitionContainer> getTypeDefinitionList(String repositoryId) {\n\t\tensureInitialized();",
        test="OneRepositoryDoesNotTakeDownTheRegistryTest",
        expect_fail=["aBrokenRepositoryIsIsolated"],
    ),
    dict(
        id="HA",
        what="a retained folder is erased from the search index again",
        file="core/src/main/java/jp/aegif/nemaki/cmis/service/impl/ObjectServiceImpl.java",
        find="if (folder != null && !failedIds.contains(folder.getId())) {",
        replace="if (folder != null) {",
        test="DeleteTreeDfsKeepsFoldersOverInvisibleChildrenTest",
        expect_fail=["aRetainedFolderStaysFindable"],
    ),
]


def sabotage_text(source: str, control: dict) -> str:
    """Apply the control's edit, refusing loudly when the anchor is gone or ambiguous."""
    if "find" in control:
        anchor = control["find"]
        count = source.count(anchor)
        if count != 1:
            raise SystemExit(
                f"[{control['id']}] anchor matches {count} times (need exactly 1). The code "
                f"under this control moved; update the control rather than trusting a stale "
                f"one. Anchor:\n{anchor}")
        return source.replace(anchor, control["replace"], 1)
    start_marker, end_marker = control["find_span"]
    start = source.find(start_marker)
    if start < 0 or source.find(start_marker, start + 1) >= 0:
        raise SystemExit(f"[{control['id']}] span start missing or ambiguous: {start_marker!r}")
    end = source.find(end_marker, start)
    if end < 0:
        raise SystemExit(f"[{control['id']}] span end not found after start: {end_marker!r}")
    end += len(end_marker)
    span = source[start:end]
    # The end marker's FIRST occurrence is taken, and nothing used to check that it was the
    # right one. A marker that also matches earlier inside the block cuts the span short, the
    # sabotage then deletes half a statement, and the run reports "nothing was measured" —
    # which is the good case. The bad case is a short span that still compiles and sabotages
    # something other than the arm under measurement. ML hit exactly this shape.
    #
    # The property checked is that the span and its replacement OPEN AND CLOSE THE SAME
    # AMOUNT. Requiring the span itself to balance was tried first and was wrong: a catch
    # block legitimately begins with the closing brace of the try before it, so
    # `} catch (E e) { ... }` has a delta of -1 and is perfectly well formed. What must not
    # differ is the two deltas — that is what leaves the file unbalanced.
    span_delta = _delimiter_delta(span)
    replacement_delta = _delimiter_delta(control["replace"])
    if span_delta != replacement_delta:
        occurrences = source.count(end_marker, start)
        raise SystemExit(
            f"[{control['id']}] the span and its replacement do not open and close the same "
            f"amount (span {span_delta}, replacement {replacement_delta}), so the end marker "
            f"matched at the wrong place — it occurs {occurrences} time(s) after the start. "
            f"Lengthen the end marker until the two agree.\nSpan:\n{span}")
    return source[:start] + control["replace"] + source[end:]


def _delimiter_delta(span: str) -> tuple:
    """How many braces and parentheses the fragment opens minus closes.

    String/char literals and comments are skipped, so a brace inside a message does not count.
    """
    depth = {"{": 0, "(": 0}
    closing = {"}": "{", ")": "("}
    i, n = 0, len(span)
    while i < n:
        c = span[i]
        if c == '"':
            i += 1
            while i < n and span[i] != '"':
                i += 2 if span[i] == "\\" else 1
        elif c == "'":
            i += 1
            while i < n and span[i] != "'":
                i += 2 if span[i] == "\\" else 1
        elif c == "/" and i + 1 < n and span[i + 1] == "/":
            while i < n and span[i] != "\n":
                i += 1
        elif c == "/" and i + 1 < n and span[i + 1] == "*":
            i = span.find("*/", i)
            i = n if i < 0 else i + 1
        elif c in depth:
            depth[c] += 1
        elif c in closing:
            depth[closing[c]] -= 1
        i += 1
    return (depth["{"], depth["("])


def run_test(test_class: str) -> tuple[bool, str, str]:
    """Run one test class; return (all_green, failure_lines, full_report_text)."""
    for old in REPORTS.glob("*.txt"):
        old.unlink()
    # No -DfailIfNoTests=false: with it, a renamed or moved test class became a zero-test
    # green — the sabotage phase would then misread the silence as "protects nothing" and the
    # restore check as a clean tree. Source anchors refuse loudly on drift; the test side has
    # to as well.
    proc = subprocess.run(
        ["mvn", "-o", "-q", "-pl", "core", "test", f"-Dtest={test_class}"],
        cwd=REPO, capture_output=True, text=True, timeout=900)
    failures = []
    report_text = []
    for report in REPORTS.glob("*.txt"):
        # -output.txt is captured STDOUT, not a report: a test that PRINTS "<<< FAILURE!"
        # would read as a failure line.
        if report.name.endswith("-output.txt"):
            continue
        body = report.read_text(errors="replace")
        report_text.append(body)
        for line in body.splitlines():
            if "<<< FAILURE!" in line or "<<< ERROR!" in line:
                failures.append(line.strip())
    # Two ways a run measures nothing, both hit by hand before this runner existed:
    # the literal marker, and — sturdier — a nonzero exit with NO reports at all
    # (a broken build writes none; a red test writes them and also exits nonzero).
    output = proc.stdout + proc.stderr
    if "COMPILATION ERROR" in output or (proc.returncode != 0 and not report_text):
        raise SystemExit(
            f"nothing was measured (exit {proc.returncode}, no reports): either the sabotage "
            f"broke the build — the failure mode two hand-run controls (FF, GC) hit — or the "
            f"test class no longer exists under that name:\n" + output[-2000:])
    if proc.returncode != 0 and not failures:
        # Reports exist but none carries a failure line, and Maven still exited nonzero: a
        # surefire fork crash or a mid-run death. This environment has produced exactly that
        # (four dumpstream files on 2026-08-30). It is neither green nor a fired lock — it is
        # an unmeasured run, and calling it either would be the substitution this whole tool
        # exists to end.
        raise SystemExit(
            f"maven exited {proc.returncode} with reports but no failure lines — the run "
            f"died without measuring anything:\n" + output[-2000:])
    return (len(failures) == 0, "\n".join(failures), "\n".join(report_text))


def _harness_broke(stanza: str) -> bool:
    """Was HarnessBroken RAISED here, or merely named?

    "Anywhere in the stanza" was too blunt: a lock whose whole subject is HarnessBroken —
    `assertThrows(HarnessBroken.class, ...)` — names it in its failure message, and that lock
    firing was scored as harness breakage. A raised exception appears as a type prefix at the
    start of a line, or after "Caused by: "; a mention appears inside a message.
    """
    for line in stanza.splitlines():
        text = line.strip()
        if text.startswith("Caused by: "):
            text = text[len("Caused by: "):]
        head = text.split(":", 1)[0]
        if head.endswith("HarnessBroken"):
            return True
    return False


def failed_as_assertion(report_text: str, method: str) -> bool:
    """Whether METHOD failed on the lock's own assertion, not on an unrelated error.

    A control that "fires" because the expected method died of an NPE or a broken
    fixture is not the lock firing — it is the sabotage breaking the harness, which
    proves nothing about the protection. The lock's refusals are all JUnit
    assertions, so the stanza after the method's failure line must name one.
    """
    lines = report_text.splitlines()
    for i, line in enumerate(lines):
        if method in line and ("<<< FAILURE!" in line or "<<< ERROR!" in line):
            # 6 lines was too narrow: surefire prints the assertion, then the stack, then
            # "Caused by:" — so a HarnessBroken wrapped inside an assertion fell outside the
            # window and the control counted as fired.
            #
            # A fixed 40 was too WIDE, which a second review caught: surefire does not pad a
            # short stanza, so the window ran into the NEXT test's failure and a real firing
            # followed by another test's HarnessBroken was scored as harness breakage. The
            # stanza ends where the next one begins.
            stanza_lines = []
            for line in lines[i:]:
                if stanza_lines and ("<<< FAILURE!" in line or "<<< ERROR!" in line):
                    break
                stanza_lines.append(line)
            stanza = "\n".join(stanza_lines)
            # HARNESS BREAKAGE FIRST, and it wins. JavaSource.methodBody and the reflection
            # helpers that report a renamed method used to throw AssertionError, so the one
            # case this function exists to exclude walked straight through the check below:
            # rename the method a control sabotages and the control reported FIRED while
            # measuring nothing. They now throw HarnessBroken, which is deliberately not an
            # AssertionError, and this is where it is rejected.
            if _harness_broke(stanza):
                return False
            # Mockito's verify() failures extend AssertionError but print their own class
            # names; a verify(never()) lock firing IS the assertion firing. And surefire's
            # .txt often starts the stanza with the MESSAGE, not the class name — "Wanted
            # but not invoked:" with spaces — which is how two real firings (HT, HV) were
            # misread as harness breakage until the message forms were added here.
            #
            # The count and ordering verifications were missing until a review pointed at
            # HG and HT, which use atLeast(): a lock that fires by "wanted 2 times but was 1"
            # printed TooFewActualInvocations, matched nothing here, and was read as harness
            # breakage — a real firing scored as "protects nothing".
            if ("AssertionFailedError" in stanza or "AssertionError" in stanza
                    or "NeverWantedButInvoked" in stanza or "WantedButNotInvoked" in stanza
                    or "MockitoAssertionError" in stanza
                    or "ArgumentsAreDifferent" in stanza
                    or "TooFewActualInvocations" in stanza
                    or "TooManyActualInvocations" in stanza
                    or "NoInteractionsWanted" in stanza
                    or "VerificationInOrderFailure" in stanza
                    or "Wanted but not invoked" in stanza
                    or "Never wanted here" in stanza
                    or "Argument(s) are different" in stanza
                    or "No interactions wanted here" in stanza
                    or "Verification in order failure" in stanza
                    or "Wanted at least" in stanza
                    or "Wanted 1 time" in stanza
                    or "Wanted 2 times" in stanza
                    or "Wanted 3 times" in stanza):
                return True
    return False


# The judgement functions above decide what every control REPORTS, so they need controls of
# their own. Each case below is a pair: an input the function must accept and an input it must
# reject. A one-sided check ("it accepts a real firing") is what let three of these defects sit
# in the runner while 149 controls reported green.
SELF_TEST_CASES = [
    # (name, callable -> actual, expected)
    ("a JUnit assertion is a firing",
     lambda: failed_as_assertion(
         "someTest -- Time elapsed: 0.1 s <<< FAILURE!\n"
         "org.opentest4j.AssertionFailedError: expected true", "someTest"), True),
    # The first version of this case used a stanza naming ONLY HarnessBroken — which the
    # matcher below rejects anyway, since it names no assertion. Reverting the rejection left
    # it green: it measured nothing. The shape that has to be refused is the one surefire
    # actually prints when a lock's helper breaks inside an assertion — BOTH names in the
    # stanza — because that is what walked through when these helpers threw AssertionError.
    ("harness breakage wins over an assertion name in the same stanza",
     lambda: failed_as_assertion(
         "someTest -- Time elapsed: 0.1 s <<< ERROR!\n"
         "org.opentest4j.AssertionFailedError: the lock could not read the method\n"
         "\tCaused by: jp.aegif.nemaki.util.test.HarnessBroken: method not found",
         "someTest"), False),
    ("a lock ABOUT HarnessBroken firing is still a firing",
     lambda: failed_as_assertion(
         "someTest -- Time elapsed: 0.1 s <<< FAILURE!\n"
         "org.opentest4j.AssertionFailedError: Unexpected exception type thrown, "
         "expected: <jp.aegif.nemaki.util.test.HarnessBroken> "
         "but was: <java.lang.AssertionError>", "someTest"), True),
    # A case that asserted "an AssertionError whose MESSAGE mentions HarnessBroken is not a
    # firing" was removed rather than kept: it contradicts the line above it. Mention is not
    # raising, and treating it as raising makes a lock whose subject IS HarnessBroken unable
    # to fire — which the runner demonstrated by scoring MS as harness breakage. The Java
    # side is what stops the old shape: no test may throw AssertionError for breakage, and
    # HarnessBreakageIsNotAFiringTest sweeps for it.
    ("an NPE is NOT a firing",
     lambda: failed_as_assertion(
         "someTest -- Time elapsed: 0.1 s <<< ERROR!\n"
         "java.lang.NullPointerException: Cannot invoke", "someTest"), False),
    ("verify(never()) firing is a firing",
     lambda: failed_as_assertion(
         "someTest -- Time elapsed: 0.1 s <<< FAILURE!\n"
         "org.mockito.exceptions.verification.NeverWantedButInvoked: \nNever wanted here",
         "someTest"), True),
    ("atLeast() falling short is a firing",
     lambda: failed_as_assertion(
         "someTest -- Time elapsed: 0.1 s <<< FAILURE!\n"
         "org.mockito.exceptions.verification.TooFewActualInvocations: \n"
         "Wanted at least 2 times but was 1", "someTest"), True),
    ("too many invocations is a firing",
     lambda: failed_as_assertion(
         "someTest -- Time elapsed: 0.1 s <<< FAILURE!\n"
         "org.mockito.exceptions.verification.TooManyActualInvocations: \n"
         "Wanted 1 time but was 3", "someTest"), True),
    ("verifyNoMoreInteractions firing is a firing",
     lambda: failed_as_assertion(
         "someTest -- Time elapsed: 0.1 s <<< FAILURE!\n"
         "org.mockito.exceptions.verification.NoInteractionsWanted: \n"
         "No interactions wanted here", "someTest"), True),
    ("inOrder firing is a firing",
     lambda: failed_as_assertion(
         "someTest -- Time elapsed: 0.1 s <<< FAILURE!\n"
         "org.mockito.exceptions.verification.VerificationInOrderFailure: \n"
         "Verification in order failure", "someTest"), True),
    ("a balanced fragment has a zero delta",
     lambda: _delimiter_delta("if (x > 0) {\n\tthrow new E(\"}\");\n}"), (0, 0)),
    ("a fragment ending mid-block does not",
     lambda: _delimiter_delta("if (x > 0) {\n\tthrow new E(\"a\");"), (1, 0)),
    ("a catch fragment legitimately closes one more than it opens",
     lambda: _delimiter_delta("} catch (Exception e) {\n\tlog.warn(\"x\");\n}"), (-1, 0)),
    ("braces inside a string literal do not count",
     lambda: _delimiter_delta('log.warn("{ unclosed in a string");'), (0, 0)),
    ("braces inside a line comment do not count",
     lambda: _delimiter_delta("// } stray in a comment\nint x = 1;"), (0, 0)),
    # The check itself, not just its helper. Every case above exercises _delimiter_delta and
    # none of them exercised sabotage_text, so deleting the comparison that USES it would have
    # left all of them green — the runner's own version of "a lock that measures the helper
    # rather than the call site". A review found it.
    ("a span whose end marker matched too early is refused by sabotage_text",
     lambda: _self_test_span_refusal(), "refused"),
    ("a well-formed span is still applied by sabotage_text",
     lambda: _self_test_span_applied(), "if (b) {\n\tSOMETHING;\n}\ntail();\n"),
    # A HarnessBroken that appears deeper in a real surefire stack than the first few lines.
    # A LONG stack: the nested cause sits past any fixed window. The first fix used 40
    # lines and the second 60, and a review pointed out that both are guesses — the stanza
    # ends where the next one begins, and nowhere else.
    ("harness breakage past any fixed window still wins",
     lambda: failed_as_assertion(
         "someTest -- Time elapsed: 0.1 s <<< ERROR!\n"
         "org.opentest4j.AssertionFailedError: the lock could not read the method\n"
         + "\tat jp.aegif.nemaki.Frame.method(Frame.java:1)\n" * 80
         + "Caused by: jp.aegif.nemaki.util.test.HarnessBroken: method not found",
         "someTest"), False),
    ("a real firing is not stolen by the NEXT test's harness breakage",
     lambda: failed_as_assertion(
         "firstTest -- Time elapsed: 0.1 s <<< FAILURE!\n"
         "org.opentest4j.AssertionFailedError: the guard did not fire\n"
         "\tat jp.aegif.nemaki.SomeLock.firstTest(SomeLock.java:3)\n"
         "secondTest -- Time elapsed: 0.1 s <<< ERROR!\n"
         "jp.aegif.nemaki.util.test.HarnessBroken: method not found",
         "firstTest"), True),
    ("harness breakage deeper in the stanza still wins",
     lambda: failed_as_assertion(
         "someTest -- Time elapsed: 0.1 s <<< ERROR!\n"
         "org.opentest4j.AssertionFailedError: the lock could not read the method\n"
         "\tat org.junit.jupiter.api.Assertions.fail(Assertions.java:1)\n"
         "\tat jp.aegif.nemaki.SomeLock.check(SomeLock.java:2)\n"
         "\tat jp.aegif.nemaki.SomeLock.aTest(SomeLock.java:3)\n"
         "\tat java.base/java.lang.reflect.Method.invoke(Method.java:4)\n"
         "\tat org.junit.platform.Runner.run(Runner.java:5)\n"
         "Caused by: jp.aegif.nemaki.util.test.HarnessBroken: method not found",
         "someTest"), False),
]


def _self_test_span_refusal():
    """sabotage_text must refuse a span whose end marker lands mid-block."""
    source = "head();\nif (a) {\n\tif (b) {\n\t\tx();\n\t}\n}\ntail();\n"
    control = {"id": "SELFTEST", "find_span": ("if (a) {", "\t}"), "replace": ""}
    try:
        sabotage_text(source, control)
    except SystemExit:
        return "refused"
    return "applied"


def _self_test_span_applied():
    """...and must still apply a span whose delta matches its replacement."""
    source = "head();\nif (a) {\n\tSOMETHING;\n}\ntail();\n"
    control = {"id": "SELFTEST", "find_span": ("if (a) {", "}"),
               "replace": "if (b) {\n\tSOMETHING;\n}"}
    return sabotage_text(source, control).replace("head();\n", "")


def run_self_test() -> int:
    """Measures the runner itself. Returns the number of failures."""
    failures = 0
    for name, thunk, expected in SELF_TEST_CASES:
        try:
            actual = thunk()
        except Exception as e:  # noqa: BLE001 - a raising judgement is a failing judgement
            actual = f"raised {type(e).__name__}: {e}"
        if actual != expected:
            failures += 1
            print(f"  SELF-TEST FAILED: {name}\n    expected {expected!r}, got {actual!r}")
        else:
            print(f"  ok: {name}")
    print(f"self-test: {len(SELF_TEST_CASES) - failures}/{len(SELF_TEST_CASES)} passed")
    return failures


def expect_fail_methods_exist() -> list:
    """Controls whose expect_fail names a method its test class does not declare.

    A control that sabotages correctly and then waits for a method that was renamed reports
    "WRONG TEST FIRED" at best and "protects nothing" at worst — and the sabotage is real, so
    nothing else notices. MY sat in that state for a round after its lock was rewritten.
    """
    problems = []
    for control in CONTROLS:
        matches = list((REPO / "core" / "src" / "test").rglob(control["test"] + ".java"))
        if not matches:
            problems.append(f"[{control['id']}] no test class named {control['test']}")
            continue
        source = matches[0].read_text()
        for method in control["expect_fail"]:
            # A DECLARATION, not any occurrence: `" method("` also matches a call or a
            # mention in a comment (false negative), and misses `method (` or a signature
            # wrapped before the paren (false positive). JUnit methods are declared with a
            # return type immediately before the name, so that is what is required.
            declared = re.search(
                r"(?:void|boolean|int|long|String|var|[A-Z]\w*)\s+"
                + re.escape(method) + r"\s*\(", source)
            if not declared:
                problems.append(
                    f"[{control['id']}] expect_fail names {method}(), which "
                    f"{control['test']} does not declare — the lock was renamed and the "
                    f"control was not followed")
    return problems


def anchors_still_match() -> list:
    """Controls whose sabotage no longer applies to the file it names.

    The anchor check used to happen lazily, inside the run, one control at a time — so a
    control whose anchor had drifted raised SystemExit in the MIDDLE of the sweep and every
    control after it in CONTROLS never ran at all. OA reached that state when a follow-up fix
    in the same round hoisted a call into a local variable, and it would have taken five
    later controls down with it while the run looked like it had simply stopped.

    Worse, the failure is silent about its own scope: the output says one control's anchor is
    missing, not that the sweep is now partial. Checking every anchor up front turns that into
    a refusal that names all of them and runs nothing.

    Applied to text held in memory. Nothing is written.
    """
    problems = []
    for control in CONTROLS:
        target = REPO / control["file"]
        if not target.exists():
            problems.append(f"[{control['id']}] {control['file']} does not exist")
            continue
        try:
            sabotage_text(target.read_text(), control)
        except SystemExit as refused:
            problems.append(f"[{control['id']}] {refused}")
    return problems


def main() -> None:
    if "--self-test" in sys.argv[1:]:
        raise SystemExit(1 if run_self_test() else 0)

    # The judgement functions decide every result below, so they are checked before any
    # control runs. A runner whose verdicts are wrong reports confidently either way.
    if run_self_test():
        raise SystemExit("the runner's own judgement functions are wrong; fix them before "
                         "trusting any control result")

    stale = expect_fail_methods_exist()
    if stale:
        raise SystemExit("controls point at locks that no longer exist:\n  "
                         + "\n  ".join(stale))

    # And the other half of the same question: the lock exists, but does the SABOTAGE still
    # apply? Both are asked before anything runs, so a drifted anchor cannot silently cut the
    # sweep short at the control where it happens to sit.
    drifted = anchors_still_match()
    if drifted:
        raise SystemExit("controls whose sabotage no longer applies — the sweep would stop "
                         "at the first of these and every control after it would not run:\n  "
                         + "\n  ".join(drifted))

    # Recover from a previous interrupted run FIRST: a leftover .nc-backup means a control
    # died between sabotage and restore, and the production file may still carry the edit.
    # Scoped to the source tree. rglob over the whole repo once picked up a backup the IDE's
    # language server had COPIED into core/target/classes as a resource, and "recovered" it by
    # writing a .java file into the compiled-classes directory — polluting the very output the
    # incremental build reuses (the known jdtls-poisons-the-WAR trap, self-inflicted).
    for leftover in (REPO / "core" / "src").rglob("*.nc-backup"):
        target = leftover.with_name(leftover.name.removesuffix(".nc-backup"))
        backup_text = leftover.read_text()
        current_text = target.read_text() if target.exists() else None
        if current_text == backup_text:
            # The interrupt hit between backup and sabotage; nothing to restore.
            leftover.unlink()
            continue
        known_sabotages = set()
        for c in CONTROLS:
            if (REPO / c["file"]) == target:
                try:
                    known_sabotages.add(sabotage_text(backup_text, c))
                except SystemExit:
                    pass
        if current_text in known_sabotages:
            target.write_text(backup_text)
            leftover.unlink()
            print(f"recovered {target.relative_to(REPO)} from an interrupted sabotage")
        else:
            # The target holds something this runner did not write — a concurrent edit the
            # previous run's finally refused to overwrite, or hand repair. The FIRST version
            # of this recovery wrote the backup over it unconditionally, undoing exactly the
            # edit the refusal had protected. Unknown state stays untouched, loudly.
            print(f"NOT restoring {target.relative_to(REPO)}: its content matches neither "
                  f"the backup nor any known sabotage. Reconcile by hand; the backup stays "
                  f"at {leftover.relative_to(REPO)}")
    wanted = set(sys.argv[1:])
    known = {c["id"] for c in CONTROLS}
    unknown = wanted - known
    if unknown:
        # ALL-or-nothing on typos: `FE GX` running only FE and exiting 0 is how a session
        # that already miscounted its own controls twice would miscount them a third time.
        raise SystemExit(f"unknown control id(s): {sorted(unknown)}; known: {sorted(known)}")
    controls = [c for c in CONTROLS if not wanted or c["id"] in wanted]
    is_subset = len(controls) < len(CONTROLS)
    if is_subset:
        # "2/2 controls fired" from a subset run reads exactly like a complete sweep, and
        # the 192-vs-194 bookkeeping this output forced had to be reconstructed by hand in
        # the ledger. A partial measurement must say its own scope. The predicate is the
        # COUNT, not "ids were named": naming all of them is a full run and must read as
        # one.
        print(f"running {len(controls)} of {len(CONTROLS)} controls — a SUBSET; "
              f"{len(CONTROLS) - len(controls)} controls are NOT measured by this run")
    results = []
    # Completion means the control's WHOLE cycle finished: sabotage, judgement, restore,
    # and the green-after re-verification. `results` gains its entry before the restore
    # half, so an abort in restore or green-after left the current id counted as
    # "completed" and MISSING from the not-run list — the enumeration lied by one.
    completed_ids = set()
    sweep_completed = False
    try:
        for control in controls:
            path = REPO / control["file"]
            backup = path.with_suffix(path.suffix + ".nc-backup")
            original = path.read_text()
            # The backup hits disk BEFORE the production file is touched, so a Ctrl-C anywhere
            # in the mutation window leaves a recoverable copy; startup (below, in main) restores
            # any leftover backup from a previous interrupted run before doing anything else.
            backup.write_text(original)
            print(f"[{control['id']}] {control['what']}")
            sabotaged = sabotage_text(original, control)
            try:
                path.write_text(sabotaged)
                green, failed, report_text = run_test(control["test"])
                if green:
                    # The finally below still restores, and the green-after re-verification after
                    # it still runs — the first version `continue`d past both, leaving the restore
                    # contract untested exactly where a finding was being reported.
                    results.append((control["id"], False,
                                    "the lock stayed GREEN under the sabotage — it protects "
                                    "nothing"))
                    print(f"[{control['id']}] DID NOT FIRE")
                else:
                    missing = [m for m in control["expect_fail"] if m not in failed]
                    not_assertions = [m for m in control["expect_fail"]
                                      if m not in missing
                                      and not failed_as_assertion(report_text, m)]
                    if missing:
                        results.append((control["id"], False,
                                        f"something failed, but not the expected lock(s) "
                                        f"{missing}; actual:\n{failed}"))
                        print(f"[{control['id']}] WRONG TEST FIRED")
                    elif not_assertions:
                        results.append((control["id"], False,
                                        f"{not_assertions} failed, but not on the lock's own "
                                        f"assertion — the sabotage broke the harness, which "
                                        f"proves nothing about the protection"))
                        print(f"[{control['id']}] FIRED FOR THE WRONG REASON")
                    else:
                        results.append((control["id"], True, failed.splitlines()[0]))
                        print(f"[{control['id']}] fired: {control['expect_fail']}")
            finally:
                # Refuse to restore over a CONCURRENT edit: if the file no longer holds the
                # sabotage this runner wrote, someone else changed it mid-control, and blindly
                # writing `original` would silently roll their work back. (Observed for real: a
                # reviewer watched this tree change under them mid-run.) The backup stays on disk
                # for hand recovery in that case.
                current = path.read_text()
                if current != sabotaged:
                    raise SystemExit(
                        f"[{control['id']}] {control['file']} changed while the control ran — "
                        f"NOT restoring over the concurrent edit; the pre-sabotage copy is at "
                        f"{backup}")
                path.write_text(original)
                backup.unlink(missing_ok=True)
            green_after, failed_after, _ = run_test(control["test"])
            if not green_after:
                raise SystemExit(
                    f"[{control['id']}] the tree is NOT green after restore — stop and look:\n"
                    + failed_after)
            completed_ids.add(control["id"])
        sweep_completed = True
    finally:
        # The anchors preflight stops STALE controls from cutting the sweep short, but a
        # mid-run SystemExit (a test class that does not compile, a restore that is not
        # green) still truncates it — and the output said which control died, never that
        # the sweep was PARTIAL or which controls were left unmeasured. Scope-silence is
        # the same defect the subset line above closes, on the abort path.
        if not sweep_completed:
            not_run = [c["id"] for c in controls if c["id"] not in completed_ids]
            print(f"\nSWEEP INCOMPLETE: {len(completed_ids)} of {len(controls)} controls "
                  f"completed; NOT fully measured (including any that died mid-run): "
                  f"{not_run}")
    print("\n== summary ==")
    fired = sum(1 for _, ok, _ in results if ok)
    for cid, ok, note in results:
        print(f"  {cid}: {'FIRED' if ok else 'DID NOT FIRE — ' + note}")
    if is_subset:
        print(f"{fired}/{len(results)} controls fired (SUBSET — "
              f"{len(CONTROLS) - len(results)} controls not measured by this run)")
    else:
        print(f"{fired}/{len(results)} controls fired")
    if fired != len(results):
        sys.exit(1)


if __name__ == "__main__":
    started = time.time()
    main()
    print(f"({int(time.time() - started)}s)")
