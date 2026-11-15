import { it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, cleanup, fireEvent, waitFor, act } from '@testing-library/react';
import i18n from '../../i18n';
import { DocumentList } from './DocumentList';
import { SearchCutShortError } from '../../services/searchCutShort';

// R349 (R344 batch, 3.4.2): the document list's name search is the second screen that calls CMISService.search. A search
// answered cut short by the ACL scan limit leaves neither the folder's rows (nor a previous search's) nor anything tied to
// them — the selection, the export of the selection, a delete of it — and shows the guidance in place of the table. A
// search, clearing it and moving to another folder are each a new dataset: the selection does not carry into it. Only the
// last load or search started writes the list. Real i18next (Japanese); the service, the router and the tree are stand-ins.
// The test names are single words because the negative-control runner reads them as lock names.
const service = vi.hoisted(() => ({
  search: vi.fn(),
  getRootFolder: vi.fn(),
  getChildrenPaged: vi.fn(),
  getObject: vi.fn(),
  listFolderConnectors: vi.fn(),
  getDocumentTypes: vi.fn(),
  getFolderTypes: vi.fn(),
  getType: vi.fn(),
  getDownloadUrl: vi.fn(),
  checkOut: vi.fn(),
  createFolder: vi.fn(),
  getObjectByPath: vi.fn(),
}));
vi.mock('../../services/cmis', () => ({
  CMISService: vi.fn().mockImplementation(function () {
    return service;
  }),
}));
vi.mock('../../contexts/AuthContext', () => ({
  useAuth: () => ({ handleAuthError: vi.fn(), authToken: null }),
}));
const router = vi.hoisted(() => ({ params: new URLSearchParams(), setParams: vi.fn() }));
vi.mock('react-router-dom', () => ({
  useNavigate: () => vi.fn(),
  useSearchParams: () => [router.params, router.setParams],
}));
vi.mock('../FolderTree/FolderTree', () => ({
  FolderTree: ({ onSelect }: { onSelect: (folderId: string, folderPath: string) => void }) => (
    <>
      <button type="button" onClick={() => onSelect('other-folder-id', '/other')}>to-the-other-folder</button>
      <button type="button" onClick={() => onSelect('root-folder-id', '/')}>to-the-root</button>
    </>
  ),
}));
vi.mock('../../services/cloud-auth', () => ({ fetchCloudAuthConfig: vi.fn().mockResolvedValue(null) }));
vi.mock('../../services/cloud-drive', () => ({
  getGoogleDriveAccessToken: vi.fn(),
  getOneDriveAccessToken: vi.fn(),
  importFromGoogleDrive: vi.fn(),
  importFromOneDrive: vi.fn(),
  listGoogleDriveFiles: vi.fn(),
  listOneDriveFiles: vi.fn(),
}));

const GUIDANCE = '完全な検索結果を確認できないため、条件を絞って再試行してください。';
const SELECTED = '選択をエクスポート (1件)';
const DELETE_SELECTED = '1件を削除';

const row = (id: string, name: string) => ({
  id, name, objectType: 'cmis:document', baseType: 'cmis:document', properties: {},
  allowableActions: { canDeleteObject: true } as Record<string, boolean>,
});
const FOLDER_ROW = { ...row('folder-row-id', 'folder-row-name.txt'), allowableActions: { canDeleteObject: true, canCheckOut: true } };
const OTHER_FOLDER_ROW = { ...row('other-folder-row-id', 'other-folder-row-name.txt'), allowableActions: { canDeleteObject: true, canCheckOut: true } };
const answer = (...objects: ReturnType<typeof row>[]) => ({ objects, numItems: objects.length, hasMoreItems: false });
const SEARCH_ANSWER = answer(row('search-row-id', 'search-row-name.txt'));
const OLDER_ANSWER = answer(row('older-search-row-id', 'older-search-row-name.txt'));

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
  await i18n.changeLanguage('ja');
  service.getRootFolder.mockResolvedValue({ id: 'root-folder-id', name: 'root', path: '/' });
  service.getChildrenPaged.mockImplementation(async (_repositoryId: string, folderId: string) => ({
    items: folderId === 'other-folder-id' ? [OTHER_FOLDER_ROW] : [FOLDER_ROW], numItems: 1, hasMoreItems: false,
  }));
  service.getObject.mockImplementation(async (_repositoryId: string, id: string) => ({ id, path: '/', allowableActions: {} }));
  service.listFolderConnectors.mockResolvedValue([]);
  service.getDocumentTypes.mockResolvedValue([]);
  service.getFolderTypes.mockResolvedValue([]);
});

afterEach(() => {
  cleanup();
  sessionStorage.clear();
  // Every stand-in starts afresh: an answer queued by a lock that stopped early does not answer the next lock.
  for (const stand of Object.values(service)) {
    stand.mockReset();
  }
});

/** Waits until {@code text} is on the screen; a timeout fails this lock's own expect(), not the query's harness error. */
async function shown(text: string) {
  await waitFor(() => expect(screen.queryByText(text), `${text} is not shown`).not.toBeNull());
}

async function gone(text: string) {
  await waitFor(() => expect(screen.queryByText(text), `${text} is still shown`).toBeNull());
}

