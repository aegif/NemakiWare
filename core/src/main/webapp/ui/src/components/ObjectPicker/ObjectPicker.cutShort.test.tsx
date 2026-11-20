import { it, expect, vi, beforeEach, afterEach, afterAll } from 'vitest';
import { render, screen, cleanup, fireEvent, waitFor, act, within } from '@testing-library/react';
import { turnAntdMotionOff, turnAntdMotionBackOn, destroyAntdNotices } from '../../test/antdNotices';
import i18n from '../../i18n';
import { ObjectPicker } from './ObjectPicker';
import { SearchCutShortError } from '../../services/searchCutShort';

// R349 (R344 batch, 3.4.2): the object picker is the third screen that calls CMISService.search. A search answered cut
// short by the ACL scan limit leaves neither the previous rows nor an object chosen from them: the guidance stands in the
// search tab and there is nothing to confirm. A later answer, reopening the dialog and a new search take the guidance away,
// and only the last search started writes the picker. Real i18next (English); the service is a stand-in. The test names
// are single words because the negative-control runner reads them as lock names.
const service = vi.hoisted(() => ({
  search: vi.fn(),
  getRootFolder: vi.fn(),
  getChildren: vi.fn(),
}));
vi.mock('../../services/cmis', () => ({
  CMISService: vi.fn().mockImplementation(function () {
    return service;
  }),
}));

const GUIDANCE = 'The complete search results could not be confirmed. Narrow the search conditions and try again.';
const row = (id: string, name: string) => ({
  id, name, objectType: 'cmis:document', baseType: 'cmis:document', properties: {}, allowableActions: {},
});
const answer = (...objects: ReturnType<typeof row>[]) => ({ objects, numItems: objects.length, hasMoreItems: false });
const PICKER_ANSWER = answer(row('picker-row-id', 'picker-row-name.txt'));
const NEWER_ANSWER = answer(row('newer-row-id', 'newer-row-name.txt'));
const OLDER_ANSWER = answer(row('older-row-id', 'older-row-name.txt'));
const onSelect = vi.fn();

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

beforeEach(async () => {
  await i18n.changeLanguage('en');
  service.getRootFolder.mockResolvedValue({ id: 'root-folder-id', name: 'root', baseType: 'cmis:folder' });
  service.getChildren.mockResolvedValue([]);
});

afterEach(async () => {
  cleanup();
  // Every stand-in starts afresh: an answer queued by a lock that stopped early does not answer the next lock.
  for (const stand of Object.values(service)) {
    stand.mockReset();
  }
  onSelect.mockReset();
  // Last, after the resets: a HarnessBroken raised here has left the stand-ins reset; the notice it reports stays,
  // and later locks of this file report it too.
  await destroyAntdNotices();
});

// antd's static notices must not outlive a lock: see src/test/antdNotices.tsx.
turnAntdMotionOff();
afterAll(() => {
  turnAntdMotionBackOn();
});

/** Waits until {@code text} is on the screen; a timeout fails this lock's own expect(), not the query's harness error. */
async function shown(text: string) {
  await waitFor(() => expect(screen.queryByText(text), `${text} is not shown`).not.toBeNull());
}

async function gone(text: string) {
  await waitFor(() => expect(screen.queryByText(text), `${text} is still shown`).toBeNull());
}

async function settle() {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
}

function picker(visible: boolean) {
  return <ObjectPicker repositoryId="bedroom" visible={visible} onSelect={onSelect} onCancel={() => undefined} />;
}

async function openedOnTheSearchTab() {
  const view = render(picker(true));
  await waitFor(() => expect(service.getRootFolder).toHaveBeenCalled());
  fireEvent.click(screen.getByRole('tab', { name: 'Search' }));
  await waitFor(() => expect(screen.queryByPlaceholderText('Enter search keywords'), 'the search tab').not.toBeNull());
  return view;
}

/** Enter in the search box — the search button holds still while a search runs, the box does not. */
function pickerSearch(keyword: string) {
  const box = screen.getByPlaceholderText('Enter search keywords');
  fireEvent.change(box, { target: { value: keyword } });
  fireEvent.keyDown(box, { key: 'Enter', code: 'Enter', keyCode: 13 });
  fireEvent.keyUp(box, { key: 'Enter', code: 'Enter', keyCode: 13 });
}

