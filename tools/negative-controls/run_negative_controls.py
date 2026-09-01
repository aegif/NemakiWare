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
        find="\t\t\tif (unreadableRows > 0) {",
        replace="\t\t\tif (false) {",
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
        find="    private static volatile boolean provisioning = false;",
        replace="    private static volatile boolean provisioning = true;",
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
        what="user-by-id: an unanswered view is 'no such user' again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/UserGroupDaoDelegate.java",
        find_span=("\t\t\tif (result == null || result.getRows() == null) {\n\t\t\t\tthrow new IllegalStateException(\"the userItemsById view did not answer",
                   'user not existing");\n\t\t\t}'),
        replace="\t\t\tif (result == null || result.getRows() == null) {\n\t\t\t\treturn null;\n\t\t\t}",
        test="IdentityAndPolicyLookupsRefuseFailuresTest",
        expect_fail=["anUnansweredUserViewRefuses"],
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
        what="group-by-id: an unanswered view is 'no such group' again",
        file="core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/UserGroupDaoDelegate.java",
        find_span=("\t\t\tif (result == null || result.getRows() == null) {\n\t\t\t\tthrow new IllegalStateException(\"the groupItemsById view did not answer",
                   'group not existing");\n\t\t\t}'),
        replace="\t\t\tif (result == null || result.getRows() == null) {\n\t\t\t\treturn null;\n\t\t\t}",
        test="IdentityAndPolicyLookupsRefuseFailuresTest",
        expect_fail=["anUnansweredGroupViewRefuses"],
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
    return source[:start] + control["replace"] + source[end:]


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
            stanza = "\n".join(lines[i:i + 6])
            # Mockito's verify() failures extend AssertionError but print their own class
            # names; a verify(never()) lock firing IS the assertion firing. And surefire's
            # .txt often starts the stanza with the MESSAGE, not the class name — "Wanted
            # but not invoked:" with spaces — which is how two real firings (HT, HV) were
            # misread as harness breakage until the message forms were added here.
            if ("AssertionFailedError" in stanza or "AssertionError" in stanza
                    or "NeverWantedButInvoked" in stanza or "WantedButNotInvoked" in stanza
                    or "MockitoAssertionError" in stanza
                    or "ArgumentsAreDifferent" in stanza
                    or "Wanted but not invoked" in stanza
                    or "Never wanted here" in stanza
                    or "Argument(s) are different" in stanza):
                return True
    return False


def main() -> None:
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
    results = []
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
    print("\n== summary ==")
    fired = sum(1 for _, ok, _ in results if ok)
    for cid, ok, note in results:
        print(f"  {cid}: {'FIRED' if ok else 'DID NOT FIRE — ' + note}")
    print(f"{fired}/{len(results)} controls fired")
    if fired != len(results):
        sys.exit(1)


if __name__ == "__main__":
    started = time.time()
    main()
    print(f"({int(time.time() - started)}s)")