/** Lets every answer already settled reach the screen. */
async function settle() {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
}

async function opened() {
  const { container } = render(<DocumentList repositoryId="bedroom" />);
  await shown('folder-row-name.txt');
  return container;
}

function searchByName(container: HTMLElement, keyword: string) {
  fireEvent.change(screen.getByPlaceholderText('検索キーワードを入力'), { target: { value: keyword } });
  const button = container.querySelector('.search-button');
  if (!button) {
    throw new Error('the search button is not there');
  }
  fireEvent.click(button);
}

function selectTheRowOf(name: string) {
  const checkbox = screen.getByText(name).closest('tr')?.querySelector('input[type="checkbox"]');
  if (!checkbox) {
    throw new Error(`the row of ${name} has no checkbox`);
  }
  fireEvent.click(checkbox);
}

it('aCutShortNameSearchLeavesNoFolderRowNorItsSelection', async () => {
  const container = await opened();
  selectTheRowOf('folder-row-name.txt');
  await shown(SELECTED);
  await shown(DELETE_SELECTED);

  service.search.mockRejectedValueOnce(new SearchCutShortError());
  searchByName(container, 'first');
  await shown(GUIDANCE);
  expect(screen.queryByText('folder-row-name.txt'), 'the folder row').toBeNull();
  expect(container.querySelector('.ant-table'), 'a table').toBeNull();
  expect(screen.queryByText(SELECTED), 'the export of the folder selection').toBeNull();
  expect(screen.queryByText(DELETE_SELECTED), 'a delete of the folder selection').toBeNull();
  expect(screen.queryByText('エクスポート'), 'the plain export').not.toBeNull();

  // The next search starts: the guidance goes, and the table shown while it runs holds no row of the folder.
  const next = deferred<typeof SEARCH_ANSWER>();
  service.search.mockReturnValueOnce(next.promise);
  searchByName(container, 'second');
  await gone(GUIDANCE);
  expect(container.querySelector('.ant-table'), 'the table of the next search').not.toBeNull();
  expect(screen.queryByText('folder-row-name.txt'), 'the folder row under the next search').toBeNull();
  await act(async () => {
    next.resolve(answer());
    await next.promise;
  });
});

it('aRowSelectedWhileTheSearchRunsIsNotLeftAfterItIsCutShort', async () => {
  const container = await opened();
  const running = deferred<typeof SEARCH_ANSWER>();
  service.search.mockReturnValueOnce(running.promise);
  searchByName(container, 'first');
  await waitFor(() => expect(service.search).toHaveBeenCalledTimes(1));
  selectTheRowOf('folder-row-name.txt');
  await shown(SELECTED);

  await act(async () => {
    running.reject(new SearchCutShortError());
    await running.promise.catch(() => undefined);
  });
  await shown(GUIDANCE);
  expect(screen.queryByText(SELECTED), 'the selection made while the search ran').toBeNull();
});

it('aNameSearchDoesNotCarryTheFolderSelection', async () => {
  const container = await opened();
  selectTheRowOf('folder-row-name.txt');
  await shown(SELECTED);

  // The search is a new dataset from the moment it starts: the folder's selection goes while it runs.
  const running = deferred<typeof SEARCH_ANSWER>();
  service.search.mockReturnValueOnce(running.promise);
  searchByName(container, 'first');
  await gone(SELECTED);
  await act(async () => {
    running.resolve(SEARCH_ANSWER);
    await running.promise;
  });
  await shown('search-row-name.txt');
  expect(screen.queryByText(SELECTED), 'the folder selection in the search').toBeNull();
  expect(screen.queryByText(GUIDANCE), 'the guidance after an unmarked answer').toBeNull();
});

it('aNameSearchAnsweredAfterACutShortOneShowsItsRowsAndNoGuidance', async () => {
  const container = await opened();
  service.search.mockRejectedValueOnce(new SearchCutShortError()).mockResolvedValueOnce(SEARCH_ANSWER);
  searchByName(container, 'first');
  await shown(GUIDANCE);
  searchByName(container, 'second');
  await shown('search-row-name.txt');
  expect(screen.queryByText(GUIDANCE), 'the guidance after the answer').toBeNull();
});

it('aFailedNameSearchLeavesNoFolderRowAndNoGuidance', async () => {
  const container = await opened();
  service.search.mockRejectedValueOnce(new Error('the search failed'));
  searchByName(container, 'first');
  await waitFor(() => expect(service.search).toHaveBeenCalledTimes(1));
  await settle();
  expect(container.querySelector('.ant-table'), 'the table of the failed search').not.toBeNull();
  expect(screen.queryByText('folder-row-name.txt'), 'the folder row as the failed search\'s').toBeNull();
  expect(screen.queryByText(GUIDANCE), 'the guidance after a failure that is not a cut short').toBeNull();
});

/** Holds the next folder load until the lock lets it answer — what the screen shows at once is the screen's own. */
function holdTheNextFolderLoad() {
  const load = deferred<{ items: ReturnType<typeof row>[]; numItems: number; hasMoreItems: boolean }>();
  service.getChildrenPaged.mockReturnValueOnce(load.promise);
  return async (...items: ReturnType<typeof row>[]) => {
    await act(async () => {
      load.resolve({ items, numItems: items.length, hasMoreItems: false });
      await load.promise;
    });
  };
}