function confirmButton(): HTMLButtonElement {
  const button = document.querySelector('.ant-modal-footer .ant-btn-primary');
  if (!button) {
    throw new Error('the confirm button is not there');
  }
  return button as HTMLButtonElement;
}

function searchSpinner() {
  return document.querySelector('.ant-modal .ant-space-compact .ant-btn-loading');
}

it('aCutShortPickerSearchLeavesNoRowNoSelectionAndNothingToConfirm', async () => {
  await openedOnTheSearchTab();
  service.search.mockResolvedValueOnce(PICKER_ANSWER).mockRejectedValueOnce(new SearchCutShortError());
  pickerSearch('first');
  await shown('picker-row-name.txt');
  fireEvent.click(screen.getByText('picker-row-name.txt'));
  await waitFor(() => expect(confirmButton().disabled, 'the confirm button with a choice').toBe(false));
  expect(confirmButton().textContent).toContain('picker-row-name.txt');

  pickerSearch('second');
  await shown(GUIDANCE);
  expect(screen.queryByText('picker-row-name.txt'), 'the previous row').toBeNull();
  expect(confirmButton().disabled, 'the confirm button after the cut short').toBe(true);
  expect(confirmButton().textContent, 'the choice on the confirm button').not.toContain('picker-row-name.txt');
  expect(document.body.textContent, 'the chosen object\'s id').not.toContain('picker-row-id');
  fireEvent.click(confirmButton());
  expect(onSelect, 'a confirm after the cut short').not.toHaveBeenCalled();

  // The next search starts: the guidance goes, and the table shown while it runs holds no previous row.
  const next = deferred<typeof PICKER_ANSWER>();
  service.search.mockReturnValueOnce(next.promise);
  pickerSearch('third');
  await gone(GUIDANCE);
  expect(screen.queryByText('picker-row-name.txt'), 'the previous row under the next search').toBeNull();
  await act(async () => {
    next.resolve(answer());
    await next.promise;
  });
});

it('aPickerSearchAnsweredAfterACutShortOneShowsItsRowsAndNoGuidance', async () => {
  await openedOnTheSearchTab();
  service.search.mockRejectedValueOnce(new SearchCutShortError()).mockResolvedValueOnce(PICKER_ANSWER);
  pickerSearch('first');
  await shown(GUIDANCE);
  pickerSearch('second');
  await shown('picker-row-name.txt');
  expect(screen.queryByText(GUIDANCE), 'the guidance after the answer').toBeNull();
});

it('reopeningThePickerTakesTheGuidanceAway', async () => {
  const { rerender } = await openedOnTheSearchTab();
  service.search.mockRejectedValueOnce(new SearchCutShortError());
  pickerSearch('first');
  await shown(GUIDANCE);
  rerender(picker(false));
  rerender(picker(true));
  await gone(GUIDANCE);
});

it('aFailedPickerSearchLeavesNoRowAndNoSelection', async () => {
  await openedOnTheSearchTab();
  service.search.mockResolvedValueOnce(PICKER_ANSWER).mockRejectedValueOnce(new Error('the search failed'));
  pickerSearch('first');
  await shown('picker-row-name.txt');
  fireEvent.click(screen.getByText('picker-row-name.txt'));
  await waitFor(() => expect(confirmButton().disabled, 'the confirm button with a choice').toBe(false));
  pickerSearch('second');
  await waitFor(() => expect(service.search).toHaveBeenCalledTimes(2));
  await settle();
  expect(screen.queryByText('picker-row-name.txt'), 'the previous row as the failed search\'s').toBeNull();
  expect(confirmButton().disabled, 'the confirm button after the failure').toBe(true);
  expect(screen.queryByText(GUIDANCE), 'the guidance after a failure that is not a cut short').toBeNull();
});

