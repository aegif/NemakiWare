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
Exit code 0 from a measuring run = every control IT RAN fired, each on its own
assertion; no lock failed under a control without being declared; every failing testcase was
attributable; and the tree was restored to green. A subset run says so in its own summary, and
its 0 covers only the controls it names. `--self-test` and `--compile-check` measure no
control at all and have their own 0.

A non-zero exit is NOT one thing. It can mean a control did not fire, fired for the wrong
reason, removes more protections than its record says, or produced a failing testcase this
runner could not attribute; it can equally mean NOTHING WAS MEASURED — the pre-flight refused
(self-test, a stale expect_fail, a drifted anchor, an unknown id), a compile-check found a
sabotage that no longer builds, a Maven run produced no reports, a sabotage no longer
applies, the tree was edited under the run so a restore was refused, or the restored tree
did not come back green. Read the OUTPUT, not the code: the last line names WHICH
KIND of finding ended the run (a control that did not fire — the summary above says which
and why — an incomplete expect_fail, or a failing testcase that could not be attributed),
and everything else
refuses where it happens, with its own message. Do not read a non-zero exit as "a protection is missing" without looking.
"""

import subprocess
import re
import sys
import time
from pathlib import Path
from xml.etree import ElementTree

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
        # Span start extended to the catch's own first comment line: the DELETE gained
        # an identical catch in round 2 and the one-line start became ambiguous.
        find_span=('        } catch (ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException e) {\n            // Transient and retryable. This reached the client as a 500 for a round, which',
                   '            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());'),
        replace='        } catch (ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException e) {\n            // Transient and retryable. This reached the client as a 500 for a round, which\n            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());',
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
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/NemakiConfAllDocs.java',
        # Re-pointed: the walk was extracted to a shared class for the import-profile
        # service; the sabotage text is unchanged, so only the file moved.
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
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/NemakiConfAllDocs.java',
        # Re-pointed: the walk was extracted to a shared class for the import-profile
        # service; the sabotage text is unchanged, so only the file moved.
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
        # Re-anchored in round 2: the index-free count now runs before every write.
        find_span=('        int rowsDefiningThisConnector;',
                   '                    + " create never overwrites one)");\n        }'),
        replace='',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['aCreateRefusesWhenTheScanFindsALegacyRow', 'anUpdateOverAnInvisibleLegacyRowRefusesRetryably', 'anUpdateWithTwoVisibleTwinsDoesNotWrite', 'anUpdateWithAHiddenTwinIsAStandingPairNotARetry', 'aCreateNeverAdoptsARowTheScanFound', 'theSelectorMustNotOutReportTheWalk'],
    ),
    dict(
        id="PB",
        what='the walk serves a re-served continuation row twice — one row counted as a standing twin',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/NemakiConfAllDocs.java',
        # Re-pointed: the walk was extracted to a shared class for the import-profile
        # service; the sabotage text is unchanged, so only the file moved.
        # Without the client-side id dedup, the continuation key that still exists is
        # re-served as the first row of the next page and migrated AGAIN — a second
        # copy attempt against an id that now exists, and double counting.
        find='                    if (id.equals(resumeAfterId)) {\n                        // the continuation key itself, re-served because it still exists\n                        continue;\n                    }\n',
        replace='',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['aBoundaryRowIsCountedExactlyOnce'],
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
        # Re-anchored in round 17 (the identity check moved into definesConnector).
        find='        if (!definesConnector(props, connectorId)) {',
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
        # Re-anchored in round 2: the index-free count now runs before every write.
        find='            rowsDefiningThisConnector = countConnectorRowsIndexFree(cloudant, dbName,\n                    def.getConnectorId());',
        replace='            rowsDefiningThisConnector = creating\n                    ? countConnectorRowsIndexFree(cloudant, dbName, def.getConnectorId()) : 0;',
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

        # Re-anchored in round 2: the index-free count now runs before every write.
        find='        } catch (IllegalStateException unprovable) {\n            // The scan could not CLASSIFY a row, so uniqueness is unprovable right now.\n            // For a CREATE that stays the existing contract (IllegalStateException →\n            // 400, with its own lock). For an UPDATE it used to escape as a 500 —\n            // recorded at closure time as "twin-free but unlocked" — while the\n            // condition is exactly as transient as the rebuilding-index refusals this\n            // exception exists for. A retry reads the row and proceeds.\n            if (creating) {\n                throw unprovable;\n            }\n            throw new ConnectorIndexNotReadyException(unprovable.getMessage());\n        }',
        replace='        } catch (IllegalStateException unprovable) {\n            throw unprovable;\n        }',
        test="ConnectorLegacyIdMigrationTest",
        expect_fail=["anUpdateWhoseScanCannotReadRefusesRetryablyToo"],
    ),
    dict(
        id="PI",
        what='the profile CREATE/UPDATE trusts the selector and the deterministic id alone — the scan is gone',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        # The import-profile twin of PA: with the index-free scan removed, the startup
        # patch's per-repository create writes a second cloud-import-<repo> beside a legacy
        # row the rebuilding selector cannot show.
        # Re-anchored in round 2: the index-free count now runs before every write.
        find_span=('        int rowsDefiningThisProfile;',
                   '                    + " create never overwrites one)");\n        }'),
        replace='',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aCreateRefusesWhenTheScanFindsALegacyRow', 'anUpdateOverAnInvisibleLegacyRowRefusesRetryably', 'anUpdateWithTwoVisibleTwinsDoesNotWrite', 'anUpdateWithAHiddenTwinIsAStandingPairNotARetry', 'aCreateNeverAdoptsARowTheScanFound', 'theSelectorMustNotOutReportTheWalk'],
    ),
    dict(
        id="PJ",
        what='the profile scan narrows back to CREATE only — the update arm reopens',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        # Twin of PG.
        # Re-anchored in round 2: the index-free count now runs before every write.
        find='            rowsDefiningThisProfile = countProfileRowsIndexFree(cloudant, dbName,\n                    def.getProfileId(), null);',
        replace='            rowsDefiningThisProfile = creating\n                    ? countProfileRowsIndexFree(cloudant, dbName, def.getProfileId(), null) : 0;',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['anUpdateOverAnInvisibleLegacyRowRefusesRetryably'],
    ),
    dict(
        id="PK",
        what='a profile update whose scan cannot read a row escapes as a 500 again',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        # Twin of PH.
        # Re-anchored in round 2: the index-free count now runs before every write.
        find='        } catch (IllegalStateException unprovable) {\n            // The scan could not CLASSIFY a row, so uniqueness is unprovable right now. A\n            // create keeps the existing contract (IllegalStateException → 400, locked); an\n            // update is refused retryably (503) — the condition is as transient as an index\n            // rebuild.\n            if (creating) {\n                throw unprovable;\n            }\n            throw new ProfileIndexNotReadyException(unprovable.getMessage());\n        }',
        replace='        } catch (IllegalStateException unprovable) {\n            throw unprovable;\n        }',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['anUpdateWhoseScanCannotReadRefusesRetryablyToo'],
    ),
    dict(
        id="PL",
        what='a profile UPDATE adopts the hidden deterministic row — the withdrawn connector fix, on profiles',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        # Twin of OQ: adopt the row because _id and _rev are in hand. The connector side
        # withdrew exactly this after a review showed the payload assembled against the
        # same selector that just missed.
        # Re-anchored in round 2: the arm now sits inside the else block, after the
        # index-free count.
        find_span=('                throw new ProfileIndexNotReadyException("import profile " + def.getProfileId()\n                        + " exists under its deterministic id but the index did not report"',
                   '                        + " it. Retry once the index has caught up.");'),
        replace='                doc.setId(deterministic.getId());\n                doc.setRev(deterministic.getRev());',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['anUpdateRefusesRetryablyWhenTheDeterministicRowIsHidden'],
    ),
    dict(
        id="PM",
        what='a new profile is saved under a GENERATED id again — the pre-closure shape',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        # The original defect itself: no setId on a selector miss, CouchDB picks the id,
        # and the id-addressed duplicate check can never see the row.
        find='            doc.setId(ImportProfileDefinition.DOC_TYPE + ":" + def.getProfileId());\n',
        replace='',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aCreateStillWorksWhenTheScanFindsNothing'],
    ),
    dict(
        id="PN",
        what='the profile migration compares and copies storage fields again',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        # Twin of PF.
        find='        Map<String, Object> content = new HashMap<>(props);\n        content.remove("_id");\n        content.remove("_rev");\n        content.remove("_attachments");\n        return content;',
        replace='        return new HashMap<>(props);',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aTwinDifferingOnlyInStorageFieldsIsIdentical', 'aCopyNeverCarriesStorageFields'],
    ),
    dict(
        id="PO",
        what='the profile divergent-twin arm retires the legacy row anyway',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        # Twin of OU.
        find='                logger.error("Import profile {} exists as BOTH {} and {} with DIFFERENT"\n                        + " content. Neither row was touched. Resolve by deleting the row"\n                        + " you do NOT want: DELETE .../admin/import-profiles/{}?docId=<one"\n                        + " of the two ids above>", profileId, legacyId, deterministicId,\n                        profileId);\n                return;',
        replace='                logger.error("Import profile {} exists as BOTH {} and {} with DIFFERENT"\n                        + " content. Neither row was touched. Resolve by deleting the row"\n                        + " you do NOT want: DELETE .../admin/import-profiles/{}?docId=<one"\n                        + " of the two ids above>", profileId, legacyId, deterministicId,\n                        profileId);',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aDivergentTwinIsUntouchedAndReported'],
    ),
    dict(
        id="PP",
        what='a conflicted profile retirement is logged and swallowed',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        # Twin of OW.
        find='            result.failures.add(profileId + " (" + e.getMessage() + ")");\n',
        replace='',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aConflictedRetirementIsReportedNotSwallowed'],
    ),
    dict(
        id="PQ",
        what='an attachment-bearing legacy profile row is migrated without its attachments',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        # Twin of PC.
        find_span=('            if (legacy.getAttachments() != null && !legacy.getAttachments().isEmpty()) {',
                   '                logger.error("Import profile row {} carries attachments and was NOT migrated",\n                        legacyId);\n                return;\n            }'),
        replace='            if (legacy.getAttachments() != null && !legacy.getAttachments().isEmpty()) {\n            }',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['anAttachmentBearingRowIsRefused'],
    ),
    dict(
        id="PR",
        what='a tombstone-blocked profile copy retries for ever',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        # Twin of PD.
        find_span=('                } catch (Exception firstAttempt) {',
                   '                    } else {\n                        throw firstAttempt;\n                    }\n                }'),
        replace='                } catch (Exception firstAttempt) {\n                    throw firstAttempt;\n                }',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aTombstoneBlockedCopyIsPurgedAndRetried'],
    ),
    dict(
        id="PS",
        what='the profile one-row delete removes rows of OTHER profiles',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        # Twin of PE.
        # Re-anchored after the repository clause joined the check (review round 1), and again
        # in round 16 of the second batch (the identity check moved into definesProfile).
        find='        if (!definesProfile(props, profileId)\n                || repositoryId == null\n                || !(unowned || repositoryId.equals(props.get("repositoryId")))) {',
        replace='        if (props == null) {',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['theOneRowDeleteRefusesAMismatchedRow'],
    ),
    dict(
        id="PT",
        what='the startup patch drops the import-profile half of the migration',
        file='core/src/main/java/jp/aegif/nemaki/patch/Patch_ConnectorDefinitionDeterministicIds.java',
        # The patch runs both halves in one pass. Removing the profile call leaves every
        # profile-service test green while no startup ever migrates a cloud-import row —
        # only the source lock on the patch notices.
        # Re-anchored after the halves were isolated: the profile call is now a runHalf
        # with a method reference, and the sabotage keeps `profilesRan` defined.
        find='            boolean profilesRan = runHalf("import profiles",\n                    () -> ctx.getBean(ImportProfileDefinitionService.class).migrateLegacyGeneratedIds(),\n                    "DELETE .../admin/import-profiles/{id}?docId=...");',
        replace='            boolean profilesRan = true;',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['thePatchAndControllerCarryTheProfileHalf'],
    ),
    dict(
        id="PU",
        what="a profile CREATE over a hidden deterministic row is refused only retryably — the 'already exists' arm is gone",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        # The profile twin of OA's arm: with the create-side refusal removed, a create over
        # a hidden deterministic row escapes as the retryable type — a 503 for something
        # that is a plain duplicate — and the lock fails on the exception type.
        # Re-anchored in round 2: the index-free count now runs before every write.
        find='                if (creating) {\n                    throw new IllegalStateException("Import profile already exists: "\n                            + def.getProfileId() + " (found by an id-addressed read; the"\n                            + " index did not report it)");\n                }\n',
        replace='',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aCreateRefusesWhenTheDeterministicRowIsHidden'],
    ),
    dict(
        id="PV",
        what="the profile one-row delete stops checking the row's repository",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        # The P1 from the profile closure's first review: the controller authorises
        # against whichever twin the selector returned, so only a check on the ADDRESSED
        # row stops a caller of repository A deleting repository B's twin.
        # Re-anchored in round 16 of the second batch (the identity check moved into
        # definesProfile).
        find='        if (!definesProfile(props, profileId)\n                || repositoryId == null\n                || !(unowned || repositoryId.equals(props.get("repositoryId")))) {',
        replace='        if (!definesProfile(props, profileId)) {',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['theOneRowDeleteRefusesAnotherRepositorysRow'],
    ),
    dict(
        id="PW",
        what='the uniqueness validation reads the selector again — a hidden default lets a second one through',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        # listByRepository is answered by the rebuilding index; the walk is not.
        # Re-anchored in round 3 (the listing gained a range flag).
        # Re-anchored again (the listing names the rule's fields).
        find='        List<ImportProfileDefinition> existing = listByRepositoryIndexFree(repoId, creating,\n                UNIQUENESS_RULE_FIELDS);',
        replace='        List<ImportProfileDefinition> existing = listByRepository(repoId);',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aHiddenDefaultProfileStillBlocksASecondDefault'],
    ),
    dict(
        id="PX",
        what='the resolution stops refusing — a row it could not read reads as absent, so every verb answers 404',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionController.java',
        # hiddenOrAbsent emptied: a profile the selector cannot show is NOT FOUND again,
        # one layer above the retryable refusal that was written for exactly this. The
        # span's end marker is the method's own unique message — the 503 return line
        # alone appears in three catches of this controller.
        # Re-anchored in round 2: the gate takes the call context (repository confinement).
        find_span=('    private ResponseEntity<Map<String, Object>> resolveMine(CallContext ctx, String profileId,\n            ImportProfileDefinition selected, java.util.function.Consumer<ImportProfileDefinition> sink) {',
                   '        return null;\n    }'),
        replace='    private ResponseEntity<Map<String, Object>> resolveMine(CallContext ctx, String profileId,\n            ImportProfileDefinition selected, java.util.function.Consumer<ImportProfileDefinition> sink) {\n        try {\n            sink.accept(mineInstead(ctx, selected, profileId));\n        } catch (RuntimeException ignored) {\n            sink.accept(null);\n        }\n        return null;\n    }',
        test='ImportProfileHiddenIsNotAbsentTest',
        expect_fail=['aHiddenProfileIsA503OnPut', 'aHiddenProfileIsA503OnDelete'],
    ),
    dict(
        id="PY",
        what="a pair with one twin hidden is answered 'retry' although the count already established the pair",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        # The count is compared only when the selector was EMPTY: a partial selector
        # (one twin visible, one hidden) writes onto the visible twin and the pair
        # diverges with a 200. Found on the profile side in round 2; mirrored here.
        # The twin arm gated on full visibility: the hidden pair falls through to the
        # hidden arm and is told to retry — a retry that can only end in 409.
        find='        if (rowsDefiningThisConnector > 1) {',
        replace='        if (rowsDefiningThisConnector > 1 && rowsDefiningThisConnector == existing.size()) {',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['anUpdateWithAHiddenTwinIsAStandingPairNotARetry'],
    ),
    dict(
        id="QA",
        what='the connector plain delete skips legacy rows — a hidden twin survives',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        # The index-free delete narrowed to deterministic ids only: the legacy twin the
        # selector hid survives a delete that reports success.
        # Re-anchored in round 17 (the identity check moved into definesConnector).
        find='                if (definesConnector(props, connectorId)) {\n                    targets.add(doc);',
        replace='                if (definesConnector(props, connectorId)\n                        && id.startsWith(ConnectorDefinition.DOC_TYPE + ":")) {\n                    targets.add(doc);',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['thePlainDeleteRemovesHiddenTwinsToo',
                     'thePlainDeleteRemovesARowWhoseConnectorIdIsTheNumber'],
    ),
    dict(
        id="QG",
        what='the connector DELETE lets the retryable refusal escape as a 500',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionController.java',
        # The catch removed: an index-free delete that could not read every row errors
        # out of the controller instead of answering retry.
        find='        } catch (ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException e) {\n            // "Deleted" must mean deleted: the index-free delete refuses when a row could\n            // not be read, and that is a retry, not a success with a survivor.\n            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());\n        }',
        replace='        }',
        test='ConnectorDefinitionControllerPartialPutTest',
        expect_fail=['aDeleteThatCannotReadEveryRowIsA503NotASuccess'],
    ),
    dict(
        id="PZ",
        what='the profile twin of PY',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        # Twin of PY — the finding's origin.
        find='        if (rowsDefiningThisProfile > 1) {',
        replace='        if (rowsDefiningThisProfile > 1 && rowsDefiningThisProfile == existing.size()) {',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['anUpdateWithAHiddenTwinIsAStandingPairNotARetry'],
    ),
    dict(
        id="QB",
        what='the profile plain delete skips legacy rows — a hidden twin survives',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        # Twin of QA.
        # Re-anchored in round 16 of the second batch (the identity check moved into
        # definesProfile).
        find='                if (definesProfile(props, profileId)\n                        && repositoryId != null && repositoryId.equals(props.get("repositoryId"))) {\n                    targets.add(doc);',
        replace='                if (definesProfile(props, profileId)\n                        && repositoryId != null && repositoryId.equals(props.get("repositoryId"))\n                        && id.startsWith(ImportProfileDefinition.DOC_TYPE + ":")) {\n                    targets.add(doc);',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['thePlainDeleteRemovesHiddenTwinsToo'],
    ),
    dict(
        id="QC",
        what='runtime auto-resolution reads the selector again — a hidden default is not found',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        # findDefaultForRepository decides WHERE ingested content lands; back on the
        # selector, a hidden default resolves to whatever fallback is visible.
        # Re-anchored in round 3 (the listing gained a range flag).
        # Re-anchored in round 3 (the additive index read wraps the walk).
        # Re-anchored (the additive index read was withdrawn: it could not establish
        # the absence of legacy rows, which is the whole point).
        find='        List<ImportProfileDefinition> candidates = listByRepositoryIndexFree(repositoryId, false);',
        replace='        List<ImportProfileDefinition> candidates = listByRepository(repositoryId);',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['theAutoResolverSeesAHiddenDefault', 'theAutoResolverRefusesAnUnreadableRow'],
    ),
    dict(
        id="QD",
        what="existsIndexFree ignores the repository — a hidden row in B turns A's 404 into 503",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        # The gate's repository confinement dropped: an existence disclosure across
        # repositories, through the status code.
        find='            return countProfileRowsIndexFree(client.getClient(), client.getDatabaseName(),\n                    profileId, repositoryId) > 0;',
        replace='            return countProfileRowsIndexFree(client.getClient(), client.getDatabaseName(),\n                    profileId, null) > 0;',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['existsIndexFreeSeesHiddenRowsAndRefusesUnreadableOnes'],
    ),
    dict(
        id="QE",
        what='the docId resolver admits delegated callers again',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionController.java',
        # The admin gate removed: the delegated checks ran on the selector's twin, so a
        # delegated user managing twin A deletes an admin-managed twin B.
        find_span=('            if (!admin) {\n                // Audited like every other denial on this controller.',
                   '                return errorResponse(HttpStatus.FORBIDDEN,\n                        "resolving divergent definition rows is an administrator operation");\n            }'),
        replace='',
        test='ImportProfileHiddenIsNotAbsentTest',
        expect_fail=['theResolverIsAdminOnly'],
    ),
    dict(
        id="QF",
        what='a profile row with no profileId reaches the uniqueness comparison again — a 500',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        # The identity guard removed: the deserialised row has a null profileId and the
        # comparison dereferences it.
        # Re-anchored in round 16 of the second batch (the identity is read by the mapper).
        find='                ImportProfileDefinition idOnly = readAlone(props, "profileId");\n                if (idOnly == null || idOnly.getProfileId() == null\n                        || idOnly.getProfileId().isBlank()) {\n                    // Deserialisable, but with no identity: the uniqueness comparison\n                    // dereferenced it and answered 500. A profile row without a profileId\n                    // is a row this rule cannot reason about.\n                    throw new IllegalStateException("the profiles of repository \'"\n                            + repositoryId + "\' cannot be listed: row " + id\n                            + " has no usable profileId");\n                }\n',
        replace='',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aRowWithoutAProfileIdRefusesTheListing'],
    ),
    dict(
        id="QH",
        what='the profile plain DELETE lets the retryable refusal escape as a 500',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionController.java',
        # Twin of QG.
        find_span=('        int elsewhere;\n        try {\n            elsewhere = importProfileDefinitionService.delete(profileId, authRepository(ctx));',
                   '            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, partly.getMessage());\n        }'),
        replace='        int elsewhere = importProfileDefinitionService.delete(profileId, authRepository(ctx));',
        test='ImportProfileHiddenIsNotAbsentTest',
        expect_fail=['thePlainDeleteCarriesTheRepositoryAndRefusesRetryably'],
    ),
    dict(
        id="QI",
        what="the uniqueness listing's unreadable-row refusal loses its create/update typing",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        # An update whose uniqueness listing could not read a row escapes as
        # IllegalStateException — a 500 — instead of the retryable type.
        find='        } catch (IllegalStateException unprovable) {\n            if (creating) {\n                throw unprovable;\n            }\n            throw new ProfileIndexNotReadyException(unprovable.getMessage());\n        }\n        return results;',
        replace='        } catch (IllegalStateException unprovable) {\n            throw unprovable;\n        }\n        return results;',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['anUnreadableRowRefusesTheUniquenessListing'],
    ),
    dict(
        id="QK",
        what='the ingest entry point lets the retryable profile refusal escape unmapped',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        # The catch removed: an unreadable profile row during auto-resolution surfaces as
        # an unexplained failure one layer up instead of a retryable refusal.
        find_span=('            } catch (ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException e) {\n                // A profile row could not be read while resolving WHERE this import lands.',
                   '                        + " temporarily unavailable, retry shortly: " + e.getMessage());\n            } catch (IllegalStateException e) {'),
        replace='            } catch (IllegalStateException e) {',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['theIngestEntryPointMapsTheRetryableRefusal'],
    ),
    dict(
        id="QL",
        what='IMAP IDLE is stopped BEFORE a delete that can still refuse',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionController.java',
        # The ordering restored to the defect: a refused (503) delete leaves live mail
        # capture stopped for a profile that still exists.
        find_span=('        int elsewhere;\n        try {\n            elsewhere = importProfileDefinitionService.delete(profileId, authRepository(ctx));\n        } catch (ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException e) {',
                   '            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());'),
        replace='        if (ingestSchedulerService != null) ingestSchedulerService.stopIdle(profileId);\n        int elsewhere;\n        try {\n            elsewhere = importProfileDefinitionService.delete(profileId, authRepository(ctx));\n        } catch (ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException e) {\n            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());',
        test='ImportProfileHiddenIsNotAbsentTest',
        expect_fail=['aRefusedDeleteLeavesImapIdleRunning'],
    ),
    dict(
        id="QP",
        what='an update over a VISIBLE twin pair writes to the first row — a winner chosen silently',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        # The twin arm compares against what the selector showed again, so a pair the
        # healthy index shows in full is not a pair.
        find='        if (rowsDefiningThisProfile > 1) {',
        replace='        if (rowsDefiningThisProfile > existing.size()) {',
        test='ImportProfileLegacyIdMigrationTest',
        # Two more locks were measured firing under this same sabotage and were not
        # declared; the runner scores an incomplete expect_fail as a gap. Added from
        # that measurement, not from reading.
        expect_fail=['anUpdateWithTwoVisibleTwinsDoesNotWrite',
                     'aCreateRefusesWhenTheScanFindsALegacyRow',
                     'anUpdateOverAnInvisibleLegacyRowRefusesRetryably'],
    ),
    dict(
        id="QQ",
        what='the connector twin of QP',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find='        if (rowsDefiningThisConnector > 1) {',
        replace='        if (rowsDefiningThisConnector > existing.size()) {',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['anUpdateWithTwoVisibleTwinsDoesNotWrite'],
    ),
    dict(
        id="QR",
        what='the standing-twin refusal escapes the profile PUT unmapped — a 500, not a 409',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionController.java',
        find='        } catch (ImportProfileDefinitionServiceImpl.ProfileHasTwinRowsException e) {\n            // Standing, not transient: two rows define this profile and an update would\n            // choose between them. 409 — a retry does not resolve it, an administrator does.\n            audit(AuditOperation.EXTERNAL_PROFILE_UPDATED, ctx, def, false, e.getMessage());\n            return errorResponse(HttpStatus.CONFLICT, e.getMessage());\n        }',
        replace='        }',
        test='ImportProfileHiddenIsNotAbsentTest',
        expect_fail=['aStandingTwinPairIsA409OnPut'],
    ),
    dict(
        id="QS",
        what='the connector twin of QR',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionController.java',
        find='        } catch (ConnectorDefinitionServiceImpl.ConnectorHasTwinRowsException e) {\n            // Standing, not transient: two rows define this connector and an update would\n            // choose between them. 409 — a retry does not resolve it, an administrator does.\n            return errorResponse(HttpStatus.CONFLICT, e.getMessage());\n        }',
        replace='        }',
        test='ConnectorDefinitionControllerPartialPutTest',
        expect_fail=['aStandingTwinPairIsA409OnPut'],
    ),
    dict(
        id="QT",
        what='the default-profile patch trusts the selector alone: a WARN on every rebuilding startup for a profile that exists',
        file='core/src/main/java/jp/aegif/nemaki/patch/Patch_DefaultCloudDriveConnectorProfile.java',
        find='            if (!profileService.exists(profileId)\n                    && !profileService.existsIndexFree(profileId, repositoryId)) {',
        replace='            if (!profileService.exists(profileId)) {',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['theDefaultProfilePatchDoesNotTrustTheSelectorAlone'],
    ),
    dict(
        id="QU",
        what='a CREATE adopts a row the index-free scan found — two concurrent creates, one configuration overwritten (profile)',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find_span=('        if (creating && rowsDefiningThisProfile > 0) {',
                   '                    + " create never overwrites one)");\n        }'),
        replace='',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aCreateNeverAdoptsARowTheScanFound'],
    ),
    dict(
        id="QV",
        what='a CREATE adopts a row the index-free scan found — two concurrent creates, one configuration overwritten (connector)',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find_span=('        if (creating && rowsDefiningThisConnector > 0) {',
                   '                    + " create never overwrites one)");\n        }'),
        replace='',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['aCreateNeverAdoptsARowTheScanFound'],
    ),
    dict(
        id="QW",
        what='the write lands on a row the index-free scan says is not there (profile)',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find_span=('        if (rowsDefiningThisProfile < existing.size()) {',
                   '                    + " which row to write to is not established. Retry shortly.");\n        }'),
        replace='',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['theSelectorMustNotOutReportTheWalk', 'aCreateWhoseSelectorOutReportsTheWalkRefuses'],
    ),
    dict(
        id="QX",
        what='the write lands on a row the index-free scan says is not there (connector)',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find_span=('        if (rowsDefiningThisConnector < existing.size()) {',
                   '                    + " which row to write to is not established. Retry shortly.");\n        }'),
        replace='',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['theSelectorMustNotOutReportTheWalk', 'aCreateWhoseSelectorOutReportsTheWalkRefuses'],
    ),
    dict(
        id="QY",
        what='a failing IDLE shutdown escapes AFTER the profile was deleted — the audit is lost and the caller reads 500',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionController.java',
        find_span=('        stopSchedulerIfThisRepositoryLosesIt(profileId, authRepository(ctx), elsewhere);\n        audit(AuditOperation.EXTERNAL_PROFILE_DELETED, ctx, existing, true, null);',
                   '        audit(AuditOperation.EXTERNAL_PROFILE_DELETED, ctx, existing, true, null);'),
        replace='        if (ingestSchedulerService != null) ingestSchedulerService.stopIdle(profileId);\n        audit(AuditOperation.EXTERNAL_PROFILE_DELETED, ctx, existing, true, null);',
        test='ImportProfileHiddenIsNotAbsentTest',
        expect_fail=['aFailingStopIdleDoesNotLoseTheAudit'],
    ),
    dict(
        id="RC",
        what='get() answers absence when the selector misses a row that is under its deterministic id (profile)',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='        if (!results.isEmpty()) {\n            return results.get(0);\n        }',
        replace='        if (true) return results.isEmpty() ? null : results.get(0);',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aDeterministicRowTheIndexCannotShowIsStillFoundByGet'],
    ),
    dict(
        id="RD",
        what='the connector twin of RC',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        # Re-anchored in round 8 of the second batch (the block gained the visible-pair
        # refusal). The sabotage is what it always was: answer from the selector alone and
        # never fall back to the deterministic id. An early return keeps the block below
        # it compiling (javac does not flag statements after `if (true) return`).
        find='        if (!results.isEmpty()) {\n            ConnectorDefinition first = results.get(0);',
        replace='        if (true) return results.isEmpty() ? null : results.get(0);\n        if (!results.isEmpty()) {\n            ConnectorDefinition first = results.get(0);',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['aDeterministicRowTheIndexCannotShowIsStillFoundByGet'],
    ),
    dict(
        id="RE",
        what="execute() calls a profile the index cannot show 'not found' — the index-free split is gone",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find_span=('            try {\n                if (importProfileDefinitionService.existsIndexFree(request.getProfileId(),',
                   '                        + " shortly: " + e.getMessage());\n            }'),
        replace='',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['theIngestPathDoesNotReportAResolvedProfileAsMissing'],
    ),
    dict(
        id="RF",
        what='a pair of legacy rows is migrated after all — enumeration order picks which configuration becomes canonical (profile)',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='            if (rows.size() > 1) {',
        replace='            if (rows.size() > 2) {',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['twoLegacyRowsForOneIdAreBothLeftStanding'],
    ),
    dict(
        id="RG",
        what='the connector twin of RF',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find='            if (rows.size() > 1) {',
        replace='            if (rows.size() > 2) {',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['twoLegacyRowsForOneIdAreBothLeftStanding'],
    ),
    dict(
        id="RH",
        what='a delete that failed part-way escapes the profile controller — a 500 with no audit of what was already removed',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionController.java',
        find_span=('        } catch (ImportProfileDefinitionServiceImpl.ProfilePartiallyDeletedException partly) {\n            // Rows ARE gone and at least one is not',
                   '            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, partly.getMessage());\n        }'),
        replace='        }',
        test='ImportProfileHiddenIsNotAbsentTest',
        expect_fail=['aPartlyFailedDeleteIsAuditedAndRetryable'],
    ),
    dict(
        id="RI",
        what='the connector twin of RH',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionController.java',
        find_span=('        } catch (ConnectorDefinitionServiceImpl.ConnectorPartiallyDeletedException partly) {',
                   '            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, partly.getMessage());\n        }'),
        replace='        }',
        test='ConnectorDefinitionControllerPartialPutTest',
        expect_fail=['aPartlyFailedDeleteIsRetryable'],
    ),
    dict(
        id="RJ",
        what='the connector POST leaves the retryable refusal unmapped — 500 on create where PUT answers 503',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionController.java',
        find_span=('        } catch (ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException e) {\n            // The CREATE arm of the same refusal the PUT maps.',
                   '            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());\n        } catch (IllegalArgumentException | IllegalStateException e) {'),
        replace='        } catch (IllegalArgumentException | IllegalStateException e) {',
        test='ConnectorDefinitionControllerPartialPutTest',
        expect_fail=['aRetryableRefusalOnCreateIsA503'],
    ),
    dict(
        id="RK",
        what='connector GET answers 404 for a connector the rebuilding index cannot show',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionController.java',
        find_span=('        if (def == null) {\n            // get() is the Mango selector.',
                   '            return ResponseEntity.notFound().build();\n        }'),
        replace='        if (def == null) return ResponseEntity.notFound().build();',
        test='ConnectorDefinitionControllerPartialPutTest',
        expect_fail=['aHiddenConnectorIsA503OnGet'],
    ),
    dict(
        id="RL",
        what='profile GET answers 404 for a profile only an index-free read can find',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionController.java',
        find='        ImportProfileDefinition[] mine = new ImportProfileDefinition[1];\n        ResponseEntity<Map<String, Object>> refused = resolveMine(ctx, profileId, def, r -> mine[0] = r);\n        if (refused != null) return refused;\n        def = mine[0];',
        replace='',
        test='ImportProfileHiddenIsNotAbsentTest',
        expect_fail=['aHiddenProfileIsA503OnGet'],
    ),
    dict(
        id="RM",
        what="the connector existsIndexFree drops its refusal type — the controller's 503 branch becomes dead code and an unreadable row is a 500",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find_span=("            // The interface promises this type, and the controller's 503 branch is written",
                   '            throw new ConnectorIndexNotReadyException(unprovable.getMessage());\n        }'),
        replace='            return false;\n        }',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['existsIndexFreeSeesHiddenRowsAndRefusesUnreadableOnes'],
    ),
    dict(
        id="RN",
        what='a delete that removed some rows and then failed is reported as an ordinary failure (profile)',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find_span=('        int removed = 0;\n        for (com.ibm.cloud.cloudant.v1.model.Document doc : targets) {\n            try {',
                   '                        rowFailed.getMessage()), rowFailed);\n            }\n        }'),
        replace='        for (com.ibm.cloud.cloudant.v1.model.Document doc : targets) {\n            cloudant.deleteDocument(new com.ibm.cloud.cloudant.v1.model.DeleteDocumentOptions.Builder()\n                    .db(dbName).docId(doc.getId()).rev(doc.getRev()).build()).execute();\n        }',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aPartlyFailedDeleteSaysHowMuchWasRemoved'],
    ),
    dict(
        id="RO",
        what='a delete that removed some rows and then failed is reported as an ordinary failure (connector)',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find_span=('        int removed = 0;\n        for (com.ibm.cloud.cloudant.v1.model.Document doc : targets) {\n            try {',
                   '                        rowFailed);\n            }\n        }'),
        replace='        for (com.ibm.cloud.cloudant.v1.model.Document doc : targets) {\n            cloudant.deleteDocument(new com.ibm.cloud.cloudant.v1.model.DeleteDocumentOptions.Builder()\n                    .db(dbName).docId(doc.getId()).rev(doc.getRev()).build()).execute();\n        }',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['aPartlyFailedDeleteSaysHowMuchWasRemoved'],
    ),
    dict(
        id="RP",
        what='a delete the store refused OUTRIGHT is reported as partly deleted — the caller retries an operation that can never succeed',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find_span=('                if (removed == 0) {',
                   '                    throw rowFailed;\n                }'),
        replace='',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aDeleteThatRemovedNothingIsNotPartial'],
    ),
    dict(
        id="RQ",
        what='a delete the store refused OUTRIGHT is reported as partly deleted — the caller retries an operation that can never succeed',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find_span=('                if (removed == 0) {',
                   '                    throw rowFailed;\n                }'),
        replace='',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['aDeleteThatRemovedNothingIsNotPartial'],
    ),
    dict(
        id="RR",
        what="connector auto-resolution reads the selector again — a connector the rebuilding index hides is 'no enabled connector found'",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find_span=('        List<ConnectorDefinition> all = listIndexFree(sourceSystems, archetype);',
                   '        for (String system : sourceSystems) {'),
        replace='        List<ConnectorDefinition> all = new ArrayList<>();\n        for (String s : sourceSystems) {\n            all.addAll(findBySelector(Map.of("type", ConnectorDefinition.DOC_TYPE,\n                    "sourceSystem", s, "sourceArchetype", archetype.name(), "enabled", true)));\n        }\n        for (String system : sourceSystems) {',
        test='ConnectorLegacyIdMigrationTest',
        # Completed from a MEASURED run (the runner now prints the locks that failed
        # undeclared); the derivation by hand had missed these.
        expect_fail=['theConnectorResolverSeesAHiddenConnector',
                     'aDisabledByNullRowTheResolverCannotReadDoesNotRefuseTheResolution',
                     'aMatchingRowWhoseEnabledIsAStoredNumberStillRefusesTheResolution',
                     'aMatchingUnreadableConnectorStillRefuses',
                     'anUnrelatedUnreadableConnectorDoesNotStopTheResolution',
                     'theConnectorResolverRefusesAnAmbiguousMatch',
                     'theConnectorResolverRefusesAnUnreadableRow'],
    ),
    dict(
        id="RS",
        what='several matching connectors are resolved to whichever row came first — storage order picks the target',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find_span=('            if (matches.size() > 1) {\n                throw new IllegalStateException("Ambiguous auto-resolve: " + matches.size()',
                   '                        + ") — name the connector in the request, or disable all but one");\n            }'),
        replace='',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['theConnectorResolverRefusesAnAmbiguousMatch'],
    ),
    dict(
        id="RT",
        what="the connector walk's refusal type is dropped — an unreadable row answers 'no such connector'",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find_span=('        } catch (IllegalStateException unprovable) {\n            throw new ConnectorIndexNotReadyException(unprovable.getMessage());\n        }\n        return results;',
                   '        return results;'),
        replace='        } catch (IllegalStateException unprovable) {\n            throw unprovable;\n        }\n        return results;',
        test='ConnectorLegacyIdMigrationTest',
        # Completed from a MEASURED run (the runner now prints the locks that failed
        # undeclared); the derivation by hand had missed these.
        expect_fail=['theConnectorResolverRefusesAnUnreadableRow',
                     'aMatchingRowWhoseEnabledIsAStoredNumberStillRefusesTheResolution',
                     'aMatchingUnreadableConnectorStillRefuses'],
    ),
    dict(
        id="RW",
        what='the ingest entry point lets the connector refusals escape unmapped',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find_span=('            try {\n                // ONE index-free walk for every alias key',
                   '                return ExternalIngestResult.error(requestId, e.getMessage());\n            }'),
        replace='            autoConnector = connectorDefinitionService.findBySystemsAndArchetype(\n                    keysTried, archetype);',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['theIngestEntryPointMapsTheConnectorRefusals'],
    ),
    dict(
        id="RX",
        what="execute() calls a connector the index cannot show 'not found'",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find_span=('            try {\n                if (connectorDefinitionService.existsIndexFree(request.getConnectorId())) {',
                   '                        + " shortly: " + e.getMessage());\n            }'),
        replace='',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['theIngestEntryPointMapsTheConnectorRefusals'],
    ),
    dict(
        id="RY",
        what='the alias keys are resolved one walk per key again — a full walk of the config database for every alias',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find='                autoConnector = connectorDefinitionService.findBySystemsAndArchetype(\n                        keysTried, archetype);',
        replace='                for (String key : keysTried) {\n                    autoConnector = connectorDefinitionService.findBySystemAndArchetype(key, archetype);\n                    if (autoConnector != null) {\n                        break;\n                    }\n                }',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['theIngestEntryPointMapsTheConnectorRefusals'],
    ),
    dict(
        id="RZ",
        what="the non-admin ingest gate answers 'profile not found' from the index alone again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ExternalIngestController.java',
        find_span=('            Denial hidden = profileHiddenOrAbsent(profileId, repositoryId);',
                   '            if (hidden != null) return hidden;'),
        replace='',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['everyIngestEntryPointAsksIndexFreeBeforeSayingNotFound'],
    ),
    dict(
        id="SA",
        what="the DLQ retry answers 'connector not found' from the index alone again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestDlqController.java',
        find_span=('            try {\n                if (connectorDefinitionService.existsIndexFree(request.getConnectorId())) {',
                   '                        + " retry shortly: " + e.getMessage());\n            }'),
        replace='',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['everyIngestEntryPointAsksIndexFreeBeforeSayingNotFound'],
    ),
    dict(
        id="SB",
        what='the row-addressed delete stops recording which row it destroyed',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionController.java',
        find_span=('            auditRow(AuditOperation.EXTERNAL_PROFILE_DELETED, ctx, profileId,\n                    authRepository(ctx), true, "one row of a divergent pair was removed",',
                   '                    null, docId);'),
        replace='',
        test='ImportProfileHiddenIsNotAbsentTest',
        expect_fail=['theRowAddressedDeleteIsAudited'],
    ),
    dict(
        id="SC",
        what='the row-addressed resolver removes the ONLY definition row — the definition is deleted through a path that skips the scheduler stop and the deletion event',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find_span=('        int rows;\n        try {',
                   '                    + " remove the profile.");\n        }'),
        replace='',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['theRowResolverRefusesTheOnlyRow'],
    ),
    dict(
        id="SD",
        what='the row-addressed resolver removes the ONLY definition row — the definition is deleted through a path that skips the scheduler stop and the deletion event',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find_span=('        int rows;\n        try {',
                   '                    + " pair. Use DELETE without docId to remove the connector.");\n        }'),
        replace='',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['theRowResolverRefusesTheOnlyRow'],
    ),
    dict(
        id="SE",
        what='a row delete that removed the LAST row is reported as an ordinary pair resolution — the profile is gone with its scheduler still running and no deletion record',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionController.java',
        find_span=('            if (remaining == 0) {',
                   '                return ResponseEntity.ok(raced);\n            }'),
        replace='',
        test='ImportProfileHiddenIsNotAbsentTest',
        expect_fail=['aRowDeleteThatLostTheRaceStopsTheSchedulerAndRecordsTheDeletion'],
    ),
    dict(
        id="SF",
        what="the row-addressed path lets the count's refusal escape as an unclassified 500 with no audit",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionController.java',
        find_span=('            } catch (ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException e) {\n                // The count that decides "is this a pair?"',
                   '                return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());'),
        replace='',
        test='ImportProfileHiddenIsNotAbsentTest',
        expect_fail=['aRowDeleteWhoseCountCannotReadIsA503'],
    ),
    dict(
        id="SG",
        what='the row delete stops reporting how many rows remain — a lost race is silent again',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find_span=('        try {\n            // ACROSS ALL REPOSITORIES, not just this one.',
                   '            return -1;\n        }'),
        replace='        return 1;',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['theRowDeleteReportsTheSurvivors'],
    ),
    dict(
        id="SH",
        what='the connector row delete stops reporting how many rows remain — a lost race is silent again',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find_span=('        try {\n            return countConnectorRowsIndexFree(cloudant, dbName, connectorId);',
                   '            return -1;\n        }'),
        replace='        return 1;',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['theRowDeleteReportsTheSurvivors'],
    ),
    dict(
        id="SI",
        what="a shared profileId locks a repository out of its own rows on DELETE — the selector's arbitrary twin decides",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionController.java',
        find_span=('        ImportProfileDefinition[] mineDel = new ImportProfileDefinition[1];',
                   '        if (existing == null) return errorResponse(HttpStatus.NOT_FOUND, "Profile not found");'),
        replace='        ImportProfileDefinition existing = importProfileDefinitionService.get(profileId);\n        if (existing == null || !belongsToAuthRepository(ctx, existing)) return errorResponse(HttpStatus.NOT_FOUND, "Profile not found");',
        test='ImportProfileHiddenIsNotAbsentTest',
        expect_fail=['aSharedProfileIdDoesNotLockThisRepositoryOut'],
    ),
    dict(
        id="SJ",
        what='every connector row is deserialised before filtering — one unrelated row a newer node wrote stops all auto-resolution',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find_span=('            if (onlySystems != null) {\n                Object system = props.get("sourceSystem");',
                   '            if (onlyArchetype != null && !onlyArchetype.name().equals(props.get("sourceArchetype"))) {\n                return;\n            }'),
        replace='',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['anUnrelatedUnreadableConnectorDoesNotStopTheResolution'],
    ),
    dict(
        id="SK",
        what='an unknown survivor count is reported as an ordinary success (profile) — and the scheduler decision is made on a guess',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionController.java',
        find_span=('            if (remaining < 0) {',
                   '                return ResponseEntity.ok(unknown);\n            }'),
        replace='',
        test='ImportProfileHiddenIsNotAbsentTest',
        expect_fail=['aRowDeleteWithAnUnknownSurvivorCountSaysSo'],
    ),
    dict(
        id="SL",
        what="the connector caller is told 'a duplicate was tidied' when the connector is gone, or when the count could not answer",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionController.java',
        find_span=('                if (remaining == 0) {',
                   '                    return ResponseEntity.ok(unknown);\n                }'),
        replace='',
        test='ConnectorDefinitionControllerPartialPutTest',
        expect_fail=['aRowDeleteReportsAnUnknownOrLostSurvivorCount'],
    ),
    dict(
        id="SM",
        what='a DISABLED row is deserialised before it is filtered out — one unreadable disabled row refuses every import',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        # Re-anchored in round 16 of the second batch (the mapper reads the field).
        find_span=('            if (onlySystems != null && readsDisabled(props)) {',
                   '                // skipped: an absent value is not a "no".\n                return;\n            }'),
        replace='',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['anUnrelatedUnreadableConnectorDoesNotStopTheResolution',
                     'aDisabledByNullRowTheResolverCannotReadDoesNotRefuseTheResolution'],
    ),
    dict(
        id="SN",
        what='the pre-filter compares against a List.of(...) with a possibly-null value — one row without a sourceSystem takes the resolution down with an NPE',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find_span=('            if (onlySystems != null) {\n                Object system = props.get("sourceSystem");',
                   '                if (system == null || !onlySystems.contains(system)) {\n                    return;\n                }\n            }'),
        replace='            if (onlySystems != null && !onlySystems.contains(props.get("sourceSystem"))) {\n                return;\n            }',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['anUnrelatedUnreadableConnectorDoesNotStopTheResolution'],
    ),
    dict(
        id="SO",
        what="the scheduler is stopped after a repository-confined delete — another repository's live capture is cut",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionController.java',
        find='            if (leftAnywhere == 0) {',
        replace='            if (leftAnywhere >= 0 || leftAnywhere < 0) {',
        test='ImportProfileHiddenIsNotAbsentTest',
        expect_fail=['aConfinedDeleteLeavesAnotherRepositorysSchedulerAlone',
                     'aSessionServingAnotherRepositoryIsLeftAlone'],
    ),
    dict(
        id="SP",
        what='the plain delete stops reporting whether any repository still has the profile',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find_span=('        try {\n            return countProfileRowsIndexFree(cloudant, dbName, profileId, null);',
                   '            return -1;\n        }\n    }'),
        replace='        return 0;\n    }',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['thePlainDeleteReportsTheRowsLeftAnywhere'],
    ),
    dict(
        id="SQ",
        what="a scan that counted ZERO rows answers 'this is the only definition row' — a claim neither read supports",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find_span=('        if (rows == 0) {',
                   '        if (rows == 1 && !unowned) {'),
        replace='        if (rows <= 1 && !unowned) {',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aCountOfZeroIsADisagreementNotTheOnlyRow'],
    ),
    dict(
        id="SR",
        what="a scan that counted ZERO rows answers 'this is the only definition row' — a claim neither read supports",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find_span=('        if (rows == 0) {',
                   '        if (rows == 1) {'),
        replace='        if (rows <= 1) {',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['aCountOfZeroIsADisagreementNotTheOnlyRow'],
    ),
    dict(
        id="SS",
        what="the row-addressed delete counts survivors in the caller's repository only — the globally keyed scheduler is stopped on a confined count",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find_span=('            return countProfileRowsIndexFree(cloudant, dbName, profileId, null);\n        } catch (RuntimeException unreadable) {\n            logger.warn("row {}',
                   '            return -1;'),
        replace='            return countProfileRowsIndexFree(cloudant, dbName, profileId, repositoryId);\n        } catch (RuntimeException unreadable) {\n            logger.warn("row {} of profile {} was deleted, but how many rows remain could not be"\n                    + " established: {}", docId, profileId, unreadable.getMessage());\n            return -1;',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['theRowDeleteReportsTheSurvivors'],
    ),
    dict(
        id="ST",
        what='the plain delete answers the same body whether the survivors were counted or could not be',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionController.java',
        find_span=('        if (elsewhere < 0) {\n            // A failed read is not an answered one',
                   '                    + " has this profile could not be established");\n        }'),
        replace='',
        test='ImportProfileHiddenIsNotAbsentTest',
        expect_fail=['aPlainDeleteWithAnUnknownCountSaysSo'],
    ),
    dict(
        id="SU",
        what='the plain delete discards the survivor count — a concurrent delete elsewhere leaves an orphaned scheduler running',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionController.java',
        find='        stopSchedulerIfThisRepositoryLosesIt(profileId, authRepository(ctx), elsewhere);',
        replace='',
        test='ImportProfileHiddenIsNotAbsentTest',
        expect_fail=['aSharedProfileIdBranchStopsTheSchedulerWhenNothingRemains'],
    ),
    dict(
        id="SV",
        what="a shared profileId hides this repository's own row from GET and PUT — the selector's arbitrary twin decides",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionController.java',
        find_span=('    private ImportProfileDefinition mineInstead(CallContext ctx, ImportProfileDefinition selected,\n            String profileId) {',
                   '        return importProfileDefinitionService.getForRepository(profileId, authRepository(ctx));\n    }'),
        replace='    private ImportProfileDefinition mineInstead(CallContext ctx, ImportProfileDefinition selected,\n            String profileId) {\n        return selected != null && belongsToAuthRepository(ctx, selected) ? selected : null;\n    }',
        test='ImportProfileHiddenIsNotAbsentTest',
        expect_fail=['theReadVerbsReachThisRepositorysRow'],
    ),
    dict(
        id="SW",
        what="getForRepository reads the selector instead of walking — the caller's own row stays unreachable behind a shared profileId",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find_span=('        try {\n            NemakiConfAllDocs.forEachRow(client.getClient(), dbName, perRow);\n        } catch (IllegalStateException unprovable) {\n            throw new ProfileIndexNotReadyException(unprovable.getMessage());\n        }\n        return found[0];\n    }\n\n    @Override\n    public ImportProfileDefinition getOwnedRowIndexFree(String profileId) {',
                   '        return found[0];\n    }\n\n    @Override\n    public ImportProfileDefinition getOwnedRowIndexFree(String profileId) {'),
        replace='        for (ImportProfileDefinition candidate : listByRepository(repositoryId)) {\n            if (profileId.equals(candidate.getProfileId())) {\n                return candidate;\n            }\n        }\n        return null;\n    }\n\n    @Override\n    public ImportProfileDefinition getOwnedRowIndexFree(String profileId) {',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['getForRepositoryAnswersWithoutTheIndex'],
    ),
    dict(
        id="SX",
        what='getForRepository deserialises every profile of the repository — one unrelated broken row refuses every read',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        # Re-anchored in round 16 of the second batch (the identity check moved into
        # definesProfile).
        find_span=('            if (!definesProfile(props, profileId)\n                    || !repositoryId.equals(props.get("repositoryId"))) {',
                   '                return;\n            }'),
        replace='            if (!ImportProfileDefinition.DOC_TYPE.equals(props.get("type"))\n                    || !repositoryId.equals(props.get("repositoryId"))) {\n                return;\n            }',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['getForRepositoryAnswersWithoutTheIndex'],
    ),
    dict(
        id="SY",
        what='a post-delete count that could not answer is reported as a survivor count (connector)',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find_span=('        } catch (RuntimeException unreadable) {\n            logger.warn("row {} of connector {} was deleted, but how many rows remain could not be',
                   '            return -1;\n        }'),
        replace='        } catch (RuntimeException unreadable) {\n            return 1;\n        }',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['aPostDeleteCountThatCannotAnswerReportsMinusOne'],
    ),
    dict(
        id="SZ",
        what='getForRepository picks one of two rows instead of refusing — and the delete that follows authorises from the one it picked',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find_span=('            if (found[0] != null) {\n                // TWO rows of this profile in this repository.',
                   '                        + profileId + "?docId=...)");\n            }'),
        replace='',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['getForRepositoryRefusesAPair'],
    ),
    dict(
        id="TA",
        what='an absence established index-free is walked again — double cost, and a settled absence can turn into a 503',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionController.java',
        find_span=('        if (def == null) {\n            // Absence established index-free by the walk above — no second walk.\n            return errorResponse(HttpStatus.NOT_FOUND, "Profile not found");\n        }',
                   '        }'),
        replace='        if (def == null) {\n            try {\n                if (importProfileDefinitionService.existsIndexFree(profileId, authRepository(ctx))) {\n                    return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, "retry shortly");\n                }\n            } catch (ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException e) {\n                return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());\n            }\n            return errorResponse(HttpStatus.NOT_FOUND, "Profile not found");\n        }',
        test='ImportProfileHiddenIsNotAbsentTest',
        expect_fail=['anAbsenceIsNotRewalked'],
    ),
    dict(
        id="TB",
        what='a post-delete count that could not answer is reported as a survivor count (profile row delete)',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find_span=('            logger.warn("row {} of profile {} was deleted, but how many rows remain could not be',
                   '            return -1;'),
        replace='            return 1;',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aProfilePostDeleteCountThatCannotAnswerReportsMinusOne'],
    ),
    dict(
        id="TC",
        what='the resolution is skipped when the selector already returned a row of this repository — a PAIR is then never seen, and a delete authorised from one twin removes both',
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionController.java',
        find='        ImportProfileDefinition[] mineDel = new ImportProfileDefinition[1];\n        ResponseEntity<Map<String, Object>> refusedDel =\n                resolveMine(ctx, profileId, null, r -> mineDel[0] = r);',
        # The sabotage used to pass `existing`, a variable the dead selector read above it
        # supplied. That read is gone, so the sabotage stopped COMPILING while its anchor
        # still matched — the pre-flight passed and the sweep died here, 11 controls short.
        replace='        ImportProfileDefinition selectedDel = importProfileDefinitionService.get(profileId);\n        ImportProfileDefinition[] mineDel = new ImportProfileDefinition[1];\n        ResponseEntity<Map<String, Object>> refusedDel =\n                resolveMine(ctx, profileId, selectedDel, r -> mineDel[0] = r);',
        test='ImportProfileHiddenIsNotAbsentTest',
        expect_fail=['aPairInThisRepositoryIsA409NotAChoice'],
    ),
    dict(
        id="TD",
        what="the folder run/credential paths answer 'no runnable profile' from the selector alone — a shared profileId or a legacy-only row is a false 404",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/FolderConnectorController.java',
        find_span=('        // two paths still on the selector. (Run path.)\n        ImportProfileDefinition profile;',
                   '            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);\n        }'),
        replace='        ImportProfileDefinition profile = importProfileDefinitionService.get(profileId);',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['everyIngestEntryPointAsksIndexFreeBeforeSayingNotFound'],
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
        id="TE",
        what="a deletion stops the IDLE session only when the profileId is gone everywhere — "
             "a session serving THIS repository keeps importing into the deleted profile",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionController.java',
        find_span=('            boolean running = ingestSchedulerService.getIdleProfiles().contains(profileId);',
                   '                        servedRepository);\n            }'),
        replace='            logger.info("import profile {} still has {} definition row(s) somewhere;"\n'
                '                    + " leaving its scheduler alone", profileId,\n'
                '                    leftAnywhere < 0 ? "an unknown number of" : String.valueOf(leftAnywhere));',
        test='ImportProfileHiddenIsNotAbsentTest',
        expect_fail=['aSessionServingThisRepositoryIsStopped', 'anUnattributableSessionIsStopped'],
    ),
    dict(
        id="TF",
        what="an unattributable IDLE session is treated as another repository's and left running",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionController.java',
        find='            if (servedRepository == null || servedRepository.equals(repositoryId)) {',
        replace='            if (servedRepository != null && servedRepository.equals(repositoryId)) {',
        test='ImportProfileHiddenIsNotAbsentTest',
        expect_fail=['anUnattributableSessionIsStopped'],
    ),
    dict(
        id="TG",
        what="the delegated ingest gate resolves index-free only on a repository mismatch — the "
             "row it authorises and the row the import uses can be different twins",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ExternalIngestController.java',
        find_span=('        ImportProfileDefinition profile;\n        try {\n            profile = importProfileDefinitionService.getForRepository(profileId, repositoryId);',
                   '            profile = selected;\n        }'),
        replace='        ImportProfileDefinition profile = importProfileDefinitionService.get(profileId);',
        test='ExternalIngestControllerGateTest',
        expect_fail=['twoRowsOfOneProfileInThisRepository_isRefusedBeforeAnyImport'],
    ),
    dict(
        id="TH",
        what="a row that belongs to no repository is refused by the row-addressed delete again — "
             "unreachable, while it makes that profileId's PUT a standing 409",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        # Re-anchored in round 17 (isBlank became namesNoRepository).
        find='        boolean unowned = props != null && namesNoRepository(props.get("repositoryId"));',
        replace='        boolean unowned = false;',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['anUnownedRowIsReachable',
                     'aBlankRepositoryRowIsReachable',
                     'theOneRowDeleteReachesARowWhoseRepositoryIdIsNotAString'],
    ),
    dict(
        id="TI",
        what="the unowned-row exemption is widened to any repository — the confinement that "
             "stops a caller of one repository deleting another's row is gone",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='                || !(unowned || repositoryId.equals(props.get("repositoryId")))) {',
        replace='                || !(true || repositoryId.equals(props.get("repositoryId")))) {',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['anotherRepositorysRowIsStillRefused'],
    ),
    dict(
        id="TJ",
        what="a terminating IDLE thread retires the profileId by key — it erases the session "
             "that replaced it, which then runs invisible and unstoppable",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/mail/ImapIdleMonitor.java',
        find='        idleSessions.remove(profileId, session);\n    }',
        replace='        idleSessions.remove(profileId);\n    }',
        test='ImapIdleSessionRegistryTest',
        expect_fail=['retirementIsByIdentityNotByKey'],
    ),
    dict(
        id="TK",
        what="registration overwrites instead of refusing — two starts both pass and the live "
             "adapter of the first can no longer be reached",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/mail/ImapIdleMonitor.java',
        find='        return idleSessions.putIfAbsent(profileId, session) == null;',
        replace='        return idleSessions.put(profileId, session) == null;',
        test='ImapIdleSessionRegistryTest',
        expect_fail=['registrationIsAtomic'],
    ),
    dict(
        id="TL",
        what="startIdle stops re-asking whether the profile still exists after it registers — "
             "a delete that finished first leaves a session importing into a removed row",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/mail/ImapIdleMonitor.java',
        find_span=('        String home = profile.getRepositoryId();',
                   '                    + home + "; IDLE not started";\n        }'),
        replace='',
        test='ImapIdleSessionRegistryTest',
        expect_fail=['aStartLosesToADeleteThatFinishedFirst', 'aStartWhoseCheckCannotAnswerIsRefused'],
    ),
    dict(
        id="TM",
        what="a profile bound to NO repository is accepted as every repository's again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ExternalIngestController.java',
        find='        if (profile.getRepositoryId() == null\n                || !profile.getRepositoryId().equals(repositoryId)) {',
        replace='        if (profile.getRepositoryId() != null\n                && !profile.getRepositoryId().equals(repositoryId)) {',
        test='ExternalIngestControllerGateTest',
        expect_fail=['aProfileBoundToNoRepository_isRefused'],
    ),
    dict(
        id="TN",
        what="a retryable import refusal falls through to 500 again — the status that opens a "
             "ticket for a condition a retry resolves",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ExternalIngestController.java',
        # Re-anchored: the arm gained the unwired-authorization-service token, so the old
        # one-line start no longer matched. Narrowed to the two tokens this control is about,
        # which also keeps it from measuring TX2's token by accident.
        find='        if (firstError.contains("retry shortly") || firstError.contains("temporarily unavailable")\n',
        replace='        if (false\n',
        test='ExternalIngestControllerGateTest',
        # The second name is from a review that traced it: round 45's target-folder
        # lock asserts 503 for a "; retry shortly" message, so removing this arm
        # reddens it too. A full sweep would have reported an undeclared firing.
        expect_fail=['aRetryableImportRefusal_is503NotAServerError',
                     'aTargetFolderReadThatCouldNotAnswerIsStillA503'],
    ),
    dict(
        id="TO",
        what="a standing twin pair reached through the import answers 500 while the same state "
             "through the gate answers 409",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ExternalIngestController.java',
        find_span=('        if (firstError.contains("definition rows")\n                || firstError.contains("more than one definition row")\n                || firstError.contains("more than one owned definition row")) {',
                   '            return HttpStatus.CONFLICT;\n        }'),
        replace='',
        test='ExternalIngestControllerGateTest',
        # The second name is from the measurement. Both locks assert 409 through the arm
        # this sabotage removes, and the declaration has been short since the commit that
        # wrote all three — a full sweep would have exited non-zero on it.
        expect_fail=['aStandingTwinPairFromTheImport_is409NotAServerError',
                     'aGetForRepositoryTwinMessage_is409NotAServerError'],
    ),
    dict(
        id="TP",
        what="the profile selector read is unwrapped again — a Mango read that throws becomes a "
             "500 in front of the verbs made index-free",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find_span=('        List<ImportProfileDefinition> results;\n        try {',
                   '            results = List.of();\n        }'),
        replace='        List<ImportProfileDefinition> results = findBySelector(Map.of(\n                "type", ImportProfileDefinition.DOC_TYPE,\n                "profileId", profileId));',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aFailingSelectorDoesNotEscape'],
    ),
    dict(
        id="TQ",
        what="the twin-row 409 names ?docId= unconditionally again — the repair it prescribes "
             "is refused in the two commonest shapes of that state",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='        int here;',
        replace='        if (true) {\n            return "Delete the unwanted row first (DELETE .../admin/import-profiles/"\n                    + profileId + "?docId=...).";\n        }\n        int here;',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['theTwinRefusalNamesAReachableRepair'],
    ),
    dict(
        id="TU",
        what="startIdle publishes the thread after DELETE has already taken the session — "
             "the adapter reconnects invisible and unstoppable",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/mail/ImapIdleMonitor.java',
        find='        if (idleSessions.get(profileId) != session) {\n            // DELETE already removed us. Starting the thread would reconnect an adapter\n            // that stopIdle has already disarmed, and the session would be absent from\n            // getIdleProfiles() — the unstoppable capture a review traced.\n            return "import profile " + profileId + " was stopped before IDLE started";\n        }\n        idle.start();',
        replace='        idle.start();',
        test='ImapIdleSessionRegistryTest',
        expect_fail=['aDeleteDuringExistenceCheckDoesNotPublishAnInvisibleSession'],
    ),
    dict(
        id="TV",
        what="startIdle re-arms idleRunning after stopIdle — a DELETE that won the race "
             "leaves a live connection",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/mail/ImapConnectorAdapter.java',
        find_span=('    boolean armIdle() {',
                   '        return true;\n    }'),
        replace='    boolean armIdle() {\n        idleRunning = true;\n        return true;\n    }',
        test='ImapIdleSessionRegistryTest',
        expect_fail=['aStopBeforeStartDoesNotRearmIdle'],
    ),
    dict(
        id="TW",
        what="a mail-path repository mismatch falls through to 500 again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ExternalIngestController.java',
        # Re-anchored: the arm gained the three delegated re-check tokens, so the old
        # whole-arm find no longer matched. Narrowed to the one token this control is about.
        find='                || firstError.contains("repository mismatch")\n',
        replace='',
        test='ExternalIngestControllerGateTest',
        expect_fail=['aMailRepositoryMismatch_is403NotAServerError'],
    ),
    dict(
        id="TX",
        what="the connector selector read is unwrapped again — a Mango read that throws "
             "becomes a 500",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        # Re-anchored in round 4 of the second batch (the read tracks whether the selector
        # answered; the flag stays declared so the code below it still compiles).
        find_span=('        List<ConnectorDefinition> results;\n        boolean selectorAnswered = true;\n        try {',
                   '            results = List.of();\n            selectorAnswered = false;\n        }'),
        replace='        List<ConnectorDefinition> results = findBySelector(Map.of(\n                "type", ConnectorDefinition.DOC_TYPE,\n                "connectorId", connectorId), refuseUnanswered);\n        boolean selectorAnswered = true;',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['aFailingSelectorDoesNotEscape'],
    ),
    dict(
        id="TY",
        what="an unowned legacy row tells the operator to repair the database instead of "
             "the reachable DELETE",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='                        + " usable repositoryId is malformed and is not migrated; delete it"\n'
             '                        + " with DELETE .../admin/import-profiles/" + profileId + "?docId="\n'
             '                        + id + ")");',
        replace='                        + " usable repositoryId is malformed and is not migrated)");',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['anUnownedRowNamesTheReachableDelete'],
    ),
    dict(
        id="TZ",
        what="startIdle admits from get() again — a selector-visible owned twin is "
             "started and a hidden owned row is reported absent",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/mail/ImapIdleMonitor.java',
        find='            current = profileService.getOwnedRowIndexFree(profileId);',
        replace='            current = profileService.get(profileId);',
        test='ImapIdleSessionRegistryTest',
        expect_fail=['aHiddenOwnedRowReachesTheConfinedRecheck',
                     'anOwnedSelectorHitStillRefusesATwinPair'],
    ),
    dict(
        id="UD",
        what="execute skips getForRepository when get() already returned a same-repository "
             "row — a standing pair is chosen by selector order",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find='        // 1. Resolve profile — always walk. A selector hit on a same-repository row\n        // skipped getForRepository, so a standing pair in that repository was chosen\n        // by index order. The non-admin gate already refused the pair; this door did not.\n        ImportProfileDefinition profile;\n        try {\n            profile = resolveProfileForRepository(\n                    request.getProfileId(), request.getRepositoryId());',
        replace='        // 1. Resolve profile — selector first (the wiring this lock removes).\n        ImportProfileDefinition profile;\n        try {\n            ImportProfileDefinition selector = importProfileDefinitionService.get(request.getProfileId());\n            if (selector != null && request.getRepositoryId() != null\n                    && request.getRepositoryId().equals(selector.getRepositoryId())) {\n                profile = selector;\n            } else {\n                profile = resolveProfileForRepository(\n                        request.getProfileId(), request.getRepositoryId());\n            }',
        test='CanonicalImportServiceTest',
        expect_fail=['aSameRepositoryTwinPair_isRefusedNotChosenBySelectorOrder',
                     'aWalkMissDoesNotResurrectASelectorRow'],
    ),
    dict(
        id="UE",
        what="the import status mapper matches only 'definition rows' again — the "
             "getForRepository wording answers 500",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ExternalIngestController.java',
        find='        if (firstError.contains("definition rows")\n                || firstError.contains("more than one definition row")\n                || firstError.contains("more than one owned definition row")) {',
        replace='        if (firstError.contains("definition rows")) {',
        test='ExternalIngestControllerGateTest',
        expect_fail=['aGetForRepositoryTwinMessage_is409NotAServerError'],
    ),
    dict(
        id="UF",
        what="IDLE treats a hidden connector as absence again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/mail/ImapIdleMonitor.java',
        find_span=('            String connId = current.getDefaultConnectorId();',
                   '            return new LiveLoad(null, null, "No connector for profile: " + profileId);'),
        replace='            return new LiveLoad(null, null, "No connector for profile: " + profileId);',
        test='ImapIdleSessionRegistryTest',
        expect_fail=['aHiddenConnectorIsNotReportedAsAbsence'],
    ),
    dict(
        id="UG",
        what="per-message IDLE admission reuses the start-time repository — a later "
             "move still authorises",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/mail/ImapIdleMonitor.java',
        find='        if (startedRepositoryId != null\n                && !startedRepositoryId.equals(current.getRepositoryId())) {\n            return new LiveLoad(null, null, "import profile " + profileId\n                    + " now belongs to a different repository; IDLE stopping");\n        }',
        replace='        if (false) {\n            return new LiveLoad(null, null, "import profile " + profileId\n                    + " now belongs to a different repository; IDLE stopping");\n        }',
        test='ImapIdleSessionRegistryTest',
        expect_fail=['aLaterRepositoryChangeStopsLiveAdmission'],
    ),
    dict(
        id="UH",
        what="execute ignores connector twin count and runs the selector's first row",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find='            // get() returns the selector\'s first row. A pair was run by index order; a\n            // walk miss (count 0) was still imported from that leftover. The profile\n            // door already refuses the same disagreement.\n            try {\n                int seen = connectorDefinitionService.countIndexFree(request.getConnectorId());\n                if (seen > 1) {',
        replace='            // get() returns the selector\'s first row. A pair was run by index order; a\n            // walk miss (count 0) was still imported from that leftover. The profile\n            // door already refuses the same disagreement.\n            try {\n                int seen = connectorDefinitionService.countIndexFree(request.getConnectorId());\n                if (false) {',
        test='CanonicalImportServiceTest',
        expect_fail=['aConnectorTwinPair_isRefusedNotChosenBySelectorOrder'],
    ),
    dict(
        id="UI",
        what="per-message IDLE admission ignores a later delegation revoke and keeps "
             "ingesting under the admin path",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/mail/ImapIdleMonitor.java',
        find='        if (Boolean.TRUE.equals(startedDelegated) && !current.isDelegated()) {\n            return new LiveLoad(null, null, "import profile " + profileId\n                    + " is no longer delegated; IDLE stopping");\n        }',
        replace='        if (false) {\n            return new LiveLoad(null, null, "import profile " + profileId\n                    + " is no longer delegated; IDLE stopping");\n        }',
        test='ImapIdleSessionRegistryTest',
        expect_fail=['aLaterDelegationRevokeStopsLiveAdmission'],
    ),
    dict(
        id="UJ",
        what="per-message IDLE admission ignores a later endpoint or secret change and "
             "keeps fetching through the start-time socket",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/mail/ImapIdleMonitor.java',
        # Re-anchored: the password read is now the refusing one, wrapped in a try. Narrowed to
        # the comparison itself, which is what this control is about.
        find='            if (!startedConnectionIdentity.equals(connectionIdentity(conn, livePassword))) {',
        replace='            if (false) {',
        test='ImapIdleSessionRegistryTest',
        # Completed from MEASUREMENT, not from reading. These locks were added to the
        # same test class AFTER this control was written, and a full sweep would have
        # exited non-zero listing them. A review enumerated the whole suite for this
        # shape rather than one round at a time.
        expect_fail=['aLaterConnectorEndpointChangeStopsLiveAdmission',
                     'aNewlineCrossingIdentityChangeStopsLiveAdmission'],
    ),
    dict(
        id="UK",
        what="execute imports a selector leftover when the walk counts zero rows",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find='            // get() returns the selector\'s first row. A pair was run by index order; a\n            // walk miss (count 0) was still imported from that leftover. The profile\n            // door already refuses the same disagreement.\n            try {\n                int seen = connectorDefinitionService.countIndexFree(request.getConnectorId());\n                if (seen > 1) {\n                    return ExternalIngestResult.error(requestId, "connector "\n                            + request.getConnectorId()\n                            + " has more than one definition row");\n                }\n                if (seen < 1) {',
        replace='            // get() returns the selector\'s first row. A pair was run by index order; a\n            // walk miss (count 0) was still imported from that leftover. The profile\n            // door already refuses the same disagreement.\n            try {\n                int seen = connectorDefinitionService.countIndexFree(request.getConnectorId());\n                if (seen > 1) {\n                    return ExternalIngestResult.error(requestId, "connector "\n                            + request.getConnectorId()\n                            + " has more than one definition row");\n                }\n                if (false) {',
        test='CanonicalImportServiceTest',
        expect_fail=['aConnectorSelectorHitWithWalkMiss_isRetryNotTheSelectorRow'],
    ),
    dict(
        id="UL",
        what="startIdle admits a disabled profile again — disable is checked only after start",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/mail/ImapIdleMonitor.java',
        find='        if (!current.isEnabled()) {\n            return new LiveLoad(null, null, "Import profile is disabled: " + profileId);\n        }',
        replace='        if (startedRepositoryId != null && !current.isEnabled()) {\n            return new LiveLoad(null, null, "Import profile is disabled: " + profileId);\n        }',
        test='ImapIdleSessionRegistryTest',
        expect_fail=['aDisabledProfileIsRefusedAtIdleStart'],
    ),
    dict(
        id="UM",
        what="IDLE never looks at connector.isEnabled — a later disable keeps fetching",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/mail/ImapIdleMonitor.java',
        find='        if (!conn.isEnabled()) {\n            return new LiveLoad(null, null, "Connector is disabled: " + conn.getConnectorId());\n        }',
        replace='        if (false) {\n            return new LiveLoad(null, null, "Connector is disabled: " + conn.getConnectorId());\n        }',
        test='ImapIdleSessionRegistryTest',
        expect_fail=['aLaterConnectorDisableStopsLiveAdmission'],
    ),
    dict(
        id="UN",
        what="IDLE starts a selector leftover connector when the walk counts zero rows",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/mail/ImapIdleMonitor.java',
        find='                if (seen < 1) {\n                    return new LiveLoad(null, null, "connector " + countedId\n                            + " exists but could not be read; retry shortly");\n                }',
        replace='                if (false) {\n                    return new LiveLoad(null, null, "connector " + countedId\n                            + " exists but could not be read; retry shortly");\n                }',
        test='ImapIdleSessionRegistryTest',
        expect_fail=['aConnectorSelectorHitWithWalkMissIsRetry'],
    ),
    dict(
        id="UO",
        what="the _all_docs walk lets a transport RuntimeException escape — callers "
             "wrap only IllegalStateException, so the import answers 500",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/NemakiConfAllDocs.java',
        find='            com.ibm.cloud.cloudant.v1.model.AllDocsResult listing;\n            try {\n                listing = cloudant.postAllDocs(page.build()).execute().getResult();\n            } catch (RuntimeException transport) {\n                // The walk used to let a transport failure out as-is. Callers wrap\n                // IllegalStateException into a typed 503 and let everything else become\n                // a 500. A reset mid-page is "could not ask", the same as a listing\n                // that did not answer.\n                throw new IllegalStateException("the _all_docs listing of \'" + dbName\n                        + "\' could not be read, so whether the rows are there cannot be"\n                        + " established; retry shortly: " + transport.getMessage(),\n                        transport);\n            }',
        replace='            com.ibm.cloud.cloudant.v1.model.AllDocsResult listing =\n                    cloudant.postAllDocs(page.build()).execute().getResult();',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aTransportFailureOnTheWalkIsTypedNotReady'],
    ),
    dict(
        id="UP",
        what="the connector walk lets the same transport RuntimeException escape",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/NemakiConfAllDocs.java',
        find='            com.ibm.cloud.cloudant.v1.model.AllDocsResult listing;\n            try {\n                listing = cloudant.postAllDocs(page.build()).execute().getResult();\n            } catch (RuntimeException transport) {\n                // The walk used to let a transport failure out as-is. Callers wrap\n                // IllegalStateException into a typed 503 and let everything else become\n                // a 500. A reset mid-page is "could not ask", the same as a listing\n                // that did not answer.\n                throw new IllegalStateException("the _all_docs listing of \'" + dbName\n                        + "\' could not be read, so whether the rows are there cannot be"\n                        + " established; retry shortly: " + transport.getMessage(),\n                        transport);\n            }',
        replace='            com.ibm.cloud.cloudant.v1.model.AllDocsResult listing =\n                    cloudant.postAllDocs(page.build()).execute().getResult();',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['aTransportFailureOnTheWalkIsTypedNotReady'],
    ),
    dict(
        id="UQ",
        what="connectionIdentity joins the five fields with newlines, so a tenantId/authType "
             "crossing pair is treated as the same IMAP socket",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/mail/ImapIdleMonitor.java',
        find='        return new ConnectionIdentity(\n                connector == null ? null : connector.getEndpoint(),\n                connector == null ? null : connector.getTenantId(),\n                connector == null ? null : connector.getAuthType(),\n                connector == null ? null : connector.getCredentialRef(),\n                password);',
        replace='        return new ConnectionIdentity(String.join("\\n",\n                connector == null || connector.getEndpoint() == null ? "" : connector.getEndpoint(),\n                connector == null || connector.getTenantId() == null ? "" : connector.getTenantId(),\n                connector == null || connector.getAuthType() == null ? "" : connector.getAuthType(),\n                connector == null || connector.getCredentialRef() == null ? "" : connector.getCredentialRef(),\n                password == null ? "" : password), "", "", "", "");',
        test='ImapIdleSessionRegistryTest',
        expect_fail=['aNewlineCrossingTenantAndAuthTypeIsNotTheSameConnection',
                     'aNewlineCrossingIdentityChangeStopsLiveAdmission'],
    ),
    dict(
        id="UR",
        what="connectionIdentity treats null and blank as the same field",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/mail/ImapIdleMonitor.java',
        find='        return new ConnectionIdentity(\n                connector == null ? null : connector.getEndpoint(),\n                connector == null ? null : connector.getTenantId(),\n                connector == null ? null : connector.getAuthType(),\n                connector == null ? null : connector.getCredentialRef(),\n                password);',
        replace='        return new ConnectionIdentity(\n                connector == null || connector.getEndpoint() == null ? "" : connector.getEndpoint(),\n                connector == null || connector.getTenantId() == null ? "" : connector.getTenantId(),\n                connector == null || connector.getAuthType() == null ? "" : connector.getAuthType(),\n                connector == null || connector.getCredentialRef() == null ? "" : connector.getCredentialRef(),\n                password == null ? "" : password);',
        test='ImapIdleSessionRegistryTest',
        expect_fail=['aNullAndBlankTenantIdAreNotTheSameConnection'],
    ),
    dict(
        id="US",
        what="get() returns a deterministic row whose body names another connectorId",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        # Narrowed in round 5 of the second batch to the ONE check its `what` names: the
        # span version removed three protections at once (first-row identity, the
        # selector-failed refusal, and this), and a firing could not be attributed.
        find='            if (fromId == null || !connectorId.equals(fromId.getConnectorId())) {',
        replace='            if (false) {',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['aDeterministicRowThatNamesAnotherConnectorIsNotReturned'],
    ),
    dict(
        id="UT",
        what="raw .eml preservation swallows a later unreadable profile as absence",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find='            ImportProfileDefinition mailProfile;\n            try {\n                mailProfile = resolveProfileForRepository(\n                        request.getProfileId(), request.getRepositoryId());\n            } catch (ImportProfileDefinitionServiceImpl.ProfileHasTwinRowsException\n                    | ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException e) {\n                warnings.add("Raw .eml preservation could not be decided; retry shortly: "\n                        + e.getMessage());\n                mailProfile = null;\n            }',
        replace='            ImportProfileDefinition mailProfile = confinedProfile(request);',
        test='IngestCreatedObjectPropagationTest',
        expect_fail=['aLaterUnreadableProfileDoesNotSilentlySkipRawEml'],
    ),
    dict(
        id="UV",
        what="IDLE admits a get() hit whose body names a different connectorId",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/mail/ImapIdleMonitor.java',
        # Re-anchored: the message stopped appending "; retry shortly" — it is a standing
        # condition, and this file was the fourth site still adding the suffix.
        find='        if (conn != null && askedConnectorId != null\n'
             '                && !askedConnectorId.equals(conn.getConnectorId())) {',
        replace='        if (false) {',
        test='ImapIdleSessionRegistryTest',
        expect_fail=['aConnectorBodyThatNamesAnotherIdIsNotAdmitted'],
    ),
    dict(
        id="UA",
        what="mail post-processing reads get(profileId) again and honours an unowned "
             "row's preserveOriginalEml",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find='            ImportProfileDefinition mailProfile;\n            try {\n                mailProfile = resolveProfileForRepository(\n                        request.getProfileId(), request.getRepositoryId());\n            } catch (ImportProfileDefinitionServiceImpl.ProfileHasTwinRowsException\n                    | ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException e) {\n                warnings.add("Raw .eml preservation could not be decided; retry shortly: "\n                        + e.getMessage());\n                mailProfile = null;\n            }',
        replace='            ImportProfileDefinition mailProfile = request.getProfileId() != null\n                    ? importProfileDefinitionService.get(request.getProfileId()) : null;',
        test='IngestCreatedObjectPropagationTest',
        expect_fail=['anUnownedPreserveFlagDoesNotCreateARawEmlChild'],
    ),
    dict(
        id="UB",
        what="connector get() leaves getConfClient() outside the fallback try — a "
             "missing client becomes a 500",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find='        try {\n            CloudantClientWrapper client = getConfClient();\n            com.ibm.cloud.cloudant.v1.model.Document row = readByDeterministicId(\n                    client.getClient(), client.getDatabaseName(), connectorId);',
        replace='        CloudantClientWrapper client = getConfClient();\n        try {\n            com.ibm.cloud.cloudant.v1.model.Document row = readByDeterministicId(\n                    client.getClient(), client.getDatabaseName(), connectorId);',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['aMissingConfClientOnTheIdFallbackDoesNotEscape'],
    ),
    dict(
        id="UC",
        what="profile get() leaves getConfClient() outside the fallback try — a "
             "missing client becomes a 500",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='        try {\n            CloudantClientWrapper client = getConfClient();\n            com.ibm.cloud.cloudant.v1.model.Document row = readByDeterministicId(\n                    client.getClient(), client.getDatabaseName(), profileId);',
        replace='        CloudantClientWrapper client = getConfClient();\n        try {\n            com.ibm.cloud.cloudant.v1.model.Document row = readByDeterministicId(\n                    client.getClient(), client.getDatabaseName(), profileId);',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aMissingConfClientOnTheIdFallbackDoesNotEscape'],
    ),
    dict(
        id="UW",
        what="the import resolution falls back to the selector when the walk finds nothing — an "
             "unowned row becomes a profile for every repository again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find='        return importProfileDefinitionService.getForRepository(profileId, repositoryId);',
        replace='        ImportProfileDefinition walked =\n                importProfileDefinitionService.getForRepository(profileId, repositoryId);\n'
                '        return walked != null ? walked : importProfileDefinitionService.get(profileId);',
        test='CanonicalImportServiceTest',
        # Completed from MEASUREMENT, not from reading. These locks were added to the
        # same test class AFTER this control was written, and a full sweep would have
        # exited non-zero listing them. A review enumerated the whole suite for this
        # shape rather than one round at a time.
        expect_fail=['testExecuteRefusesAProfileBoundToNoRepository',
                     'aWalkMissDoesNotResurrectASelectorRow',
                     'testExecuteProfileRepositoryMismatch'],
    ),
    dict(
        id="UX",
        what="the delegated gate stops saying which row it authorised — the import's staleness "
             "check has nothing to compare against and a moved target folder is used",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ExternalIngestController.java',
        find_span=('        request.setAuthorizedProfileFingerprint(',
                   '                CanonicalImportServiceImpl.authorizationFingerprint(profile));'),
        replace='',
        test='ExternalIngestControllerGateTest',
        expect_fail=['theGateStampsTheRowItAuthorized'],
    ),
    dict(
        id="UY",
        what="the import adopts whatever row it resolves — a PUT between authorisation and "
             "execution moves the target folder and the caller was never authorised for it",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find='        if (authorized.equals(authorizationFingerprint(resolved))) {\n            return null;\n        }',
        replace='        if (true) {\n            return null;\n        }',
        test='CanonicalImportServiceTest',
        expect_fail=['testExecuteRefusesARowThatIsNotTheOneAuthorized'],
    ),
    dict(
        id="UZ",
        what="the staleness check refuses every gated import, including the ones whose row did "
             "not change",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        # The first version removed the null short-circuit instead, which NPEs on every
        # ungated import: 36 tests errored and the named lock still passed, so the runner
        # scored it "something failed, but not the expected lock". Sabotage the comparison.
        find='        if (authorized.equals(authorizationFingerprint(resolved))) {',
        replace='        if (false) {',
        test='CanonicalImportServiceTest',
        # Completed from MEASUREMENT, not from reading. These locks were added to the
        # same test class AFTER this control was written, and a full sweep would have
        # exited non-zero listing them. A review enumerated the whole suite for this
        # shape rather than one round at a time.
        expect_fail=['testExecuteRunsWhenTheRowIsStillTheAuthorizedOne',
                     'testExecuteRefusesWhenTheAuthorizedFolderIsNotTheOneResolved'],
    ),
    dict(
        id="VA",
        what="a blank repositoryId stops counting as unowned — the row the migration tells the "
             "operator to delete by docId is refused by that very API",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        # Re-anchored in round 17 (isBlank became namesNoRepository).
        find='        boolean unowned = props != null && namesNoRepository(props.get("repositoryId"));',
        replace='        boolean unowned = props != null && props.get("repositoryId") == null;',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aBlankRepositoryRowIsReachable',
                     'theOneRowDeleteReachesARowWhoseRepositoryIdIsNotAString'],
    ),
    dict(
        id="VB",
        what="the IDLE resolution treats a blank repositoryId as owned again — a capture starts "
             "on a row no repository can manage",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        # Re-anchored in round 17 (isBlank became namesNoRepository).
        find='                    || namesNoRepository(props.get("repositoryId"))) {\n                // Blank, not only null:',
        replace='                    || props.get("repositoryId") == null) {\n                // Blank, not only null:',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aBlankRepositoryRowIsNotOwned',
                     'aRowWhoseRepositoryIdIsNotAStringIsNotOwned'],
    ),
    dict(
        id="VC",
        what="the deterministic-id fallback returns whatever document occupies the id — GET of "
             "one profile answers with another row",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        # Re-anchored in round 16 of the second batch (the identity check moved into
        # definesProfile).
        find_span=('            Map<String, Object> props = row.getProperties();\n            if (!definesProfile(props, profileId)) {',
                   '                return null;\n            }'),
        replace='',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['theDeterministicIdIsNotReserved'],
    ),
    dict(
        id="VD",
        what="the scheduler enumerates through the Mango selector again — a rebuilding index "
             "reads as 'nothing scheduled' and every scheduled capture is skipped in silence",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerService.java',
        find_span=('        java.util.Set<String> knownForPoll = new java.util.HashSet<>(repositoryInfoMap.keys());',
                   '                .toList();'),
        replace='        return repositoryInfoMap.keys().stream()\n'
                '                .flatMap(repoId -> profileService.listByRepository(repoId).stream())\n'
                '                .filter(ImportProfileDefinition::isEnabled)\n'
                '                .filter(ImportProfileDefinition::isSchedulerEnabled)\n'
                '                .toList();',
        test='IngestSchedulerDelegatedRunTest',
        # The other eight are from the measurement: the selector-based enumeration this
        # sabotage restores makes the whole delegated-run fixture stop reaching the gate.
        expect_fail=['anUnreadableScheduleIsNotAnEmptyOne',
                     'autoDisable_writesMarkerFields_whenInactiveCreatorStreakExceedsThreshold',
                     'optInOff_warnsOncePerProfile_evenAcrossMultiplePolls',
                     'optInOn_butCreatorInactive_skipsAndDoesNotFetch',
                     'optInOn_creatorActiveAndAuthorised_progressesPastGate',
                     'optInOn_creatorLostCmisAll_skipsAndDoesNotFetch',
                     'optInOn_inactiveCreator_doesNotEmitLegacyOptOutWarn',
                     'targetFolderDisappearsBetweenTicks_emitsTargetFolderUnresolvable_notConnectorNotDelegated',
                     'targetFolderResolves_butConnectorNoLongerDelegated_stillEmitsConnectorNotDelegated'],
    ),
    dict(
        id="VE",
        what="a schedule that could not be read is reported as an empty one — the poll skips "
             "every capture and says nothing is configured",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerService.java',
        find_span=('            } catch (ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException couldNotAsk) {',
                   '                return;\n            }'),
        replace='            } catch (ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException couldNotAsk) {\n'
                '                profiles = java.util.List.of();\n            }',
        test='IngestSchedulerDelegatedRunTest',
        expect_fail=['anUnreadableScheduleIsNotAnEmptyOne'],
    ),
    dict(
        id="VF",
        what="a delegated import stops re-asking cmis:all at the write — the scheduler, webhook "
             "and IDLE authorise a profile and their orchestrators build unstamped requests",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find='        if (profile == null || !profile.isDelegated()) {',
        replace='        if (true) {',
        test='CanonicalImportServiceTest',
        # The list below is the MEASURED one, not a derived one. Four rounds in a row a
        # lock added in the same commit as its own control was left out of an OLDER
        # control's list; a review found this batch's three, and the count it derived
        # by reading (~9 for VF) was short of what the run reported (12).
        expect_fail=['testDelegatedImportReAsksTheAuthorizationAtTheWrite',
                     'testDelegatedImportRefusesWhenTheAuthorizationIsNotWired',
                     'aWriteInAnotherRepositoryThanTheCallerAuthenticatedIn_is403NotAServerError',
                     'aWriteWhoseAuthorizationServiceIsNotWired_is503NotAServerError',
                     'aWriteWithARevokedConnectorDelegation_is403NotAServerError',
                     'aWriteWithoutCmisAllOnTheTargetFolder_is403NotABadRequest',
                     'testARevokeDuringTheDedupeReadStillStopsTheWrite',
                     'testARevokeDuringTheRelationshipListingStillStopsTheWrite',
                     'testARevokedDelegationStopsTheRelationshipCreation',
                     'testAnAdministratorOfAnotherRepositoryIsStillRefused',
                     'testAnImportWithNoContentStreamIsAlsoReChecked',
                     'testDelegatedImportReAsksTheConnectorDelegation',
                     'testDelegatedImportWithNoCallerIsRefused',
                     'testTheDelegationIsReAskedAfterTheContentIsRead'],
    ),
    dict(
        id="VG",
        what="a missing authorization service lets a delegated import through instead of "
             "refusing it",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        # `if (false)` left the field null and the next line NPE'd, so the runner scored it
        # "the sabotage broke the harness". Permitting is the defect; make it permit.
        # Skipping only the FOLDER check left the connector check calling a null service, so
        # the sabotage crashed instead of permitting. Skip the whole delegated block: that is
        # what "missing wiring permits" actually looks like.
        # Permitting is the defect; refusing to run the rest avoids the NPE a null service
        # would otherwise cause, which the runner scores as harness breakage.
        find_span=('        if (ingestAuthorizationService == null || callContext == null) {',
                   '                            : "the authorization service is not available"));\n        }'),
        replace='        if (ingestAuthorizationService == null || callContext == null) {\n            return null;\n        }',
        test='CanonicalImportServiceTest',
        # Measured, not derived — see the note on VF.
        expect_fail=['testDelegatedImportRefusesWhenTheAuthorizationIsNotWired',
                     'aWriteWhoseAuthorizationServiceIsNotWired_is503NotAServerError',
                     'testDelegatedImportWithNoCallerIsRefused'],
    ),
    dict(
        id="VH",
        what="the folder cmis:all was checked on is no longer compared with the folder written "
             "into — a path-only profile re-resolves to whatever now sits at that path",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find_span=('        String authorizedFolder = request.getAuthorizedTargetFolderId();',
                   '                    + " the one this import was authorised against. Retry shortly.");\n        }'),
        replace='',
        test='CanonicalImportServiceTest',
        expect_fail=['testExecuteRefusesWhenTheAuthorizedFolderIsNotTheOneResolved'],
    ),
    dict(
        id="VI",
        what="a schedulable row with no profileId enters the schedule again — the delegated "
             "tick NPEs on a null key and every profile after it is skipped",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find_span=('            if (def.getProfileId() == null || def.getProfileId().isBlank()) {',
                   '                return;\n            }'),
        replace='',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['anIdentitylessRowIsNotScheduled'],
    ),
    dict(
        id="VJ",
        what="a derived write (raw .eml, mail attachment, note attachment) loses the "
             "authorisation stamps — note's files_only default writes ONLY children",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ExternalIngestRequest.java',
        find_span=('    public void copyAuthorizationStampsTo(ExternalIngestRequest derived) {',
                   '        derived.setAuthorizedTargetFolderId(this.authorizedTargetFolderId);\n    }'),
        replace='    public void copyAuthorizationStampsTo(ExternalIngestRequest derived) {\n    }',
        test='AuthorizationStampsTravelWithDerivedWritesTest',
        expect_fail=['bothStampsAreCopied'],
    ),
    dict(
        id="VK",
        what="one of the three derived writes is built without the stamps again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find='                    request.copyAuthorizationStampsTo(emlReq);',
        replace='',
        test='AuthorizationStampsTravelWithDerivedWritesTest',
        expect_fail=['everyDerivedRequestIsStamped'],
    ),
    dict(
        id="VL",
        what="a delegated write with no caller skips the authorisation again — an admin profile "
             "that turned delegated mid-fetch arrives here unexamined",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find='        if (ingestAuthorizationService == null || callContext == null) {',
        replace='        if (ingestAuthorizationService == null) {',
        test='CanonicalImportServiceTest',
        expect_fail=['testDelegatedImportWithNoCallerIsRefused'],
    ),
    dict(
        id="VM",
        what="the write point stops re-asking connector delegation — a revoke during a fetch "
             "leaves the write it authorised unexamined",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find_span=('        if (connector != null && !ingestAuthorizationService.canUseConnectorForDelegatedProfile(',
                   '                    + " caller and target folder");\n        }'),
        replace='',
        test='CanonicalImportServiceTest',
        # Measured, not derived — see the note on VF.
        expect_fail=['testDelegatedImportReAsksTheConnectorDelegation',
                     'aWriteWithARevokedConnectorDelegation_is403NotAServerError'],
    ),
    dict(
        id="VN",
        what="the delegated re-check stops exempting administrators — the folder Run endpoint "
             "and DLQ replay, which admit admins on purpose, start refusing",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find_span=('        if (ingestAuthorizationService.isAdmin(callContext)) {',
                   '            return null;\n        }'),
        replace='',
        test='CanonicalImportServiceTest',
        expect_fail=['testAnAdministratorIsNotSubjectToTheDelegatedRecheck'],
    ),
    dict(
        id="VO",
        what="the delegation is asked once, before the content stream is read — a revoke that "
             "lands while a large attachment downloads is authorised by a stale decision",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find_span=('            ExternalIngestResult revoked = refuseIfDelegationNoLongerAuthorizes(',
                   '            if (revoked != null) return revoked;'),
        replace='',
        test='CanonicalImportServiceTest',
        # Completed from MEASUREMENT, not from reading. These locks were added to the
        # same test class AFTER this control was written, and a full sweep would have
        # exited non-zero listing them. A review enumerated the whole suite for this
        # shape rather than one round at a time.
        expect_fail=['testTheDelegationIsReAskedAfterTheContentIsRead',
                     'testAnImportWithNoContentStreamIsAlsoReChecked',
                     'testARevokeDuringTheDedupeReadStillStopsTheWrite',
                     'testARevokeDuringTheRelationshipListingStillStopsTheWrite'],
    ),
    dict(
        id="VP",
        what="the administrator exemption skips repository confinement — an admin of one "
             "repository imports into another",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find_span=('        if (!ingestAuthorizationService.isAuthenticatedRepository(callContext, repositoryId)) {',
                   '                    + " against");\n        }'),
        replace='',
        test='CanonicalImportServiceTest',
        # Completed from MEASUREMENT, not from reading. These locks were added to the
        # same test class AFTER this control was written, and a full sweep would have
        # exited non-zero listing them. A review enumerated the whole suite for this
        # shape rather than one round at a time.
        expect_fail=['testAnAdministratorOfAnotherRepositoryIsStillRefused',
                     'aWriteInAnotherRepositoryThanTheCallerAuthenticatedIn_is403NotAServerError'],
    ),
        dict(
        id="VQ",
        what="the resync deletion plan is no longer formed before the authorisation is "
             "re-asked (the early enumeration is removed), so a revoke landing during the "
             "listing is not seen by the deletions that follow",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        # The first version restored the combined helper but LEFT the read-phase enumeration,
        # so the revoke still landed before the check and the lock stayed green. Remove the
        # early read: that is what "formed before the check" means.
        find_span=('                    resyncPlan = collectExistingRelationshipIds(',
                   '                            callContext, repositoryId, existingDoc.getId());'),
        replace='                    resyncPlan = java.util.List.of();',
        test='CanonicalImportServiceTest',
        expect_fail=['testARevokeDuringTheRelationshipListingStillStopsTheWrite'],
    ),
    dict(
        id="VR",
        what="an empty relationship page that still claims more ends the listing — a partial "
             "plan is deleted and reported as a complete resync",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find_span=('                if (rels != null && Boolean.TRUE.equals(rels.hasMoreItems())) {',
                   '                            + " reports more to come");\n                }'),
        replace='',
        test='IngestDedupeFailuresReachCallerTest',
        expect_fail=['anEmptyPageClaimingMoreIsIncomplete'],
    ),
    dict(
        id="VS",
        what="the relationship cap returns what it collected instead of refusing — part of the "
             "edges are deleted and the resync reports success",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find_span=('            if (ids.size() > MAX_RESYNC_RELATIONSHIPS) {',
                   '                        + " exist, which is beyond what this policy will replace");\n            }'),
        replace='            if (ids.size() > MAX_RESYNC_RELATIONSHIPS) {\n                break;\n            }',
        test='IngestDedupeFailuresReachCallerTest',
        expect_fail=['aListingBeyondTheCapRefuses'],
    ),
    dict(
        id="VT",
        what="the relationship creation stops re-asking the delegated authorisation — the "
             "existence read is a database read placed after the last authorisation",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        # Re-anchored in round 2 of the second batch (the refusal became a LinkOutcome).
        find_span=('            if (authorizingProfile != null && authorizingProfile.isDelegated()) {',
                   '                            + revokedHere.errors().get(0));\n                }\n            }'),
        replace='',
        test='CanonicalImportServiceTest',
        # The second name is from the measurement, not from reading: removing the re-check
        # leaves the round-44 lock's assertDoesNotThrow reddening as well. The declaration
        # has been short since that lock was added and was never re-measured.
        expect_fail=['testARevokedDelegationStopsTheRelationshipCreation',
                     'aLinkWhoseFolderReadRefusesIsNotLinked_notAnEscapingException'],
    ),
    dict(
        id="VU",
        what="the archetype wrappers create their links without an authorising profile again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find_span=('        ImportProfileDefinition current = importProfileDefinitionService.getForRepository(',
                   '        return current;'),
        replace='        return null;',
        test='CanonicalImportServiceTest',
        # Completed from MEASUREMENT, not from reading. These locks were added to the
        # same test class AFTER this control was written, and a full sweep would have
        # exited non-zero listing them. A review enumerated the whole suite for this
        # shape rather than one round at a time.
        expect_fail=['testARevokedDelegationStopsTheRelationshipCreation',
                     'testAProfileGoneDuringTheImportIsAWarningNotA500',
                     'testAnUnresolvableConnectorRefusesTheLinkInsteadOfSkippingTheCheck'],
    ),
    dict(
        id="VV",
        what="an unresolvable connector is passed on as null, skipping the connector half of "
             "the link's authorisation",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find_span=('        if (profile != null && profile.isDelegated() && request != null\n                && request.getConnectorId() != null && connector == null) {',
                   '                    + " could not be resolved, so its delegation could not be checked";\n        }'),
        replace='',
        test='CanonicalImportServiceTest',
        expect_fail=['testAnUnresolvableConnectorRefusesTheLinkInsteadOfSkippingTheCheck'],
    ),
    dict(
        id="VW",
        what="a link that cannot be authorised escapes as an exception again — one relationship "
             "takes the whole import down after the object was committed",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        # A span ending on the next '}' duplicated the catch clause ("exception already
        # caught"). One line is enough: the catch body is what turns a refusal into a warning.
        find='            return "the relationship was not created: " + cannotAuthorize.getMessage();',
        replace='            throw cannotAuthorize;',
        test='CanonicalImportServiceTest',
        expect_fail=['testAProfileGoneDuringTheImportIsAWarningNotA500'],
    ),
    # ── fail-closed reads, second batch: the webhook receiver and the selector's page cap ──
    dict(
        id="VX",
        what="the webhook receiver picks its recipients out of the Mango selector again — a "
             "rebuilding index reads as 'no profile', 200, and the sender's event is gone",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestWebhookController.java',
        # Re-anchored in round 2 (the listing became OwnedProfiles; the uninterpretable-row
        # loop above the anchor still reads `owned`, so the sabotage compiles).
        find='        return owned.profiles().stream()',
        replace='        return profileService.list().stream()',
        test='IngestWebhookBoxDropboxTest',
        expect_fail=['theRecipientsAreReadFromTheWalkNotTheSelector'],
    ),
    dict(
        id="VY",
        what="a recipient listing that could not be completed falls to the generic 500 again — "
             "the sender reads it as our bug, not as an answer to retry",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestWebhookController.java',
        # The whole 503 catch clause goes; the closing brace of the try is supplied by the
        # catch that follows it (the RecipientUnreadableException one since round 2 of the
        # second batch; the generic catch before that).
        find_span=('        } catch (ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException couldNotList) {',
                   '                    .body(Map.of("error", "Import profiles could not be read; retry shortly"));'),
        replace='',
        test='IngestWebhookBoxDropboxTest',
        expect_fail=['aListingThatCannotBeCompletedIsA503NotNoProfile'],
    ),
    dict(
        id="VZ",
        what="the shared owned-row walk lists rows that name no repository — an unowned row "
             "becomes a webhook recipient the delegated gate then refuses",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        # The three-line form is what makes this anchor unique: getOwnedRowIndexFree has the
        # same condition, followed by a comment instead of the return.
        # Re-anchored in round 17 (isBlank became namesNoRepository).
        find='                    || namesNoRepository(props.get("repositoryId"))) {\n                return;\n            }',
        replace='                    || false) {\n                return;\n            }',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['theOwnedListingSeesEveryOwnedRowAndReportsTheRest',
                     'theOwnedListingSkipsARowWhoseRepositoryIdIsNotAString'],
    ),
    dict(
        id="WA",
        what="the shared owned-row walk skips a row it could not read and answers with the "
             "rest — at the receiver, 'these are all the profiles'",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find_span=('                throw new IllegalStateException(what + " cannot be listed: a row"',
                   '                        + (row.getError() != null ? row.getError() : "no id") + ")");'),
        replace='                return;',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['theOwnedListingRefusesAnUnreadableRow'],
    ),
    dict(
        id="WB",
        what="the profile selector listing returns its first page as the whole answer again — "
             "the 201st profile is never listed, and nothing says so",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='        return NemakiConfFind.allMatching(cloudant, dbName, selector);',
        replace='        PostFindOptions findOptions = new PostFindOptions.Builder()\n'
                '                .db(dbName).selector(selector).limit(200).build();\n'
                '        FindResult findResult = cloudant.postFind(findOptions).execute().getResult();\n'
                '        List<com.ibm.cloud.cloudant.v1.model.Document> docs = findResult.getDocs();\n'
                '        return docs != null ? docs : List.of();',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['theSelectorListingPagesPastTheFirstPage'],
    ),
    dict(
        id="WC",
        what="the connector selector listing returns its first page as the whole answer again — "
             "the profile fix's one-arm twin",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find='        return NemakiConfFind.allMatching(cloudant, dbName, selector);',
        replace='        PostFindOptions findOptions = new PostFindOptions.Builder()\n'
                '                .db(dbName).selector(selector).limit(200).build();\n'
                '        FindResult findResult = cloudant.postFind(findOptions).execute().getResult();\n'
                '        List<com.ibm.cloud.cloudant.v1.model.Document> docs = findResult.getDocs();\n'
                '        return docs != null ? docs : List.of();',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['theSelectorListingPagesPastTheFirstPage'],
    ),
    dict(
        id="WD",
        what="the relationship existence check fails open to 'no such edge' in silence again — "
             "the link is created and nothing tells the caller the check did not happen",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find='            return EdgeLookup.unanswered(e.getMessage());',
        replace='            return EdgeLookup.absent();',
        test='CanonicalImportServiceTest',
        # Completed from MEASUREMENT, not from reading. These locks were added to the
        # same test class AFTER this control was written, and a full sweep would have
        # exited non-zero listing them. A review enumerated the whole suite for this
        # shape rather than one round at a time.
        expect_fail=['createDirectRelationship_createsAndSaysSo_whenExistenceCheckThrows',
                     'theCaptureRecordCarriesTheUnansweredCheck'],
    ),
    dict(
        id="WE",
        what="every first link is reported as an unanswered duplicate check — the over-report "
             "twin of WD",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find='                if (!edge.answered()) {',
        replace='                if (true) {',
        test='CanonicalImportServiceTest',
        expect_fail=['createDirectRelationship_saysNothing_whenExistenceCheckAnswersNoEdge'],
    ),
    dict(
        id="WF",
        what="an unanswered duplicate check REFUSES the link — the over-throw: a transient read "
             "stops a legitimate first relationship",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find_span=('                if (!edge.answered()) {',
                   '                            + "), so a duplicate edge may now exist";\n                }'),
        # Replacement re-typed in round 2 (the core returns a LinkOutcome). The sweep died
        # here once: the anchor still matched and the replacement no longer compiled —
        # only the NEW controls had been compile-checked.
        replace='                if (!edge.answered()) {\n'
                '                    return LinkOutcome.notLinked("the relationship was not created: " + edge.failure());\n'
                '                }',
        test='CanonicalImportServiceTest',
        # Completed from MEASUREMENT, not from reading. These locks were added to the
        # same test class AFTER this control was written, and a full sweep would have
        # exited non-zero listing them. A review enumerated the whole suite for this
        # shape rather than one round at a time.
        expect_fail=['createDirectRelationship_createsAndSaysSo_whenExistenceCheckThrows',
                     'aMissingContentServiceIsAnUnansweredCheckNotAnAbsentEdge',
                     'theCaptureRecordCarriesTheUnansweredCheck',
                     'thePublicEntryPointAnswersNullForALinkCreatedWithoutItsCheck'],
    ),
    dict(
        id="WG",
        what="the uniqueness rule interprets whole rows again — one row with a value this node "
             "cannot read stops every create and update of its repository",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='                UNIQUENESS_RULE_FIELDS);',
        replace='                null);',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aRowTheRuleCanStillReadDoesNotBlockACreate'],
    ),
    dict(
        id="WH",
        what="the naive fix: rows that do not deserialise are skipped — a second default whose "
             "other fields are broken slips past the rule",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='                if (onlyFields != null) {\n'
             '                    content.keySet().retainAll(onlyFields);\n'
             '                }',
        replace='                if (onlyFields != null) {\n'
                '                    try {\n'
                '                        MAPPER.convertValue(content, ImportProfileDefinition.class);\n'
                '                    } catch (Exception e) {\n'
                '                        return;\n'
                '                    }\n'
                '                    content.keySet().retainAll(onlyFields);\n'
                '                }',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aRowTheRuleCanReadStillCountsForTheRule'],
    ),
    dict(
        id="WI",
        what="a disabled row the resolver cannot interpret refuses the whole repository's "
             "auto-resolution again — over a row that could not have been chosen",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        # Re-anchored in round 5 of the second batch (the skip reads both disabled shapes)
        # and again in round 16 (the mapper reads the field).
        find='                if (readsDisabled(props)) {\n'
             '                    return;\n'
             '                }\n',
        replace='',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aDisabledRowTheResolverCannotReadDoesNotRefuseTheResolve',
                     'aDisabledByStringRowTheResolverCannotReadDoesNotRefuseTheResolve',
                     'aDisabledByNullRowTheResolverCannotReadDoesNotRefuseTheResolve'],
    ),
    dict(
        id="WJ",
        what="an enabled row the resolver cannot interpret is skipped — the resolution answers "
             "with whatever else was readable, and content lands under the wrong profile",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='                    throw new IllegalStateException("the profiles of repository \'"\n'
             '                            + repositoryId + "\' cannot be listed: row " + id\n'
             '                            + " could not be read as a profile (" + e.getMessage() + ")");',
        replace='                    return;',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['anEnabledRowTheResolverCannotInterpretStillRefuses'],
    ),
    # ── round 2 of the second batch: what two reviews found ──
    dict(
        id="WK",
        what="the CALL SITE stops telling an unanswered duplicate check apart — the helper "
             "still says unanswered, and nothing is reported (WD's call-site twin)",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find='                if (!edge.answered()) {',
        replace='                if (false) {',
        test='CanonicalImportServiceTest',
        # Completed from MEASUREMENT, not from reading. These locks were added to the
        # same test class AFTER this control was written, and a full sweep would have
        # exited non-zero listing them. A review enumerated the whole suite for this
        # shape rather than one round at a time.
        expect_fail=['createDirectRelationship_createsAndSaysSo_whenExistenceCheckThrows',
                     'aMissingContentServiceIsAnUnansweredCheckNotAnAbsentEdge',
                     'theCaptureRecordCarriesTheUnansweredCheck'],
    ),
    # WL (the receiver's index-free existence check) was withdrawn in round 3 together with
    # the protection it measured: a walk of the configuration database per unauthenticated
    # request was an amplifier. getOrRefuse replaced it (XC / XD / XE).
    dict(
        id="WM",
        what="a connector read that throws escapes the receiver as a 500 again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestWebhookController.java',
        find='            return connectorCouldNotBeRead(connectorId, couldNotRead.getMessage());',
        replace='            throw couldNotRead;',
        test='IngestWebhookBoxDropboxTest',
        expect_fail=['aConnectorReadThatCouldNotBeAnsweredIsA503NotA401'],
    ),
    dict(
        id="WN",
        what="a recipient row the walk could not read is left out again — the readable rows "
             "answer 'no profile' (200) for a recipient that exists",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestWebhookController.java',
        find_span=('            if (broken.addressedTo(connId, connector.getSourceArchetype())) {',
                   '                        + " and could not be read as a profile (" + broken.reason() + ")");\n            }'),
        replace='            if (broken.addressedTo(connId, connector.getSourceArchetype())) {\n                continue;\n            }',
        test='IngestWebhookBoxDropboxTest',
        expect_fail=['aRecipientRowThatCannotBeInterpretedIsA503NotNoProfile',
                     'aBrokenRecipientRowRefusesTheDispatchEvenBesideReadableOnes',
                     'aRowWhoseAddresseeCannotBeReadRefusesEveryConnectorsDispatch'],
    ),
    dict(
        id="YD",
        what="the receiver's catch for an unreadable recipient row is gone — the refusal falls to "
             "the generic 500 (VY's twin for RecipientUnreadableException)",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestWebhookController.java',
        find_span=('        } catch (RecipientUnreadableException recipientBroken) {',
                   '                            + " read; retry later"));'),
        replace='',
        test='IngestWebhookBoxDropboxTest',
        expect_fail=['aRecipientRowThatCannotBeInterpretedIsA503NotNoProfile'],
    ),
    dict(
        id="WO",
        what="the walk stops reporting the rows it could not read — the receiver has nothing "
             "to refuse on and answers from the readable rows alone",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='                reportUninterpretable(uninterpretable, id, props, e.getMessage());\n',
        replace='',
        test='ImportProfileLegacyIdMigrationTest',
        # The locks that read uninterpretable() for a row broken by DESERIALISATION. The
        # three that stay green do so for three different reasons: the nameless-row report is
        # a SECOND call site this sabotage does not touch; one isEmpty() lock's row is read as
        # disabled, so nothing was going to be reported; and the other's row names no
        # repository, so the walk drops it before it is deserialised at all. (A review found
        # the middle reason stated for both.) These nine were derived by hand over three
        # rounds; a measured run then CONFIRMED the list is complete (zero undeclared). The
        # runner prints undeclared locks now, so the next completion comes from a run rather
        # than from derivation — but this one did not, and a review caught the comment saying
        # otherwise.
        expect_fail=['theOwnedListingSeesEveryOwnedRowAndReportsTheRest',
                     'aNumericProfileIdInTheReadPathsOwnShapeStillReadsAsTheMapperReadsIt',
                     'whichValuesOfADisabledFlagCountIsTheMappersAnswer',
                     'aRowWhoseConnectorFieldsHaveNoReadableShapeAddressesEveryConnector',
                     'aRowWhoseArchetypeFieldHasNoReadableShapeAdmitsEveryArchetypeButNamesOnlyItsConnectors',
                     'aRowWhoseConnectorFieldsHaveNoReadableShapeIsNotAddressedToAnArchetypeItExcludes',
                     'aMiscasedArchetypeNameMakesTheRowUnreadableAndItAddressesEveryArchetype',
                     'aNumericConnectorIdReadsAsTheMapperReadsIt',
                     'aNullElementInTheArchetypeListReadsAsTheMapperReadsIt'],
    ),
    dict(
        id="WP",
        what="the public entry point reports a created link as an error again — the fetch "
             "that imported nothing is recorded FAILED and the circuit breaker advances",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find='        if (!outcome.linked()) {\n            return outcome.message();\n        }',
        replace='        if (true) {\n            return outcome.message();\n        }',
        test='CanonicalImportServiceTest',
        expect_fail=['thePublicEntryPointAnswersNullForALinkCreatedWithoutItsCheck'],
    ),
    dict(
        id="WQ",
        what="a bookmark seen before is followed again — an A→B→A cycle appends the same "
             "pages for ever",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/NemakiConfFind.java',
        find='            if (next == null || next.isBlank() || !seen.add(next)) {',
        replace='            if (next == null || next.isBlank()) {',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['theSelectorListingRefusesABookmarkCycle'],
    ),
    dict(
        id="WR",
        what="a selector answer without docs is returned as an empty (or short) listing",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/NemakiConfFind.java',
        find_span=('                throw new IllegalStateException("the selector listing of \'" + dbName',
                   '                        + " established");'),
        replace='                return all;',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['theSelectorListingRefusesAListingThatDidNotAnswer'],
    ),
    dict(
        id="WS",
        what="a full page with no new bookmark is returned as the whole answer",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/NemakiConfFind.java',
        find_span=('                throw new IllegalStateException("a full selector page of \'" + dbName',
                   '                        + " make progress");'),
        replace='                return all;',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['theSelectorListingRefusesAFullPageWithoutABookmark'],
    ),
    dict(
        id="WT",
        what="the identity check runs before the disabled skip again — a disabled row with no "
             "profileId refuses every write of its repository",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        # Re-anchored in round 5 of the second batch (the skip reads both disabled shapes)
        # and again in round 16 (the mapper reads both fields).
        find_span=('                if (readsDisabled(props)) {\n'
                   '                    return;\n'
                   '                }\n'
                   '                // After the disabled skip, not before it:',
                   '                            + " has no usable profileId");\n'
                   '                }'),
        replace='                ImportProfileDefinition idOnly = readAlone(props, "profileId");\n'
                '                if (idOnly == null || idOnly.getProfileId() == null\n'
                '                        || idOnly.getProfileId().isBlank()) {\n'
                '                    throw new IllegalStateException("the profiles of repository \'"\n'
                '                            + repositoryId + "\' cannot be listed: row " + id\n'
                '                            + " has no usable profileId");\n'
                '                }\n'
                '                if (readsDisabled(props)) {\n'
                '                    return;\n'
                '                }',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aDisabledRowWithoutAProfileIdDoesNotBlockACreate'],
    ),
    dict(
        id="WU",
        what="a content service that is not wired answers 'no such edge' again — could not "
             "ask, with the value of asked-and-none",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find='            return EdgeLookup.unanswered("contentService is not wired");',
        replace='            return EdgeLookup.absent();',
        test='CanonicalImportServiceTest',
        expect_fail=['aMissingContentServiceIsAnUnansweredCheckNotAnAbsentEdge'],
    ),
    dict(
        id="WV",
        what="the connector selector listing lets the bare IllegalStateException out — the "
             "create path answers 400 for a listing that could not be completed",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find='            throw new ConnectorIndexNotReadyException(incomplete.getMessage());',
        replace='            throw incomplete;',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['theSelectorListingRefusesAFullPageWithoutABookmark'],
    ),
    dict(
        id="WW",
        what="the profile selector listing lets the bare IllegalStateException out — WV's "
             "profile twin",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='            throw new ProfileIndexNotReadyException(incomplete.getMessage());',
        replace='            throw incomplete;',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['theSelectorListingRefusesAFullPageWithoutABookmark'],
    ),
    # ── round 3 of the second batch: what the second review round found ──
    dict(
        id="WX",
        what="the Dropbox URL-verification GET lets a connector read that threw escape as a "
             "500 again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestWebhookController.java',
        find='            return connectorCouldNotBeRead(connectorId, couldNotReadOnVerify.getMessage());',
        replace='            throw couldNotReadOnVerify;',
        test='IngestWebhookBoxDropboxTest',
        expect_fail=['theHandshakeAnswers503WhenTheConnectorReadCouldNotBeAnswered'],
    ),
    dict(
        id="WY",
        what="a row with no profileId is dropped from the owned listing without being reported "
             "— it may still name the connector the receiver is answering for",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='                reportUninterpretable(uninterpretable, id, props, "the row has no profileId");\n',
        replace='',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['theOwnedListingReportsANamelessRowToo'],
    ),
    dict(
        id="WZ",
        what="a broken recipient row only refuses when nothing else is readable — beside a "
             "readable recipient the dispatch goes ahead with the broken one left out",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestWebhookController.java',
        find='            if (broken.addressedTo(connId, connector.getSourceArchetype())) {',
        replace='            if (owned.profiles().isEmpty() && broken.addressedTo(connId, connector.getSourceArchetype())) {',
        test='IngestWebhookBoxDropboxTest',
        expect_fail=['aBrokenRecipientRowRefusesTheDispatchEvenBesideReadableOnes',
                     'aRowWhoseAddresseeCannotBeReadRefusesEveryConnectorsDispatch'],
    ),
    dict(
        id="XA",
        what="an unwired profile service answers 'no profile' (200) again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestWebhookController.java',
        find_span=('            throw new RecipientUnreadableException("the import profile service is not wired,"',
                   '                    + " cannot be established");'),
        replace='            return List.of();',
        test='IngestWebhookBoxDropboxTest',
        expect_fail=['anUnwiredProfileServiceIsA503NotNoProfile'],
    ),
    dict(
        id="XB",
        what="a connector field of unreadable shape reads as 'names nobody' again — the skip "
             "the uninterpretable-row record exists to prevent, one field down",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='                connectorsOnly == null, reason));',
        replace='                false, reason));',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aRowWhoseConnectorFieldsHaveNoReadableShapeAddressesEveryConnector',
                     'aRowWhoseConnectorFieldsHaveNoReadableShapeIsNotAddressedToAnArchetypeItExcludes'],
    ),
    dict(
        id="XC",
        what="getOrRefuse answers null for a failed id read again — 'could not ask' with the "
             "value of 'no such connector'",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find='            if (refuseUnanswered) {',
        replace='            if (false) {',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['getOrRefuseRefusesWhenTheIdReadFails'],
    ),
    dict(
        id="XD",
        what="the receiver resolves the connector through get() again — a failed read is 401, "
             "'your signature is wrong'",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestWebhookController.java',
        find='            connector = connectorDefinitionService.getOrRefuse(connectorId);\n'
             '        } catch (ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException couldNotRead) {',
        replace='            connector = connectorDefinitionService.get(connectorId);\n'
                '        } catch (ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException couldNotRead) {',
        test='IngestWebhookBoxDropboxTest',
        expect_fail=['aConnectorReadThatCouldNotBeAnsweredIsA503NotA401'],
    ),
    dict(
        id="XE",
        what="the Dropbox URL-verification GET resolves the connector through get() again — "
             "a failed read is 404",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestWebhookController.java',
        find='            connector = connectorDefinitionService.getOrRefuse(connectorId);\n'
             '        } catch (ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException couldNotReadOnVerify) {',
        replace='            connector = connectorDefinitionService.get(connectorId);\n'
                '        } catch (ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException couldNotReadOnVerify) {',
        test='IngestWebhookBoxDropboxTest',
        expect_fail=['theHandshakeAnswers503WhenTheConnectorReadCouldNotBeAnswered'],
    ),
    dict(
        id="XF",
        what="the connector admin listing lets the typed refusal escape as a Spring 500 again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionController.java',
        find='    @ExceptionHandler(ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException.class)\n',
        replace='',
        test='ConnectorDefinitionControllerPartialPutTest',
        expect_fail=['theListAnswers503WhenTheListingCannotBeCompleted'],
    ),
    dict(
        id="XG",
        what="the profile admin listing lets the typed refusal escape as a Spring 500 again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionController.java',
        find='    @ExceptionHandler({ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException.class,\n'
             '            ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException.class})\n'
             '    public ResponseEntity<Map<String, Object>> definitionRowsCouldNotBeRead(RuntimeException e) {\n'
             '        return errorResponse(',
        replace='    public ResponseEntity<Map<String, Object>> definitionRowsCouldNotBeRead(RuntimeException e) {\n'
                '        return errorResponse(',
        test='ImportProfileHiddenIsNotAbsentTest',
        expect_fail=['theListAnswers503WhenTheListingCannotBeCompleted'],
    ),
    dict(
        id="XH",
        what="the folder connector listing lets the typed refusal escape as a Spring 500 again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/FolderConnectorController.java',
        # Re-anchored TWICE. The handler gained the settings refusal, so the annotation is no
        # longer two lines; and the first re-anchor replaced the type list with
        # RuntimeException.class, which catches MORE, so the control did not fire at all. The
        # lock drives MockMvc, so what has to go is the ANNOTATION — Spring dispatches by it.
        find='    @ExceptionHandler({ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException.class,',
        replace='    @ExceptionHandler({IllegalMonitorStateException.class,',
        test='FolderConnectorControllerTest',
        expect_fail=['theListAnswers503WhenTheListingCannotBeCompleted'],
    ),
    # ── round 4 of the second batch: what the third review round found ──
    dict(
        id="XI",
        what="a selector that did not answer plus an absent deterministic row reads as 'no such "
             "connector' again — a legacy-id row was never excluded",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find='                if (refuseUnanswered && !selectorAnswered) {',
        replace='                if (false) {',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['getOrRefuseRefusesWhenTheSelectorFailedAndTheIdReadFindsNothing'],
    ),
    dict(
        id="XJ",
        what="the receiver ignores a row whose addressee cannot be read — 'names nobody I can "
             "see' read as 'does not name me'",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestWebhookController.java',
        find='            if (broken.addressedTo(connId, connector.getSourceArchetype())) {',
        replace='            if (!broken.addresseeUnknown() && broken.addressedTo(connId, connector.getSourceArchetype())) {',
        test='IngestWebhookBoxDropboxTest',
        expect_fail=['aRowWhoseAddresseeCannotBeReadRefusesEveryConnectorsDispatch'],
    ),
    dict(
        id="XK",
        what="a row disabled by the string \"false\" is reported as a possible recipient — the "
             "profile listing reads the string differently from the connector listing again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        # Re-targeted in round 5 at the CALL SITE (the reporting of a row), not the helper;
        # re-anchored in round 16 (the mapper reads the field).
        find='        if (readsDisabled(props)) {\n            return;\n        }\n        ImportProfileDefinition idOnly',
        replace='        if (Boolean.FALSE.equals(props.get("enabled"))) {\n            return;\n        }\n        ImportProfileDefinition idOnly',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['theOwnedListingSeesEveryOwnedRowAndReportsTheRest',
                     'aRowWhoseEnabledIsAnExplicitNullIsNotARecipient',
                     'whichValuesOfADisabledFlagCountIsTheMappersAnswer'],
    ),
    dict(
        id="XL",
        what="the refusing read skips a row the selector shows but cannot read — a legacy-id row "
             "this node cannot read looks like no row",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        # Re-targeted in round 5 at the CALL SITE: the dedicated catch that keeps the
        # unreadable row's refusal from being swallowed as a selector failure. Without it the
        # readable deterministic row answers in the unreadable row's place.
        find_span=('        } catch (UnreadableSelectorRowException unreadableRow) {',
                   '            throw unreadableRow;\n        } catch (RuntimeException selectorFailed) {'),
        replace='        } catch (RuntimeException selectorFailed) {',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['getOrRefuseRefusesWhenTheSelectorShowsARowItCannotRead'],
    ),
    dict(
        id="XN",
        what="the dedicated catch takes the listing's typed refusal too — an incomplete selector "
             "page refuses over a readable deterministic row, for get() and getOrRefuse alike",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find='        } catch (UnreadableSelectorRowException unreadableRow) {',
        replace='        } catch (ConnectorIndexNotReadyException unreadableRow) {',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['getOrRefuseFallsBackToTheDeterministicRowWhenTheSelectorListingIsIncomplete',
                     'getFallsBackToTheDeterministicRowWhenTheSelectorListingIsIncomplete'],
    ),
    # ── round 8 of the second batch: what the seventh review round found ──
    dict(
        id="XO",
        what="a selector transport failure escapes the profile listing untyped again — a 500 "
             "where the three listing refusals answer 503",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find_span=('        } catch (RuntimeException transportFailed) {\n'
                   '            // The SDK\'s own failure (a reset, a 5xx) is a listing that could not be',
                   '            throw new ProfileIndexNotReadyException("the selector listing of \'" + dbName\n'
                   '                    + "\' could not be read: " + transportFailed.getMessage());\n'
                   '        }'),
        replace='        }',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['theSelectorListingRefusesATransportFailureWithTheTypedRefusal'],
    ),
    dict(
        id="XP",
        what="a selector transport failure escapes the connector listing untyped again — XO's "
             "connector twin",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find_span=('        } catch (RuntimeException transportFailed) {\n'
                   '            // The SDK\'s own failure (a reset, a 5xx) is a listing that could not be',
                   '            throw new ConnectorIndexNotReadyException("the selector listing of \'" + dbName\n'
                   '                    + "\' could not be read: " + transportFailed.getMessage());\n'
                   '        }'),
        replace='        }',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['theSelectorListingRefusesATransportFailureWithTheTypedRefusal'],
    ),
    dict(
        id="XQ",
        what="the refusing read runs with the first of a visible pair again — the receiver's "
             "secret and enabled state chosen by Mango ordering",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find='                if (refuseUnanswered && definitionsOf(connectorId, results) > 1) {',
        replace='                if (false) {',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['getOrRefuseRefusesAVisiblePair'],
    ),
    dict(
        id="XW",
        what="the over-throw twin of XQ: get() refuses a visible pair too — its callers gain an "
             "exception path this batch did not change them for",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find='                if (refuseUnanswered && definitionsOf(connectorId, results) > 1) {',
        replace='                if (definitionsOf(connectorId, results) > 1) {',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['getStillReturnsTheFirstOfAVisiblePair'],
    ),
    dict(
        id="XX",
        what="the connector listing's transport failure loses its WARN — a 503 with no trace "
             "anywhere",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find='            logger.warn("the selector listing of \'{}\' could not be read", dbName, transportFailed);\n',
        replace='',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['theSelectorTransportFailureIsLoggedWithItsCause'],
    ),
    dict(
        id="XY",
        what="the profile listing's transport failure loses its WARN — XX's profile twin",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='            logger.warn("the selector listing of \'{}\' could not be read", dbName, transportFailed);\n',
        replace='',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['theSelectorTransportFailureIsLoggedWithItsCause'],
    ),
    dict(
        id="XZ",
        what="a deterministic row that cannot be read as a connector is refused as a read that "
             "did not answer — the operator is sent after the connection instead of the row",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find='                throw new ConnectorIndexNotReadyException(rowFound',
        replace='                throw new ConnectorIndexNotReadyException(false',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['getOrRefuseNamesTheRowWhenTheDeterministicRowCannotBeReadAsAConnector'],
    ),
    dict(
        id="YA",
        what="an SDK IllegalStateException on the connector listing loses its WARN — the one "
             "RuntimeException the WARN arm did not see",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find='            logger.warn("the selector listing of \'{}\' could not be completed", dbName, incomplete);\n',
        replace='',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['anSdkIllegalStateExceptionIsLoggedWithItsCause'],
    ),
    dict(
        id="YB",
        what="an SDK IllegalStateException on the profile listing loses its WARN — YA's profile twin",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='            logger.warn("the selector listing of \'{}\' could not be completed", dbName, incomplete);\n',
        replace='',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['anSdkIllegalStateExceptionIsLoggedWithItsCause'],
    ),
    dict(
        id="YC",
        what="the other arm of XZ: a failed id read is reported as a row that exists — the "
             "weaker fact read as the stronger one",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find='                throw new ConnectorIndexNotReadyException(rowFound',
        replace='                throw new ConnectorIndexNotReadyException(true',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['getOrRefuseRefusesWhenTheIdReadFails'],
    ),
    dict(
        id="YE",
        what="the profile listing's skip WARN loses the row's id again — the operator has no "
             "handle on the row the listing left out",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='                logger.warn("Failed to deserialize import profile row {}: {}", rawDoc.getId(),\n'
             '                        e.getMessage());',
        replace='                logger.warn("Failed to deserialize import profile: {}", e.getMessage());',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['theAdminListingNamesTheRowItCannotRead'],
    ),
    dict(
        id="YF",
        what="the connector listing's skip WARN loses the row's id again — YE's connector twin",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find='                logger.warn("Failed to deserialize connector definition row {}: {}", rawDoc.getId(),\n'
             '                        e.getMessage());',
        replace='                logger.warn("Failed to deserialize connector definition: {}", e.getMessage());',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['theAdminListingNamesTheRowItCannotRead'],
    ),
    dict(
        id="YG",
        what="the receiver refuses on the broken row's connector name alone again — a row whose "
             "archetypes plainly exclude the connector stops a dispatch it could never have received",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestWebhookController.java',
        find='            if (broken.addressedTo(connId, connector.getSourceArchetype())) {',
        replace='            if (broken.namesConnector(connId)) {',
        test='IngestWebhookBoxDropboxTest',
        expect_fail=['aBrokenRowWhoseArchetypesExcludeTheConnectorDoesNotStopTheDispatch',
                     'aBrokenRowWhoseConnectorFieldsCannotBeReadButWhoseArchetypesExcludeTheConnectorDoesNotStopTheDispatch'],
    ),
    dict(
        id="YH",
        what="an archetype field that is a bare string is 'helpfully' read as a one-name list "
             "again — the mapper refuses it, and a reader that coerces it restricts the row to "
             "that archetype instead of admitting all",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='                alone.put(field, props.get(field));',
        replace='                alone.put(field, "allowedArchetypes".equals(field) && props.get(field) instanceof String s\n'
                '                        ? List.of(s) : props.get(field));',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aRowWhoseArchetypeFieldHasNoReadableShapeAdmitsEveryArchetypeButNamesOnlyItsConnectors'],
    ),
    # YI (a receiver re-deriving the archetype admission from raw strings without the
    # unknown-name arm) was withdrawn in round 15 of the second batch: the row now carries the
    # list as the mapper read it, so an unknown name is a refused list (null) before the
    # receiver sees it, and no call-site sabotage can reopen that loss. YJ measures the reading.
    dict(
        id="YJ",
        what="the per-field reader turns lenient about enum names — an archetype name this node "
             "does not know reads as a null element instead of a refusal, and a list of unknown "
             "names excludes every archetype (the silent loss YG's relaxation must not reopen)",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='            return MAPPER.convertValue(alone, ImportProfileDefinition.class);',
        # Jackson 3 keeps this on EnumFeature (a DatatypeFeature), not DeserializationFeature;
        # the compile-check caught the first spelling before the sweep could die on it.
        replace='            return MAPPER.rebuild().enable(tools.jackson.databind.cfg.EnumFeature'
                '.READ_UNKNOWN_ENUM_VALUES_AS_NULL).build()'
                '.convertValue(alone, ImportProfileDefinition.class);',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aMiscasedArchetypeNameMakesTheRowUnreadableAndItAddressesEveryArchetype'],
    ),
    dict(
        id="YK",
        what="the archetype list is not carried on the uninterpretable row — the receiver "
             "is back to judging on the name alone",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='                archetypesOnly == null ? null : archetypesOnly.getAllowedArchetypes(),',
        replace='                null,',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['theOwnedListingSeesEveryOwnedRowAndReportsTheRest',
                     'aRowWhoseConnectorFieldsHaveNoReadableShapeIsNotAddressedToAnArchetypeItExcludes',
                     'aNullElementInTheArchetypeListReadsAsTheMapperReadsIt'],
    ),
    dict(
        id="YL",
        what="a connector with no archetype is admitted by a restricting list — the readable "
             "path's isArchetypeAllowed admits it by none",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionService.java',
        find='            return reading.isArchetypeAllowed(archetype);',
        replace='            return archetype == null || reading.isArchetypeAllowed(archetype);',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['theOwnedListingSeesEveryOwnedRowAndReportsTheRest'],
    ),
    dict(
        id="YP",
        what="a connector id the mapper coerces (42 → \"42\") is called unreadable again — the "
             "hand-rolled 'a String, or nothing' reading, which made such a row name every "
             "connector",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='                connectorsOnly == null, reason));',
        replace='                connectorsOnly == null || !(props.get("defaultConnectorId") == null'
                ' || props.get("defaultConnectorId") instanceof String), reason));',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aNumericConnectorIdReadsAsTheMapperReadsIt'],
    ),
    dict(
        id="YQ",
        what="a list with a null element is refused before the mapper sees it — the hand-rolled "
             "'list of strings' reading, which admitted every archetype for a list that plainly "
             "excludes one",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='        try {\n'
             '            return MAPPER.convertValue(alone, ImportProfileDefinition.class);',
        replace='        for (Object v : alone.values()) {\n'
                '            if (v instanceof List<?> l && l.stream().anyMatch(java.util.Objects::isNull)) {\n'
                '                return null;\n'
                '            }\n'
                '        }\n'
                '        try {\n'
                '            return MAPPER.convertValue(alone, ImportProfileDefinition.class);',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aNullElementInTheArchetypeListReadsAsTheMapperReadsIt'],
    ),
    dict(
        id="YR",
        what="the profile services' disabled check goes back to the literal and the string by "
             "hand — an explicit null, which the mapper reads as false, is a possible recipient "
             "again (and the auto-resolver refuses on it again)",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='        ImportProfileDefinition enabledOnly = readAlone(props, "enabled");\n'
             '        return enabledOnly != null && !enabledOnly.isEnabled();',
        replace='        Object enabled = props.get("enabled");\n'
                '        return Boolean.FALSE.equals(enabled) || "false".equalsIgnoreCase(String.valueOf(enabled));',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aRowWhoseEnabledIsAnExplicitNullIsNotARecipient',
                     'aDisabledByNullRowTheResolverCannotReadDoesNotRefuseTheResolve',
                     'whichValuesOfADisabledFlagCountIsTheMappersAnswer'],
    ),
    dict(
        id="YS",
        what="the uniqueness listing's identity check goes back to 'a String, or nothing' — a "
             "profileId the mapper coerces (42 → \"42\") refuses every write of its repository "
             "again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='                ImportProfileDefinition idOnly = readAlone(props, "profileId");\n'
             '                if (idOnly == null || idOnly.getProfileId() == null\n'
             '                        || idOnly.getProfileId().isBlank()) {',
        replace='                Object pid = props.get("profileId");\n'
                '                if (!(pid instanceof String) || ((String) pid).isBlank()) {',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aNumericProfileIdIsAnIdentityForTheUniquenessListing',
                     'aNumericLegacyRowIsATwinForTheCreateOfItsStringId'],
    ),
    dict(
        id="YT",
        what="the connector resolution's disabled check goes back to the literal and the string "
             "by hand — a disabled-by-null row this node cannot read refuses the whole "
             "resolution again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        # Re-anchored in round 17 of the second batch (readsDisabled goes through readAlone).
        find='        return readAlone(props, "enabled") instanceof ConnectorDefinition read && !read.isEnabled();',
        replace='        return Boolean.FALSE.equals(props.get("enabled"))'
                ' || "false".equalsIgnoreCase(String.valueOf(props.get("enabled")));',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['aDisabledByNullRowTheResolverCannotReadDoesNotRefuseTheResolution'],
    ),
    dict(
        id="ZY",
        what="a value the mapper REFUSES for enabled is read as 'disabled' — a connector row "
             "this node cannot read is skipped instead of refusing the resolution",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find='        return readAlone(props, "enabled") instanceof ConnectorDefinition read && !read.isEnabled();',
        replace='        ConnectorDefinition read = readAlone(props, "enabled");\n'
                '        return read == null || !read.isEnabled();',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['aMatchingRowWhoseEnabledIsAStoredNumberStillRefusesTheResolution'],
    ),
    dict(
        id="ZZ",
        what="ZY's profile twin: an enabled the mapper refuses counts as disabled, so a row "
             "this node cannot read is silently not a recipient",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='        ImportProfileDefinition enabledOnly = readAlone(props, "enabled");\n'
             '        return enabledOnly != null && !enabledOnly.isEnabled();',
        replace='        ImportProfileDefinition enabledOnly = readAlone(props, "enabled");\n'
                '        return enabledOnly == null || !enabledOnly.isEnabled();',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['whichValuesOfADisabledFlagCountIsTheMappersAnswer'],
    ),
    dict(
        id="ZU",
        what="a rewritten row is not counted — a pass that only normalised is summarised by the "
             "startup patch as 'no legacy rows', which reads as 'nothing was touched'",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='            result.normalised++;\n',
        replace='',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aRowAlreadyAtItsDeterministicIdIsNormalisedInPlace'],
    ),
    dict(
        id="ZV",
        what="the connector rewrite is not counted — ZU's twin",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find='            result.normalised++;\n',
        replace='',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['aRowAlreadyAtItsDeterministicIdIsNormalisedInPlace'],
    ),
    dict(
        id="ZW",
        what="the startup patch's quiet summary stops counting normalised rows — a pass that "
             "rewrote rows is logged as 'no legacy rows'",
        file='core/src/main/java/jp/aegif/nemaki/patch/Patch_ConnectorDefinitionDeterministicIds.java',
        find=' && result.normalised == 0',
        replace='',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['thePatchSummaryDoesNotCallANormalisingPassEmpty'],
    ),
    dict(
        id="ZX",
        what="the connector rewrite's failure arm is swallowed — ZT's twin",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find='        } catch (RuntimeException rowFailed) {\n'
             '            result.failures.add(id + " (its stored connectorId is not the string \\"" + connectorId\n'
             '                    + "\\", which the Mango selector cannot match, and rewriting it failed: "\n'
             '                    + rowFailed.getMessage() + ")");\n'
             '        }',
        replace='        } catch (RuntimeException rowFailed) {\n'
                '            logger.debug("rewrite of {} failed: {}", id, rowFailed.getMessage());\n'
                '        }',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['aRefusedRewriteIsReported'],
    ),
    dict(
        id="ZP",
        what="the in-place rewrite stops refusing a row that carries attachments — the binaries "
             "the row is the only holder of are destroyed by a pass that reports clean",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find_span=('        if (row.getAttachments() != null && !row.getAttachments().isEmpty()) {',
                   '                    + " rewritten; updates of this profile answer 503 until it is repaired", id);\n            return;\n        }'),
        replace='',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aRowWithAttachmentsIsNotRewrittenInPlace'],
    ),
    dict(
        id="ZQ",
        what="the connector in-place rewrite stops refusing a row with attachments — ZP's twin",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find_span=('        if (row.getAttachments() != null && !row.getAttachments().isEmpty()) {',
                   '                    + " rewritten; updates of this connector answer 503 until it is repaired", id);\n            return;\n        }'),
        replace='',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['aRowWithAttachmentsIsNotRewrittenInPlace'],
    ),
    dict(
        id="ZR",
        what="the migration's comparison stops reading each row's OWN identity — a foreign row "
             "occupying the deterministic id compares equal to the legacy one, and the only row "
             "defining the profile is retired as its duplicate",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='        ImportProfileDefinition idOnly = readAlone(props, "profileId");\n'
             '        if (idOnly != null && idOnly.getProfileId() != null) {\n'
             '            content.put("profileId", idOnly.getProfileId());\n'
             '        }\n',
        replace='        content.put("profileId", "");\n',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aForeignRowOnTheDeterministicIdIsStillDivergent'],
    ),
    dict(
        id="ZS",
        what="the connector migration's comparison stops reading each row's own identity — "
             "ZR's twin",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find='        ConnectorDefinition idOnly = readAlone(props, "connectorId");\n'
             '        if (idOnly != null && idOnly.getConnectorId() != null) {\n'
             '            content.put("connectorId", idOnly.getConnectorId());\n'
             '        }\n',
        replace='        content.put("connectorId", "");\n',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['aForeignRowOnTheDeterministicIdIsStillDivergent'],
    ),
    dict(
        id="ZT",
        what="a rewrite the store refuses is swallowed — the pass reports clean while the row "
             "still cannot be matched by the selector",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='        } catch (RuntimeException rowFailed) {\n'
             '            result.failures.add(id + " (its stored profileId is not the string \\"" + profileId\n'
             '                    + "\\", which the Mango selector cannot match, and rewriting it failed: "\n'
             '                    + rowFailed.getMessage() + ")");\n'
             '        }',
        replace='        } catch (RuntimeException rowFailed) {\n'
                '            logger.debug("rewrite of {} failed: {}", id, rowFailed.getMessage());\n'
                '        }',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aRefusedRewriteIsReported'],
    ),
    dict(
        id="ZJ",
        what="the migration compares raw contents again — an interrupted normalising pass calls "
             "an identical pair divergent and never retires the legacy row",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        # Re-anchored in round 19 (each side is now read on its own).
        find='            } else if (!java.util.Objects.equals(\n'
             '                    normalisedContent(legacy.getProperties()),\n'
             '                    normalisedContent(existing.getProperties()))) {',
        replace='            } else if (!java.util.Objects.equals(contentOnly(legacy.getProperties()),\n'
                '                    contentOnly(existing.getProperties()))) {',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['anInterruptedNormalisingMigrationRetiresTheLegacyRowOnTheNextPass'],
    ),
    dict(
        id="ZK",
        what="a row already at its deterministic id is passed over without looking at its stored "
             "identity — the normalisation stays conditional on the row being under a legacy id",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='                if (!profileId.equals(props.get("profileId"))) {\n'
             '                    unnormalised.put(id, doc);\n'
             '                }\n',
        replace='',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aRowAlreadyAtItsDeterministicIdIsNormalisedInPlace',
                     'aRowWithAttachmentsIsNotRewrittenInPlace',
                     'aRefusedRewriteIsReported'],
    ),
    dict(
        id="ZL",
        what="the owned-row lookup goes back to null-or-blank — a row whose repositoryId is not "
             "a string is handed back as owned, and IDLE starts a capture on it",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='            if (!definesProfile(props, profileId)\n'
             '                    || namesNoRepository(props.get("repositoryId"))) {',
        replace='            if (!definesProfile(props, profileId)\n'
                '                    || props.get("repositoryId") == null\n'
                '                    || (props.get("repositoryId") instanceof String blank && blank.isBlank())) {',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aRowWhoseRepositoryIdIsNotAStringIsNotOwned'],
    ),
    dict(
        id="ZM",
        what="the shared owned-row walk goes back to null-or-blank — a row that names no "
             "repository becomes a webhook recipient and a scheduled capture again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='                    || namesNoRepository(props.get("repositoryId"))) {\n'
             '                return;\n'
             '            }',
        replace='                    || props.get("repositoryId") == null\n'
                '                    || (props.get("repositoryId") instanceof String blank && blank.isBlank())) {\n'
                '                return;\n'
                '            }',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['theOwnedListingSkipsARowWhoseRepositoryIdIsNotAString'],
    ),
    dict(
        id="ZN",
        what="the connector migration compares raw contents again — ZJ's twin",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        # Re-anchored in round 19 (each side is now read on its own).
        find='            } else if (!java.util.Objects.equals(\n'
             '                    normalisedContent(legacy.getProperties()),\n'
             '                    normalisedContent(existing.getProperties()))) {',
        replace='            } else if (!java.util.Objects.equals(contentOnly(legacy.getProperties()),\n'
                '                    contentOnly(existing.getProperties()))) {',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['anInterruptedNormalisingMigrationRetiresTheLegacyRowOnTheNextPass'],
    ),
    dict(
        id="ZO",
        what="a connector row already at its deterministic id is passed over without looking at "
             "its stored identity — ZK's twin",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find='                if (!connectorId.equals(props.get("connectorId"))) {\n'
             '                    unnormalised.put(id, doc);\n'
             '                }\n',
        replace='',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['aRowAlreadyAtItsDeterministicIdIsNormalisedInPlace',
                     'aRowWithAttachmentsIsNotRewrittenInPlace',
                     'aRefusedRewriteIsReported'],
    ),
    dict(
        id="YZ",
        what="the plain delete's walk compares the raw profileId again — a row the listing "
             "calls \"42\" survives a delete that reports success",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='                if (definesProfile(props, profileId)\n'
             '                        && repositoryId != null && repositoryId.equals(props.get("repositoryId"))) {\n'
             '                    targets.add(doc);',
        replace='                if (ImportProfileDefinition.DOC_TYPE.equals(props.get("type"))\n'
                '                        && profileId.equals(props.get("profileId"))\n'
                '                        && repositoryId != null && repositoryId.equals(props.get("repositoryId"))) {\n'
                '                    targets.add(doc);',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['thePlainDeleteRemovesARowWhoseProfileIdIsTheNumber'],
    ),
    dict(
        id="ZA",
        what="the row-addressed delete compares the raw profileId again — the repair its own "
             "409 prescribes is refused for a row the listing calls \"42\"",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='        if (!definesProfile(props, profileId)\n'
             '                || repositoryId == null\n'
             '                || !(unowned || repositoryId.equals(props.get("repositoryId")))) {',
        replace='        if (props == null\n'
                '                || !ImportProfileDefinition.DOC_TYPE.equals(props.get("type"))\n'
                '                || !profileId.equals(props.get("profileId"))\n'
                '                || repositoryId == null\n'
                '                || !(unowned || repositoryId.equals(props.get("repositoryId")))) {',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['theOneRowDeleteAcceptsARowWhoseProfileIdIsTheNumber'],
    ),
    dict(
        id="ZB",
        what="the startup migration classifies the identity raw again — a row the duplicate "
             "check counts as \"42\" is reported as having no usable profileId",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='            ImportProfileDefinition idOnly = readAlone(props, "profileId");\n'
             '            String profileId = idOnly == null ? null : idOnly.getProfileId();',
        replace='            Object pid = props.get("profileId");\n'
                '            String profileId = pid instanceof String ? (String) pid : null;',
        test='ImportProfileLegacyIdMigrationTest',
        # aRowWithAttachmentsIsNotRewrittenInPlace / aRefusedRewriteIsReported were EXCLUDED
        # here: under the raw read those rows are reported as "no usable profileId" with the
        # same id in the failure, which was all both locks asserted, so they stayed green. A
        # review caught that over-declaration (the runner scores it WRONG TEST FIRED); an
        # audit then caught the locks themselves — asserting an id and not a reason, they
        # could not tell their own branch from an unrelated one. Both now assert the reason,
        # so both belong here.
        expect_fail=['theMigrationNormalisesAProfileIdTheMapperCoerces',
                     'anInterruptedNormalisingMigrationRetiresTheLegacyRowOnTheNextPass',
                     'aRowAlreadyAtItsDeterministicIdIsNormalisedInPlace',
                     'aForeignRowOnTheDeterministicIdIsStillDivergent',
                     'aRowWithAttachmentsIsNotRewrittenInPlace',
                     'aRefusedRewriteIsReported',
                     'aDivergentPairsDeterministicRowIsNotNormalised',
                     'twoLegacyRowsLeaveTheDeterministicRowAlone'],
    ),
    dict(
        id="ZC",
        what="the migrated copy keeps the stored number as its profileId — the type-strict "
             "Mango selector can never match it, and the profile stays un-writable",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='                copy.put("profileId", profileId);\n',
        replace='',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['theMigrationNormalisesAProfileIdTheMapperCoerces'],
    ),
    dict(
        id="ZD",
        what="the connector plain delete compares the raw connectorId again — DELETE "
             ".../connectors/42 answers success while the row stays",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find='                if (definesConnector(props, connectorId)) {\n'
             '                    targets.add(doc);',
        replace='                if (ConnectorDefinition.DOC_TYPE.equals(props.get("type"))\n'
                '                        && connectorId.equals(props.get("connectorId"))) {\n'
                '                    targets.add(doc);',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['thePlainDeleteRemovesARowWhoseConnectorIdIsTheNumber'],
    ),
    dict(
        id="ZE",
        what="the connector uniqueness count compares the raw connectorId again — a create of "
             "\"42\" writes a twin beside the legacy row the listings call \"42\"",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find='            if (definesConnector(props, connectorId)) {\n'
             '                found[0]++;',
        replace='            if (ConnectorDefinition.DOC_TYPE.equals(props.get("type"))\n'
                '                    && connectorId.equals(props.get("connectorId"))) {\n'
                '                found[0]++;',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['aNumericLegacyRowIsCountedByTheCreateOfItsStringId',
                     'theOneRowDeleteAcceptsARowWhoseConnectorIdIsTheNumber'],
    ),
    dict(
        id="ZF",
        what="the connector migration classifies the identity raw again — ZB's connector twin",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find='            ConnectorDefinition idOnly = readAlone(props, "connectorId");\n'
             '            String connectorId = idOnly == null ? null : idOnly.getConnectorId();',
        replace='            Object cid = props.get("connectorId");\n'
                '            String connectorId = cid instanceof String ? (String) cid : null;',
        test='ConnectorLegacyIdMigrationTest',
        # ZB's reasoning, connector side: the two locks that asserted only a doc id were
        # excluded here and now assert their reason, so they belong in the list.
        expect_fail=['theMigrationNormalisesAConnectorIdTheMapperCoerces',
                     'anInterruptedNormalisingMigrationRetiresTheLegacyRowOnTheNextPass',
                     'aRowAlreadyAtItsDeterministicIdIsNormalisedInPlace',
                     'aForeignRowOnTheDeterministicIdIsStillDivergent',
                     'aRowWithAttachmentsIsNotRewrittenInPlace',
                     'aRefusedRewriteIsReported',
                     'aDivergentPairsDeterministicRowIsNotNormalised',
                     'twoLegacyRowsLeaveTheDeterministicRowAlone'],
    ),
    dict(
        id="ZG",
        what="the migrated connector copy keeps the stored number as its connectorId — ZC's twin",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find='                copy.put("connectorId", connectorId);\n',
        replace='',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['theMigrationNormalisesAConnectorIdTheMapperCoerces'],
    ),
    dict(
        id="ZH",
        what="'names no repository' goes back to null-or-blank — a row whose repositoryId is "
             "not a string is refused by the very DELETE the migration's message prescribes",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='        boolean unowned = props != null && namesNoRepository(props.get("repositoryId"));',
        replace='        boolean unowned = props != null && (props.get("repositoryId") == null\n'
                '                || (props.get("repositoryId") instanceof String blank && blank.isBlank()));',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['theOneRowDeleteReachesARowWhoseRepositoryIdIsNotAString'],
    ),
    dict(
        id="ZI",
        what="the connector row-addressed delete compares the raw connectorId again — ZA's twin",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find='        Map<String, Object> props = row != null ? row.getProperties() : null;\n'
             '        if (!definesConnector(props, connectorId)) {',
        replace='        Map<String, Object> props = row != null ? row.getProperties() : null;\n'
                '        if (props == null\n'
                '                || !ConnectorDefinition.DOC_TYPE.equals(props.get("type"))\n'
                '                || !connectorId.equals(props.get("connectorId"))) {',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['theOneRowDeleteAcceptsARowWhoseConnectorIdIsTheNumber'],
    ),
    dict(
        id="YU",
        what="the count that stops a twin compares the raw profileId again — a legacy row whose "
             "id is the number 42 is invisible to the create of \"42\", and the twin is written",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='            if (definesProfile(props, profileId)\n'
             '                    && (repositoryId == null || repositoryId.equals(props.get("repositoryId")))) {',
        replace='            if (ImportProfileDefinition.DOC_TYPE.equals(props.get("type"))\n'
                '                    && profileId.equals(props.get("profileId"))\n'
                '                    && (repositoryId == null || repositoryId.equals(props.get("repositoryId")))) {',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aNumericLegacyRowIsATwinForTheCreateOfItsStringId',
                     'theOneRowDeleteAcceptsARowWhoseProfileIdIsTheNumber'],
    ),
    dict(
        id="YV",
        what="getForRepository compares the raw profileId again — the row the listing calls "
             "\"42\" cannot be looked up by that id",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='            if (!definesProfile(props, profileId)\n'
             '                    || !repositoryId.equals(props.get("repositoryId"))) {',
        replace='            if (!ImportProfileDefinition.DOC_TYPE.equals(props.get("type"))\n'
                '                    || !repositoryId.equals(props.get("repositoryId"))\n'
                '                    || !profileId.equals(props.get("profileId"))) {',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['getForRepositoryReadsANumericProfileIdAsTheMapperReadsIt'],
    ),
    dict(
        id="YW",
        what="the shared identity check compares the raw value again — every count, lookup and "
             "delete disagrees with the listing at once",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='        return idOnly != null && profileId.equals(idOnly.getProfileId());',
        replace='        return profileId.equals(props.get("profileId"));',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aNumericLegacyRowIsATwinForTheCreateOfItsStringId',
                     'getForRepositoryReadsANumericProfileIdAsTheMapperReadsIt',
                     'getOwnedRowIndexFreeReadsANumericProfileIdAsTheMapperReadsIt',
                     'theDeterministicIdReadAcceptsARowWhoseProfileIdIsTheNumber',
                     'thePlainDeleteRemovesARowWhoseProfileIdIsTheNumber',
                     'theOneRowDeleteAcceptsARowWhoseProfileIdIsTheNumber'],
    ),
    dict(
        id="YX",
        what="getOwnedRowIndexFree compares the raw profileId again — YV's twin on the owned-row "
             "walk",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='            if (!definesProfile(props, profileId)\n'
             '                    || namesNoRepository(props.get("repositoryId"))) {',
        replace='            if (!ImportProfileDefinition.DOC_TYPE.equals(props.get("type"))\n'
                '                    || !profileId.equals(props.get("profileId"))\n'
                '                    || namesNoRepository(props.get("repositoryId"))) {',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['getOwnedRowIndexFreeReadsANumericProfileIdAsTheMapperReadsIt'],
    ),
    dict(
        id="YY",
        what="the deterministic-id read disowns a row whose profileId is the number again — "
             "get() null for a row every walk sees",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='            if (!definesProfile(props, profileId)) {',
        replace='            if (props == null\n'
                '                    || !ImportProfileDefinition.DOC_TYPE.equals(props.get("type"))\n'
                '                    || !profileId.equals(props.get("profileId"))) {',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['theDeterministicIdReadAcceptsARowWhoseProfileIdIsTheNumber'],
    ),
    dict(
        id="YM",
        what="the receiver refuses on unreadable connector fields before looking at a readable "
             "archetype list that plainly excludes the connector — the fold's over-throw, at "
             "the call site",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestWebhookController.java',
        find='            if (broken.addressedTo(connId, connector.getSourceArchetype())) {',
        replace='            if (broken.addresseeUnknown() || broken.addressedTo(connId, connector.getSourceArchetype())) {',
        test='IngestWebhookBoxDropboxTest',
        expect_fail=['aBrokenRowWhoseConnectorFieldsCannotBeReadButWhoseArchetypesExcludeTheConnectorDoesNotStopTheDispatch'],
    ),
    dict(
        id="YN",
        what="the archetype field's shape is folded into addresseeUnknown again — a row naming "
             "someone else stops every connector's dispatch (the regression cc97f50c5 carried)",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='                connectorsOnly == null, reason));',
        replace='                connectorsOnly == null || archetypesOnly == null, reason));',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aRowWhoseArchetypeFieldHasNoReadableShapeAdmitsEveryArchetypeButNamesOnlyItsConnectors',
                     'aMiscasedArchetypeNameMakesTheRowUnreadableAndItAddressesEveryArchetype'],
    ),
    dict(
        id="YO",
        what="admitsArchetype short-circuits on the connector flag again — YM's helper twin, "
             "measured at the record",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionService.java',
        find='        public boolean admitsArchetype(SourceArchetype archetype) {\n'
             '            ImportProfileDefinition reading = new ImportProfileDefinition();',
        replace='        public boolean admitsArchetype(SourceArchetype archetype) {\n'
                '            if (addresseeUnknown) {\n'
                '                return true;\n'
                '            }\n'
                '            ImportProfileDefinition reading = new ImportProfileDefinition();',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aRowWhoseConnectorFieldsHaveNoReadableShapeIsNotAddressedToAnArchetypeItExcludes'],
    ),
    dict(
        id="XR",
        what="the over-throw twin of XI: a genuinely absent connector refuses whenever the read "
             "is the refusing one — every 401 becomes a 503",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find='                if (refuseUnanswered && !selectorAnswered) {',
        replace='                if (refuseUnanswered) {',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['getOrRefuseAnswersNullWhenBothReadsAnswerAbsent'],
    ),
    dict(
        id="XS",
        what="the over-throw twin of XC: get() refuses a failed id read — its callers gain an "
             "exception path this batch did not change them for",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find='            if (refuseUnanswered) {',
        replace='            if (true) {',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['getStillAnswersNullWhenTheIdReadFails'],
    ),
    dict(
        id="XT",
        what="the over-throw twin of XL: get() refuses a selector row it cannot read instead of "
             "skipping it",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find='                    "connectorId", connectorId), refuseUnanswered);',
        replace='                    "connectorId", connectorId), true);',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['getStillSkipsARowTheSelectorCannotRead'],
    ),
    dict(
        id="XU",
        what="the over-throw twin of WN: every broken row stops every dispatch, whichever "
             "connector it names",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestWebhookController.java',
        find='            if (broken.addressedTo(connId, connector.getSourceArchetype())) {',
        replace='            if (true) {',
        test='IngestWebhookBoxDropboxTest',
        expect_fail=['aBrokenRowOfAnotherConnectorDoesNotStopTheDispatch',
                     'aBrokenRowWhoseArchetypesExcludeTheConnectorDoesNotStopTheDispatch',
                     'aBrokenRowWhoseConnectorFieldsCannotBeReadButWhoseArchetypesExcludeTheConnectorDoesNotStopTheDispatch'],
    ),
    dict(
        id="XV",
        what="the capture record drops the unanswered-check detail — 'succeeded' alone, as the "
             "ordinary case reads",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find='                captureScope.record("createRelationship", MutationOutcome.SUCCEEDED,\n'
             '                        unansweredCheck);',
        replace='                captureScope.record("createRelationship", MutationOutcome.SUCCEEDED);',
        test='CanonicalImportServiceTest',
        expect_fail=['theCaptureRecordCarriesTheUnansweredCheck'],
    ),
    dict(
        id="XM",
        what="the repository listing skips only the literal false again — a row disabled by the "
             "string \"false\" that the node cannot read refuses the whole auto-resolution",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        # Re-anchored in round 16 of the second batch (the mapper reads the field).
        find='                if (readsDisabled(props)) {\n'
             '                    return;\n'
             '                }\n'
             '                // After the disabled skip, not before it:',
        replace='                if (Boolean.FALSE.equals(props.get("enabled"))) {\n'
                '                    return;\n'
                '                }\n'
                '                // After the disabled skip, not before it:',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aDisabledByStringRowTheResolverCannotReadDoesNotRefuseTheResolve',
                     'aDisabledByNullRowTheResolverCannotReadDoesNotRefuseTheResolve'],
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
    dict(
        id="QJ",
        what="the normalising pass runs over a divergent pair's deterministic row again — "
             "'neither row is touched' stops being true for the connector migration",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find='            if (result.divergent.size() > divergentBefore) {\n'
             '                // The legacy row and the deterministic row disagree. Same rule as above: the\n'
             '                // pass below must not rewrite the row the operator is comparing.\n'
             '                unnormalised.remove(deterministicId);\n'
             '            }\n',
        replace='',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['aDivergentPairsDeterministicRowIsNotNormalised'],
    ),
    dict(
        id="QM",
        what="the same, for the arm that reports TWO legacy rows — the divergence decided "
             "before the copy step is ever reached",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java',
        find='                unnormalised.remove(deterministicId);\n'
             '                continue;\n',
        replace='                continue;\n',
        test='ConnectorLegacyIdMigrationTest',
        expect_fail=['twoLegacyRowsLeaveTheDeterministicRowAlone'],
    ),
    dict(
        id="QN",
        what="QJ's profile twin — the normalising pass rewrites one member of a divergent pair",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='            if (result.divergent.size() > divergentBefore) {\n'
             '                // The legacy row and the deterministic row disagree. Same rule as above: the\n'
             '                // pass below must not rewrite the row the operator is comparing.\n'
             '                unnormalised.remove(deterministicId);\n'
             '            }\n',
        replace='',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['aDivergentPairsDeterministicRowIsNotNormalised'],
    ),
    dict(
        id="QO",
        what="QM's profile twin — the TWO-legacy-rows arm stops skipping the normalising pass",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='                unnormalised.remove(deterministicId);\n'
             '                continue;\n',
        replace='                continue;\n',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['twoLegacyRowsLeaveTheDeterministicRowAlone'],
    ),
    dict(
        id="QZ",
        what="the checkpoint reset calls a pass complete again — the profile row that names "
             "the scoped keys was never read, and 'All checkpoints reset' was the answer",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CheckpointManager.java',
        find='        boolean profileRowRead = profile != null;',
        replace='        boolean profileRowRead = true;',
        test='CheckpointManagerTest',
        expect_fail=['aResetThatCouldNotNameTheScopedKeysDoesNotReportAll'],
    ),
    dict(
        id="QP2",
        what="a connector read that FAILED becomes 'no connector context' again, and the "
             "import flow is picked from the file name",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ExternalIngestController.java',
        find='        } catch (RuntimeException lookupFailed) {\n',
        replace='        } catch (RuntimeException lookupFailed) {\n'
                '            if (true) return null;\n',
        test='ExternalIngestControllerGateTest',
        expect_fail=['aFailedConnectorReadDoesNotPickTheImportFlowFromTheFileName'],
    ),
    dict(
        id="QQ2",
        what="a connector row the index cannot show becomes 'no connector context' again — "
             "QP's other half, the one get() cannot tell from absence",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ExternalIngestController.java',
        find='        // fall through to the heuristics.\n'
             '        if (connectorDefinitionService.existsIndexFree(connectorId)) {\n',
        replace='        // fall through to the heuristics.\n'
                '        if (false) {\n',
        test='ExternalIngestControllerGateTest',
        expect_fail=['aHiddenConnectorRowDoesNotPickTheImportFlowFromTheFileName'],
    ),
    dict(
        id="QR2",
        what="the multipart door swallows a typed read refusal as 400 'Invalid request' again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ExternalIngestController.java',
        find='        } catch (ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException\n'
             '                | ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException\n'
             '                | ConnectorArchetypeUnusableException refused) {\n',
        replace='        } catch (ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException\n'
                '                | ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException\n'
                '                | ConnectorArchetypeUnusableException refused) {\n'
                '            if (true) return ResponseEntity.status(HttpStatus.BAD_REQUEST)\n'
                '                    .body(ExternalIngestResult.error("unknown", "Invalid request"));\n',
        test='ExternalIngestControllerGateTest',
        expect_fail=['theMultipartDoorAnswersTheSameRefusalAsTheJsonDoor'],
    ),
    dict(
        id="QS2",
        what="the DLQ retry swallows a typed read refusal as 500 'Retry failed' again, which "
             "is what made its @ExceptionHandler unreachable",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestDlqController.java',
        find='        } catch (ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException\n'
             '                | ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException refused) {\n',
        replace='        } catch (ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException\n'
                '                | ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException refused) {\n'
                '            if (true) return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR,\n'
                '                    "Retry failed: " + refused.getMessage());\n',
        test='DlqReplayArchetypeGateTest',
        expect_fail=['aRetryWhoseConnectorCannotBeReadIsNotOurBug'],
    ),
    dict(
        id="QT2",
        what="the IDLE endpoint goes back to a binary split — a standing twin pair and an "
             "established absence both answer 400 again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerController.java',
        find='        if (message.contains("more than one definition row")',
        replace='        if (false && message.contains("more than one definition row")',
        test='IngestSchedulerControllerAnswerTest',
        expect_fail=['aStandingPairIsA409'],
    ),
    dict(
        id="QU2",
        what="QT2's other half — the 404 arm for an absence the read established",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerController.java',
        find='        if (message.startsWith("Profile not found")\n',
        replace='        if (false\n',
        test='IngestSchedulerControllerAnswerTest',
        # The second name is from the measurement: both locks drive the same 404 arm.
        expect_fail=['absenceIsA404AndABadSettingIsStillA400',
                     'absenceAfterTheFactIsA404AndTheIdCannotSteerTheStatus'],
    ),
    dict(
        id="QV2",
        what="the reset endpoint reads a BLANK scope as one named scope again, while the "
             "manager reads it as 'reset everything'",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerController.java',
        find='        if (scope != null && !scope.isBlank()) {\n'
             '            response.put("message", "Checkpoint reset for "',
        replace='        if (scope != null) {\n'
                '            response.put("message", "Checkpoint reset for "',
        test='IngestSchedulerControllerAnswerTest',
        expect_fail=['aBlankScopeIsNotOneNamedScope'],
    ),
    dict(
        id="QW2",
        what="the named-scope reset claims the profile row took part again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CheckpointManager.java',
        find='            return new ResetSummary(1, false);',
        replace='            return new ResetSummary(1, true);',
        test='CheckpointManagerTest',
        expect_fail=['resetCheckpoint_specificScope'],
    ),
    dict(
        id="QX2",
        what="the cross-repository divergence arm stops skipping the normalising pass — the "
             "third of the profile migration's three divergence arms",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java',
        find='                    unnormalised.remove(deterministicId);\n'
             '                    continue;\n',
        replace='                    continue;\n',
        test='ImportProfileLegacyIdMigrationTest',
        expect_fail=['legacyRowsInDifferentRepositoriesLeaveTheDeterministicRowAlone'],
    ),
    dict(
        id="QY2",
        what="a connector row that was READ but says no archetype becomes 'no connector "
             "context' again — the other arm of QP2's hole",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ExternalIngestController.java',
        find='                if (connector.getSourceArchetype() == null) {\n',
        replace='                if (false) {\n',
        test='ExternalIngestControllerGateTest',
        # Completed from MEASUREMENT, not from reading. These locks were added to the
        # same test class AFTER this control was written, and a full sweep would have
        # exited non-zero listing them. A review enumerated the whole suite for this
        # shape rather than one round at a time.
        expect_fail=['aConnectorRowWithNoArchetypeDoesNotPickTheFlowFromTheFileName',
                     'aDelegatedIngestRefusedByAReadIsStillAudited'],
    ),
    dict(
        id="QZ2",
        what="the IDLE STOP endpoint goes back to a fixed 400, so an unwired node is a bad "
             "request again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerController.java',
        find='            // Same classifier as the start: an unwired node is not a bad request here either.\n'
             '            return ResponseEntity.status(statusOfIdleRefusal(error, profileId)).body(response);\n',
        replace='            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);\n',
        test='IngestSchedulerControllerAnswerTest',
        # The second name is from the measurement: that lock drives the stop endpoint too.
        expect_fail=['stopClassifiesLikeStart', 'anUnwiredNodeSaysSoPerVerb'],
    ),
    dict(
        id="RA2",
        what="the unwired IDLE monitor answers 'ImapIdleMonitor not available' again — a "
             "deployment fault with no marker, which the endpoint reads as a bad request",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerService.java',
        find='    private static String idleNotWired(String verb) {\n'
             '        return "the IMAP IDLE monitor is not wired on this node, so IDLE could not be " + verb\n'
             '                + "; retry shortly against a node that runs it";\n',
        replace='    private static String idleNotWired(String verb) {\n'
                '        if (true) return "ImapIdleMonitor not available";\n'
                '        return "the IMAP IDLE monitor is not wired on this node, so IDLE could not be " + verb\n'
                '                + "; retry shortly against a node that runs it";\n',
        test='IngestSchedulerControllerAnswerTest',
        expect_fail=['anUnwiredNodeSaysSoPerVerb'],
    ),
    dict(
        id="RB2",
        what="a session that is already running is a bad request again, where every other "
             "standing conflict on this endpoint answers 409",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerController.java',
        find='        if (message.startsWith("IDLE already running")) {\n',
        replace='        if (false) {\n',
        test='IngestSchedulerControllerAnswerTest',
        expect_fail=['aStandingPairIsA409'],
    ),
    dict(
        id="RC2",
        what="an unwired scheduled listing answers 'nothing is scheduled' again, which the "
             "poll's own comment says cannot happen",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerService.java',
        find='    public ImportProfileDefinitionService.OwnedProfiles scheduledProfilesWithUnreadable() {\n'
             '        if (repositoryInfoMap == null || profileService == null) {\n',
        replace='    public ImportProfileDefinitionService.OwnedProfiles scheduledProfilesWithUnreadable() {\n'
                '        if (repositoryInfoMap == null || profileService == null) {\n'
                '            if (true) return new ImportProfileDefinitionService.OwnedProfiles(\n'
                '                    List.of(), List.of());\n',
        test='IngestSchedulerControllerAnswerTest',
        expect_fail=['theEndpointListingRefusesWhenUnwired'],
    ),
    dict(
        id="RD2",
        what="an UNWIRED connector service becomes 'no connector context' again — the third "
             "arm of the archetype hole, found after the first two were closed",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ExternalIngestController.java',
        find='        if (connectorDefinitionService == null) {\n',
        replace='        if (false) {\n',
        test='ExternalIngestControllerGateTest',
        expect_fail=['anUnwiredConnectorServiceDoesNotPickTheFlowFromTheFileName'],
    ),
    dict(
        id="RE2",
        what="a delegated ingest refused by a read leaves no audit entry again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ExternalIngestController.java',
        find='            if (delegatedRequest) {\n'
             '                auditDelegatedAttempt(callContext, repositoryId, request, false,\n'
             '                        refused.getMessage(), DenialReason.SERVICES_UNAVAILABLE);\n'
             '            }\n',
        replace='',
        test='ExternalIngestControllerGateTest',
        expect_fail=['aDelegatedIngestRefusedByAReadIsStillAudited'],
    ),
    dict(
        id="RF2",
        what="an authorisation denial on the IDLE endpoint is a malformed request again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerController.java',
        find='        if (message.startsWith("Delegated authorization denied")) {\n',
        replace='        if (false) {\n',
        test='IngestSchedulerControllerAnswerTest',
        expect_fail=['wiringIsA503AndADenialIsA403'],
    ),
    dict(
        id="RG2",
        what="the delegated-wiring refusal loses its retry marker again, so an unwired "
             "scheduler is a malformed request",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/mail/ImapIdleMonitor.java',
        find='                return "delegated IMAP IDLE could not be authorised: the scheduler is"\n'
             '                        + " not wired on this node; retry shortly against a node that"\n'
             '                        + " runs it";\n',
        replace='                return "Delegated IMAP IDLE requires scheduler wiring for authorization";\n',
        # Measured by the MONITOR's own test: the controller test feeds the message in by
        # hand, so a control on the product's wording left it green and did not fire.
        test='ImapIdleMonitorWiringTest',
        expect_fail=['anUnwiredSchedulerSaysSo'],
    ),
    dict(
        id="RH2",
        what="a profile that lost its row is a malformed request again, and the substring "
             "arms decide before the prefix-anchored ones so the profileId steers the status",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerController.java',
        find='        if (message.startsWith("Profile not found")\n'
             '                || message.startsWith("import profile ") && message.contains(\n'
             '                        " no longer has a row in repository ")) {\n',
        replace='        if (false) {\n',
        test='IngestSchedulerControllerAnswerTest',
        expect_fail=['absenceAfterTheFactIsA404AndTheIdCannotSteerTheStatus',
                     'absenceIsA404AndABadSettingIsStillA400'],
    ),
    dict(
        id="RI2",
        what="an unwired node lists no IDLE session instead of saying it cannot list them",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerService.java',
        # Re-anchored: idleUndurableMisses added a second identical unwired guard, so the bare
        # shape now matches twice. Pinned to the method itself, and the sabotage still does what
        # this control is about — answer the empty list instead of refusing.
        find='    public List<String> getIdleProfiles() {',
        replace='    public List<String> getIdleProfiles() {\n        if (true) return List.of();',
        test='IngestSchedulerControllerAnswerTest',
        expect_fail=['anUnwiredNodeCannotListSessions'],
    ),
    dict(
        id="RJ2",
        what="the ingest refusal answers a bare map again, not the endpoint's own document",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ExternalIngestController.java',
        find='        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)\n'
             '                .body(ExternalIngestResult.error("unknown", String.valueOf(e.getMessage())));\n'
             '    }\n'
             '\n'
             '    /** A connector whose stored row cannot say which flow the request belongs to. */\n',
        replace='        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)\n'
                '                .body(ExternalIngestResult.success("unknown", "unknown", "1.0", false, null));\n'
                '    }\n'
                '\n'
                '    /** A connector whose stored row cannot say which flow the request belongs to. */\n',
        test='ExternalIngestControllerGateTest',
        expect_fail=['theRefusalAnswersTheEndpointsOwnDocument'],
    ),
    dict(
        id="RK2",
        what="the IDLE classifier reads the caller's profileId again, so a hostile id can buy "
             "a settled answer or take a 503 away",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerController.java',
        find='        String message = profileId == null || profileId.isBlank() ? raw\n'
             '                : raw.replace(profileId, "{id}");\n',
        replace='        String message = raw;\n',
        test='IngestSchedulerControllerAnswerTest',
        expect_fail=['absenceAfterTheFactIsA404AndTheIdCannotSteerTheStatus'],
    ),
    dict(
        id="RL2",
        what="GET /status throws away the scheduled list it had read when the idle listing "
             "refuses",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerController.java',
        find='        try {\n'
             '            response.put("idleProfiles", schedulerService.getIdleProfiles());\n'
             '        } catch (ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException couldNotAsk) {\n'
             '            response.put("idleProfilesUnavailable", couldNotAsk.getMessage());\n'
             '        }\n',
        replace='        response.put("idleProfiles", schedulerService.getIdleProfiles());\n',
        test='IngestSchedulerControllerAnswerTest',
        expect_fail=['theStatusEndpointDoesNotLoseWhatItAlreadyHas'],
    ),
    dict(
        id="RM2",
        what="a delegated attempt refused inside the AUTHORIZATION GATE leaves no audit entry "
             "again — the half the previous round's fix and its javadoc both missed",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ExternalIngestController.java',
        find='            } catch (RuntimeException refused) {\n'
             '                // The gate READS the connector too, and get() rethrows when the deterministic\n',
        replace='            } catch (RuntimeException refused) {\n'
                '                if (true) throw refused;\n'
                '                // The gate READS the connector too, and get() rethrows when the deterministic\n',
        test='ExternalIngestControllerGateTest',
        expect_fail=['aDelegatedIngestRefusedInsideTheGateIsAlsoAudited'],
    ),
    dict(
        id="RN2",
        what="the monitor stops writing the prefix the endpoint's 404 arm reads, so an absent "
             "profile is a malformed request again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/mail/ImapIdleMonitor.java',
        find='            return new LiveLoad(null, null, "Profile not found: " + profileId);\n',
        replace='            return new LiveLoad(null, null, "no such import profile: " + profileId);\n',
        test='ImapIdleMonitorWiringTest',
        expect_fail=['theStatusArmsAreCoupledToTheProductsWording'],
    ),
    dict(
        id="RO2",
        what="the monitor stops writing the prefix the endpoint's 403 arm reads, so an "
             "authorisation denial is a malformed request again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/mail/ImapIdleMonitor.java',
        find='                return "Delegated authorization denied for profile " + profileId\n',
        replace='                return "delegation refused for profile " + profileId\n',
        test='ImapIdleMonitorWiringTest',
        expect_fail=['theStatusArmsAreCoupledToTheProductsWording'],
    ),
    dict(
        id="RP2",
        what="the connector resolution stops saying WHY it could not resolve — one null for "
             "an unwired node, a refused read, an absent row and a disabled row alike",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerService.java',
        find='            } catch (ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException refused) {\n',
        replace='            } catch (ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException refused) {\n'
                '                if (true) return new ConnectorForProfile(null, Unresolved.NOT_USABLE);\n',
        test='IngestSchedulerControllerAnswerTest',
        expect_fail=['theResolutionSaysWhichReasonItIs'],
    ),
    dict(
        id="RQ2",
        what="the manual trigger answers 400 'No compatible connector' for every reason again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerController.java',
        find='        response.put("status", "error");\n'
             '        switch (resolution.why()) {\n',
        replace='        response.put("status", "error");\n'
                '        if (true) {\n'
                '            response.put("message", "No compatible connector found for profile: "\n'
                '                    + profile.getProfileId());\n'
                '            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);\n'
                '        }\n'
                '        switch (resolution.why()) {\n',
        test='IngestSchedulerControllerAnswerTest',
        # Completed from MEASUREMENT, not from reading. These locks were added to the
        # same test class AFTER this control was written, and a full sweep would have
        # exited non-zero listing them. A review enumerated the whole suite for this
        # shape rather than one round at a time.
        expect_fail=['theTriggerEndpointSplitsByReason',
                     'anUnwiredWalkServiceDoesNotFabricateAbsence'],
    ),
    dict(
        id="RR2",
        what="the dashboard says only 'ready: false' again, which reads as 'the connector is "
             "missing or disabled' for three reasons that say nothing about it",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerController.java',
        find='                entry.put("notReadyReason", String.valueOf(resolution.why()));\n'
             '                entry.put("notReadyIsAnAnswer", resolution.answered());\n',
        replace='',
        test='IngestSchedulerControllerAnswerTest',
        expect_fail=['theDashboardSaysWhyNotReady'],
    ),
    dict(
        id="RS2",
        what="the lineage attribution describes a profile row that was never READ as one whose "
             "schedule configured-by is 'unrecorded'",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find='        if (profile == null && !profileRowRead) {\n',
        replace='        if (false) {\n',
        test='IngestEvidenceSnapshotTest',
        # Completed from MEASUREMENT, not from reading. These locks were added to the
        # same test class AFTER this control was written, and a full sweep would have
        # exited non-zero listing them. A review enumerated the whole suite for this
        # shape rather than one round at a time.
        expect_fail=['anUnreadProfileRowIsNotAttributedAsUnrecorded',
                     'theReimportEventSaysTheProfileRowCouldNotBeRead'],
    ),
    dict(
        id="RT2",
        what="the re-import emit stops passing the READ's outcome, so a profile row that "
             "refused is attributed as one with no configured-by — the CALL SITE, not the "
             "helper the other lock drives directly",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find='                    resolveExecutionAttribution(profile, callContext, profileRead.answered(),\n',
        replace='                    resolveExecutionAttribution(profile, callContext, true,\n',
        test='IngestEvidenceSnapshotTest',
        expect_fail=['theReimportEventSaysTheProfileRowCouldNotBeRead'],
    ),
    dict(
        id="RU2",
        what="the named default's reason is discarded again for a profile that is not on a "
             "schedule, so an unreadable connector becomes 'no candidate' — a fact",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerService.java',
        find='        return new ConnectorForProfile(null,\n'
             '                fromTheNamedDefault != null ? fromTheNamedDefault : Unresolved.NO_CANDIDATE);\n',
        replace='        return new ConnectorForProfile(null, Unresolved.NO_CANDIDATE);\n',
        test='IngestSchedulerControllerAnswerTest',
        expect_fail=['aNonSchedulerProfileKeepsTheReason'],
    ),
    dict(
        id="RV2",
        what="a delegated denial reports a revoked cmis:all again, whatever the real reason "
             "the audit recorded",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerService.java',
        find='            return new DelegatedAuthorization(false, null, tick.why());\n',
        replace='            return new DelegatedAuthorization(false, null,\n'
                '                    DenialReason.CREATOR_CMIS_ALL_LOST);\n',
        test='IngestSchedulerControllerAnswerTest',
        expect_fail=['aDelegatedDenialCarriesItsOwnReason'],
    ),
    dict(
        id="RW2",
        what="the ownership transfer calls a connector row it could not read 'unknown' again, "
             "in the audit trail",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionController.java',
        find='                    boolean rowIsThere;\n'
             '                    try {\n'
             '                        rowIsThere = connectorDefinitionService.existsIndexFree(cid);\n',
        replace='                    boolean rowIsThere = false;\n'
                '                    try {\n'
                '                        rowIsThere = false && connectorDefinitionService.existsIndexFree(cid);\n',
        test='ImportProfileOwnershipTransferTest',
        expect_fail=['adminToDelegated_connectorRowCouldNotBeRead_is503NotUnknown'],
    ),
    dict(
        id="RX2",
        what="an unwired walk service answers 'connector does not exist' again — absence "
             "fabricated by a node with nothing to ask",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerController.java',
        find='                if (connectorDefinitionService == null) {\n',
        replace='                if (false) {\n',
        test='IngestSchedulerControllerAnswerTest',
        expect_fail=['anUnwiredWalkServiceDoesNotFabricateAbsence'],
    ),
    dict(
        id="RY2",
        what="a scheduled row the walk could not read is answered 'not found or not "
             "scheduler-enabled' again — two statements, neither established",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerController.java',
        find='            if (walked.uninterpretable().stream()\n'
             '                    .anyMatch(row -> profileId.equals(row.profileId()))) {\n',
        replace='            if (false) {\n',
        test='IngestSchedulerControllerAnswerTest',
        expect_fail=['anUnreadableScheduledRowIsNotReportedAsAbsent'],
    ),
    dict(
        id="RZ2",
        what="the folder verbs answer 400 'No connector resolved' for every reason again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/FolderConnectorController.java',
        find='        body.put("status", "error");\n'
             '        String id = profile.getDefaultConnectorId();\n'
             '        switch (resolution.why()) {\n',
        replace='        body.put("status", "error");\n'
                '        String id = profile.getDefaultConnectorId();\n'
                '        if (true) {\n'
                '            body.put("message", "No connector resolved for profile: "\n'
                '                    + profile.getProfileId());\n'
                '            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);\n'
                '        }\n'
                '        switch (resolution.why()) {\n',
        test='FolderConnectorControllerTest',
        expect_fail=['run_connectorCouldNotBeRead_is503NotBadRequest',
                     'run_connectorEstablishedAbsent_is404_andHidden_is503'],
    ),
    dict(
        id="SA2",
        what="a profile whose connector could not be resolved vanishes from the folder again, "
             "so the UI reads 'there is nothing to run here'",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/FolderConnectorController.java',
        find='                    if (!resolution.answered()) {\n'
             '                        unresolved.add(profile.getProfileId());\n',
        replace='                    if (false) {\n'
                '                        unresolved.add(profile.getProfileId());\n',
        test='FolderConnectorControllerTest',
        expect_fail=['list_namesTheProfilesWhoseConnectorCouldNotBeResolved'],
    ),
    dict(
        id="SB2",
        what="the POLL's listing answers 'nothing is scheduled' on an unwired node again — "
             "RC2's twin, for the other of the two methods that carry this guard",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerService.java',
        find='    public List<ImportProfileDefinition> getScheduledProfiles() {\n'
             '        if (repositoryInfoMap == null || profileService == null) {\n',
        replace='    public List<ImportProfileDefinition> getScheduledProfiles() {\n'
                '        if (repositoryInfoMap == null || profileService == null) {\n'
                '            if (true) return List.of();\n',
        test='IngestSchedulerControllerAnswerTest',
        expect_fail=['anUnwiredScheduledListingRefuses'],
    ),
    dict(
        id="SC2",
        what="every non-admin create and update calls a connector row it could not read "
             "'unknown' again — RW2's twin, for the site that runs most",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionController.java',
        find='                boolean rowIsThere;\n'
             '                try {\n'
             '                    rowIsThere = connectorDefinitionService.existsIndexFree(cid);\n',
        replace='                boolean rowIsThere = false;\n'
                '                try {\n'
                '                    rowIsThere = false && connectorDefinitionService.existsIndexFree(cid);\n',
        test='ImportProfileOwnershipTransferTest',
        # The second name is from the measurement: both locks drive the same split.
        expect_fail=['createAndUpdate_connectorRowCouldNotBeRead_is503NotUnknown',
                     'create_connectorRowCouldNotBeRead_is503NotUnknown_throughTheEndpoint'],
    ),
    dict(
        id="SD2",
        what="a DLQ payload this node could not read is answered as 'this entry had nothing "
             "to restore', the retry runs content-less and then deletes the entry",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestJobService.java',
        find='            throw new DlqContentUnreadableException("the stored payload of DLQ entry " + dlqId\n'
             '                    + " could not be read: " + e.getMessage(), e);\n',
        replace='            return null;\n',
        test='DlqReplayArchetypeGateTest',
        # The SERVICE's throw. The controller lock mocks the service, so it measures the
        # controller's arm and left this green — the pair was found by the control not firing.
        expect_fail=['thePayloadReadRefusesRatherThanAnsweringNone'],
    ),
    dict(
        id="SE2",
        what="the DLQ retry swallows an unreadable payload again and imports the entry with "
             "no content, then deletes the row",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestDlqController.java',
        find='                } catch (IngestJobService.DlqContentUnreadableException unreadable) {\n',
        replace='                } catch (IngestJobService.DlqContentUnreadableException unreadable) {\n'
                '                    content = null;\n'
                '                    if (true) { /* swallowed */ } else\n',
        test='DlqReplayArchetypeGateTest',
        expect_fail=['aRetryWhosePayloadCannotBeReadDoesNotRunContentLess'],
    ),
    dict(
        id="SF2",
        what="the duplicate check answers 'there is no existing document' on an unwired "
             "content store again, and the caller creates one",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find='        if (contentDaoService == null) {\n',
        replace='        if (contentDaoService == null) {\n'
                '            if (true) return null;\n',
        test='IngestEvidenceSnapshotTest',
        expect_fail=['theDuplicateCheckRefusesAnUnwiredStore'],
    ),
    dict(
        id="SG2",
        what="a re-import pass that changed evidence and could not attribute the event says "
             "nothing to the caller again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find='                warnings.add("evidence was changed by this pass, but no re-import lineage"\n',
        replace='                if (false) warnings.add("evidence was changed by this pass, but no re-import lineage"\n',
        test='IngestEvidenceSnapshotTest',
        expect_fail=['anUnattributableReimportEventIsNotSilent'],
    ),
    dict(
        id="SH2",
        what="an unwired connector service is 'this profile has no connector' again on the "
             "IDLE path, which the endpoint answers 400",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/mail/ImapIdleMonitor.java',
        find='        if (connectorService == null) {\n',
        replace='        if (connectorService == null) {\n'
                '            if (true) return null;\n',
        test='ImapIdleMonitorWiringTest',
        expect_fail=['anUnwiredConnectorServiceSaysSo'],
    ),
    dict(
        id="SI2",
        what="a DLQ entry whose read could not answer is reported as absent again, which the "
             "endpoint turns into 404 'not found'",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestJobService.java',
        find='        } catch (RuntimeException couldNotAsk) {\n'
             '            // The selector itself did not answer. Not "there is no such entry".\n',
        replace='        } catch (RuntimeException couldNotAsk) {\n'
                '            if (true) return null;\n'
                '            // The selector itself did not answer. Not "there is no such entry".\n',
        test='DlqReplayArchetypeGateTest',
        # The three beyond the first are MEASURED. They went short when the round-50 locks were
        # added to this class: making getDlqEntry answer null instead of refusing takes
        # saveToDlq down the "the read ANSWERED that there is no row" arm, which is exactly
        # what those three assert it must not do. A review predicted all three by reading, and
        # the run confirmed them.
        expect_fail=['theEntryReadRefusesRatherThanAnsweringNone',
                     'aProbeThatFoundNoRowIsNotAnAnsweredAbsence',
                     'aProbeThatThrewIsNotAnAnsweredAbsence',
                     'anUnreadableRowDoesNotResetTheFailureHistory'],
    ),
    dict(
        id="SJ2",
        what="a target folder PATH this node could not resolve answers the same null as an "
             "absent one, and the caller says the profile configured neither field",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find='                throw new TargetFolderUnreadableException("the target folder path \'" + folderPath\n'
             '                        + "\' of this profile could not be resolved: " + e.getMessage(), e);\n',
        replace='                if (true) return null;\n',
        test='IngestEvidenceSnapshotTest',
        # ONE name again. A review traced that both target-folder locks funnelled through
        # this catch and predicted an undeclared firing — true of the code as it stood. The
        # rethrow guard added in the same round takes the answered arms out of this catch, so
        # only the failure arm reddens now. Measured, not reasoned: declaring both made the
        # runner report WRONG TEST FIRED.
        expect_fail=['anUnresolvableTargetFolderPathIsNotAMissingSetting'],
    ),
    dict(
        id="SK2",
        what="the DLQ endpoint swallows the stored-but-unreadable refusal again, so the "
             "handler that answers 503 becomes dead code and the caller sees 404 or 500",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestDlqController.java',
        find='        IngestDeadLetterRecord dlq = ingestJobService.getDlqEntry(dlqId);\n',
        replace='        IngestDeadLetterRecord dlq;\n'
                '        try {\n'
                '            dlq = ingestJobService.getDlqEntry(dlqId);\n'
                '        } catch (IngestJobService.DlqEntryUnreadableException swallowed) {\n'
                '            dlq = null;\n'
                '        }\n',
        test='DlqReplayArchetypeGateTest',
        expect_fail=['aStoredButUnreadableEntryIsNotReportedAsAbsent'],
    ),
    dict(
        id="SL2",
        what="the path that resolves to a DOCUMENT is answered as 'the profile configured "
             "neither field' again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find='                        throw new TargetFolderUnreadableException("the target folder path \'"\n'
             '                                + folderPath + "\' of this profile resolves to a " + baseType\n',
        replace='                        if (true) return null;\n'
                '                        throw new TargetFolderUnreadableException("the target folder path \'"\n'
                '                                + folderPath + "\' of this profile resolves to a " + baseType\n',
        test='IngestEvidenceSnapshotTest',
        expect_fail=['aTargetFolderPathThatIsNotAFolderIsNotAMissingSetting'],
    ),
    dict(
        id="SM2",
        what="the non-admin CREATE path stops calling the connector scope check, which SC2's "
             "helper-direct lock could not see",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionController.java',
        find='        ResponseEntity<Map<String, Object>> connectorErr = validateDelegatedConnectors(ctx, repositoryId, folderId, def);\n'
             '        if (connectorErr != null) return connectorErr;\n'
             '\n'
             '        // Stamp the safe fields LAST so a misuse can\'t override them via payload.\n',
        replace='        // Stamp the safe fields LAST so a misuse can\'t override them via payload.\n',
        test='ImportProfileOwnershipTransferTest',
        expect_fail=['create_connectorRowCouldNotBeRead_is503NotUnknown_throughTheEndpoint'],
    ),
    dict(
        id="SN2",
        what="writing over an unreadable DLQ row clears its payload flag again, so the next "
             "retry imports content-less and deletes the row",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestJobService.java',
        # Re-anchored: a second call site was added (the re-probe of an INHERITED assumption),
        # so the bare call now matches twice. The preceding comment line pins the catch arm.
        find='                // document, so it is read from the document rather than assumed absent.\n'
             '                earlierPayloadIsStillAttached = storedDocumentHasAttachment(dlqId);\n',
        replace='                // document, so it is read from the document rather than assumed absent.\n',
        test='DlqReplayArchetypeGateTest',
        # The two beyond the first ARE measured — but not for the reason first written here.
        # A review showed why: the fixture answers postFind by CALL INDEX, so deleting this
        # call shifts upsertDocument's own read onto the throwing index and nothing is written
        # at all. Both probe outcomes reachable from that fixture already return null, so the
        # merge input is unchanged by this sabotage. Recorded as a trap: harden the fixture to
        # answer by selector and SN2 becomes WRONG TEST FIRED while the protection stands.
        expect_fail=['writingOverAnUnreadableRowKeepsTheAttachedPayload',
                     'aProbeThatThrewIsNotAnAnsweredAbsence',
                     'anUnreadableRowDoesNotResetTheFailureHistory'],
    ),
    dict(
        id="SO2",
        what="the target-folder refusals are re-wrapped by the method's own generic catch "
             "again, so an ANSWERED 'this path is a document' is reported as a retry",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find='            } catch (TargetFolderUnreadableException alreadySaid) {\n',
        replace='            } catch (TargetFolderUnreadableException alreadySaid) {\n'
                '                if (true) throw new TargetFolderUnreadableException(\n'
                '                        "the target folder path could not be resolved: "\n'
                '                                + alreadySaid.getMessage(), alreadySaid);\n',
        test='IngestEvidenceSnapshotTest',
        expect_fail=['aTargetFolderPathThatIsNotAFolderIsNotAMissingSetting'],
    ),
    dict(
        id="SP2",
        what="a standing misconfiguration is marked retryable again, so the caller appends "
             "'; retry shortly' and the ingest endpoint answers 503 for it",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find='                                + ", not a folder; fix the profile", null, false);\n',
        replace='                                + ", not a folder; fix the profile", null, true);\n',
        test='IngestEvidenceSnapshotTest',
        expect_fail=['aTargetFolderPathThatIsNotAFolderIsNotAMissingSetting'],
    ),
    dict(
        id="SQ2",
        what="a link that cannot be authorised escapes createLink again, so one relationship "
             "turns a document that was already committed into an error result and a DLQ row",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find='            logger.warn("Relationship {} → {} was not created: {}", sourceId, targetId,\n'
             '                    folderRefused.getMessage());\n'
             '            return LinkOutcome.notLinked("Relationship not authorised: "\n'
             '                    + folderRefused.getMessage());\n',
        replace='            throw folderRefused;\n',
        test='CanonicalImportServiceTest',
        # The older lock drives the OTHER arm (the wrapper's own resolution), which is
        # why this control did not fire until a lock for this arm existed.
        expect_fail=['aLinkWhoseFolderReadRefusesIsNotLinked_notAnEscapingException'],
    ),
    dict(
        id="SR2",
        what="the CALL SITE appends '; retry shortly' unconditionally again, so a standing "
             "profile misconfiguration is answered as a retry",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find='                    + (couldNotResolve.isRetryable() ? "; retry shortly" : ""));\n',
        replace='                    + "; retry shortly");\n',
        # The service's own call site, driven through execute(). The endpoint lock stubs
        # the import service, so it measures the classifier and not this suffix.
        test='CanonicalImportServiceTest',
        expect_fail=['aStandingTargetFolderMisconfigurationIsNotToldToRetry'],
    ),
    dict(
        id="SS2",
        what="the standing-misconfiguration message matches no status arm again, so the "
             "ingest endpoint answers 500 for it",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ExternalIngestController.java',
        # Re-anchored: the 400 arm gained the two caller-mistake tokens, so this token is no
        # longer the last one in the arm. Narrowed to its own line.
        find='                || firstError.contains("fix the profile")\n',
        replace='',
        test='ExternalIngestControllerGateTest',
        expect_fail=['aStandingProfileMisconfigurationIsA400_notARetryAndNotOurBug'],
    ),
    dict(
        id="SU2",
        # Same sabotage as SS2, different class on purpose. SS2's lock hands the classifier a
        # message the test wrote itself, because the service is stubbed there; the snapshot's
        # lock hands it the message the PRODUCT built. Without this control the second half of
        # that join is a claim nothing measures.
        what="the standing-misconfiguration arm is deleted, measured against the message the "
             "product actually emits rather than one the test wrote",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ExternalIngestController.java',
        # Re-anchored: the 400 arm gained the two caller-mistake tokens, so this token is no
        # longer the last one in the arm. Narrowed to its own line.
        find='                || firstError.contains("fix the profile")\n',
        replace='',
        test='IngestEvidenceSnapshotTest',
        expect_fail=['aTargetFolderPathThatIsNotAFolderIsNotAMissingSetting'],
    ),
    dict(
        id="TU2",
        what="the write-point repository-confinement refusal matches no status arm again, so "
             "a denial is answered as a server fault",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ExternalIngestController.java',
        find='                || firstError.contains("not the repository this caller authenticated")\n',
        replace='',
        test='CanonicalImportServiceTest',
        expect_fail=['aWriteInAnotherRepositoryThanTheCallerAuthenticatedIn_is403NotAServerError'],
    ),
    dict(
        id="TV2",
        what="the write-point cmis:all refusal loses its 403 arm, falling back onto the 400 "
             "arm it only ever matched by the accident of saying \"is required\"",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ExternalIngestController.java',
        find='                || firstError.contains("was not held when this import ran")\n',
        replace='',
        test='CanonicalImportServiceTest',
        expect_fail=['aWriteWithoutCmisAllOnTheTargetFolder_is403NotABadRequest'],
    ),
    dict(
        id="TW2",
        what="the revoked-connector refusal at the write point matches no status arm again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ExternalIngestController.java',
        # Neutralised IN PLACE rather than deleted. Twice now these controls have drifted
        # because their anchor held the LAST token of an arm and a token was appended after
        # it; a `false &&` in front of the same token does not care what follows.
        find='|| firstError.contains("no longer delegated")',
        replace='|| (false && firstError.contains("no longer delegated"))',
        test='CanonicalImportServiceTest',
        expect_fail=['aWriteWithARevokedConnectorDelegation_is403NotAServerError'],
    ),
    dict(
        id="TX2",
        what="an unwired authorization service at the write point is answered as a server "
             "fault instead of \"could not ask, retry\"",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ExternalIngestController.java',
        # Re-anchored: the 503 arm gained the [transient] token, so the old find (which
        # included the arm's closing brace) no longer matched. Narrowed to this token's line.
        find='                || firstError.contains("the authorization service is not available")\n',
        replace='',
        test='CanonicalImportServiceTest',
        expect_fail=['aWriteWhoseAuthorizationServiceIsNotWired_is503NotAServerError'],
    ),
    dict(
        id="TY2",
        what="the 400 arm is moved above the 403 arm — the cmis:all refusal says \"is "
             "required\", so a denial becomes the caller\'s bad request without any message "
             "changing. The ordering is a claim the comment makes; this measures it.",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ExternalIngestController.java',
        # Rewritten: the first version re-emitted BOTH arms' token lists literally, so a token
        # added to the product's 403 arm later would have been silently deleted by this
        # sabotage while the anchors still matched — the control would have drifted from
        # measuring ORDER to measuring token removal. A review found it. Inserting one 400
        # check above the 403 arm says "the 400 arm is first" and copies nothing.
        find='        if (firstError.contains("not allowed") || firstError.contains("scoped to repository")\n',
        replace='        if (firstError.contains("is required")) return HttpStatus.BAD_REQUEST;\n'
                '        if (firstError.contains("not allowed") || firstError.contains("scoped to repository")\n',
        test='CanonicalImportServiceTest',
        expect_fail=['aWriteWithoutCmisAllOnTheTargetFolder_is403NotABadRequest'],
    ),
    dict(
        id="UA2",
        what="a REFUSED DLQ retry goes back to answering 200 \'failed\' with a retryCount — "
             "an authorisation refusal told the operator to try again, forever",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestDlqController.java',
        find_span=('                HttpStatus refusal = ExternalIngestController.classifyErrorStatus(result);',
                   '                            + "; the entry is kept and nothing was imported");\n                }'),
        replace='',
        test='DlqRetryRefusalStatusTest',
        expect_fail=['aRefusedRetryIsNotAnswered200'],
    ),
    dict(
        id="UB2",
        what="the import\'s own [transient] verdict is thrown away at the door again, so a "
             "condition the product classified as retryable answers 500",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ExternalIngestController.java',
        # Neutralised IN PLACE — see TW2. Round 49 recorded that four controls drifted for
        # holding the last token of an arm, and this one was written in that same round with
        # the same shape; a review caught the repeat.
        find='|| firstError.contains("[transient] ")',
        replace='|| (false && firstError.contains("[transient] "))',
        test='CanonicalImportServiceTest',
        # Measured: the archetype-path lock asserts the same arm.
        expect_fail=['aTransientStoreFailureDuringTheWrite_is503NotAServerError',
                     'aTransientFailureInAnArchetypePath_alsoCarriesTheVerdictIntoTheAnswer'],
    ),
    dict(
        id="UC2",
        what="the write\'s own ACL denial answers 500 again — the third checkpoint disagreeing "
             "with the two before it",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ExternalIngestController.java',
        # The arm moved above the retryable block and gained the second denial format.
        find='        if (firstError.contains("permission denied! repositoryid=")\n',
        replace='        if (false\n',
        test='CanonicalImportServiceTest',
        # Measured: the ordering lock asserts the same arm from the other side.
        expect_fail=['aPermissionDeniedDuringTheWrite_is403NotAServerError',
                     'aDenialWhoseTextHappensToCarry503_isStill403'],
    ),
    dict(
        id="UD2",
        what="two caller mistakes answer 500 again, while the same door answers 400 for the "
             "sibling mistake one layer up",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ExternalIngestController.java',
        find='                || firstError.contains("exceeds max size")\n'
             '                || firstError.contains("invalid metadata format")) return HttpStatus.BAD_REQUEST;',
        replace='                ) return HttpStatus.BAD_REQUEST;',
        test='CanonicalImportServiceTest',
        expect_fail=['aMetadataPayloadOverTheCap_is400NotAServerError'],
    ),
    dict(
        id="UE2",
        what="the 404 arm is moved above the 503 arm — a read that FAILED with a cause saying "
             "\'not found\' is then answered as an absence, which is this batch\'s headline "
             "defect. The ordering was measured on the 403/400 pair and not on this one.",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ExternalIngestController.java',
        find='        if (firstError.contains("retry shortly") || firstError.contains("temporarily unavailable")\n',
        replace='        if (firstError.contains("not found")) return HttpStatus.NOT_FOUND;\n'
                '        if (firstError.contains("retry shortly") || firstError.contains("temporarily unavailable")\n',
        test='CanonicalImportServiceTest',
        expect_fail=['aFailedProfileReadWhoseCauseSaysNotFound_isStill503NotA404'],
    ),
    dict(
        id="UF2",
        what="the cmis:all arm of the write-point re-authorisation is deleted. Five controls "
             "isolated the other arms of that method and none this one; only VF, which kills "
             "the whole method, touched it.",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find_span=('        if (!ingestAuthorizationService.canManageProfileForFolder(',
                   '                    + " not held when this import ran");\n        }'),
        replace='',
        test='CanonicalImportServiceTest',
        # Measured. The six beyond the first are the locks that reach this arm through the
        # other two call sites of the method (the dedupe read, the relationship listing and
        # the link creation), which is why removing one arm reddens them all.
        expect_fail=['aWriteWithoutCmisAllOnTheTargetFolder_is403NotABadRequest',
                     'testARevokeDuringTheDedupeReadStillStopsTheWrite',
                     'testARevokeDuringTheRelationshipListingStillStopsTheWrite',
                     'testARevokedDelegationStopsTheRelationshipCreation',
                     'testAnImportWithNoContentStreamIsAlsoReChecked',
                     'testDelegatedImportReAsksTheAuthorizationAtTheWrite',
                     'testTheDelegationIsReAskedAfterTheContentIsRead'],
    ),
    dict(
        id="UG2",
        # SU2's twin on the 503 side. TN runs this same sabotage, but only against
        # ExternalIngestControllerGateTest, where the message is the test's own. Here the
        # message is the one the PRODUCT built. Without this the 503 half of the join was
        # measured on a hand-written string only — the exact asymmetry SU2 removed on 400.
        what="the retry token is deleted from the status classifier, measured against the "
             "messages the product actually emits",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ExternalIngestController.java',
        find='        if (firstError.contains("retry shortly") || firstError.contains("temporarily unavailable")\n',
        replace='        if (false\n',
        test='CanonicalImportServiceTest',
        # The third is MEASURED: the idempotency refusal added in the round after this control
        # ends in "; retry shortly" and asserts 503 through this very arm.
        expect_fail=['aTargetFolderReadThatCouldNotAnswerKeepsItsRetryMarker',
                     'aFailedProfileReadWhoseCauseSaysNotFound_isStill503NotA404',
                     'anIdempotencyRecordThatCouldNotBeReadRefuses_ratherThanReplacing'],
    ),
    dict(
        id="UH2",
        what="the attachment probe answers FALSE again when it threw — 'could not ask' told "
             "apart from 'there is none' only in the javadoc",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestJobService.java',
        find_span=('        } catch (RuntimeException couldNotAsk) {\n'
                   '            logger.warn("whether the stored DLQ row for {} still carries its payload could not"',
                   '            return null;\n        }'),
        replace='        } catch (RuntimeException couldNotAsk) {\n            return false;\n        }',
        test='DlqReplayArchetypeGateTest',
        expect_fail=['aProbeThatThrewIsNotAnAnsweredAbsence'],
    ),
    dict(
        id="UI2",
        what="an index that returned NO ROW is read as 'the payload is gone' again — the same "
             "collapse one line below the arm the previous round fixed",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestJobService.java',
        find_span=('            if (raw.isEmpty()) {',
                   '                return null;\n            }'),
        replace='            if (raw.isEmpty()) {\n                return false;\n            }',
        test='DlqReplayArchetypeGateTest',
        expect_fail=['aProbeThatFoundNoRowIsNotAnAnsweredAbsence'],
    ),
    dict(
        id="UJ2",
        what="a row that could not be READ is restated as the item's first failure again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestJobService.java',
        find_span=('        } else if (historyUnknown) {',
                   '            dlq.setRetryCount(0);\n        } else {'),
        replace='        } else {',
        test='DlqReplayArchetypeGateTest',
        expect_fail=['anUnreadableRowDoesNotResetTheFailureHistory'],
    ),
    dict(
        id="UK2",
        what="an entry whose bytes were never stored is replayed content-less again, then "
             "reported as success and DELETED",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestDlqController.java',
        # Re-anchored: the guard no longer tests !hasContent — a row can carry an EARLIER
        # attempt's payload while THIS attempt's bytes were refused, and that inverse was
        # getting through. VE2 measures the inverse; this control still measures the guard.
        find_span=('            if (dlq.getPayloadDropReason() != null) {',
                   '                        + " item through its connector instead");\n            }'),
        replace='',
        test='DlqRetryRefusalStatusTest',
        # Completed from MEASUREMENT, not from reading. These locks were added to the
        # same test class AFTER this control was written, and a full sweep would have
        # exited non-zero listing them. A review enumerated the whole suite for this
        # shape rather than one round at a time.
        expect_fail=['anEntryWhoseBytesWereDroppedIsNotReplayed',
                     'anOlderPayloadIsNotPairedWithNewerMetadata'],
    ),
    dict(
        id="UL2",
        what="the retry answer reports one more than the row stores",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestDlqController.java',
        find='                response.put("retryCount", dlq.getRetryCount());',
        replace='                response.put("retryCount", dlq.getRetryCount() + 1);',
        test='DlqRetryRefusalStatusTest',
        expect_fail=['anOrdinaryFailureIsStill200'],
    ),
    dict(
        id="UM2",
        what="a reservation that could not be ATTEMPTED is reported as a rival holding it",
        # Retargeted at the CONTROLLER. The endpoint lock stubs the service, so sabotaging
        # the service's throw left it green — the runner said DID NOT FIRE. UU2 measures the
        # service half against a lock that drives the real one.
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestDlqController.java',
        find_span=('        } catch (IngestJobService.DlqRetryNotReservableException couldNotAsk) {',
                   '                    + "; the entry is kept and nothing was imported");\n        }'),
        replace='        } catch (IngestJobService.DlqRetryNotReservableException couldNotAsk) {\n'
                '            throw couldNotAsk;\n        }',
        test='DlqRetryRefusalStatusTest',
        expect_fail=['aReservationThatCouldNotBeAttemptedIsNot429'],
    ),
    dict(
        id="UN2",
        what="a purge that stopped part-way returns its partial count again, so the endpoint "
             "answers 'success, 0 deleted' for a read that never ran",
        # Retargeted at the CONTROLLER — see UM2. UV2 measures the service half.
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestDlqController.java',
        find_span=('        } catch (IngestJobService.DlqPurgeIncompleteException stopped) {',
                   '            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(partial);\n        }'),
        replace='        } catch (IngestJobService.DlqPurgeIncompleteException stopped) {\n'
                '            throw stopped;\n        }',
        test='DlqRetryRefusalStatusTest',
        expect_fail=['aPurgeThatStoppedIsNotSuccess'],
    ),
    dict(
        id="UO2",
        what="the page declares the queue finished because the row past it could not be decoded",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestDlqController.java',
        # Re-anchored: the page no longer contains the probe row at all — the service decodes
        # exactly the page and answers hasMore separately, because counting the probe row into
        # the page made an offset page cover a different span of raw rows than the caller's
        # next offset assumes. The sabotage now re-derives hasMore from the entry count, which
        # is the same defect in its new home.
        find='        boolean hasMore = fetched.hasMore();',
        replace='        boolean hasMore = entries.size() > cappedLimit;',
        test='DlqRetryRefusalStatusTest',
        expect_fail=['aPageWithAnUndecodableRowDoesNotClaimTheEnd'],
    ),
    dict(
        id="UP2",
        # The CALL SITE, not the helper. A control that broke readSettingOrRefuse itself would
        # leave this lock green, because the lock stubs the settings service — what it measures
        # is that the import ASKS the refusing way.
        what="the idempotency read goes back to the reading that cannot refuse, so a failed "
             "configuration read is 'no such record' and a replace DELETES the earlier document",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find='                        String existing = integrationSettingsService.readSettingOrRefuse(idempKey);',
        replace='                        String existing = integrationSettingsService.readSetting(idempKey);',
        test='CanonicalImportServiceTest',
        expect_fail=['anIdempotencyRecordThatCouldNotBeReadRefuses_ratherThanReplacing'],
    ),
    dict(
        id="UQ2",
        what="the checkpoint read goes back to the reading that cannot refuse, so a failed "
             "configuration read restarts the connector from the beginning and then advances "
             "the checkpoint past everything it did not list",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CheckpointManager.java',
        find='        String value = settingsService.readSettingOrRefuse(key);\n        return (value != null && !value.isBlank()) ? value : null;',
        replace='        String value = settingsService.readSetting(key);\n        return (value != null && !value.isBlank()) ? value : null;',
        test='CheckpointManagerTest',
        expect_fail=['loadSimple_refusesWhenTheStoreDidNotAnswer'],
    ),
    dict(
        id="UR2",
        what="the four archetype paths drop the transient verdict from their ANSWER again, "
             "keeping it only in the DLQ row",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find='        return ExternalIngestResult.error(requestId, committedObjectId,\n                verdict + prefix + e.getMessage(), warnings);',
        replace='        return ExternalIngestResult.error(requestId, committedObjectId,\n                prefix + e.getMessage(), warnings);',
        test='CanonicalImportServiceTest',
        expect_fail=['aTransientFailureInAnArchetypePath_alsoCarriesTheVerdictIntoTheAnswer'],
    ),
    dict(
        id="US2",
        what="the denial arm drops back BELOW the retryable arm, so a denial whose interpolated "
             "folder name carries '503' is answered as a retry",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ExternalIngestController.java',
        find_span=('        if (firstError.contains("permission denied! repositoryid=")',
                   '            return HttpStatus.FORBIDDEN;\n        }'),
        replace='',
        test='CanonicalImportServiceTest',
        expect_fail=['aDenialWhoseTextHappensToCarry503_isStill403',
                     'aPermissionDeniedDuringTheWrite_is403NotAServerError',
                     'aTopLevelFolderDenial_is403NotAServerError'],
    ),
    dict(
        id="UT2",
        what="the SECOND denial format is dropped, so every top-level-folder denial is 500",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/ExternalIngestController.java',
        find='                || firstError.contains("permission denied to top level folders")) {',
        replace='                ) {',
        test='CanonicalImportServiceTest',
        expect_fail=['aTopLevelFolderDenial_is403NotAServerError'],
    ),
    dict(
        id="UU2",
        what="the reservation returns false for a store that never answered — the SERVICE half "
             "of UM2, measured against a lock that drives the real service",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestJobService.java',
        find_span=('        } catch (Exception couldNotAsk) {\n'
                   '            // NOT the same as losing a write conflict.',
                   '                    + " could not be reserved: " + couldNotAsk.getMessage(), couldNotAsk);\n        }'),
        replace='        } catch (Exception couldNotAsk) {\n            return false;\n        }',
        test='DlqReplayArchetypeGateTest',
        expect_fail=['theReservationRefusesRatherThanLookingLost'],
    ),
    dict(
        id="UV2",
        what="the purge returns its partial count for a read that never ran — the SERVICE half "
             "of UN2",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestJobService.java',
        find_span=('        } catch (Exception couldNotFinish) {\n'
                   '            // Returning the partial count made the caller answer',
                   '                    + " before it stopped", deleted, couldNotFinish);\n        }'),
        replace='        } catch (Exception couldNotFinish) {\n        }',
        test='DlqReplayArchetypeGateTest',
        expect_fail=['thePurgeRefusesRatherThanLookingComplete'],
    ),
    dict(
        id="UW2",
        what="the IMAP checkpoint read goes back to the reading that cannot refuse — the twin "
             "of UQ2, whose call site had a lock and no control",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CheckpointManager.java',
        find='        String value = settingsService.readSettingOrRefuse(key);\n        if (value == null || value.isBlank()) return new long[]{0, 0};',
        replace='        String value = settingsService.readSetting(key);\n        if (value == null || value.isBlank()) return new long[]{0, 0};',
        test='CheckpointManagerTest',
        expect_fail=['loadValidity_refusesWhenTheStoreDidNotAnswer'],
    ),
    dict(
        id="UX2",
        what="an ASSUMED payload that a read has disproven refuses again, making the flag a "
             "fixed point and DELETE the only way out of a metadata-only entry",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestDlqController.java',
        find_span=('                } else if (dlq.isPayloadPresenceAssumed()) {',
                   '                            + " answered that it carries none, so it was replayed without one");'),
        replace='                } else if (dlq.isPayloadPresenceAssumed()) {\n'
                '                    return errorResponse(HttpStatus.CONFLICT, "refused");',
        test='DlqRetryRefusalStatusTest',
        expect_fail=['anAssumedPayloadDisprovedByAReadIsReplayed'],
    ),
    dict(
        id="UZ2",
        what="the record that this item's bytes were never stored is erased by the next "
             "byte-less failure, so the 409 that protects the entry lasts one save",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestJobService.java',
        find='            String carriedForward = existing != null ? existing.getPayloadDropReason() : null;\n'
             '            dlq.setPayloadDropReason(payload != null ? null\n'
             '                    : (dropReason != null ? dropReason : carriedForward));',
        replace='            dlq.setPayloadDropReason(dropReason);',
        test='DlqReplayArchetypeGateTest',
        expect_fail=['theDropReasonSurvivesTheNextFailure'],
    ),
    dict(
        id="VB2",
        what="a stored row the mapper refused is answered as a retry again — a standing "
             "condition wearing the transient answer",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerController.java',
        # Re-anchored: the arm was narrowed to one prefix test after a review found the
        # two-item list missing "as THAT connector".
        find_span=('        if (message.contains("could not be read as ")) {\n'
                   '            return HttpStatus.CONFLICT;',
                   '        }\n        if (couldNotAsk(message) || couldNotAsk(raw)) {'),
        replace='        if (couldNotAsk(message) || couldNotAsk(raw)) {',
        test='IngestSchedulerControllerAnswerTest',
        expect_fail=['aRowTheMapperRefusedIsAStandingConflict_notARetry'],
    ),
    dict(
        id="VC2",
        what="a credential the store could not answer is reported as 'the connection changed' "
             "again, and the IDLE session is torn down for a fact nothing established",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/mail/ImapIdleMonitor.java',
        find_span=('            String livePassword;\n            try {',
                   '                return new LiveLoad(null, null, couldNotAsk.getMessage());\n            }'),
        replace='            String livePassword = fetchSupport.resolvePassword(conn);',
        test='ImapIdleSessionRegistryTest',
        expect_fail=['aCredentialReadThatFailedIsNotAConnectionChange'],
    ),
    dict(
        id="VD2",
        what="a corrupt stored row is classified as 'could not ask' again, so IDLE spins a "
             "full nemaki_conf walk per message for ever instead of stopping",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/mail/ImapIdleMonitor.java',
        # Re-anchored: one prefix test now — see VB2.
        find_span=('        if (refusal.contains("could not be read as ")) {',
                   '            return false;\n        }\n        return refusal.contains("retry shortly")'),
        replace='        return refusal.contains("retry shortly")',
        test='ImapIdleSessionRegistryTest',
        # The second is MEASURED: both locks assert that a corrupt/ambiguous stored row is a
        # settled refusal, and this sabotage removes the arm both of them rest on.
        expect_fail=['aCorruptRowIsASettledRefusal',
                     'theDeterministicIdMismatchIsSettled'],
    ),
    dict(
        id="VE2",
        what="a row holding an EARLIER attempt's payload is replayed under this attempt's "
             "metadata again — the guard drops back to testing !hasContent",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestDlqController.java',
        find='            if (dlq.getPayloadDropReason() != null) {',
        replace='            if (!dlq.isHasContent() && dlq.getPayloadDropReason() != null) {',
        test='DlqRetryRefusalStatusTest',
        expect_fail=['anOlderPayloadIsNotPairedWithNewerMetadata'],
    ),
    dict(
        id="VF2",
        what="the last page offers a continuation token again, walking a client through an "
             "endless run of empty pages",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestDlqController.java',
        find='        if (hasMore) response.put("nextOffset", safeOffset + cappedLimit);',
        replace='        response.put("nextOffset", safeOffset + cappedLimit);',
        test='DlqRetryRefusalStatusTest',
        expect_fail=['theLastPageHasNoNextOffset'],
    ),
    dict(
        id="VG2",
        what="a delete the selector could not see is answered as success again",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestDlqController.java',
        find_span=('        int deleted = ingestJobService.deleteDlqEntry(dlqId);',
                   '                    + " caught up — nothing was deleted, so retry");\n        }'),
        # The variable stays: dropping it broke compilation, and the runner correctly refused
        # to score that as a firing. What this control removes is the arm that REFUSES.
        replace='        int deleted = ingestJobService.deleteDlqEntry(dlqId);\n'
                '        Map<String, Object> response = new LinkedHashMap<>();',
        test='DlqRetryRefusalStatusTest',
        expect_fail=['aDeleteThatRemovedNothingIsNotSuccess'],
    ),
    dict(
        id="VH2",
        what="the job listing goes back to answering a bare array, so a run whose row cannot "
             "be decoded looks like it never happened",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestDlqController.java',
        find_span=('    private ResponseEntity<?> jobsOrEnvelope(IngestJobService.JobPage page) {',
                   '        return ResponseEntity.ok(response);\n    }'),
        replace='    private ResponseEntity<?> jobsOrEnvelope(IngestJobService.JobPage page) {\n'
                '        return ResponseEntity.ok(page.entries());\n    }',
        test='DlqRetryRefusalStatusTest',
        expect_fail=['aJobListingThatDroppedARowSaysSo'],
    ),
    dict(
        id="VI2",
        what="the checkpoint ENUMERATION goes back to the reading that cannot refuse, so the "
             "admin endpoint answers 'never polled' from a store that did not answer",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CheckpointManager.java',
        find='            String value = settingsService.readSettingOrRefuse(key);\n            if (value != null && !value.isBlank()) result.put(scope, value);',
        replace='            String value = settingsService.readSetting(key);\n            if (value != null && !value.isBlank()) result.put(scope, value);',
        test='CheckpointManagerTest',
        expect_fail=['enumeration_refusesWhenTheStoreDidNotAnswer'],
    ),
    dict(
        id="VJ2",
        what="a payload the store would not take leaves the row claiming it holds one — a "
             "fixed point whose retry answers 409 for ever",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestJobService.java',
        # Re-anchored: the block gained a re-read (putAttachment can commit and then throw) and
        # a check of the correcting write's own result, so the old span no longer balances.
        # Neutralised at the branch instead: taking the "the payload IS stored" arm skips the
        # correction entirely, which is exactly the defect — the row keeps hasContent=true with
        # no attachment.
        # Re-anchored: the branch was inverted when the probe's three outcomes were split into
        # "answered absent" and "not known". Skipping the correction entirely is still the
        # defect — the row keeps hasContent=true with nothing recorded about the write.
        find='                    if (Boolean.FALSE.equals(reallyThere)) {',
        replace='                    if (false) {',
        test='DlqReplayArchetypeGateTest',
        expect_fail=['anAttachmentThatFailedIsNotClaimedAsStored'],
    ),
    dict(
        id="VK2",
        what="the exclusion drops back to a two-item list, so the deterministic-id mismatch "
             "('as THAT connector') is read as transient and IDLE never tears down",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/mail/ImapIdleMonitor.java',
        find='        if (refusal.contains("could not be read as ")) {',
        replace='        if (refusal.contains("could not be read as a profile")\n'
                '                || refusal.contains("could not be read as a connector")) {',
        test='ImapIdleSessionRegistryTest',
        expect_fail=['theDeterministicIdMismatchIsSettled'],
    ),
    dict(
        id="VL2",
        what="a checkpoint read that could not answer escapes the endpoint as a Spring 500 — "
             "'our bug' for the one condition this batch converts to 503",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerController.java',
        find='            jp.aegif.nemaki.rest.controller.IntegrationSettingsService\n'
             '                    .SettingUnreadableException.class})',
        replace='            })',
        test='IngestSchedulerControllerAnswerTest',
        expect_fail=['aCheckpointReadThatCouldNotAnswerIs503_notOurBug'],
    ),
    dict(
        id="VM2",
        what="a skip on a row whose source was never READ deletes it again, destroying the "
             "only record of the loss with the tool that exists to recover it",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestDlqController.java',
        find='            if (result.skipped() && dlq.isSourceNeverRead()) {',
        replace='            if (false) {',
        test='DlqRetryRefusalStatusTest',
        expect_fail=['aSkipDoesNotResolveARowWhoseSourceWasNeverRead'],
    ),
    dict(
        id="VN2",
        what="the retry path ignores the delete's return value again, so a row the index could "
             "not show is reported resolved and reappears in the next listing",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestDlqController.java',
        find='                response.put("status", removed > 0 ? "resolved" : "resolved-entry-kept");',
        replace='                response.put("status", "resolved");',
        test='DlqRetryRefusalStatusTest',
        expect_fail=['aResolvedRetryWhoseDeleteRemovedNothingSaysSo'],
    ),
    dict(
        id="VP2",
        what="an ANSWERED absence is recorded as an assumption again — the probe's three "
             "outcomes collapse back to two",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestJobService.java',
        find='                    if (Boolean.FALSE.equals(reallyThere)) {',
        replace='                    if (!Boolean.TRUE.equals(reallyThere)) {',
        test='DlqReplayArchetypeGateTest',
        expect_fail=['anAttachmentWriteOfUnknownOutcomeIsNotAssertedEitherWay'],
    ),
    dict(
        id="VQ2",
        what="a save that wrote nothing reports that it wrote a row, so the IMAP monitor "
             "announces a dead-letter the same outage prevented",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestJobService.java',
        find='            return docId != null;',
        replace='            return true;',
        test='DlqReplayArchetypeGateTest',
        expect_fail=['aSaveWhoseWriteDidNotLandSaysSo'],
    ),
    dict(
        id="VR2",
        what="the helper reports 'did not throw' instead of the service's answer — the claim "
             "the previous round moved one frame down",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/FetchSupport.java',
        find='            return ingestJobService.saveSourceNeverReadToDlq(request, errorMessage);',
        replace='            ingestJobService.saveSourceNeverReadToDlq(request, errorMessage);\n            return true;',
        test='DlqReplayArchetypeGateTest',
        expect_fail=['aFetchSupportSaveThatWroteNothingSaysSo'],
    ),
    dict(
        id="VS2",
        what="the never-read mark is inherited even by an attempt that DID read the source, so "
             "a replayable row can never be resolved",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestJobService.java',
        find='            dlq.setSourceNeverRead(sourceNeverRead\n'
             '                    || (!sourceWasRead && existing != null && existing.isSourceNeverRead()));',
        replace='            dlq.setSourceNeverRead(sourceNeverRead\n'
                '                    || (existing != null && existing.isSourceNeverRead()));',
        test='DlqReplayArchetypeGateTest',
        expect_fail=['aLaterAttemptThatReadTheSourceClearsTheMark'],
    ),
    dict(
        id="VT2",
        what="a configuration-store outage is counted against the connector's circuit breaker "
             "again — the connector was never asked",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerService.java',
        find='                    if (settings instanceof jp.aegif.nemaki.rest.controller\n'
             '                            .IntegrationSettingsService.SettingUnreadableException) {',
        replace='                    if (false) {',
        test='SchedulerConfigOutageBreakerTest',
        expect_fail=['aConfigurationOutageDoesNotOpenTheConnectorsBreaker'],
    ),
    dict(
        id="VU2",
        what="an ordinary save clears the never-read mark again, so a partial fetch's row is "
             "deleted by a replay that imported nothing",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestJobService.java',
        find='        saveToDlqReporting(request, errorMessage, contentBytes, sourceNeverRead, false);',
        replace='        saveToDlqReporting(request, errorMessage, contentBytes, sourceNeverRead, !sourceNeverRead);',
        test='DlqReplayArchetypeGateTest',
        expect_fail=['anOrdinarySaveDoesNotClearTheMark'],
    ),
    dict(
        id="VV2",
        what="the row claims a payload before the attachment has been stored, so a failed "
             "corrective write leaves an unqualified claim nothing can repair",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestJobService.java',
        find='            dlq.setPayloadPresenceAssumed(payload != null\n'
             '                    || (payload == null && presenceCouldNotBeEstablished));',
        replace='            dlq.setPayloadPresenceAssumed(payload == null && presenceCouldNotBeEstablished);',
        test='DlqReplayArchetypeGateTest',
        expect_fail=['aPayloadIsNotClaimedBeforeItIsStored'],
    ),
    dict(
        id="VW2",
        what="the attachment goes back to a filename-derived name, so a later attempt's "
             "payload lands ALONGSIDE the earlier one and the replay picks the wrong bytes",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestJobService.java',
        find='            String attName = "payload";',
        replace='            String attName = fileName != null ? fileName : "content";',
        test='DlqReplayArchetypeGateTest',
        expect_fail=['aSecondPayloadReplacesTheFirstRatherThanJoiningIt'],
    ),
    dict(
        id="VX2",
        what="the IDLE callback calls a delegated authorisation it could not ASK a revocation "
             "again, drops the message and tears the session down for good",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/mail/ImapIdleMonitor.java',
        # The PREDICATE, which is what the lock can reach: the arm's own wiring needs a live
        # IMAP session and is recorded as unmeasured.
        find='        return why == DenialReason.CREATOR_LOOKUP_FAILED\n'
             '                || why == DenialReason.SERVICES_UNAVAILABLE;',
        replace='        return false;',
        test='ImapIdleSessionRegistryTest',
        expect_fail=['aDelegatedAuthorisationThatCouldNotBeAskedIsNotARevocation'],
    ),
    dict(
        id="VY2",
        what="the orchestrators swallow the configuration refusal again, so a store outage is "
             "reported as the connector failing and opens its circuit breaker",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/note/NotionFetchOrchestrator.java',
        find_span=('        } catch (jp.aegif.nemaki.rest.controller.IntegrationSettingsService\n'
                   '                .SettingUnreadableException couldNotAsk) {',
                   '            throw couldNotAsk;'),
        replace='        } catch (jp.aegif.nemaki.rest.controller.IntegrationSettingsService\n'
                '                .SettingUnreadableException couldNotAsk) {\n'
                '            FetchSupport.addError(errors, "Notion connection failed: "\n'
                '                    + couldNotAsk.getMessage());',
        test='NotionOrchestratorRefusalTest',
        expect_fail=['aCheckpointOutageIsNotReportedAsTheConnectorFailing'],
    ),
    dict(
        id="VZ2",
        what="the IDLE status endpoint stops reporting the messages that were neither captured "
             "nor recorded, so a half-capturing session looks healthy",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerController.java',
        find_span=('        Map<String, Integer> missed = schedulerService.idleUndurableMisses();',
                   '                    + " UID checkpoint has not moved, so re-fetch the mailbox to recover");\n        }'),
        replace='',
        test='IngestSchedulerControllerAnswerTest',
        expect_fail=['theIdleStatusReportsMessagesThatWereNeitherCapturedNorRecorded'],
    ),
    dict(
        id="ST2",
        what="the retry marker is dropped from a target-folder read that could not answer, so "
             "the ingest endpoint falls from 503 to the 500 fallback",
        file='core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java',
        find='                    + (couldNotResolve.isRetryable() ? "; retry shortly" : ""));\n',
        replace='                    );\n',
        test='CanonicalImportServiceTest',
        expect_fail=['aTargetFolderReadThatCouldNotAnswerKeepsItsRetryMarker'],
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


# [control id, [lock names]] for locks that failed under a control that did not declare them.
# Printed at the end of a run; see the call site for why the run reports this rather than a
# reviewer deriving it.
UNDECLARED: list = []
# [control id, [names]] for failing testcases this runner could not attribute. A hole in
# the reader, not in the protections — but it makes the undeclared report incomplete without
# saying so, which is the shape of defect that took four rounds to find last time.
PARSE_GAPS: list = []


# A failure header, matched WHOLE: "<fully.qualified.Class>.method -- Time elapsed: 0.1 s
# <<< FAILURE!" and its variants. The 2.x reporter separated the name from the time with
# spaces instead of " -- "; that shape is matched too, so it can be REFUSED rather than read
# (this repository pins surefire 3.5.2).
def failing_methods_in_reports(xml_texts: list) -> tuple:
    """(method names that FAILED, testcase names that could not be read as a method).

    Read from surefire's XML, where the name is an ATTRIBUTE. Four review rounds running found
    a hole in reading these names out of the .txt prose instead — a class-level line taken for
    a lock, a message quoting a header taken for one, a message embedding a header taken for a
    phantom lock — each time in a reader that looked like it worked, and each time closed by
    one more rule. An attribute cannot be confused with prose, so the family is closed rather
    than narrowed.

    A <testcase> counts as failed when it carries a <failure> or an <error>. Its name is the
    method with the argument list dropped ("someLock(Path)"); a parameterised invocation's
    index sits after that list, so it goes with it.

    A CONTAINER-level entry — surefire writes name="" when a lifecycle method fails, because
    its adapter passes a null method for a ClassSource — names no method: it is returned in
    the second list, not silently dropped, because a run whose failures this reader cannot
    attribute must say so. A name that IS a simple class name WOULD be read as a method here;
    a review disassembled the adapter and established that surefire does not write that shape,
    so there is nothing to measure a guard against. This paragraph is the record of that, not
    a claim that the code checks it.

    The third return is each failing testcase's failure TEXT, keyed by method name. It is what
    the "did it fail on its own assertion" judgement reads. Taking that text out of the .txt
    instead meant finding a stanza in prose, and a review measured both misreadings that
    allows: a real firing scored as harness breakage, and harness breakage scored as a firing.
    An element's own text cannot run into the next test's.
    """
    failed, unreadable, texts = set(), [], {}
    for text in xml_texts:
        try:
            root = ElementTree.fromstring(text)
        except ElementTree.ParseError as broken:
            unreadable.append(f"(a report could not be parsed: {broken})")
            continue
        for case in root.iter("testcase"):
            problems = [child for child in case
                        if child.tag in ("failure", "error")]
            if not problems:
                continue
            raw = (case.get("name") or "").strip()
            name = raw.split("(", 1)[0].strip()
            if not re.fullmatch(r"[A-Za-z_$][\w$]*", name):
                unreadable.append(raw or "(a failing testcase with no name)")
                continue
            failed.add(name)
            body = []
            for child in problems:
                body.append(child.get("type") or "")
                body.append(child.get("message") or "")
                body.append(child.text or "")
            # APPEND, not assign: a parameterised method's invocations all normalise to the
            # same name, and keeping only the last made the assertion judgement depend on the
            # order surefire happened to write them — one invocation breaking the harness and
            # another firing the assertion gave opposite answers. Joined, harness breakage
            # wins, which is the fail-closed reading. A review found the overwrite.
            texts[name] = "\n".join(
                part for part in ([texts.get(name, "")] + body) if part)
    return failed, unreadable, texts


def missing_locks(expect_fail: list, failed_methods: set) -> list:
    """The declared locks that did NOT fail — the "WRONG TEST FIRED" input.

    Exact membership, not a substring of the report text. The substring form reads a longer
    method name as though a shorter declared one had failed; this tree has no such pair inside
    one class today (a review checked), and the runner does not have to depend on that.
    """
    return [m for m in expect_fail if m not in failed_methods]


def exit_decision_is_wired() -> bool:
    """Whether main() actually exits with what run_exit_message decided.

    The decision has cases; the CALL had none, and deleting it left the suite green — the
    "sabotage the call site, not the helper" rule applied to this runner itself. Read from
    source because a self-test cannot run main().
    """
    source = Path(__file__).read_text()
    # The whole BLOCK, not two strings that also occur in this function's own body — the first
    # version matched itself and stayed True with the wiring deleted. Measured: removing the
    # block in a scratch copy now turns this case red.
    return ("\n    message = run_exit_message(results, UNDECLARED, PARSE_GAPS)\n"
            "    if message:\n        sys.exit(message)\n") in source


def run_exit_message(results: list, undeclared: list, gaps: list):
    """What a measuring run must exit with, or None when it may exit 0.

    A judgement function with its own cases, because the exit was the one part of this
    mechanism nobody could measure: a review pointed out that deleting the fatal branch left
    every self-test green. Order matters — a control that did not fire is the most serious
    thing a run can find, and it is reported first.
    """
    if any(not ok for _, ok, _ in results):
        return "at least one control did not fire; see the summary above"
    if undeclared:
        return ("controls whose expect_fail is incomplete (the locks above failed under them "
                "on their own assertions and are not declared)")
    if gaps:
        return ("failing testcases this runner could not attribute; the undeclared-lock report is not "
                "complete for the controls listed above")
    return None


def run_test(test_class: str) -> tuple:
    """Run one class; return (all_green, failed_methods, unreadable_names, failure_texts).

    Everything comes from surefire's XML: the failing method NAMES (an attribute) and each
    failing testcase's failure TEXT (its own element). The .txt reports are not read at all.
    Taking either out of that prose is what five review rounds kept finding holes in — a
    class-level line taken for a lock, a message quoting a header taken for one, a message
    embedding one taken for a phantom lock, and, for the assertion judgement, a stanza that
    ran into another test's message in both directions. An attribute and an element cannot be
    confused with prose.
    """
    for old in REPORTS.glob("*.txt"):
        old.unlink()
    for old in REPORTS.glob("*.xml"):
        old.unlink()
    # No -DfailIfNoTests=false: with it, a renamed or moved test class became a zero-test
    # green — the sabotage phase would then misread the silence as "protects nothing" and the
    # restore check as a clean tree. Source anchors refuse loudly on drift; the test side has
    # to as well.
    proc = subprocess.run(
        ["mvn", "-o", "-q", "-pl", "core", "test", f"-Dtest={test_class}"],
        cwd=REPO, capture_output=True, text=True, timeout=900)
    failed_methods, unreadable, failure_texts = failing_methods_in_reports(
        [x.read_text(errors="replace") for x in REPORTS.glob("TEST-*.xml")])
    # Two ways a run measures nothing, both hit by hand before this runner existed:
    # the literal marker, and — sturdier — a nonzero exit with NO reports at all
    # (a broken build writes none; a red test writes them and also exits nonzero).
    output = proc.stdout + proc.stderr
    reports_exist = any(REPORTS.glob("TEST-*.xml"))
    if "COMPILATION ERROR" in output or (proc.returncode != 0 and not reports_exist):
        raise SystemExit(
            f"nothing was measured (exit {proc.returncode}, no reports): either the sabotage "
            f"broke the build — the failure mode two hand-run controls (FF, GC) hit — or the "
            f"test class no longer exists under that name:\n" + output[-2000:])
    if proc.returncode != 0 and not failed_methods and not unreadable:
        # Reports exist but none carries a failure, and Maven still exited nonzero: a
        # surefire fork crash or a mid-run death. This environment has produced exactly that
        # (four dumpstream files on 2026-08-30). It is neither green nor a fired lock — it is
        # an unmeasured run, and calling it either would be the substitution this whole tool
        # exists to end.
        raise SystemExit(
            f"maven exited {proc.returncode} with reports but no failures — the run "
            f"died without measuring anything:\n" + output[-2000:])
    return (not failed_methods and not unreadable, failed_methods, unreadable, failure_texts)


def _harness_broke(failure_text: str) -> bool:
    """Was HarnessBroken RAISED here, or merely named?

    "Anywhere in the failure text" was too blunt: a lock whose whole subject is HarnessBroken —
    `assertThrows(HarnessBroken.class, ...)` — names it in its failure message, and that lock
    firing was scored as harness breakage. A raised exception appears as a type prefix at the
    start of a line, or after "Caused by: "; a mention appears inside a message.
    """
    for line in failure_text.splitlines():
        text = line.strip()
        if text.startswith("Caused by: "):
            text = text[len("Caused by: "):]
        head = text.split(":", 1)[0]
        if head.endswith("HarnessBroken"):
            return True
    return False


def failure_is_assertion(failure_text: str) -> bool:
    """Whether THIS testcase's failure is the lock's own assertion, not an unrelated error.

    A control that "fires" because the expected method died of an NPE or a broken fixture is
    not the lock firing — it is the sabotage breaking the harness, which proves nothing about
    the protection. The lock's refusals are all JUnit assertions, so the failure has to name
    one.

    The text is ONE testcase's <failure>/<error> element, so there is no stanza to find and no
    window to size. The previous version searched the .txt for a line naming the method and
    read forward to the next failure marker; a review measured both ways that misreads when a
    failure MESSAGE contains a header-shaped line — a real firing scored as harness breakage,
    and harness breakage scored as a firing.
    """
    if _harness_broke(failure_text):
        # HARNESS BREAKAGE FIRST, and it wins. JavaSource.methodBody and the reflection
        # helpers that report a renamed method used to throw AssertionError, so the one case
        # this function exists to exclude walked straight through the check below: rename the
        # method a control sabotages and the control reported FIRED while measuring nothing.
        # They now throw HarnessBroken, which is deliberately not an AssertionError.
        return False
    # Mockito's verify() failures extend AssertionError but print their own class names; a
    # verify(never()) lock firing IS the assertion firing. The message forms are here because
    # two real firings (HT, HV) were misread as harness breakage without them, and the count
    # and ordering verifications because a lock firing by "wanted 2 times but was 1" printed
    # TooFewActualInvocations and was read the same way.
    return any(marker in failure_text for marker in (
        "AssertionFailedError", "AssertionError",
        "NeverWantedButInvoked", "WantedButNotInvoked", "MockitoAssertionError",
        "ArgumentsAreDifferent", "TooFewActualInvocations", "TooManyActualInvocations",
        "NoInteractionsWanted", "VerificationInOrderFailure",
        "Wanted but not invoked", "Never wanted here", "Argument(s) are different",
        "No interactions wanted here", "Verification in order failure",
        "Wanted at least", "Wanted 1 time", "Wanted 2 times", "Wanted 3 times"))


SELF_TEST_CASES = [
    # (name, callable -> actual, expected)
    # The reader had no case at all when it was added, and then four rounds of holes while it
    # parsed prose. It reads the XML now; these cases are the shapes this tree's reports
    # actually contain, taken from core/target/surefire-reports.
    ("a failing testcase names its method",
     lambda: failing_methods_in_reports([
         '<testsuite><testcase name="someLock" classname="jp.aegif.SomeTest">'
         '<failure>boom</failure></testcase></testsuite>']),
     ({"someLock"}, [], {"someLock": "boom"})),
    ("an errored testcase counts too",
     lambda: failing_methods_in_reports([
         '<testsuite><testcase name="someLock" classname="jp.aegif.SomeTest">'
         '<error>boom</error></testcase></testsuite>']),
     ({"someLock"}, [], {"someLock": "boom"})),
    ("a PASSING testcase is not a failure",
     lambda: failing_methods_in_reports([
         '<testsuite><testcase name="someLock" classname="jp.aegif.SomeTest"/></testsuite>']),
     (set(), [], {})),
    ("a method that takes arguments (this tree's real shape)",
     lambda: failing_methods_in_reports([
         '<testsuite><testcase name="aRefusedDocumentLeavesNoFile(Path)"'
         ' classname="jp.aegif.nemaki.rest.importexport.ExportsRefuseMissingBytesTest">'
         '<failure>boom</failure></testcase></testsuite>']),
     ({"aRefusedDocumentLeavesNoFile"}, [], {"aRefusedDocumentLeavesNoFile": "boom"})),
    ("a parameterised invocation (this tree's real shape)",
     lambda: failing_methods_in_reports([
         '<testsuite><testcase name="testLinkLocalAllowedInSetupOnAllowedPort(String)[1]"'
         ' classname="jp.aegif.nemaki.api.setup.filter.UrlValidatorTest$PrivateAddressMatrix">'
         '<failure>boom</failure></testcase></testsuite>']),
     ({"testLinkLocalAllowedInSetupOnAllowedPort"}, [],
      {"testLinkLocalAllowedInSetupOnAllowedPort": "boom"})),
    ("a CONTAINER-level failure names no method, and is REPORTED rather than dropped",
     lambda: failing_methods_in_reports([
         '<testsuite><testcase name="" classname="jp.aegif.SomeTest">'
         '<error>lifecycle</error></testcase></testsuite>']),
     (set(), ["(a failing testcase with no name)"], {})),
    # The whole point of reading the XML: prose cannot be mistaken for a name. Both shapes
    # below were demonstrated as misreadings of the previous, line-based reader.
    # A parameterised method's invocations normalise to one name. Keeping only the last made
    # the answer depend on the order surefire wrote them; joined, the harness break wins, which
    # is the fail-closed reading. The assertion is placed LAST here on purpose: with the
    # overwrite, that order answered "fired".
    ("one invocation breaking the harness outweighs another's assertion",
     lambda: failure_is_assertion(failing_methods_in_reports([
         '<testsuite>'
         '<testcase name="someLock(String)[1]" classname="jp.aegif.SomeTest">'
         '<error type="jp.aegif.nemaki.util.test.HarnessBroken">renamed</error></testcase>'
         '<testcase name="someLock(String)[2]" classname="jp.aegif.SomeTest">'
         '<failure type="org.opentest4j.AssertionFailedError">expected true</failure></testcase>'
         '</testsuite>'])[2]["someLock"]),
     False),
    # The sibling <system-out>/<system-err> elements are NOT read. This tree's reports really
    # carry them (57 of 115 testcases in one class), and a review demonstrated both misreadings
    # that including them allows: captured output naming AssertionError turning a harness
    # break into a "firing", and captured output naming HarnessBroken turning a real firing
    # into a "harness break". Two cases, one per direction.
    ("captured output naming an assertion does not make a harness break a firing",
     lambda: failure_is_assertion(failing_methods_in_reports([
         '<testsuite><testcase name="someLock" classname="jp.aegif.SomeTest">'
         '<error type="java.lang.NullPointerException">at jp.aegif</error>'
         '<system-out>java.lang.AssertionError: printed, not thrown</system-out>'
         '</testcase></testsuite>'])[2]["someLock"]),
     False),
    ("captured output naming HarnessBroken does not unmake a real firing",
     lambda: failure_is_assertion(failing_methods_in_reports([
         '<testsuite><testcase name="someLock" classname="jp.aegif.SomeTest">'
         '<failure type="org.opentest4j.AssertionFailedError">expected true</failure>'
         '<system-out>jp.aegif.nemaki.util.test.HarnessBroken: printed, not thrown</system-out>'
         '</testcase></testsuite>'])[2]["someLock"]),
     True),
    # The marker list has two halves — Mockito's exception CLASS names and its printed MESSAGE
    # forms — and every earlier case carried both, so neither half was measured. One case per
    # half. (The message forms are what a .txt stanza starting at the message needed; with the
    # XML's type attribute they are close to dead weight, and this is where that would show.)
    ("a Mockito failure known only by its class name is a firing",
     lambda: failure_is_assertion(
         "org.mockito.exceptions.verification.WantedButNotInvoked\nsee the log"), True),
    ("a Mockito failure known only by its message is a firing",
     lambda: failure_is_assertion("Wanted but not invoked: connector.get()"), True),
    # A POSITIVE control, and the point of reading the XML: a failure MESSAGE shaped like
    # another test's header names nothing, and its text stays attached to the testcase it
    # belongs to. No rule in this reader has to be removed for that — the structure gives it.
    ("a failure MESSAGE that looks like a header names nothing (positive control)",
     lambda: failing_methods_in_reports([
         '<testsuite><testcase name="someLock" classname="jp.aegif.SomeTest"><failure>'
         'jp.aegif.nemaki.Other.otherLock -- Time elapsed: 0.1 s &lt;&lt;&lt; FAILURE!'
         '</failure></testcase></testsuite>'])[:2],
     ({"someLock"}, [])),
    ("a report that cannot be parsed is reported, not counted as green",
     lambda: failing_methods_in_reports(["<testsuite><testcase"])[1] != [],
     True),
    # The WIRING, not just the decision: a review pointed out that deleting the sys.exit call
    # in main() left every case green while undeclared locks and unreadable lines exited 0.
    # Read from this file's own source, the way the Java locks read a patch's source.
    ("the exit decision is wired into main()",
     lambda: exit_decision_is_wired(), True),
    # A declared lock whose name is a PREFIX of another test's: with a substring search over
    # the report text (what this used to do), the longer one failing would read as the shorter
    # one having failed. The pair is real — anUpdateRefusesRetryably and
    # anUpdateRefusesRetryablyWhenTheDeterministicRowIsHidden both exist in this tree, in two
    # different classes, which is why nothing has misread yet.
    ("a declared lock is missing when only a LONGER name failed",
     lambda: missing_locks(["anUpdateRefusesRetryably"],
                           {"anUpdateRefusesRetryablyWhenTheDeterministicRowIsHidden"}),
     ["anUpdateRefusesRetryably"]),
    ("a declared lock that failed is not missing",
     lambda: missing_locks(["anUpdateRefusesRetryably"], {"anUpdateRefusesRetryably"}), []),
    ("a clean run may exit 0",
     lambda: run_exit_message([("A", True, "")], [], []), None),
    ("a control that did not fire is the most serious finding",
     lambda: run_exit_message([("A", False, "")], [("A", ["x"])], [("A", ["y"])]),
     "at least one control did not fire; see the summary above"),
    ("an undeclared lock alone still fails the run",
     lambda: run_exit_message([("A", True, "")], [("A", ["x"])], []) is not None, True),
    ("an unreadable line alone still fails the run",
     lambda: run_exit_message([("A", True, "")], [], [("A", ["y"])]) is not None, True),
    # The assertion judgement reads ONE testcase's failure element now, so these cases are
    # failure bodies, not report stanzas. What they measure is unchanged: harness breakage
    # wins over any assertion name, and Mockito's own verification failures count as firings.
    ("a JUnit assertion is a firing",
     lambda: failure_is_assertion(
         "org.opentest4j.AssertionFailedError\nexpected true"), True),
    ("harness breakage wins over an assertion name in the same failure",
     lambda: failure_is_assertion(
         "org.opentest4j.AssertionFailedError\nassertion text\n"
         "Caused by: jp.aegif.nemaki.util.test.HarnessBroken: the method was renamed"), False),
    ("a lock ABOUT HarnessBroken firing is still a firing",
     lambda: failure_is_assertion(
         "org.opentest4j.AssertionFailedError\nexpected HarnessBroken to be thrown"), True),
    ("an NPE is NOT a firing",
     lambda: failure_is_assertion("java.lang.NullPointerException\nat jp.aegif"), False),
    ("verify(never()) firing is a firing",
     lambda: failure_is_assertion(
         "org.mockito.exceptions.verification.NeverWantedButInvoked\nNever wanted here"), True),
    ("atLeast() falling short is a firing",
     lambda: failure_is_assertion(
         "org.mockito.exceptions.verification.TooFewActualInvocations\nWanted at least 2"),
     True),
    ("too many invocations is a firing",
     lambda: failure_is_assertion(
         "org.mockito.exceptions.verification.TooManyActualInvocations\nWanted 1 time"), True),
    ("verifyNoMoreInteractions firing is a firing",
     lambda: failure_is_assertion(
         "org.mockito.exceptions.verification.NoInteractionsWanted\n"
         "No interactions wanted here"), True),
    ("inOrder firing is a firing",
     lambda: failure_is_assertion(
         "org.mockito.exceptions.verification.VerificationInOrderFailure\n"
         "Verification in order failure"), True),
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
    # A HarnessBroken deeper in a real stack than the first few lines. The .txt version of
    # this judgement needed a window for that, and two guesses at its size were wrong; an
    # element's own text has no window to size, so what remains to measure is that the cause
    # still wins however deep it sits.
    ("harness breakage past any depth still wins",
     lambda: failure_is_assertion(
         "org.opentest4j.AssertionFailedError: the lock could not read the method\n"
         + "\tat jp.aegif.nemaki.Frame.method(Frame.java:1)\n" * 80
         + "Caused by: jp.aegif.nemaki.util.test.HarnessBroken: method not found"), False),
    ("harness breakage under an assertion still wins",
     lambda: failure_is_assertion(
         "org.opentest4j.AssertionFailedError: the lock could not read the method\n"
         "\tat org.junit.jupiter.api.Assertions.fail(Assertions.java:1)\n"
         "\tat jp.aegif.nemaki.SomeLock.check(SomeLock.java:2)\n"
         "Caused by: jp.aegif.nemaki.util.test.HarnessBroken: method not found"), False),
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


def compile_check(ids: list) -> list:
    """Which sabotages no longer COMPILE.

    The pre-flight asks whether a sabotage still APPLIES (its anchor matches) and whether the
    span it replaces is delimiter-balanced. Neither question is "does the result build". A
    control whose replacement referenced a variable a fix had deleted passed the pre-flight
    and killed a seven-hour sweep at the control where it sat, leaving eleven unmeasured — and
    100 controls are pure deletions, the same shape waiting to happen. This is slow (one
    compile per control), so it is a separate mode, run over the controls whose target file
    has changed.

    Returns a list of problem strings; empty means every checked sabotage builds.
    """
    problems = []
    for cid in ids:
        control = next((c for c in CONTROLS if c["id"] == cid), None)
        if control is None:
            problems.append(f"[{cid}] no such control")
            continue
        path = REPO / control["file"]
        original = path.read_text()
        try:
            path.write_text(sabotage_text(original, control))
            result = subprocess.run(
                ["mvn", "-o", "-q", "-pl", "core", "test-compile", "-DskipTests"],
                cwd=REPO, capture_output=True, text=True)
            if result.returncode != 0:
                errors = [line for line in (result.stdout + result.stderr).splitlines()
                          if "ERROR" in line or "error:" in line][:4]
                problems.append(f"[{cid}] the sabotage does not compile:\n    "
                                + "\n    ".join(errors))
            else:
                print(f"  {cid}: compiles")
        finally:
            path.write_text(original)
    return problems


def main() -> None:
    if "--self-test" in sys.argv[1:]:
        raise SystemExit(1 if run_self_test() else 0)

    if "--compile-check" in sys.argv[1:]:
        wanted = [a for a in sys.argv[1:] if a != "--compile-check"]
        ids = wanted or [c["id"] for c in CONTROLS]
        print(f"compile-checking {len(ids)} of {len(CONTROLS)} sabotages")
        found = compile_check(ids)
        if found:
            raise SystemExit("sabotages that no longer compile — the sweep would die at the "
                             "first of these and every control after it would not run:\n  "
                             + "\n  ".join(found))
        print("every checked sabotage compiles")
        raise SystemExit(0)

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
                green, failed_methods, unreadable, failure_texts = run_test(control["test"])
                if green:
                    # The finally below still restores, and the green-after re-verification after
                    # it still runs — the first version `continue`d past both, leaving the restore
                    # contract untested exactly where a finding was being reported.
                    results.append((control["id"], False,
                                    "the lock stayed GREEN under the sabotage — it protects "
                                    "nothing"))
                    print(f"[{control['id']}] DID NOT FIRE")
                else:
                    missing = missing_locks(control["expect_fail"], failed_methods)
                    not_assertions = [m for m in control["expect_fail"]
                                      if m not in missing
                                      and not failure_is_assertion(failure_texts.get(m, ""))]
                    if missing:
                        results.append((control["id"], False,
                                        f"something failed, but not the expected lock(s) "
                                        f"{missing}; actual: {sorted(failed_methods)}"))
                        print(f"[{control['id']}] WRONG TEST FIRED")
                    elif not_assertions:
                        results.append((control["id"], False,
                                        f"{not_assertions} failed, but not on the lock's own "
                                        f"assertion — the sabotage broke the harness, which "
                                        f"proves nothing about the protection"))
                        print(f"[{control['id']}] FIRED FOR THE WRONG REASON")
                    else:
                        results.append((control["id"], True, ", ".join(sorted(failed_methods))))
                        print(f"[{control['id']}] fired: {control['expect_fail']}")
                        # Locks that failed WITHOUT being declared. Not a bad verdict — extra
                        # failures are tolerated by design — but the record of "which
                        # protections this sabotage removes" is then incomplete, and four
                        # rounds in a row a lock added in the same commit as its control was
                        # left out of an OLDER control's list. Derivation by hand kept missing
                        # them; the run knows the answer, so it says it.
                        # Only locks that failed on their own assertion: a test that died
                        # with an exception under the sabotage lost its harness, which says
                        # nothing about a protection being removed. A review asked for the
                        # distinction before this list is acted on.
                        gaps = unreadable
                        if gaps:
                            PARSE_GAPS.append((control["id"], gaps))
                            print(f"[{control['id']}] failing testcases this runner could not attribute"
                                  f" (the undeclared report is incomplete here): {gaps}")
                        undeclared = sorted(
                            name for name in failed_methods
                            if name not in control["expect_fail"]
                            and failure_is_assertion(failure_texts.get(name, "")))
                        if undeclared:
                            UNDECLARED.append((control["id"], undeclared))
                            print(f"[{control['id']}] also failed, undeclared: {undeclared}")
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
            green_after, failed_after, unreadable_after, _ = run_test(control["test"])
            if not green_after:
                raise SystemExit(
                    f"[{control['id']}] the tree is NOT green after restore — stop and look: "
                    + ", ".join(sorted(failed_after) + unreadable_after))
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
    if UNDECLARED:
        # Printed here, and fatal below: an extra failing lock does not weaken the VERDICT on
        # the protections (they all fired), but the record of what a sabotage removes has to
        # be completed from the run rather than derived by hand, which missed one four rounds
        # running.
        print("\n== locks that failed without being declared (complete the expect_fail lists) ==")
        for cid, names in UNDECLARED:
            print(f"  {cid}: {names}")
    if PARSE_GAPS:
        print("\n== failing testcases this runner could not attribute (the undeclared report above is "
              "incomplete for these controls) ==")
        for cid, lines in PARSE_GAPS:
            print(f"  {cid}: {lines}")
    # Every list is printed BEFORE the decision, so a run that ends non-zero has already said
    # everything it measured. The decision itself is a judgement function with its own cases:
    # a review pointed out that deleting the fatal branch left every self-test green.
    message = run_exit_message(results, UNDECLARED, PARSE_GAPS)
    if message:
        sys.exit(message)


if __name__ == "__main__":
    started = time.time()
    main()
    print(f"({int(time.time() - started)}s)")