it('clearingTheSearchTakesTheGuidanceAway', async () => {
  const container = await opened();
  service.search.mockRejectedValueOnce(new SearchCutShortError());
  searchByName(container, 'first');
  await shown(GUIDANCE);
  const answerTheFolder = holdTheNextFolderLoad();
  fireEvent.click(screen.getByText('クリア'));
  await gone(GUIDANCE);
  await answerTheFolder(FOLDER_ROW);
  await shown('folder-row-name.txt');
});

it('clearingTheSearchDoesNotCarryItsSelection', async () => {
  const container = await opened();
  service.search.mockResolvedValueOnce(SEARCH_ANSWER);
  searchByName(container, 'first');
  await shown('search-row-name.txt');
  selectTheRowOf('search-row-name.txt');
  await shown(SELECTED);
  const answerTheFolder = holdTheNextFolderLoad();
  fireEvent.click(screen.getByText('クリア'));
  await gone(SELECTED);
  await answerTheFolder(FOLDER_ROW);
  await shown('folder-row-name.txt');
  expect(screen.queryByText(SELECTED), 'the search selection in the folder').toBeNull();
});

it('movingToAnotherFolderTakesTheGuidanceAwayAndLeavesTheSearch', async () => {
  const container = await opened();
  service.search.mockRejectedValueOnce(new SearchCutShortError());
  searchByName(container, 'first');
  await shown(GUIDANCE);
  const answerTheFolder = holdTheNextFolderLoad();
  fireEvent.click(screen.getByText('to-the-other-folder'));
  await gone(GUIDANCE);
  expect(screen.queryByText('クリア'), 'the search mode while the other folder loads').toBeNull();
  await answerTheFolder(OTHER_FOLDER_ROW);
  await shown('other-folder-row-name.txt');
  expect(screen.queryByText('クリア'), 'the search mode in the other folder').toBeNull();
});

it('movingToAnotherFolderDoesNotCarryTheSelection', async () => {
  await opened();
  selectTheRowOf('folder-row-name.txt');
  await shown(SELECTED);
  // The other folder is another dataset from the moment the move starts: the selection goes while it loads.
  const answerTheFolder = holdTheNextFolderLoad();
  fireEvent.click(screen.getByText('to-the-other-folder'));
  await gone(SELECTED);
  await answerTheFolder(OTHER_FOLDER_ROW);
  await shown('other-folder-row-name.txt');
  expect(screen.queryByText(SELECTED), 'the selection of the previous folder').toBeNull();
});

it('anOlderNameSearchAnsweredLaterDoesNotOverwriteANewerCutShort', async () => {
  const container = await opened();
  const older = deferred<typeof SEARCH_ANSWER>();
  service.search.mockReturnValueOnce(older.promise).mockRejectedValueOnce(new SearchCutShortError());
  searchByName(container, 'older');
  searchByName(container, 'newer');
  await shown(GUIDANCE);
  await act(async () => {
    older.resolve(OLDER_ANSWER);
    await older.promise;
  });
  await settle();
  expect(screen.queryByText(GUIDANCE), 'the guidance of the newer search').not.toBeNull();
  expect(screen.queryByText('older-search-row-name.txt'), 'the older row').toBeNull();

  // Nor is the older answer kept to surface under the next search.
  const next = deferred<typeof SEARCH_ANSWER>();
  service.search.mockReturnValueOnce(next.promise);
  searchByName(container, 'next');
  await gone(GUIDANCE);
  expect(screen.queryByText('older-search-row-name.txt'), 'the older row under the next search').toBeNull();
  await act(async () => {
    next.resolve(answer());
    await next.promise;
  });
});

it('anOlderCutShortArrivingLaterDoesNotOverwriteANewerAnswer', async () => {
  const container = await opened();
  const older = deferred<typeof SEARCH_ANSWER>();
  service.search.mockReturnValueOnce(older.promise).mockResolvedValueOnce(SEARCH_ANSWER);
  searchByName(container, 'older');
  searchByName(container, 'newer');
  await shown('search-row-name.txt');
  await act(async () => {
    older.reject(new SearchCutShortError());
    await older.promise.catch(() => undefined);
  });
  await settle();
  expect(screen.queryByText('search-row-name.txt'), 'the row of the newer search').not.toBeNull();
  expect(screen.queryByText(GUIDANCE), 'the guidance of the older search').toBeNull();
});

it('anOlderAnswerArrivingFirstNeitherWritesNorEndsTheNewerSearch', async () => {
  const container = await opened();
  const older = deferred<typeof SEARCH_ANSWER>();
  const newer = deferred<typeof SEARCH_ANSWER>();
  service.search.mockReturnValueOnce(older.promise).mockReturnValueOnce(newer.promise);
  searchByName(container, 'older');
  searchByName(container, 'newer');
  await waitFor(() => expect(service.search).toHaveBeenCalledTimes(2));
  await act(async () => {
    older.resolve(OLDER_ANSWER);
    await older.promise;
  });
  await settle();
  expect(screen.queryByText('older-search-row-name.txt'), 'the older row').toBeNull();
  expect(container.querySelector('.ant-table-wrapper .ant-spin-spinning'), 'the newer search\'s spinner').not.toBeNull();
  await act(async () => {
    newer.resolve(SEARCH_ANSWER);
    await newer.promise;
  });
  await shown('search-row-name.txt');
});