it('anOlderPickerSearchAnsweredLaterDoesNotOverwriteANewerCutShort', async () => {
  await openedOnTheSearchTab();
  const older = deferred<typeof PICKER_ANSWER>();
  service.search.mockReturnValueOnce(older.promise).mockRejectedValueOnce(new SearchCutShortError());
  pickerSearch('older');
  pickerSearch('newer');
  await shown(GUIDANCE);
  await act(async () => {
    older.resolve(OLDER_ANSWER);
    await older.promise;
  });
  await settle();
  expect(screen.queryByText(GUIDANCE), 'the guidance of the newer search').not.toBeNull();
  expect(screen.queryByText('older-row-name.txt'), 'the older row').toBeNull();

  // Nor is the older answer kept to surface under the next search.
  const next = deferred<typeof PICKER_ANSWER>();
  service.search.mockReturnValueOnce(next.promise);
  pickerSearch('next');
  await gone(GUIDANCE);
  expect(screen.queryByText('older-row-name.txt'), 'the older row under the next search').toBeNull();
  await act(async () => {
    next.resolve(answer());
    await next.promise;
  });
});

it('anOlderPickerCutShortArrivingLaterDoesNotOverwriteANewerAnswer', async () => {
  await openedOnTheSearchTab();
  const older = deferred<typeof PICKER_ANSWER>();
  service.search.mockReturnValueOnce(older.promise).mockResolvedValueOnce(NEWER_ANSWER);
  pickerSearch('older');
  pickerSearch('newer');
  await shown('newer-row-name.txt');
  fireEvent.click(screen.getByText('newer-row-name.txt'));
  await waitFor(() => expect(confirmButton().disabled, 'the confirm button with a choice').toBe(false));
  await act(async () => {
    older.reject(new SearchCutShortError());
    await older.promise.catch(() => undefined);
  });
  await settle();
  expect(screen.queryByText('newer-row-name.txt'), 'the row of the newer search').not.toBeNull();
  expect(confirmButton().disabled, 'the choice from the newer search').toBe(false);
  expect(screen.queryByText(GUIDANCE), 'the guidance of the older search').toBeNull();
});

it('anOlderPickerAnswerArrivingFirstNeitherWritesNorEndsTheNewerSearch', async () => {
  await openedOnTheSearchTab();
  const older = deferred<typeof PICKER_ANSWER>();
  const newer = deferred<typeof PICKER_ANSWER>();
  service.search.mockReturnValueOnce(older.promise).mockReturnValueOnce(newer.promise);
  pickerSearch('older');
  pickerSearch('newer');
  await waitFor(() => expect(service.search).toHaveBeenCalledTimes(2));
  await act(async () => {
    older.resolve(OLDER_ANSWER);
    await older.promise;
  });
  await settle();
  expect(screen.queryByText('older-row-name.txt'), 'the older row').toBeNull();
  expect(searchSpinner(), 'the newer search\'s spinner').not.toBeNull();
  await act(async () => {
    newer.resolve(NEWER_ANSWER);
    await newer.promise;
  });
  await shown('newer-row-name.txt');
});

it('aSearchFromBeforeThePickerWasReopenedWritesNothing', async () => {
  const { rerender } = await openedOnTheSearchTab();
  const before = deferred<typeof PICKER_ANSWER>();
  service.search.mockReturnValueOnce(before.promise);
  pickerSearch('before');
  await waitFor(() => expect(searchSpinner(), 'the spinner of the search').not.toBeNull());
  rerender(picker(false));
  rerender(picker(true));
  await act(async () => {
    before.resolve(OLDER_ANSWER);
    await before.promise;
  });
  await settle();
  expect(screen.queryByText('older-row-name.txt'), 'the row of the search from before').toBeNull();
  expect(searchSpinner(), 'the spinner of the search from before').toBeNull();
});

it('aNewPickerSearchDoesNotCarryTheChoice', async () => {
  await openedOnTheSearchTab();
  const next = deferred<typeof PICKER_ANSWER>();
  service.search.mockResolvedValueOnce(PICKER_ANSWER).mockReturnValueOnce(next.promise);
  pickerSearch('first');
  await shown('picker-row-name.txt');
  fireEvent.click(screen.getByText('picker-row-name.txt'));
  await waitFor(() => expect(confirmButton().disabled, 'the confirm button with a choice').toBe(false));

  // The next search runs: the choice from the previous rows is not confirmable meanwhile.
  pickerSearch('second');
  await waitFor(() => expect(confirmButton().disabled, 'the confirm button while the next search runs').toBe(true));
  expect(confirmButton().textContent, 'the choice on the confirm button').not.toContain('picker-row-name.txt');
  expect(document.body.textContent, 'the chosen object\'s id').not.toContain('picker-row-id');
  fireEvent.click(confirmButton());
  expect(onSelect, 'a confirm while the next search runs').not.toHaveBeenCalled();
  await act(async () => {
    next.resolve(answer());
    await next.promise;
  });
});