it('anOperationOnASearchRowReloadsTheFolderOutsideTheSearch', async () => {
  const container = await opened();
  service.search.mockResolvedValueOnce(answer({
    ...row('search-row-id', 'search-row-name.txt'),
    allowableActions: { canDeleteObject: true, canCheckOut: true },
  }));
  service.checkOut.mockResolvedValue({});
  searchByName(container, 'first');
  await shown('search-row-name.txt');
  selectTheRowOf('search-row-name.txt');
  await shown(SELECTED);

  // The check-out reloads the folder: its rows are the folder's, not the search's.
  const checkout = screen.getByText('search-row-name.txt').closest('tr')?.querySelector('.anticon-edit')?.closest('button');
  if (!checkout) {
    throw new Error('the search row has no check-out button');
  }
  fireEvent.click(checkout);
  await shown('folder-row-name.txt');
  expect(service.checkOut, 'the check-out').toHaveBeenCalledTimes(1);
  expect(screen.queryByText('クリア'), 'the search mode over the folder\'s rows').toBeNull();
  expect(screen.queryByText(SELECTED), 'the selection made in the search').toBeNull();
});

it('anOperationAfterACutShortSearchReloadsTheFolderWithoutTheGuidance', async () => {
  service.getObject.mockImplementation(async (_repositoryId: string, id: string) => ({
    id, path: '/', allowableActions: { canCreateFolder: true },
  }));
  const container = await opened();
  service.search.mockRejectedValueOnce(new SearchCutShortError());
  service.createFolder.mockResolvedValue({});
  searchByName(container, 'first');
  await shown(GUIDANCE);

  // A folder made from the toolbar reloads the folder.
  fireEvent.click(screen.getByText('フォルダ作成'));
  const name = await waitFor(() => {
    const box = screen.queryByPlaceholderText('フォルダ名を入力');
    expect(box, 'the folder name box').not.toBeNull();
    return box as HTMLElement;
  });
  fireEvent.change(name, { target: { value: 'made-after-the-cut-short' } });
  const form = name.closest('form');
  if (!form) {
    throw new Error('the folder form is not there');
  }
  fireEvent.submit(form);
  await waitFor(() => expect(service.createFolder, 'the folder made').toHaveBeenCalledTimes(1));
  await gone(GUIDANCE);
  await shown('folder-row-name.txt');
  expect(screen.queryByText('クリア'), 'the search mode over the folder\'s rows').toBeNull();
});

/** Clicks the check-out button of the row that shows {@code name}. */
function checkOutTheRowOf(name: string) {
  const checkout = screen.getByText(name).closest('tr')?.querySelector('.anticon-edit')?.closest('button');
  if (!checkout) {
    throw new Error(`the row of ${name} has no check-out button`);
  }
  fireEvent.click(checkout);
}

it('anOperationBegunInTheFolderEndingAfterANameSearchLeavesTheSearch', async () => {
  const container = await opened();
  const checkout = deferred<unknown>();
  service.checkOut.mockReturnValueOnce(checkout.promise);
  checkOutTheRowOf('folder-row-name.txt');
  await waitFor(() => expect(service.checkOut, 'the check-out begun in the folder').toHaveBeenCalledTimes(1));

  // While it runs, a name search is answered and a row of it is chosen.
  service.search.mockResolvedValueOnce(SEARCH_ANSWER);
  searchByName(container, 'first');
  await shown('search-row-name.txt');
  selectTheRowOf('search-row-name.txt');
  await shown(SELECTED);

  // The check-out ends and reloads the folder: its rows are the folder's, not the search's.
  await act(async () => {
    checkout.resolve({});
    await checkout.promise;
  });
  await shown('folder-row-name.txt');
  expect(screen.queryByText('クリア'), 'the search mode over the folder\'s rows').toBeNull();
  expect(screen.queryByText(SELECTED), 'the selection made in the search').toBeNull();
});

it('aRowSelectedWhileANameSearchRunsIsNotCarriedIntoItsAnswer', async () => {
  const container = await opened();
  const running = deferred<typeof SEARCH_ANSWER>();
  service.search.mockReturnValueOnce(running.promise);
  searchByName(container, 'first');
  await waitFor(() => expect(service.search).toHaveBeenCalledTimes(1));
  selectTheRowOf('folder-row-name.txt');
  await shown(SELECTED);
  await act(async () => {
    running.resolve(SEARCH_ANSWER);
    await running.promise;
  });
  await shown('search-row-name.txt');
  expect(screen.queryByText(SELECTED), 'the selection made while the search ran, over its answer').toBeNull();
});

it('aNameSearchStartedWhileAFolderLoadsLeavesThatFolderItsOwnActions', async () => {
  service.getObject.mockImplementation(async (_repositoryId: string, id: string) => ({
    id, path: '/', allowableActions: id === 'other-folder-id' ? {} : { canCreateFolder: true },
  }));
  const container = await opened();
  await shown('フォルダ作成');

  // Moving to a folder where nothing can be made; a name search starts before that folder answers.
  const answerTheFolder = holdTheNextFolderLoad();
  const loadsBefore = service.getChildrenPaged.mock.calls.length;
  fireEvent.click(screen.getByText('to-the-other-folder'));
  await waitFor(() => expect(service.getChildrenPaged.mock.calls.length, 'the other folder\'s load').toBe(loadsBefore + 1));
  service.search.mockResolvedValueOnce(SEARCH_ANSWER);
  searchByName(container, 'first');
  await shown('search-row-name.txt');
  await answerTheFolder(OTHER_FOLDER_ROW);

  // The search keeps the rows; the folder's own actions are the folder's it moved to.
  await gone('フォルダ作成');
  expect(screen.queryByText('search-row-name.txt'), 'the rows of the search').not.toBeNull();
  expect(screen.queryByText('other-folder-row-name.txt'), 'the other folder\'s rows over the search').toBeNull();
});

it('aFailedFolderReloadAfterAnOperationInTheSearchLeavesTheSearch', async () => {
  const container = await opened();
  service.search.mockResolvedValueOnce(answer({
    ...row('search-row-id', 'search-row-name.txt'),
    allowableActions: { canDeleteObject: true, canCheckOut: true },
  }));
  service.checkOut.mockResolvedValue({});
  searchByName(container, 'first');
  await shown('search-row-name.txt');
  selectTheRowOf('search-row-name.txt');
  await shown(SELECTED);

  // The check-out ends; reloading the folder fails.
  service.getChildrenPaged.mockRejectedValueOnce(new Error('the folder could not be read'));
  checkOutTheRowOf('search-row-name.txt');
  await waitFor(() => expect(service.checkOut).toHaveBeenCalledTimes(1));
  await gone('クリア');
  expect(screen.queryByText(SELECTED), 'the selection made in the search').toBeNull();
  expect(screen.queryByText('search-row-name.txt'), 'the search row as the folder\'s').toBeNull();
});

it('anOperationEndingAfterAFolderMoveReloadsTheFolderAndPageNowShown', async () => {
  const ROOT_PAGE_2_ROW = { ...row('root-page-2-row-id', 'root-page-2-row-name.txt'), allowableActions: { canCheckOut: true } };
  service.getChildrenPaged.mockImplementation(async (_repositoryId: string, folderId: string, paging: { skipCount: number }) => (
    folderId === 'other-folder-id' ? { items: [OTHER_FOLDER_ROW], numItems: 1, hasMoreItems: false }
      : paging.skipCount === 0 ? { items: [FOLDER_ROW], numItems: -1, hasMoreItems: true }
        : { items: [ROOT_PAGE_2_ROW], numItems: -1, hasMoreItems: false }));
  const container = await opened();
  const next = container.querySelector('.ant-pagination-next');
  if (!next) {
    throw new Error('the list has no next page');
  }
  fireEvent.click(next);
  await shown('root-page-2-row-name.txt');

  // A check-out begun on the second page of the root ends after moving to another folder.
  const checkout = deferred<unknown>();
  service.checkOut.mockReturnValueOnce(checkout.promise);
  checkOutTheRowOf('root-page-2-row-name.txt');
  await waitFor(() => expect(service.checkOut).toHaveBeenCalledTimes(1));
  fireEvent.click(screen.getByText('to-the-other-folder'));
  await shown('other-folder-row-name.txt');
  await act(async () => {
    checkout.resolve({});
    await checkout.promise;
  });
  await settle();

  // Its reload reads the folder and page shown now: the other folder's first page.
  const reload = service.getChildrenPaged.mock.calls[service.getChildrenPaged.mock.calls.length - 1];
  expect([reload[1], reload[2]?.skipCount], 'the folder and page the reload read').toEqual(['other-folder-id', 0]);
  expect(screen.queryByText('other-folder-row-name.txt'), 'the folder shown').not.toBeNull();
  expect(screen.queryByText('root-page-2-row-name.txt'), 'the root\'s rows over the other folder').toBeNull();
});

it('aRowSelectedWhileTheFolderLoadsAfterClearingIsNotCarriedIntoIt', async () => {
  const container = await opened();
  service.search.mockResolvedValueOnce(SEARCH_ANSWER);
  searchByName(container, 'first');
  await shown('search-row-name.txt');
  const answerTheFolder = holdTheNextFolderLoad();
  fireEvent.click(screen.getByText('クリア'));
  await gone('クリア');

  // While the folder loads, the search's row is still under the spinner and is chosen.
  selectTheRowOf('search-row-name.txt');
  await shown(SELECTED);
  await answerTheFolder(FOLDER_ROW);
  await shown('folder-row-name.txt');
  expect(screen.queryByText(SELECTED), 'the selection made while the folder loaded').toBeNull();
});