it('aChoiceFromTheTreeWhileThePickerSearchRunsIsNotLeftAfterItIsCutShort', async () => {
  service.getChildren.mockResolvedValue([{ ...row('tree-row-id', 'tree-row-name.txt') }]);
  await openedOnTheSearchTab();
  const running = deferred<typeof PICKER_ANSWER>();
  service.search.mockReturnValueOnce(running.promise);
  pickerSearch('first');
  await waitFor(() => expect(service.search).toHaveBeenCalledTimes(1));

  // While the search runs, an object is chosen in the tree.
  fireEvent.click(screen.getByRole('tab', { name: 'Browse Tree' }));
  await shown('tree-row-name.txt');
  fireEvent.click(screen.getByText('tree-row-name.txt'));
  await waitFor(() => expect(confirmButton().disabled, 'the confirm button with a choice').toBe(false));

  await act(async () => {
    running.reject(new SearchCutShortError());
    await running.promise.catch(() => undefined);
  });
  await settle();
  expect(confirmButton().disabled, 'the confirm button after the cut short').toBe(true);
  expect(onSelect, 'a confirm').not.toHaveBeenCalled();
});

it('aChoiceFromTheTreeWhileThePickerSearchRunsIsNotLeftAfterItIsAnswered', async () => {
  service.getChildren.mockResolvedValue([{ ...row('tree-row-id', 'tree-row-name.txt') }]);
  await openedOnTheSearchTab();
  const running = deferred<typeof PICKER_ANSWER>();
  service.search.mockReturnValueOnce(running.promise);
  pickerSearch('first');
  await waitFor(() => expect(service.search).toHaveBeenCalledTimes(1));

  // While the search runs, an object is chosen in the tree; then the search answers.
  fireEvent.click(screen.getByRole('tab', { name: 'Browse Tree' }));
  await shown('tree-row-name.txt');
  fireEvent.click(screen.getByText('tree-row-name.txt'));
  await waitFor(() => expect(confirmButton().disabled, 'the confirm button with a choice').toBe(false));
  await act(async () => {
    running.resolve(PICKER_ANSWER);
    await running.promise;
  });
  await settle();
  expect(confirmButton().disabled, 'the confirm button over the answered rows').toBe(true);
  expect(confirmButton().textContent, 'the tree choice on the confirm button').not.toContain('tree-row-name.txt');
});

const ROOTS = {
  old: { id: 'old-root-id', name: 'old-root-name', baseType: 'cmis:folder' },
  fresh: { id: 'new-root-id', name: 'new-root-name', baseType: 'cmis:folder' },
};

function childrenByRoot() {
  service.getChildren.mockImplementation(async (_repositoryId: string, folderId: string) => (
    folderId === 'old-root-id' ? [row('old-child-id', 'old-child-name.txt')]
      : folderId === 'new-root-id' ? [row('new-child-id', 'new-child-name.txt')] : []));
}

it('aRootLoadFromBeforeThePickerWasReopenedWritesNothing', async () => {
  childrenByRoot();
  const before = deferred<typeof ROOTS.old>();
  service.getRootFolder.mockReturnValueOnce(before.promise).mockResolvedValueOnce(ROOTS.fresh);
  const rootsBefore = service.getRootFolder.mock.calls.length;
  const { rerender } = render(picker(true));
  await waitFor(() => expect(service.getRootFolder.mock.calls.length, 'the first root load').toBe(rootsBefore + 1));
  rerender(picker(false));
  rerender(picker(true));
  await shown('new-child-name.txt');

  // The root load from before the reopening answers last.
  await act(async () => {
    before.resolve(ROOTS.old);
    await before.promise;
  });
  await settle();
  expect(screen.queryByText('old-root-name'), 'the root from before the reopening').toBeNull();
  expect(screen.queryByText('old-child-name.txt'), 'its children').toBeNull();
  expect(screen.queryByText('new-child-name.txt'), 'the root loaded on reopening').not.toBeNull();
});