it('aRowSelectedWhileAnotherFolderLoadsIsNotCarriedIntoIt', async () => {
  await opened();
  const answerTheFolder = holdTheNextFolderLoad();
  fireEvent.click(screen.getByText('to-the-other-folder'));
  await waitFor(() => expect(service.getChildrenPaged.mock.calls.length, 'the other folder\'s load').toBeGreaterThan(1));

  // While the other folder loads, the previous folder's row is still under the spinner and is chosen.
  selectTheRowOf('folder-row-name.txt');
  await shown(SELECTED);
  await answerTheFolder(OTHER_FOLDER_ROW);
  await shown('other-folder-row-name.txt');
  expect(screen.queryByText(SELECTED), 'the selection made while the other folder loaded').toBeNull();
});

it('anOperationEndingAfterANameSearchStartedLeavesTheSearchSpinner', async () => {
  const container = await opened();
  const checkout = deferred<unknown>();
  service.checkOut.mockReturnValueOnce(checkout.promise);
  checkOutTheRowOf('folder-row-name.txt');
  await waitFor(() => expect(service.checkOut).toHaveBeenCalledTimes(1));
  const running = deferred<typeof SEARCH_ANSWER>();
  service.search.mockReturnValueOnce(running.promise);
  searchByName(container, 'first');
  await waitFor(() => expect(service.search).toHaveBeenCalledTimes(1));

  // The check-out fails while the search runs: the spinner is the search's.
  await act(async () => {
    checkout.reject(new Error('the check-out failed'));
    await checkout.promise.catch(() => undefined);
  });
  await settle();
  expect(container.querySelector('.ant-table-wrapper .ant-spin-spinning'), 'the spinner of the search still running').not.toBeNull();
  await act(async () => {
    running.resolve(SEARCH_ANSWER);
    await running.promise;
  });
  await shown('search-row-name.txt');
});

it('aRowSelectedWhileAMoveIsOvertakenByAnOperationsReloadIsNotCarriedIntoIt', async () => {
  await opened();
  const checkout = deferred<unknown>();
  service.checkOut.mockReturnValueOnce(checkout.promise);
  checkOutTheRowOf('folder-row-name.txt');
  await waitFor(() => expect(service.checkOut).toHaveBeenCalledTimes(1));

  // The move's own load is held; the previous folder's row is chosen under its spinner.
  const answerTheMove = holdTheNextFolderLoad();
  fireEvent.click(screen.getByText('to-the-other-folder'));
  await waitFor(() => expect(service.getChildrenPaged.mock.calls.length, 'the move\'s load').toBeGreaterThan(1));
  selectTheRowOf('folder-row-name.txt');
  await shown(SELECTED);

  // The check-out ends first: its reload reads the folder moved to and writes it, overtaking the move's load.
  await act(async () => {
    checkout.resolve({});
    await checkout.promise;
  });
  await shown('other-folder-row-name.txt');
  expect(screen.queryByText(SELECTED), 'the selection made while the move loaded').toBeNull();
  await answerTheMove(OTHER_FOLDER_ROW);
});

it('anOperationEndingAfterAMoveToADeletedFolderFallsBackToTheRoot', async () => {
  await opened();
  const checkout = deferred<unknown>();
  service.checkOut.mockReturnValueOnce(checkout.promise);
  checkOutTheRowOf('folder-row-name.txt');
  await waitFor(() => expect(service.checkOut).toHaveBeenCalledTimes(1));

  // Moving to a folder that is gone; its load is held, so the check-out's reload is the one that finds it gone.
  const answerTheMove = holdTheNextFolderLoad();
  fireEvent.click(screen.getByText('to-the-other-folder'));
  await waitFor(() => expect(service.getChildrenPaged.mock.calls.length, 'the move\'s load').toBeGreaterThan(1));
  service.getChildrenPaged.mockRejectedValueOnce(new Error('the folder was not found'));
  await act(async () => {
    checkout.resolve({});
    await checkout.promise;
  });

  // The reload judges the folder it read: gone, and not the root — so the list goes back to the root.
  await shown('folder-row-name.txt');
  expect(sessionStorage.getItem('nemakiware_selectedFolderId_bedroom'), 'the folder that is gone, kept as the one shown')
    .not.toBe('other-folder-id');
  await answerTheMove(OTHER_FOLDER_ROW);
});

it('aReloadOfTheSameFolderKeepsItsSelection', async () => {
  await opened();
  fireEvent.click(screen.getByText('to-the-other-folder'));
  await shown('other-folder-row-name.txt');
  selectTheRowOf('other-folder-row-name.txt');
  await shown(SELECTED);

  // A check-out in the folder shown reloads the same folder: the selection made in it stays.
  service.checkOut.mockResolvedValueOnce({});
  const loadsBefore = service.getChildrenPaged.mock.calls.length;
  checkOutTheRowOf('other-folder-row-name.txt');
  await waitFor(() => expect(service.getChildrenPaged.mock.calls.length, 'the reload').toBe(loadsBefore + 1));
  await settle();
  expect(screen.queryByText(SELECTED), 'the selection made in the folder, after reloading it').not.toBeNull();
});