it('aRootLoadFromBeforeThePickerWasReopenedDoesNotEndTheNewOnesSpinner', async () => {
  childrenByRoot();
  const before = deferred<typeof ROOTS.old>();
  const fresh = deferred<typeof ROOTS.fresh>();
  service.getRootFolder.mockReturnValueOnce(before.promise).mockReturnValueOnce(fresh.promise);
  const rootsBefore = service.getRootFolder.mock.calls.length;
  const { rerender } = render(picker(true));
  await waitFor(() => expect(service.getRootFolder.mock.calls.length, 'the first root load').toBe(rootsBefore + 1));
  rerender(picker(false));
  rerender(picker(true));
  await waitFor(() => expect(service.getRootFolder.mock.calls.length, 'the root load on reopening').toBe(rootsBefore + 2));

  // The load from before answers first, while the new one runs.
  await act(async () => {
    before.resolve(ROOTS.old);
    await before.promise;
  });
  await settle();
  expect(screen.queryByText('old-child-name.txt'), 'the children from before the reopening').toBeNull();
  expect(document.querySelector('.ant-modal .ant-tree'), 'the tree shown while the root load still runs').toBeNull();
  await act(async () => {
    fresh.resolve(ROOTS.fresh);
    await fresh.promise;
  });
  await shown('new-child-name.txt');
});

it('aRootLoadReopenedBetweenItsRootAndItsChildrenWritesNoChildren', async () => {
  const oldChildren = deferred<ReturnType<typeof row>[]>();
  service.getChildren.mockImplementation(async (_repositoryId: string, folderId: string) => (
    folderId === 'old-root-id' ? oldChildren.promise
      : folderId === 'new-root-id' ? [row('new-child-id', 'new-child-name.txt')] : []));
  service.getRootFolder.mockResolvedValueOnce(ROOTS.old).mockResolvedValueOnce(ROOTS.fresh);
  const { rerender } = render(picker(true));
  await waitFor(() => expect(service.getChildren, 'the children of the first root asked for').toHaveBeenCalledWith('bedroom', 'old-root-id'));

  // Reopened while the first root's children are still on their way.
  rerender(picker(false));
  rerender(picker(true));
  await shown('new-child-name.txt');
  await act(async () => {
    oldChildren.resolve([row('old-child-id', 'old-child-name.txt')]);
    await oldChildren.promise;
  });
  await settle();
  expect(screen.queryByText('old-child-name.txt'), 'the children from before the reopening').toBeNull();
  expect(screen.queryByText('new-child-name.txt'), 'the children loaded on reopening').not.toBeNull();
});

const FOLDER_A = { ...row('folder-a-id', 'folder-a-name'), baseType: 'cmis:folder' };
const FOLDER_B = { ...row('folder-b-id', 'folder-b-name'), baseType: 'cmis:folder' };

/** The root holds folders A and B; A's children are held until the lock answers them. */
function foldersAAndB() {
  const childrenOfA = deferred<ReturnType<typeof row>[]>();
  service.getChildren.mockImplementation(async (_repositoryId: string, folderId: string) => (
    folderId === 'root-folder-id' ? [FOLDER_A, FOLDER_B]
      : folderId === 'folder-a-id' ? childrenOfA.promise
        : folderId === 'folder-b-id' ? [row('b-child-id', 'b-child-name.txt')] : []));
  return childrenOfA;
}

async function chooseInTheTree(name: string) {
  await waitFor(() => expect(document.querySelector('.ant-modal .ant-tree'), 'the tree').not.toBeNull());
  const tree = document.querySelector('.ant-modal .ant-tree') as HTMLElement;
  await waitFor(() => expect(within(tree).queryByText(name), `${name} in the tree`).not.toBeNull());
  fireEvent.click(within(tree).getByText(name));
}