it('anOperationEndingAfterAMoveToAStaleRootReloadsTheFreshRoot', async () => {
  const callsBefore = service.getChildrenPaged.mock.calls.length;
  await opened();
  fireEvent.click(screen.getByText('to-the-other-folder'));
  await shown('other-folder-row-name.txt');
  const checkout = deferred<unknown>();
  service.checkOut.mockReturnValueOnce(checkout.promise);
  checkOutTheRowOf('other-folder-row-name.txt');
  await waitFor(() => expect(service.checkOut).toHaveBeenCalledTimes(1));

  // Back to the root, whose id is stale; the move's load is held, so the check-out's reload is the one that finds it gone.
  const answerTheMove = holdTheNextFolderLoad();
  const loadsBefore = service.getChildrenPaged.mock.calls.length;
  fireEvent.click(screen.getByText('to-the-root'));
  await waitFor(() => expect(service.getChildrenPaged.mock.calls.length, 'the move\'s load').toBe(loadsBefore + 1));
  service.getChildrenPaged.mockRejectedValueOnce(new Error('the folder was not found'));
  service.getRootFolder.mockResolvedValueOnce({ id: 'fresh-root-id', name: 'root', path: '/' });
  await act(async () => {
    checkout.resolve({});
    await checkout.promise;
  });

  // The reload judges the folder it read — the root — and resolves the fresh one.
  await waitFor(() => expect(service.getChildrenPaged.mock.calls.slice(callsBefore).some((call) => call[1] === 'fresh-root-id'),
    'the fresh root read').toBe(true));
  await shown('folder-row-name.txt');
  await answerTheMove(FOLDER_ROW);
});

it('aFolderLoadAnsweredAfterTheListWasLeftWritesNoAddress', async () => {
  const { unmount } = render(<DocumentList repositoryId="bedroom" />);
  await shown('folder-row-name.txt');
  const gone = deferred<{ items: ReturnType<typeof row>[]; numItems: number; hasMoreItems: boolean }>();
  service.getChildrenPaged.mockReturnValueOnce(gone.promise);
  const loadsBefore = service.getChildrenPaged.mock.calls.length;
  fireEvent.click(screen.getByText('to-the-other-folder'));
  await waitFor(() => expect(service.getChildrenPaged.mock.calls.length, 'the move\'s load').toBe(loadsBefore + 1));
  router.setParams.mockClear();

  // The list is left; then the folder answers that it is gone.
  unmount();
  await act(async () => {
    gone.reject(new Error('the folder was not found'));
    await gone.promise.catch(() => undefined);
  });
  await settle();
  expect(router.setParams, 'the address written after the list was left').not.toHaveBeenCalled();
});

it('aFreshRootResolvedAfterAMoveDoesNotTakeTheListBack', async () => {
  const callsBefore = service.getChildrenPaged.mock.calls.length;
  await opened();
  fireEvent.click(screen.getByText('to-the-other-folder'));
  await shown('other-folder-row-name.txt');
  const checkout = deferred<unknown>();
  service.checkOut.mockReturnValueOnce(checkout.promise);
  checkOutTheRowOf('other-folder-row-name.txt');
  await waitFor(() => expect(service.checkOut).toHaveBeenCalled());

  // Back to the root, whose id is stale; the check-out's reload finds it gone and asks for the fresh root.
  const answerTheMove = holdTheNextFolderLoad();
  const loadsBefore = service.getChildrenPaged.mock.calls.length;
  fireEvent.click(screen.getByText('to-the-root'));
  await waitFor(() => expect(service.getChildrenPaged.mock.calls.length, 'the move\'s load').toBe(loadsBefore + 1));
  service.getChildrenPaged.mockRejectedValueOnce(new Error('the folder was not found'));
  const freshRoot = deferred<{ id: string; name: string; path: string }>();
  service.getRootFolder.mockReturnValueOnce(freshRoot.promise);
  const rootsBefore = service.getRootFolder.mock.calls.length;
  await act(async () => {
    checkout.resolve({});
    await checkout.promise;
  });
  await waitFor(() => expect(service.getRootFolder.mock.calls.length, 'the fresh root asked for').toBe(rootsBefore + 1));

  // Before the fresh root answers, the other folder is chosen again.
  fireEvent.click(screen.getByText('to-the-other-folder'));
  await shown('other-folder-row-name.txt');
  await act(async () => {
    freshRoot.resolve({ id: 'fresh-root-id', name: 'root', path: '/' });
    await freshRoot.promise;
  });
  await settle();
  expect(service.getChildrenPaged.mock.calls.slice(callsBefore).some((call) => call[1] === 'fresh-root-id'), 'the list taken back to the root')
    .toBe(false);
  expect(screen.queryByText('other-folder-row-name.txt'), 'the folder chosen').not.toBeNull();
  await answerTheMove(FOLDER_ROW);
});

it('anOperationEndingAfterTheListWasLeftStartsNoReload', async () => {
  const { unmount } = render(<DocumentList repositoryId="bedroom" />);
  await shown('folder-row-name.txt');
  fireEvent.click(screen.getByText('to-the-other-folder'));
  await shown('other-folder-row-name.txt');
  const checkout = deferred<unknown>();
  service.checkOut.mockReturnValueOnce(checkout.promise);
  checkOutTheRowOf('other-folder-row-name.txt');
  await waitFor(() => expect(service.checkOut).toHaveBeenCalled());
  router.setParams.mockClear();
  const loadsBefore = service.getChildrenPaged.mock.calls.length;

  // The list is left; then the check-out ends.
  unmount();
  await act(async () => {
    checkout.resolve({});
    await checkout.promise;
  });
  await settle();
  expect(service.getChildrenPaged.mock.calls.length, 'a reload started after the list was left').toBe(loadsBefore);
  expect(router.setParams, 'the address written after the list was left').not.toHaveBeenCalled();
});

/** The other folder is /a/b, so the list can go up to /a and its breadcrumb names a and b. */
async function openedInADeepFolder() {
  service.getObject.mockImplementation(async (_repositoryId: string, id: string) => ({
    id, path: id === 'other-folder-id' ? '/a/b' : '/', allowableActions: {},
  }));
  const view = render(<DocumentList repositoryId="bedroom" />);
  await shown('folder-row-name.txt');
  fireEvent.click(screen.getByText('to-the-other-folder'));
  await shown('other-folder-row-name.txt');
  await waitFor(() => expect(screen.queryByText('b'), 'the breadcrumb of /a/b').not.toBeNull());
  return view;
}

it('goingUpAnsweredAfterTheListWasLeftWritesNoAddress', async () => {
  const { unmount } = await openedInADeepFolder();
  const parent = deferred<{ id: string }>();
  service.getObjectByPath.mockReturnValueOnce(parent.promise);
  fireEvent.click(screen.getByText('上へ'));
  await waitFor(() => expect(service.getObjectByPath).toHaveBeenCalledWith('bedroom', '/a'));
  router.setParams.mockClear();
  unmount();
  await act(async () => {
    parent.resolve({ id: 'a-folder-id' });
    await parent.promise;
  });
  await settle();
  expect(router.setParams, 'the address written after the list was left').not.toHaveBeenCalled();
});

it('goingUpAnsweredAfterAnotherFolderWasChosenLeavesTheListThere', async () => {
  await openedInADeepFolder();
  const parent = deferred<{ id: string }>();
  service.getObjectByPath.mockReturnValueOnce(parent.promise);
  fireEvent.click(screen.getByText('上へ'));
  await waitFor(() => expect(service.getObjectByPath).toHaveBeenCalledWith('bedroom', '/a'));

  // Before the parent is resolved, the root is chosen in the tree.
  fireEvent.click(screen.getByText('to-the-root'));
  await shown('folder-row-name.txt');
  const loadsBefore = service.getChildrenPaged.mock.calls.length;
  await act(async () => {
    parent.resolve({ id: 'a-folder-id' });
    await parent.promise;
  });
  await settle();
  expect(service.getChildrenPaged.mock.calls.slice(loadsBefore).some((call) => call[1] === 'a-folder-id'),
    'the list taken away from the folder chosen').toBe(false);
  expect(screen.queryByText('folder-row-name.txt'), 'the folder chosen').not.toBeNull();
});

it('aBreadcrumbAnsweredAfterTheListWasLeftWritesNoAddress', async () => {
  const { unmount } = await openedInADeepFolder();
  const segment = deferred<{ id: string }>();
  service.getObjectByPath.mockReturnValueOnce(segment.promise);
  fireEvent.click(screen.getByText('a'));
  await waitFor(() => expect(service.getObjectByPath).toHaveBeenCalledWith('bedroom', '/a'));
  router.setParams.mockClear();
  unmount();
  await act(async () => {
    segment.resolve({ id: 'a-folder-id' });
    await segment.promise;
  });
  await settle();
  expect(router.setParams, 'the address written after the list was left').not.toHaveBeenCalled();
});

it('goingUpAnsweredAfterAnOperationsReloadStillGoesUp', async () => {
  await openedInADeepFolder();
  const checkout = deferred<unknown>();
  service.checkOut.mockReturnValueOnce(checkout.promise);
  checkOutTheRowOf('other-folder-row-name.txt');
  await waitFor(() => expect(service.checkOut).toHaveBeenCalled());
  const parent = deferred<{ id: string }>();
  service.getObjectByPath.mockReturnValueOnce(parent.promise);
  fireEvent.click(screen.getByText('上へ'));
  await waitFor(() => expect(service.getObjectByPath).toHaveBeenCalledWith('bedroom', '/a'));

  // The check-out ends and reloads the same folder while the parent is resolved.
  const loadsBefore = service.getChildrenPaged.mock.calls.length;
  await act(async () => {
    checkout.resolve({});
    await checkout.promise;
  });
  await waitFor(() => expect(service.getChildrenPaged.mock.calls.length, 'the reload').toBeGreaterThan(loadsBefore));
  await act(async () => {
    parent.resolve({ id: 'a-folder-id' });
    await parent.promise;
  });

  // Going up was asked for in the folder still shown: it goes.
  await waitFor(() => expect(service.getChildrenPaged.mock.calls.slice(loadsBefore).some((call) => call[1] === 'a-folder-id'),
    'the parent loaded').toBe(true));
});