it('aFolderChosenEarlierInTheTreeAnsweredLastWritesNothing', async () => {
  const childrenOfA = foldersAAndB();
  render(picker(true));
  await chooseInTheTree('folder-a-name');
  await waitFor(() => expect(service.getChildren).toHaveBeenCalledWith('bedroom', 'folder-a-id'));
  await chooseInTheTree('folder-b-name');
  await shown('b-child-name.txt');

  // A's children answer last.
  await act(async () => {
    childrenOfA.resolve([row('a-child-id', 'a-child-name.txt')]);
    await childrenOfA.promise;
  });
  await settle();
  expect(screen.queryByText('a-child-name.txt'), 'the children of the folder chosen first').toBeNull();
  expect(screen.queryByText('b-child-name.txt'), 'the children of the folder chosen last').not.toBeNull();
});

it('aFolderChosenBeforeThePickerWasReopenedWritesNothing', async () => {
  const childrenOfA = foldersAAndB();
  const { rerender } = render(picker(true));
  await chooseInTheTree('folder-a-name');
  await waitFor(() => expect(service.getChildren).toHaveBeenCalledWith('bedroom', 'folder-a-id'));
  rerender(picker(false));
  rerender(picker(true));
  await chooseInTheTree('folder-b-name');
  await shown('b-child-name.txt');

  // The folder chosen before the reopening answers last.
  await act(async () => {
    childrenOfA.resolve([row('a-child-id', 'a-child-name.txt')]);
    await childrenOfA.promise;
  });
  await settle();
  expect(screen.queryByText('a-child-name.txt'), 'the children of the folder chosen before the reopening').toBeNull();
  expect(screen.queryByText('b-child-name.txt'), 'the children of the folder chosen after it').not.toBeNull();
});

it('reopeningThePickerShowsNoRowOfTheFolderShownBefore', async () => {
  foldersAAndB();
  const { rerender } = render(picker(true));
  await waitFor(() => expect(document.querySelector('.ant-modal .ant-table-tbody')?.textContent ?? '',
    'the root\'s rows in the table').toContain('folder-b-name'));

  // Reopened; the root load is held.
  const held = deferred<typeof ROOTS.fresh>();
  service.getRootFolder.mockReturnValueOnce(held.promise);
  rerender(picker(false));
  rerender(picker(true));
  await settle();
  expect(document.querySelector('.ant-modal .ant-table-tbody')?.textContent ?? '', 'the rows shown before the reopening')
    .not.toContain('folder-b-name');
  await act(async () => {
    held.resolve(ROOTS.fresh);
    await held.promise;
  });
});

it('aFolderChosenBeforeThePickerWasReopenedDoesNotReplaceTheRootsRows', async () => {
  const childrenOfA = foldersAAndB();
  const { rerender } = render(picker(true));
  await chooseInTheTree('folder-a-name');
  await waitFor(() => expect(service.getChildren).toHaveBeenCalledWith('bedroom', 'folder-a-id'));

  // Reopened: the table shows the root's rows again, and nothing is chosen in the tree.
  rerender(picker(false));
  rerender(picker(true));
  await waitFor(() => expect(document.querySelector('.ant-modal .ant-table-tbody')?.textContent ?? '',
    'the root\'s rows after the reopening').toContain('folder-b-name'));

  // The folder chosen before the reopening answers.
  await act(async () => {
    childrenOfA.resolve([row('a-child-id', 'a-child-name.txt')]);
    await childrenOfA.promise;
  });
  await settle();
  expect(screen.queryByText('a-child-name.txt'), 'the children of the folder chosen before the reopening').toBeNull();
  expect(document.querySelector('.ant-modal .ant-table-tbody')?.textContent ?? '', 'the root\'s rows').toContain('folder-b-name');
});

it('choosingAnotherFolderInTheTreeDropsTheRowsAndTheChoiceOfTheOneBefore', async () => {
  const childrenOfA = foldersAAndB();
  render(picker(true));
  await chooseInTheTree('folder-b-name');
  await shown('b-child-name.txt');
  fireEvent.click(screen.getByText('b-child-name.txt'));
  await waitFor(() => expect(confirmButton().disabled, 'the confirm button with a choice in B').toBe(false));

  // Folder A is chosen; its children are on their way.
  await chooseInTheTree('folder-a-name');
  await waitFor(() => expect(service.getChildren).toHaveBeenCalledWith('bedroom', 'folder-a-id'));
  expect(screen.queryByText('b-child-name.txt'), 'the rows of B while A loads').toBeNull();
  expect(confirmButton().disabled, 'the choice in B while A loads').toBe(true);
  await act(async () => {
    childrenOfA.resolve([row('a-child-id', 'a-child-name.txt')]);
    await childrenOfA.promise;
  });
  await shown('a-child-name.txt');
  expect(confirmButton().disabled, 'the choice in B under A').toBe(true);
  expect(confirmButton().textContent, 'the choice in B on the confirm button').not.toContain('b-child-name.txt');
});

it('aChoiceMadeWhileATreeFolderLoadsIsDroppedWhenItAnswers', async () => {
  const childrenOfA = foldersAAndB();
  render(picker(true));
  await chooseInTheTree('folder-a-name');
  await waitFor(() => expect(service.getChildren).toHaveBeenCalledWith('bedroom', 'folder-a-id'));

  // While A loads, an object is chosen from a search.
  fireEvent.click(screen.getByRole('tab', { name: 'Search' }));
  service.search.mockResolvedValueOnce(PICKER_ANSWER);
  pickerSearch('first');
  await shown('picker-row-name.txt');
  fireEvent.click(screen.getByText('picker-row-name.txt'));
  await waitFor(() => expect(confirmButton().disabled, 'the confirm button with a choice').toBe(false));

  // A answers: the choice made while it loaded goes.
  await act(async () => {
    childrenOfA.resolve([row('a-child-id', 'a-child-name.txt')]);
    await childrenOfA.promise;
  });
  await settle();
  expect(confirmButton().disabled, 'the choice made while A loaded, after A answered').toBe(true);
});

it('anExpansionFromBeforeThePickerWasReopenedWritesNothing', async () => {
  // The root's children: the table's first, then the tree's expansion, which is held; after the reopening, new ones.
  const heldExpansion = deferred<ReturnType<typeof row>[]>();
  let rootReads = 0;
  service.getChildren.mockImplementation(async (_repositoryId: string, folderId: string) => {
    if (folderId !== 'root-folder-id') return [];
    rootReads++;
    if (rootReads === 1) return [FOLDER_A];
    if (rootReads === 2) return heldExpansion.promise;
    return [FOLDER_B];
  });
  const { rerender } = render(picker(true));
  await waitFor(() => expect(rootReads, 'the tree\'s expansion of the root asked for').toBe(2));
  rerender(picker(false));
  rerender(picker(true));
  await chooseInTheTree('folder-b-name');

  // The expansion from before the reopening answers last.
  await act(async () => {
    heldExpansion.resolve([{ ...row('old-folder-id', 'old-folder-name'), baseType: 'cmis:folder' }]);
    await heldExpansion.promise;
  });
  await settle();
  const tree = document.querySelector('.ant-modal .ant-tree') as HTMLElement;
  expect(within(tree).queryByText('old-folder-name'), 'the expansion from before the reopening').toBeNull();
  expect(within(tree).queryByText('folder-b-name'), 'the tree loaded on reopening').not.toBeNull();
});

it('aReopenedPickerWhoseRootFailsShowsNoTreeFromBefore', async () => {
  foldersAAndB();
  const { rerender } = render(picker(true));
  await chooseInTheTree('folder-a-name');

  // Reopened; the root cannot be read this time.
  service.getRootFolder.mockRejectedValueOnce(new Error('the root could not be read'));
  rerender(picker(false));
  rerender(picker(true));
  await waitFor(() => expect(service.getRootFolder.mock.calls.length, 'the root load on reopening').toBeGreaterThan(1));
  await settle();
  const tree = document.querySelector('.ant-modal .ant-tree');
  expect(tree ? within(tree as HTMLElement).queryByText('folder-a-name') : null, 'the tree from before the reopening').toBeNull();
  expect(tree ? within(tree as HTMLElement).queryByText('root') : null, 'the root from before the reopening').toBeNull();
});
